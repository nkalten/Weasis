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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tasks offered by the server, with the ROIs each one segments and its support of the fast mode.
 * {@code GET /tasks} is the source of truth; the built-in list only keeps the plugin usable while
 * the server is unreachable, and carries no ROI.
 */
public class TaskCatalog {

  public static final String DEFAULT_CT_TASK = "total"; // NON-NLS
  public static final String DEFAULT_MR_TASK = "total_mr"; // NON-NLS

  /**
   * Tasks whose fast mode lowers the resolution. An allowlist rather than the list of the rejected
   * tasks: an unknown task then only loses the option instead of failing on the server.
   */
  private static final Set<String> BUILT_IN_FAST_TASKS =
      Set.of("total", "total_v3", "total_mr", "body", "body_mr"); // NON-NLS

  /** Mirrors the tasks the server offers, which excludes the commercial and the internal ones. */
  // @formatter:off
  private static final List<String> BUILT_IN_TASKS =
      List.of(
          "total", "total_mr", "total_v3", "total_highres_test", // NON-NLS
          "body", "body_mr", "lung_vessels", "lung_nodules", // NON-NLS
          "pleural_pericard_effusion", "liver_vessels", "liver_segments", "liver_segments_mr", // NON-NLS
          "liver_lesions", "liver_lesions_mr", "kidney_cysts", "ventricle_parts", // NON-NLS
          "brain_aneurysm", "cerebral_bleed", "head_glands_cavities", "head_muscles", // NON-NLS
          "headneck_bones_vessels", "headneck_muscles", "craniofacial_structures", "teeth", // NON-NLS
          "oculomotor_muscles", "vertebrae_mr", "vertebrae_body", "vertebrae_pp", // NON-NLS
          "vertebrae_pp_refined", "abdominal_muscles", "breasts", "trunk_cavities", // NON-NLS
          "hip_implant"); // NON-NLS
  // @formatter:on

  private Map<String, TaskInfo> tasks = Map.of();

  /** Keeps the server order, which groups related tasks together. */
  public void update(Map<String, TaskInfo> serverTasks) {
    this.tasks =
        serverTasks == null
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(serverTasks));
  }

  public boolean isLoaded() {
    return !tasks.isEmpty();
  }

  public List<String> taskNames() {
    return isLoaded() ? List.copyOf(tasks.keySet()) : BUILT_IN_TASKS;
  }

  public List<String> rois(String task) {
    TaskInfo info = tasks.get(task);
    return info == null ? List.of() : info.rois();
  }

  /** Falls back to the allowlist for a task the server did not describe. */
  public boolean supportsFast(String task) {
    TaskInfo info = tasks.get(task);
    return info == null ? BUILT_IN_FAST_TASKS.contains(task) : info.fast();
  }
}
