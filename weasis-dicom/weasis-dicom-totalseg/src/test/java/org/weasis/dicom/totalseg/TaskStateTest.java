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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class TaskStateTest {

  @ParameterizedTest
  @CsvSource({
    "PENDING,PENDING",
    "processing,PROCESSING",
    "SUCCESS,SUCCESS",
    "FAILURE,FAILURE",
    "RETRY,OTHER",
    ",OTHER"
  })
  void fromValueIsCaseInsensitiveAndNeverFails(String input, TaskState expected) {
    assertEquals(expected, TaskState.fromValue(input));
  }

  /** Unknown Celery states must keep the client polling instead of aborting the job. */
  @ParameterizedTest
  @ValueSource(strings = {"PENDING", "PROCESSING", "STARTED", "REVOKED"})
  void everyNonTerminalStateIsRunning(String state) {
    assertTrue(TaskState.fromValue(state).isRunning());
  }

  @ParameterizedTest
  @ValueSource(strings = {"SUCCESS", "FAILURE"})
  void terminalStatesStopThePolling(String state) {
    assertFalse(TaskState.fromValue(state).isRunning());
  }
}
