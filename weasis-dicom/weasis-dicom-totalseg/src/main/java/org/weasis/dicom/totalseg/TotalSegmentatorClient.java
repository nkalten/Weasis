/*
 * Copyright (c) 2026 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.dicom.totalseg;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.core.util.FileUtil;
import org.weasis.core.util.StringUtil;

/**
 * Client of the TotalSegmentator API: submits a zipped DICOM series, follows the Celery task and
 * downloads the resulting DICOM object.
 */
public class TotalSegmentatorClient {

  private static final Logger LOGGER = LoggerFactory.getLogger(TotalSegmentatorClient.class);

  private static final Pattern FILENAME_PATTERN =
      Pattern.compile("filename\\*?=\"?([^\";]+)\"?", Pattern.CASE_INSENSITIVE); // NON-NLS
  private static final String CRLF = "\r\n";

  private final URI baseUri;
  private final Map<String, String> headers;
  private final Duration requestTimeout;
  private final HttpClient httpClient;

  // A newer API may add fields to the responses without breaking an older plugin.
  private final ObjectMapper mapper =
      JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

  public TotalSegmentatorClient(
      String baseUrl, Map<String, String> headers, Duration connectTimeout, Duration requestTimeout)
      throws TotalSegmentatorException {
    this.baseUri = toBaseUri(baseUrl);
    this.headers = headers == null ? Map.of() : Map.copyOf(headers);
    this.requestTimeout = Objects.requireNonNullElse(requestTimeout, Duration.ofMinutes(30));
    this.httpClient =
        HttpClient.newBuilder()
            // Uvicorn ignores the h2c upgrade attempted over plain HTTP and then drops the body.
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Objects.requireNonNullElse(connectTimeout, Duration.ofSeconds(10)))
            .build();
  }

  private static URI toBaseUri(String baseUrl) throws TotalSegmentatorException {
    if (!StringUtil.hasText(baseUrl)) {
      throw new TotalSegmentatorException(Messages.getString("no.server.url"));
    }
    String url = baseUrl.trim();
    while (url.endsWith("/")) {
      url = url.substring(0, url.length() - 1);
    }
    try {
      URI uri = URI.create(url);
      if (uri.getScheme() == null || uri.getHost() == null) {
        throw new IllegalArgumentException("Missing scheme or host"); // NON-NLS
      }
      return uri;
    } catch (IllegalArgumentException e) {
      throw new TotalSegmentatorException(
          String.format(Messages.getString("invalid.server.url"), baseUrl));
    }
  }

  public URI getBaseUri() {
    return baseUri;
  }

  /** Overall health of the API and of its Celery workers. */
  public String health() throws IOException {
    JsonNode node = readTree(send(request("/health").GET(), BodyHandlers.ofString()));
    String status = text(node, "status"); // NON-NLS
    return status == null ? Messages.getString("unknown") : status;
  }

  /** Available tasks with the ROIs each one segments and its support of the fast mode. */
  public Map<String, TaskInfo> tasks() throws IOException {
    HttpResponse<String> response = send(request("/tasks").GET(), BodyHandlers.ofString());
    checkSuccess(response, response.body());
    return mapper.readValue(response.body(), new TypeReference<Map<String, TaskInfo>>() {});
  }

  /**
   * Uploads the zipped series and queues it for segmentation.
   *
   * @return the identifier to poll with {@link #status(String)}
   */
  public String submit(Path zipFile, SegmentationOptions options) throws IOException {
    List<String> query = new ArrayList<>();
    query.add(param("output_type", options.outputType().value())); // NON-NLS
    query.add(param("task", options.task())); // NON-NLS
    query.add(param("fast", Boolean.toString(options.fast()))); // NON-NLS
    if (options.hasSegType()) {
      query.add(param("seg_type", options.segType().value())); // NON-NLS
    }
    for (String roi : options.roiSubset()) {
      query.add(param("roi_subset", roi)); // NON-NLS
    }

    String boundary = "WeasisBoundary" + UUID.randomUUID(); // NON-NLS
    HttpRequest.Builder builder =
        request("/segment?" + String.join("&", query)) // NON-NLS
            .header("Content-Type", "multipart/form-data; boundary=" + boundary) // NON-NLS
            .POST(multipartBody(zipFile, boundary));

    String taskId = text(readTree(send(builder, BodyHandlers.ofString())), "task_id"); // NON-NLS
    if (!StringUtil.hasText(taskId)) {
      throw new TotalSegmentatorException(Messages.getString("no.task.id"));
    }
    return taskId;
  }

  /**
   * Registers the options of series about to be sent by C-STORE, which cannot carry them. The DICOM
   * listener applies them when the series is complete.
   */
  public void registerDicomParams(List<String> seriesUids, SegmentationOptions options)
      throws IOException {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("series_uids", seriesUids); // NON-NLS
    body.put("output_type", options.outputType().value()); // NON-NLS
    body.put("task", options.task()); // NON-NLS
    body.put("fast", options.fast()); // NON-NLS
    if (options.hasSegType()) {
      body.put("seg_type", options.segType().value()); // NON-NLS
    }
    if (!options.roiSubset().isEmpty()) {
      body.put("roi_subset", options.roiSubset()); // NON-NLS
    }

    HttpRequest.Builder builder =
        request("/dicom/params") // NON-NLS
            .header("Content-Type", "application/json") // NON-NLS
            .POST(BodyPublishers.ofString(mapper.writeValueAsString(body)));
    HttpResponse<String> response = send(builder, BodyHandlers.ofString());
    checkSuccess(response, response.body());
  }

  public TaskStatus status(String taskId) throws IOException {
    JsonNode node =
        readTree(send(request(path("/status/", taskId)).GET(), BodyHandlers.ofString()));
    return new TaskStatus(
        Objects.requireNonNullElse(text(node, "task_id"), taskId), // NON-NLS
        TaskState.fromValue(text(node, "state")), // NON-NLS
        text(node, "status"), // NON-NLS
        text(node, "result_filename"), // NON-NLS
        text(node, "error")); // NON-NLS
  }

  /** {@code null} for a missing field and for an explicit JSON {@code null}. */
  private static String text(JsonNode node, String field) {
    JsonNode value = node.path(field);
    return value.isTextual() ? value.asText() : null;
  }

  /**
   * Downloads the result into {@code targetDir}.
   *
   * @param fallbackName name to use when the response carries no Content-Disposition
   */
  public Path downloadResult(String taskId, Path targetDir, String fallbackName)
      throws IOException {
    HttpResponse<InputStream> response =
        send(request(path("/result/", taskId)).GET(), BodyHandlers.ofInputStream());
    try (InputStream in = response.body()) {
      if (response.statusCode() >= 300) {
        throw new TotalSegmentatorException(
            new String(in.readAllBytes(), StandardCharsets.UTF_8), response.statusCode());
      }
      Path file = targetDir.resolve(resolveFileName(response, fallbackName, taskId));
      FileUtil.writeStreamWithIOException(in, file);
      return file;
    }
  }

  /** Frees the server-side result; failures are logged only, the result is already downloaded. */
  public void deleteResult(String taskId) {
    try {
      send(request(path("/result/", taskId)).DELETE(), BodyHandlers.ofString());
    } catch (IOException e) {
      LOGGER.warn("Cannot delete the TotalSegmentator result of task {}", taskId, e);
    }
  }

  private HttpRequest.Builder request(String path) {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create(baseUri + path)).timeout(requestTimeout);
    headers.forEach(builder::header);
    return builder;
  }

  private static String path(String prefix, String taskId) {
    return prefix + URLEncoder.encode(taskId, StandardCharsets.UTF_8);
  }

  private static String param(String name, String value) {
    return name + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private <T> HttpResponse<T> send(HttpRequest.Builder builder, HttpResponse.BodyHandler<T> handler)
      throws IOException {
    try {
      return httpClient.send(builder.build(), handler);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new TotalSegmentatorException(Messages.getString("request.interrupted"));
    }
  }

  private JsonNode readTree(HttpResponse<String> response) throws IOException {
    checkSuccess(response, response.body());
    return mapper.readTree(response.body());
  }

  private void checkSuccess(HttpResponse<?> response, String body)
      throws TotalSegmentatorException {
    int status = response.statusCode();
    if (status >= 300) {
      throw new TotalSegmentatorException(errorMessage(body, status), status);
    }
  }

  /**
   * Unwraps the FastAPI {@code detail} field, which holds either a message or validation errors.
   */
  private String errorMessage(String body, int statusCode) {
    if (StringUtil.hasText(body)) {
      try {
        JsonNode detail = mapper.readTree(body).path("detail"); // NON-NLS
        if (detail.isTextual()) {
          return detail.asText();
        }
        if (detail.isArray() && !detail.isEmpty()) {
          List<String> messages = new ArrayList<>();
          detail.forEach(node -> messages.add(node.path("msg").asText(node.toString()))); // NON-NLS
          return String.join(" - ", messages);
        }
      } catch (IOException e) {
        LOGGER.debug("Response body is not JSON", e);
      }
    }
    return String.format(Messages.getString("http.error"), statusCode);
  }

  /**
   * Streams the file from disk instead of buffering it: a whole CT series easily exceeds the heap.
   */
  private static BodyPublisher multipartBody(Path zipFile, String boundary) throws IOException {
    byte[] prefix =
        ("--"
                + boundary
                + CRLF
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" // NON-NLS
                + zipFile.getFileName()
                + "\""
                + CRLF
                + "Content-Type: application/zip" // NON-NLS
                + CRLF
                + CRLF)
            .getBytes(StandardCharsets.UTF_8);
    byte[] suffix = (CRLF + "--" + boundary + "--" + CRLF).getBytes(StandardCharsets.UTF_8);
    long length = prefix.length + Files.size(zipFile) + suffix.length;

    return BodyPublishers.fromPublisher(
        BodyPublishers.ofInputStream(
            () -> {
              try {
                List<InputStream> parts =
                    List.of(
                        new ByteArrayInputStream(prefix),
                        Files.newInputStream(zipFile),
                        new ByteArrayInputStream(suffix));
                return new SequenceInputStream(Collections.enumeration(parts));
              } catch (IOException e) {
                throw new UncheckedIOException(e);
              }
            }),
        length);
  }

  /** Content-Disposition first, then the name the status response announced, then the task id. */
  private static String resolveFileName(
      HttpResponse<?> response, String fallbackName, String taskId) {
    String disposition = response.headers().firstValue("content-disposition").orElse(null);
    if (StringUtil.hasText(disposition)) {
      Matcher matcher = FILENAME_PATTERN.matcher(disposition);
      if (matcher.find()) {
        String name = FileUtil.getValidFileName(matcher.group(1).trim());
        if (StringUtil.hasText(name)) {
          return name;
        }
      }
    }
    if (StringUtil.hasText(fallbackName)) {
      return FileUtil.getValidFileName(fallbackName);
    }
    return FileUtil.getValidFileName(taskId) + ".dcm"; // NON-NLS
  }
}
