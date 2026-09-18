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
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** The API exchanges DICOM as ZIP archives, in both directions. */
final class ZipUtil {

  private static final byte[] ZIP_MAGIC = {0x50, 0x4B, 0x03, 0x04};

  private ZipUtil() {}

  /**
   * Flattens {@code sourceDir} into a ZIP: the export tree writes one folder per study and series,
   * which the server does not need.
   *
   * @return the number of archived files
   */
  static int zipFlattened(Path sourceDir, Path zipFile) throws IOException {
    int count = 0;
    try (Stream<Path> files = Files.walk(sourceDir);
        ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zipFile))) {
      List<Path> regularFiles = files.filter(Files::isRegularFile).toList();
      for (Path file : regularFiles) {
        out.putNextEntry(new ZipEntry(count + "_" + file.getFileName()));
        Files.copy(file, out);
        out.closeEntry();
        count++;
      }
    }
    return count;
  }

  static boolean isZip(Path file) throws IOException {
    byte[] header = new byte[ZIP_MAGIC.length];
    try (InputStream in = Files.newInputStream(file)) {
      if (in.readNBytes(header, 0, header.length) < header.length) {
        return false;
      }
    }
    return Arrays.equals(header, ZIP_MAGIC);
  }

  /**
   * Discards the entry paths, which also keeps a crafted archive from escaping {@code targetDir}.
   */
  static void unzipFlattened(Path zipFile, Path targetDir) throws IOException {
    try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zipFile))) {
      ZipEntry entry;
      int index = 0;
      while ((entry = in.getNextEntry()) != null) {
        if (entry.isDirectory()) {
          continue;
        }
        Path name = Path.of(entry.getName()).getFileName();
        Path target = targetDir.resolve(index++ + "_" + (name == null ? "entry" : name)); // NON-NLS
        try (OutputStream out = Files.newOutputStream(target)) {
          in.transferTo(out);
        }
      }
    }
  }
}
