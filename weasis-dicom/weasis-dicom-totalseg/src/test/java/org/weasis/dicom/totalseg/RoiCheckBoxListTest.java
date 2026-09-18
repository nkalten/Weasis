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

import java.util.List;
import org.junit.jupiter.api.Test;

class RoiCheckBoxListTest {

  @Test
  void noTickMeansEveryRoiOfTheTask() {
    RoiCheckBoxList list = new RoiCheckBoxList();

    list.setRois(List.of("liver", "spleen"), List.of());

    assertAll(
        () -> assertEquals(List.of(), list.getCheckedRois()), () -> assertTrue(list.isEnabled()));
  }

  @Test
  void checkedRoisKeepTheOrderOfTheTask() {
    RoiCheckBoxList list = new RoiCheckBoxList();

    list.setRois(List.of("liver", "spleen", "kidney_left"), List.of("kidney_left", "liver"));

    assertEquals(List.of("liver", "kidney_left"), list.getCheckedRois());
  }

  @Test
  void tickedRoisMissingFromTheNewTaskAreDropped() {
    RoiCheckBoxList list = new RoiCheckBoxList();

    list.setRois(List.of("liver"), List.of("liver", "vertebrae_L1"));

    assertEquals(List.of("liver"), list.getCheckedRois());
  }

  @Test
  void anEmptyTaskDisablesTheList() {
    RoiCheckBoxList list = new RoiCheckBoxList();

    list.setRois(List.of(), List.of("liver"));

    assertAll(
        () -> assertFalse(list.isEnabled()), () -> assertEquals(List.of(), list.getCheckedRois()));
  }
}
