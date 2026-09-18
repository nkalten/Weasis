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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.core.util.StringUtil;

/**
 * Zips a DICOM folder, submits it, follows the task and downloads the segmentation. Runs on a
 * worker thread and reports through a {@link Feedback}, which also carries the cancel request.
 */
public class SegmentationJob {

  private static final Logger LOGGER = LoggerFactory.getLogger(SegmentationJob.class);

  /** Longest a cancel request can go unnoticed while waiting between two polls. */
  private static final long CANCEL_CHECK_MILLIS = 500;

  public interface Feedback {
    void status(String message);

    boolean isCancelled();
  }

  private final TotalSegmentatorClient client;
  private final TotalSegConfig config;

  public SegmentationJob(TotalSegmentatorClient client, TotalSegConfig config) {
    this.client = client;
    this.config = config;
  }

  /**
   * @param dicomDir folder holding the DICOM files to segment, searched recursively
   * @param workDir folder for the archive and the downloaded result
   * @return the folder holding the downloaded DICOM objects, or {@code null} if cancelled
   */
  public Path run(Path dicomDir, SegmentationOptions options, Path workDir, Feedback feedback)
      throws IOException {
    feedback.status(Messages.getString("compressing.series"));
    Path zip = workDir.resolve("dicom.zip"); // NON-NLS
    int fileCount = ZipUtil.zipFlattened(dicomDir, zip);
    if (fileCount == 0) {
      throw new TotalSegmentatorException(Messages.getString("no.image.selected"));
    }

    feedback.status(String.format(Messages.getString("uploading.files"), fileCount));
    String taskId;
    try {
      taskId = client.submit(zip, options);
    } finally {
      // A whole series, so it is worth reclaiming even when the upload failed.
      Files.deleteIfExists(zip);
    }
    LOGGER.info("TotalSegmentator task {} submitted with {} files", taskId, fileCount);

    TaskStatus status = waitForCompletion(taskId, feedback);
    if (status == null) {
      cancelOnServer(taskId);
      return null;
    }

    feedback.status(Messages.getString("downloading.result"));
    Path resultDir = Files.createDirectories(workDir.resolve("result")); // NON-NLS
    Path result = client.downloadResult(taskId, resultDir, status.resultFile().orElse(null));
    if (ZipUtil.isZip(result)) {
      ZipUtil.unzipFlattened(result, resultDir);
      Files.deleteIfExists(result);
    }
    if (config.deleteResultOnServer()) {
      client.deleteResult(taskId);
    }
    return resultDir;
  }

  /**
   * @return the final status, or {@code null} if the user cancelled
   */
  private TaskStatus waitForCompletion(String taskId, Feedback feedback) throws IOException {
    Instant deadline = Instant.now().plus(config.maxWait());
    TaskStatus status = client.status(taskId);
    publish(status, feedback);

    while (status.state().isRunning()) {
      if (Instant.now().isAfter(deadline)) {
        throw new TotalSegmentatorException(
            String.format(Messages.getString("task.timeout"), config.maxWaitMinutes()));
      }
      if (!sleepUnlessCancelled(feedback)) {
        return null;
      }
      status = client.status(taskId);
      publish(status, feedback);
    }

    if (status.state() == TaskState.FAILURE) {
      String error = StringUtil.hasText(status.error()) ? status.error() : status.status();
      throw new TotalSegmentatorException(String.format(Messages.getString("task.failed"), error));
    }
    return status;
  }

  private static void publish(TaskStatus status, Feedback feedback) {
    feedback.status(StringUtil.hasText(status.status()) ? status.status() : status.state().name());
  }

  /**
   * @return {@code false} as soon as the job must stop
   */
  private boolean sleepUnlessCancelled(Feedback feedback) {
    long remaining = config.pollInterval().toMillis();
    while (remaining > 0) {
      if (feedback.isCancelled()) {
        return false;
      }
      try {
        Thread.sleep(Math.min(CANCEL_CHECK_MILLIS, remaining));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
      remaining -= CANCEL_CHECK_MILLIS;
    }
    return !feedback.isCancelled();
  }

  /** The API has no cancel endpoint, so only the result file can be reclaimed. */
  private void cancelOnServer(String taskId) {
    LOGGER.info("TotalSegmentator task {} abandoned by the user", taskId);
    client.deleteResult(taskId);
  }
}
