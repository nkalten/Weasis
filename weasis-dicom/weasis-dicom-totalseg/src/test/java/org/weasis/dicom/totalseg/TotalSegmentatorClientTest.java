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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Drives the client against a local HTTP server replaying the documented API responses. */
class TotalSegmentatorClientTest {

  @TempDir Path tempDir;

  private HttpServer server;
  private TotalSegmentatorClient client;

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.start();
    client =
        new TotalSegmentatorClient(
            "http://localhost:" + server.getAddress().getPort() + "/",
            Map.of("X-Api-Key", "secret"),
            Duration.ofSeconds(5),
            Duration.ofSeconds(10));
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  @Test
  void tasksReturnsEveryTaskWithItsRoisAndFastSupport() throws IOException {
    respond(
        "/tasks",
        200,
        """
        {"total": {"rois": ["liver", "spleen"], "fast": true},
         "teeth": {"rois": [], "fast": false}}""");

    Map<String, TaskInfo> tasks = client.tasks();

    assertAll(
        () -> assertEquals(2, tasks.size()),
        () -> assertEquals(List.of("liver", "spleen"), tasks.get("total").rois()),
        () -> assertTrue(tasks.get("total").fast()),
        () -> assertEquals(List.of(), tasks.get("teeth").rois()),
        () -> assertFalse(tasks.get("teeth").fast()));
  }

  @Test
  void tasksIgnoresFieldsAddedByANewerApi() throws IOException {
    respond("/tasks", 200, "{\"total\": {\"rois\": [], \"fast\": true, \"fastest\": true}}");

    assertTrue(client.tasks().get("total").fast());
  }

  @Test
  void submitSendsTheZipAsMultipartWithTheOptionsInTheQuery() throws IOException {
    AtomicReference<String> query = new AtomicReference<>();
    AtomicReference<String> apiKey = new AtomicReference<>();
    AtomicReference<Long> length = new AtomicReference<>();
    Path zip = tempDir.resolve("dicom.zip");
    Files.write(zip, new byte[512]);

    server.createContext(
        "/segment",
        exchange -> {
          query.set(exchange.getRequestURI().getQuery());
          apiKey.set(exchange.getRequestHeaders().getFirst("X-Api-Key"));
          length.set((long) exchange.getRequestBody().readAllBytes().length);
          send(exchange, 200, "{\"task_id\":\"abc-123\",\"message\":\"queued\"}");
        });

    String taskId =
        client.submit(
            zip,
            new SegmentationOptions(
                "total_mr",
                OutputType.DICOM_RTSTRUCT,
                SegType.BINARY,
                List.of("liver", "spleen"),
                true));

    assertAll(
        () -> assertEquals("abc-123", taskId),
        () -> assertEquals("secret", apiKey.get()),
        () -> assertTrue(query.get().contains("task=total_mr"), query.get()),
        () -> assertTrue(query.get().contains("output_type=dicom_rtstruct"), query.get()),
        () -> assertTrue(query.get().contains("fast=true"), query.get()),
        () -> assertTrue(query.get().contains("roi_subset=liver"), query.get()),
        () -> assertTrue(query.get().contains("roi_subset=spleen"), query.get()),
        // An RT structure set has no label map, so the server must not receive a seg_type.
        () -> assertFalse(query.get().contains("seg_type"), query.get()),
        // The whole multipart envelope must reach the server, not just its declared length.
        () -> assertTrue(length.get() > 512, "body was truncated: " + length.get()));
  }

  @Test
  void registerDicomParamsPostsTheOptionsAsJson() throws IOException {
    AtomicReference<String> contentType = new AtomicReference<>();
    AtomicReference<JsonNode> body = new AtomicReference<>();
    server.createContext(
        "/dicom/params",
        exchange -> {
          contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
          body.set(new ObjectMapper().readTree(exchange.getRequestBody()));
          send(exchange, 200, "{\"message\":\"Parameters registered for 2 series\"}");
        });

    client.registerDicomParams(
        List.of("1.2.3", "1.2.4"),
        new SegmentationOptions(
            "total_mr", OutputType.DICOM_RTSTRUCT, SegType.BINARY, List.of("liver"), true));

    JsonNode json = body.get();
    assertAll(
        () -> assertEquals("application/json", contentType.get()),
        () -> assertEquals("[\"1.2.3\",\"1.2.4\"]", json.get("series_uids").toString()),
        () -> assertEquals("dicom_rtstruct", json.get("output_type").asText()),
        () -> assertEquals("total_mr", json.get("task").asText()),
        () -> assertTrue(json.get("fast").asBoolean()),
        () -> assertTrue(json.path("seg_type").isMissingNode(), json.toString()),
        () -> assertEquals("[\"liver\"]", json.get("roi_subset").toString()));
  }

  @Test
  void registerDicomParamsOmitsAnEmptyRoiSubset() throws IOException {
    AtomicReference<JsonNode> body = new AtomicReference<>();
    server.createContext(
        "/dicom/params",
        exchange -> {
          body.set(new ObjectMapper().readTree(exchange.getRequestBody()));
          send(exchange, 200, "{\"message\":\"ok\"}");
        });

    client.registerDicomParams(
        List.of("1.2.3"),
        new SegmentationOptions("total", OutputType.DICOM_SEG, SegType.BINARY, null, false));

    assertAll(
        () -> assertTrue(body.get().path("roi_subset").isMissingNode(), body.get().toString()),
        () -> assertEquals("binary", body.get().get("seg_type").asText()));
  }

