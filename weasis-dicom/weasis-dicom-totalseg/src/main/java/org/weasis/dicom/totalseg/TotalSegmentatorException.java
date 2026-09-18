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

import java.io.IOException;
import java.io.Serial;

/** Carries the message the API returned, so it can be shown to the user as is. */
public class TotalSegmentatorException extends IOException {

  @Serial private static final long serialVersionUID = 1L;

  private final int httpStatus;

  public TotalSegmentatorException(String message) {
    this(message, 0);
  }

  public TotalSegmentatorException(String message, int httpStatus) {
    super(message);
    this.httpStatus = httpStatus;
  }

  public int getHttpStatus() {
    return httpStatus;
  }
}
