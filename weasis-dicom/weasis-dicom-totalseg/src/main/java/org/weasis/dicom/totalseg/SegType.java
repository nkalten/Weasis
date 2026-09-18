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

/** Encoding of the segments inside a DICOM SEG: one multi-label frame set or one per ROI. */
public enum SegType {
  LABELMAP("labelmap", "seg.type.labelmap"), // NON-NLS
  BINARY("binary", "seg.type.binary"); // NON-NLS

  private final String value;
  private final String labelKey;

  SegType(String value, String labelKey) {
    this.value = value;
    this.labelKey = labelKey;
  }

  public String value() {
    return value;
  }

  @Override
  public String toString() {
    return Messages.getString(labelKey);
  }

  public static SegType fromValue(String value) {
    for (SegType type : values()) {
      if (type.value.equals(value)) {
        return type;
      }
    }
    return LABELMAP;
  }
}
