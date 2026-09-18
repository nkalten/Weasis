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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SegmentationJobTest {

  private static final SegmentationOptions OPTIONS =
      new SegmentationOptions("total", OutputType.DICOM_SEG, SegType.LABELMAP, List.of(), false);

  @TempDir Path tempDir;

  private TotalSegmentatorClient client;
  private Path dicomDir;
  private Path workDir;
  private RecordingFeedback feedback;

  @BeforeEach
  void setUp() throws IOException {
    client = mock(TotalSegmentatorClient.class);
    dicomDir = Files.createDirectories(tempDir.resolve("dicom"));
    Files.writeString(dicomDir.resolve("1.dcm"), "image");
    workDir = Files.createDirectories(tempDir.resolve("work"));
    feedback = new RecordingFeedback();
  }

  private static TotalSegConfig config(boolean deleteResult) {
    return new TotalSegConfig(
        "http://localhost", "TOTALSEG_SCP", 11112, "", "", 1, 60, deleteResult);
  }

  private static TaskStatus status(TaskState state, String filename, String error) {
    return new TaskStatus("t1", state, state.name(), filename, error);
  }

  @Test
  void pollsUntilSuccessThenDownloadsAndReleasesTheResult() throws IOException {
    when(client.submit(any(), eq(OPTIONS))).thenReturn("t1");
    when(client.status("t1"))
        .thenReturn(
            status(TaskState.PENDING, null, null),
            status(TaskState.PROCESSING, null, null),
            status(TaskState.SUCCESS, "seg.dcm", null));
    when(client.downloadResult(eq("t1"), any(), eq("seg.dcm")))
        .thenAnswer(i -> writeFile(i.getArgument(1, Path.class).resolve("seg.dcm"), "DICM"));

    Path resultDir =
        new SegmentationJob(client, config(true)).run(dicomDir, OPTIONS, workDir, feedback);

    assertAll(
        () -> assertEquals(workDir.resolve("result"), resultDir),
        () -> assertTrue(Files.exists(resultDir.resolve("seg.dcm"))),
        () -> verify(client).deleteResult("t1"),
        // The archive is a temporary of the upload only and must not outlive it.
        () -> assertTrue(Files.notExists(workDir.resolve("dicom.zip"))),
        () -> assertTrue(feedback.messages.contains(TaskState.PROCESSING.name())));
  }

  @Test
  void keepsTheResultOnTheServerWhenConfiguredTo() throws IOException {
    when(client.submit(any(), any())).thenReturn("t1");
    when(client.status("t1")).thenReturn(status(TaskState.SUCCESS, "seg.dcm", null));
    when(client.downloadResult(anyString(), any(), any()))
        .thenAnswer(i -> writeFile(i.getArgument(1, Path.class).resolve("seg.dcm"), "DICM"));

    new SegmentationJob(client, config(false)).run(dicomDir, OPTIONS, workDir, feedback);

    verify(client, never()).deleteResult(anyString());
  }

  @Test
  void aZippedResultIsExpandedIntoTheResultFolder() throws IOException {
    when(client.submit(any(), any())).thenReturn("t1");
    when(client.status("t1")).thenReturn(status(TaskState.SUCCESS, "seg.zip", null));
    when(client.downloadResult(anyString(), any(), any()))
        .thenAnswer(i -> writeZip(i.getArgument(1, Path.class).resolve("seg.zip")));

    Path resultDir =
        new SegmentationJob(client, config(true)).run(dicomDir, OPTIONS, workDir, feedback);

    try (var files = Files.list(resultDir)) {
      List<String> names = files.map(p -> p.getFileName().toString()).toList();
      assertAll(
          () -> assertEquals(2, names.size(), names.toString()),
          () -> assertTrue(names.stream().allMatch(n -> n.endsWith(".dcm")), names.toString()));
    }
  }

  @Test
  void aFailedTaskSurfacesTheServerError() throws IOException {
    when(client.submit(any(), any())).thenReturn("t1");
    when(client.status("t1")).thenReturn(status(TaskState.FAILURE, null, "no DICOM files found"));

    TotalSegmentatorException e =
        assertThrows(
            TotalSegmentatorException.class,
            () ->
                new SegmentationJob(client, config(true))
                    .run(dicomDir, OPTIONS, workDir, feedback));

    assertTrue(e.getMessage().contains("no DICOM files found"), e.getMessage());
  }

  @Test
  void anEmptySelectionIsRefusedBeforeAnyUpload() throws IOException {
    Path empty = Files.createDirectories(tempDir.resolve("empty"));

    assertThrows(
        TotalSegmentatorException.class,
        () -> new SegmentationJob(client, config(true)).run(empty, OPTIONS, workDir, feedback));

    verify(client, never()).submit(any(), any());
  }

  @Test
  void cancellingStopsThePollingAndReclaimsTheResult() throws IOException {
    when(client.submit(any(), any())).thenReturn("t1");
    when(client.status("t1")).thenReturn(status(TaskState.PROCESSING, null, null));
    feedback.cancelled = true;

    Path resultDir =
        new SegmentationJob(client, config(true)).run(dicomDir, OPTIONS, workDir, feedback);

    assertAll(
        () -> assertNull(resultDir),
        () -> verify(client).deleteResult("t1"),
        () -> verify(client, never()).downloadResult(anyString(), any(), any()));
  }

  @Test
  void aTaskThatNeverEndsIsAbandonedAfterTheConfiguredWait() throws IOException {
    when(client.submit(any(), any())).thenReturn("t1");
    when(client.status("t1")).thenReturn(status(TaskState.PROCESSING, null, null));
    TotalSegConfig expired =
        new TotalSegConfig("http://localhost", "TOTALSEG_SCP", 11112, "", "", 1, 0, true);

    assertThrows(
        TotalSegmentatorException.class,
        () -> new SegmentationJob(client, expired).run(dicomDir, OPTIONS, workDir, feedback));
  }

  private static Path writeFile(Path file, String content) throws IOException {
    Files.createDirectories(file.getParent());
    return Files.writeString(file, content);
  }

  private static Path writeZip(Path file) throws IOException {
    Files.createDirectories(file.getParent());
    try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(file))) {
      for (String name : List.of("seg/1.dcm", "seg/2.dcm")) {
        out.putNextEntry(new ZipEntry(name));
        out.write("DICM".getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
      }
    }
    return file;
  }

  private static final class RecordingFeedback implements SegmentationJob.Feedback {
    private final List<String> messages = new ArrayList<>();
    private boolean cancelled;

    @Override
    public void status(String message) {
      messages.add(message);
    }

    @Override
    public boolean isCancelled() {
      return cancelled;
    }
  }
}