  @Test
  void segTypeDefaultsToLabelmapForADicomSeg() throws IOException {
    AtomicReference<String> query = new AtomicReference<>();
    Path zip = tempDir.resolve("dicom.zip");
    Files.write(zip, new byte[8]);
    server.createContext(
        "/segment",
        exchange -> {
          query.set(exchange.getRequestURI().getQuery());
          send(exchange, 200, "{\"task_id\":\"abc\"}");
        });

    client.submit(zip, new SegmentationOptions("total", OutputType.DICOM_SEG, null, null, false));

    assertTrue(query.get().contains("seg_type=labelmap"), query.get());
  }

  @Test
  void registerDicomParamsReportsTheServerRejection() {
    respond("/dicom/params", 400, "{\"detail\":\"Invalid task 'foo'\"}");

    TotalSegmentatorException e =
        assertThrows(
            TotalSegmentatorException.class,
            () ->
                client.registerDicomParams(
                    List.of("1.2.3"),
                    new SegmentationOptions("foo", OutputType.DICOM_SEG, null, null, false)));
    assertEquals("Invalid task 'foo'", e.getMessage());
  }

  @Test
  void submitRejectsAnAnswerWithoutTaskId() throws IOException {
    Path zip = tempDir.resolve("dicom.zip");
    Files.write(zip, new byte[8]);
    respond("/segment", 200, "{\"message\":\"queued\"}");

    assertThrows(
        TotalSegmentatorException.class,
        () ->
            client.submit(
                zip,
                new SegmentationOptions(
                    "total", OutputType.DICOM_SEG, SegType.BINARY, null, false)));
  }

  @Test
  void statusMapsNullFieldsToNullRatherThanTheStringNull() throws IOException {
    respond(
        "/status/abc",
        200,
        "{\"task_id\":\"abc\",\"state\":\"PROCESSING\",\"status\":\"Running on 240 files...\","
            + "\"result_filename\":null,\"error\":null}");

    TaskStatus status = client.status("abc");

    assertAll(
        () -> assertEquals("abc", status.taskId()),
        () -> assertEquals(TaskState.PROCESSING, status.state()),
        () -> assertEquals("Running on 240 files...", status.status()),
        () -> assertNull(status.resultFilename()),
        () -> assertTrue(status.resultFile().isEmpty()),
        () -> assertNull(status.error()));
  }

  @Test
  void downloadResultNamesTheFileFromContentDisposition() throws IOException {
    server.createContext(
        "/result/abc",
        exchange -> {
          exchange
              .getResponseHeaders()
              .add("Content-Disposition", "attachment; filename=\"segmentation_abc.dcm\"");
          send(exchange, 200, "DICM-payload");
        });

    Path file = client.downloadResult("abc", tempDir, "ignored.dcm");

    assertAll(
        () -> assertEquals("segmentation_abc.dcm", file.getFileName().toString()),
        () -> assertEquals("DICM-payload", Files.readString(file)));
  }

  @Test
  void downloadResultFallsBackToTheAnnouncedName() throws IOException {
    respond("/result/abc", 200, "payload");

    Path file = client.downloadResult("abc", tempDir, "segmentation_abc.dcm");

    assertEquals("segmentation_abc.dcm", file.getFileName().toString());
  }

  @Test
  void downloadResultFallsBackToTheTaskIdWhenNothingIsAnnounced() throws IOException {
    respond("/result/abc", 200, "payload");

    Path file = client.downloadResult("abc", tempDir, null);

    assertEquals("abc.dcm", file.getFileName().toString());
  }

  @Test
  void validationErrorsAreReportedWithTheServerMessages() throws IOException {
    respond(
        "/tasks",
        422,
        "{\"detail\":[{\"loc\":[\"query\",\"task\"],\"msg\":\"unknown task\",\"type\":\"value_error\"}]}");

    TotalSegmentatorException e = assertThrows(TotalSegmentatorException.class, client::tasks);

    assertAll(
        () -> assertEquals(422, e.getHttpStatus()),
        () -> assertEquals("unknown task", e.getMessage()));
  }

  @Test
  void aNonJsonErrorBodyStillYieldsTheHttpStatus() throws IOException {
    respond("/health", 503, "<html>Bad Gateway</html>");

    TotalSegmentatorException e = assertThrows(TotalSegmentatorException.class, client::health);

    assertAll(
        () -> assertEquals(503, e.getHttpStatus()),
        () -> assertTrue(e.getMessage().contains("503"), e.getMessage()));
  }

  @Test
  void healthReportsTheServiceStatus() throws IOException {
    respond(
        "/health", 200, "{\"status\":\"healthy\",\"broker\":\"valkey\",\"celery\":\"connected\"}");

    assertEquals("healthy", client.health());
  }

  @Test
  void deleteResultSwallowsServerFailures() throws IOException {
    respond("/result/abc", 500, "boom");

    client.deleteResult("abc");
  }

  @Test
  void aUrlWithoutSchemeIsRejectedUpFront() {
    assertAll(
        () ->
            assertThrows(
                TotalSegmentatorException.class,
                () -> new TotalSegmentatorClient("localhost:8000", Map.of(), null, null)),
        () ->
            assertThrows(
                TotalSegmentatorException.class,
                () -> new TotalSegmentatorClient("  ", Map.of(), null, null)));
  }

  @Test
  void trailingSlashesAreStrippedFromTheBaseUrl() throws IOException {
    TotalSegmentatorClient c =
        new TotalSegmentatorClient("http://localhost:8000///", Map.of(), null, null);

    assertEquals("http://localhost:8000", c.getBaseUri().toString());
  }

  private void respond(String path, int status, String body) {
    server.createContext(path, exchange -> send(exchange, status, body));
  }

  private static void send(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }
}
