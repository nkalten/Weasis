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

import java.util.List;

/**
 * One entry of {@code GET /tasks}.
 *
 * @param rois structures the task segments
 * @param fast whether the task has a lower-resolution model; the other tasks either reject {@code
 *     fast=true} or silently ignore it
 */
public record TaskInfo(List<String> rois, boolean fast) {

  public TaskInfo {
    rois = rois == null ? List.of() : List.copyOf(rois);
  }
}
