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
import java.util.Objects;

/**
 * Query parameters of {@code POST /segment}.
 *
 * @param segType encoding of the DICOM SEG segments, ignored for an RT structure set
 * @param roiSubset ROIs to keep, or an empty list to segment every ROI of the task
 * @param fast lower resolution, faster inference
 */
public record SegmentationOptions(
    String task, OutputType outputType, SegType segType, List<String> roiSubset, boolean fast) {

  public SegmentationOptions {
    Objects.requireNonNull(task);
    outputType = Objects.requireNonNullElse(outputType, OutputType.DICOM_SEG);
    segType = Objects.requireNonNullElse(segType, SegType.LABELMAP);
    roiSubset = roiSubset == null ? List.of() : List.copyOf(roiSubset);
  }

  /** {@code seg_type} only shapes a DICOM SEG, an RT structure set always holds contours. */
  public boolean hasSegType() {
    return outputType == OutputType.DICOM_SEG;
  }
}
