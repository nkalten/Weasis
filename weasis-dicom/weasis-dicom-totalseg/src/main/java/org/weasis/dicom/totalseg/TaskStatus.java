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

import java.util.Optional;

/**
 * @param status human-readable detail, such as the number of files being processed
 * @param resultFilename set only when the state is SUCCESS
 * @param error set only when the state is FAILURE
 */
public record TaskStatus(
    String taskId, TaskState state, String status, String resultFilename, String error) {

  public Optional<String> resultFile() {
    return Optional.ofNullable(resultFilename);
  }
}
