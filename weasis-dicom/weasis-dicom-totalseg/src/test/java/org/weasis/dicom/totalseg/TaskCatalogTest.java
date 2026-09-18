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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TaskCatalogTest {

  @Test
  void fallsBackToBuiltInTasksBeforeTheServerAnswers() {
    TaskCatalog catalog = new TaskCatalog();

    assertAll(
        () -> assertFalse(catalog.isLoaded()),
        () -> assertTrue(catalog.taskNames().contains(TaskCatalog.DEFAULT_CT_TASK)),
        () -> assertTrue(catalog.taskNames().contains(TaskCatalog.DEFAULT_MR_TASK)),
        () -> assertTrue(catalog.rois(TaskCatalog.DEFAULT_CT_TASK).isEmpty()));
  }

  @Test
  void serverTasksReplaceTheBuiltInListAndKeepTheirOrder() {
    Map<String, List<String>> server = new LinkedHashMap<>();
    server.put("zebra", List.of("a")); // NON-NLS
    server.put("alpha", List.of("b", "c")); // NON-NLS
    TaskCatalog catalog = new TaskCatalog();

    catalog.update(server);

    assertAll(
        () -> assertTrue(catalog.isLoaded()),
        () -> assertEquals(List.of("zebra", "alpha"), catalog.taskNames()),
        () -> assertEquals(List.of("b", "c"), catalog.rois("alpha")),
        () -> assertTrue(catalog.rois("unknown").isEmpty()));
  }

  @Test
  void emptyServerAnswerKeepsTheBuiltInList() {
    TaskCatalog catalog = new TaskCatalog();

    catalog.update(Map.of());

    assertFalse(catalog.isLoaded());
    assertTrue(catalog.taskNames().size() > 1);
  }
}
