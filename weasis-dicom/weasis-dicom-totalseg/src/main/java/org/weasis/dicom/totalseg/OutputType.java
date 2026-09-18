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

/** DICOM object the API builds from the segmentation masks. */
public enum OutputType {
  DICOM_SEG("dicom_seg", "DICOM SEG"), // NON-NLS
  DICOM_RTSTRUCT("dicom_rtstruct", "DICOM RT Structure Set"); // NON-NLS

  private final String value;
  private final String label;

  OutputType(String value, String label) {
    this.value = value;
    this.label = label;
  }

  public String value() {
    return value;
  }

  @Override
  public String toString() {
    return label;
  }

  public static OutputType fromValue(String value) {
    for (OutputType type : values()) {
      if (type.value.equals(value)) {
        return type;
      }
    }
    return DICOM_SEG;
  }
}
