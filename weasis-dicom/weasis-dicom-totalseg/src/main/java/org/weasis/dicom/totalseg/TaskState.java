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

/** Celery states reported by {@code GET /status/{task_id}}. */
public enum TaskState {
  PENDING,
  PROCESSING,
  SUCCESS,
  FAILURE,
  /** Any other Celery state (STARTED, RETRY, REVOKED, …), which the client keeps polling. */
  OTHER;

  public static TaskState fromValue(String state) {
    if (state != null) {
      for (TaskState value : values()) {
        if (value != OTHER && value.name().equalsIgnoreCase(state)) {
          return value;
        }
      }
    }
    return OTHER;
  }

  public boolean isRunning() {
    return this != SUCCESS && this != FAILURE;
  }
}
