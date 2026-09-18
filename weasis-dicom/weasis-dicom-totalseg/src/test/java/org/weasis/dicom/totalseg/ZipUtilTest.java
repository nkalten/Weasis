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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ZipUtilTest {

  @TempDir Path tempDir;

  @Test
  void zipFlattenedCollectsNestedFilesAndCountsThem() throws IOException {
    Path source = Files.createDirectories(tempDir.resolve("source/study/series")); // NON-NLS
    Files.writeString(source.resolve("1.dcm"), "one"); // NON-NLS
    Files.writeString(source.resolve("2.dcm"), "two"); // NON-NLS
    Files.writeString(tempDir.resolve("source/3.dcm"), "three"); // NON-NLS
    Path zip = tempDir.resolve("out.zip"); // NON-NLS

    int count = ZipUtil.zipFlattened(tempDir.resolve("source"), zip); // NON-NLS

    assertAll(
        () -> assertEquals(3, count),
        () -> assertTrue(ZipUtil.isZip(zip)),
        () -> assertTrue(Files.size(zip) > 0));
  }

  /** Same file name in two series: flattening must not let one overwrite the other. */
  @Test
  void zipFlattenedKeepsEntriesWithCollidingNames() throws IOException {
    Path source = tempDir.resolve("source"); // NON-NLS
    Files.createDirectories(source.resolve("a")); // NON-NLS
    Files.createDirectories(source.resolve("b")); // NON-NLS
    Files.writeString(source.resolve("a/img.dcm"), "first"); // NON-NLS
    Files.writeString(source.resolve("b/img.dcm"), "second"); // NON-NLS
    Path zip = tempDir.resolve("out.zip"); // NON-NLS
    ZipUtil.zipFlattened(source, zip);

    Path target = Files.createDirectories(tempDir.resolve("target")); // NON-NLS
    ZipUtil.unzipFlattened(zip, target);

    try (Stream<Path> files = Files.list(target)) {
      assertEquals(2, files.count());
    }
  }

  @Test
  void emptyFolderYieldsNoEntry() throws IOException {
    Path source = Files.createDirectories(tempDir.resolve("empty")); // NON-NLS
    Path zip = tempDir.resolve("empty.zip"); // NON-NLS

    assertEquals(0, ZipUtil.zipFlattened(source, zip));
  }

  @Test
  void isZipReadsTheMagicNumberNotTheExtension() throws IOException {
    Path notAZip = tempDir.resolve("result.zip"); // NON-NLS
    Files.write(notAZip, "DICM".getBytes(StandardCharsets.UTF_8)); // NON-NLS
    Path shortFile = tempDir.resolve("short.bin"); // NON-NLS
    Files.write(shortFile, new byte[] {0x50});

    assertAll(
        () -> assertFalse(ZipUtil.isZip(notAZip)), () -> assertFalse(ZipUtil.isZip(shortFile)));
  }

  @Test
  void unzipFlattenedIgnoresDirectoriesAndEntryPaths() throws IOException {
    Path zip = tempDir.resolve("nested.zip"); // NON-NLS
    try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
      out.putNextEntry(new ZipEntry("seg/"));
      out.closeEntry();
      writeEntry(out, "seg/../../escape.dcm", "payload"); // NON-NLS
      writeEntry(out, "seg/inner.dcm", "payload"); // NON-NLS
    }
    Path target = Files.createDirectories(tempDir.resolve("target")); // NON-NLS

    ZipUtil.unzipFlattened(zip, target);

    try (Stream<Path> files = Files.list(target)) {
      List<String> names = files.map(p -> p.getFileName().toString()).sorted().toList();
      assertAll(
          () -> assertEquals(2, names.size()),
          () -> assertTrue(names.getFirst().endsWith("escape.dcm")), // NON-NLS
          () -> assertTrue(names.getLast().endsWith("inner.dcm"))); // NON-NLS
    }
  }

  private static void writeEntry(ZipOutputStream out, String name, String content)
      throws IOException {
    out.putNextEntry(new ZipEntry(name));
    out.write(content.getBytes(StandardCharsets.UTF_8));
    out.closeEntry();
  }
}
