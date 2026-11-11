package com.novelweb.library.imports;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImportContentValidatorTests {

  private static final byte[] MIMETYPE = "application/epub+zip".getBytes(StandardCharsets.US_ASCII);

  @TempDir Path temporaryDirectory;

  private ImportContentValidator validator;

  @BeforeEach
  void setUp() {
    validator = validator(100, 1_048_576, 2_097_152, 100.0, 16);
  }

  @Test
  void acceptsUtf8TextAndRejectsMalformedOrBinaryText() throws IOException {
    Path valid =
        write("story.txt", "Chương một — câu chuyện riêng tư.".getBytes(StandardCharsets.UTF_8));
    Path malformed = write("bad.txt", new byte[] {(byte) 0xc3, 0x28});
    Path binary = write("binary.txt", new byte[] {'a', 0, 'b'});

    assertDoesNotThrow(() -> validator.validate(valid, "story.txt", "text/plain"));
    assertThrows(
        ImportContentValidator.InvalidContentException.class,
        () -> validator.validate(malformed, "bad.txt", "text/plain"));
    assertThrows(
        ImportContentValidator.InvalidContentException.class,
        () -> validator.validate(binary, "binary.txt", "text/plain"));
  }

  @Test
  void checksHtmlMarkupAndDeclaredMimeAgainstExtension() throws IOException {
    Path valid =
        write(
            "story.html",
            "<?xml version=\"1.0\"?><html><body>Story</body></html>"
                .getBytes(StandardCharsets.UTF_8));
    Path plain =
        write("plain.html", "This is plain text, not HTML.".getBytes(StandardCharsets.UTF_8));
    Path wrongExtension =
        write("story.txt", "<html><body>Story</body></html>".getBytes(StandardCharsets.UTF_8));

    assertDoesNotThrow(() -> validator.validate(valid, "story.html", "text/html"));
    assertThrows(
        ImportContentValidator.InvalidContentException.class,
        () -> validator.validate(plain, "plain.html", "text/html"));
    assertThrows(
        ImportContentValidator.InvalidContentException.class,
        () -> validator.validate(wrongExtension, "story.txt", "text/plain"));
    assertThrows(
        ImportContentValidator.InvalidContentException.class,
        () -> validator.validate(valid, "story.html", "text/plain"));

    Path oversized = temporaryDirectory.resolve("oversized.html");
    try (var output = Files.newOutputStream(oversized)) {
      output.write("<html><body>".getBytes(StandardCharsets.UTF_8));
      output.write(new byte[(int) HtmlDocumentParser.MAX_SOURCE_BYTES]);
    }
    assertThrows(
        ImportContentValidator.InvalidContentException.class,
        () -> validator.validate(oversized, "oversized.html", "text/html"));
  }

  @Test
  void acceptsOnlyEpubWithFirstStoredMimetypeAndContainerDocument() throws IOException {
    Path valid = writeEpub("valid.epub", safeEntries());
    List<ArchiveEntry> missingMimetype =
        List.of(deflated("META-INF/container.xml", "<container/>"));
    List<ArchiveEntry> mimetypeNotFirst =
        List.of(
            deflated("META-INF/container.xml", "<container/>"),
            stored("mimetype", MIMETYPE),
            deflated("OPS/chapter.xhtml", "<html/>"));
    List<ArchiveEntry> compressedMimetype =
        List.of(
            deflated("mimetype", new String(MIMETYPE, StandardCharsets.US_ASCII)),
            deflated("META-INF/container.xml", "<container/>"));

    assertDoesNotThrow(() -> validator.validate(valid, "valid.epub", "application/epub+zip"));
    assertThrows(
        ImportContentValidator.InvalidContentException.class,
        () ->
            validator.validate(
                writeEpub("missing.epub", missingMimetype),
                "missing.epub",
                "application/epub+zip"));
    assertThrows(
        ImportContentValidator.InvalidContentException.class,
        () ->
            validator.validate(
                writeEpub("misordered.epub", mimetypeNotFirst),
                "misordered.epub",
                "application/epub+zip"));
    assertThrows(
        ImportContentValidator.InvalidContentException.class,
        () ->
            validator.validate(
                writeEpub("compressed.epub", compressedMimetype),
                "compressed.epub",
                "application/epub+zip"));
  }

  @Test
  void rejectsTraversalDuplicateAndDepthEscapingEpubPaths() throws IOException {
    List<ArchiveEntry> traversal = new ArrayList<>(safeEntries());
    traversal.add(deflated("../outside.xhtml", "<html/>"));
    List<ArchiveEntry> duplicate = new ArrayList<>(safeEntries());
    duplicate.add(deflated("ops/CHAPTER.xhtml", "<html/>"));
    List<ArchiveEntry> invalidName = new ArrayList<>(safeEntries());
    invalidName.add(deflated("OPS/bad:name.xhtml", "<html/>"));
    List<ArchiveEntry> deep = new ArrayList<>(safeEntries());
    deep.add(deflated("OPS/one/two/chapter.xhtml", "<html/>"));
    Path traversalArchive = writeEpub("traversal.epub", traversal);
    Path duplicateArchive = writeEpub("duplicate.epub", duplicate);
    Path invalidNameArchive = writeEpub("invalid-name.epub", invalidName);
    Path deepArchive = writeEpub("deep.epub", deep);
    ImportContentValidator shallowValidator = validator(100, 1_048_576, 2_097_152, 100.0, 3);

    assertThrows(
        ImportContentValidator.InvalidContentException.class,
        () -> validator.validate(traversalArchive, "traversal.epub", "application/epub+zip"));
    assertThrows(
        ImportContentValidator.InvalidContentException.class,
        () -> validator.validate(duplicateArchive, "duplicate.epub", "application/epub+zip"));
    assertThrows(
        ImportContentValidator.InvalidContentException.class,
        () -> validator.validate(invalidNameArchive, "invalid-name.epub", "application/epub+zip"));
    assertThrows(
        ImportContentValidator.InvalidContentException.class,
        () -> shallowValidator.validate(deepArchive, "deep.epub", "application/epub+zip"));
  }

  @Test
  void enforcesArchiveEntryExpandedByteAndCompressionRatioLimits() throws IOException {
    List<ArchiveEntry> manyEntries = new ArrayList<>(safeEntries());
    manyEntries.add(deflated("OPS/extra.xhtml", "<html/>"));
    Path tooMany = writeEpub("many.epub", manyEntries);

    List<ArchiveEntry> expanded = new ArrayList<>(safeEntries());
    expanded.add(deflated("OPS/large.txt", "x".repeat(1024)));
    Path tooLarge = writeEpub("expanded.epub", expanded);

    List<ArchiveEntry> compressed = new ArrayList<>(safeEntries());
    compressed.add(deflated("OPS/repetitive.txt", "z".repeat(1024)));
    Path highRatio = writeEpub("ratio.epub", compressed);

    assertThrows(
        ImportContentValidator.InvalidContentException.class,
        () ->
            validator(2, 1_048_576, 2_097_152, 100.0, 16)
                .validate(tooMany, "many.epub", "application/epub+zip"));
    assertThrows(
        ImportContentValidator.InvalidContentException.class,
        () ->
            validator(100, 512, 2_097_152, 100.0, 16)
                .validate(tooLarge, "expanded.epub", "application/epub+zip"));
    assertThrows(
        ImportContentValidator.InvalidContentException.class,
        () ->
            validator(100, 1_048_576, 2_097_152, 2.0, 16)
                .validate(highRatio, "ratio.epub", "application/epub+zip"));
  }

  @Test
  void rejectsMissingOrInconsistentZipEndRecordAndExpiredValidationBudget() throws IOException {
    byte[] validBytes = Files.readAllBytes(writeEpub("source.epub", safeEntries()));
    Path missingEndRecord =
        write("missing-end.epub", java.util.Arrays.copyOf(validBytes, validBytes.length - 22));
    byte[] inconsistentBytes = validBytes.clone();
    int endRecordOffset = inconsistentBytes.length - 22;
    inconsistentBytes[endRecordOffset + 10] = (byte) 4;
    inconsistentBytes[endRecordOffset + 11] = 0;
    Path inconsistentEndRecord = write("inconsistent-end.epub", inconsistentBytes);
    byte[] missingCentralDirectoryBytes = validBytes.clone();
    missingCentralDirectoryBytes[endRecordOffset + 12] = 0;
    missingCentralDirectoryBytes[endRecordOffset + 13] = 0;
    missingCentralDirectoryBytes[endRecordOffset + 14] = 0;
    missingCentralDirectoryBytes[endRecordOffset + 15] = 0;
    Path missingCentralDirectory = write("missing-central.epub", missingCentralDirectoryBytes);
    Path valid = write("timed.epub", validBytes);

    assertThrows(
        ImportContentValidator.InvalidContentException.class,
        () -> validator.validate(missingEndRecord, "missing-end.epub", "application/epub+zip"));
    assertThrows(
        ImportContentValidator.InvalidContentException.class,
        () ->
            validator.validate(
                inconsistentEndRecord, "inconsistent-end.epub", "application/epub+zip"));
    assertThrows(
        ImportContentValidator.InvalidContentException.class,
        () ->
            validator.validate(
                missingCentralDirectory, "missing-central.epub", "application/epub+zip"));
    assertThrows(
        ImportContentValidator.InvalidContentException.class,
        () ->
            validator(100, 1_048_576, 2_097_152, 100.0, 16, Duration.ofNanos(1))
                .validate(valid, "timed.epub", "application/epub+zip"));
  }

  private ImportContentValidator validator(
      int entryLimit, long entryBytes, long expandedBytes, double ratio, int depth) {
    return validator(entryLimit, entryBytes, expandedBytes, ratio, depth, Duration.ofSeconds(2));
  }

  private ImportContentValidator validator(
      int entryLimit,
      long entryBytes,
      long expandedBytes,
      double ratio,
      int depth,
      Duration timeout) {
    ImportStagingProperties properties =
        new ImportStagingProperties(
            temporaryDirectory.resolve("staging"),
            2_097_152,
            Duration.ofMinutes(15),
            Duration.ofHours(24),
            entryLimit,
            entryBytes,
            expandedBytes,
            ratio,
            depth,
            timeout,
            100,
            Duration.ofMinutes(1),
            5,
            10_485_760,
            10,
            Duration.ofSeconds(6),
            5,
            Duration.ofSeconds(10),
            Duration.ofHours(24));
    return new ImportContentValidator(properties);
  }

  private Path write(String name, byte[] content) throws IOException {
    Path path = temporaryDirectory.resolve(name);
    Files.write(path, content);
    return path;
  }

  private Path writeEpub(String name, List<ArchiveEntry> entries) throws IOException {
    Path path = temporaryDirectory.resolve(name);
    try (ZipOutputStream output =
        new ZipOutputStream(Files.newOutputStream(path), StandardCharsets.UTF_8)) {
      for (ArchiveEntry entry : entries) {
        ZipEntry zipEntry = new ZipEntry(entry.name());
        if (entry.stored()) {
          CRC32 crc = new CRC32();
          crc.update(entry.content());
          zipEntry.setMethod(ZipEntry.STORED);
          zipEntry.setSize(entry.content().length);
          zipEntry.setCompressedSize(entry.content().length);
          zipEntry.setCrc(crc.getValue());
        }
        output.putNextEntry(zipEntry);
        output.write(entry.content());
        output.closeEntry();
      }
    }
    return path;
  }

  private static List<ArchiveEntry> safeEntries() {
    return List.of(
        stored("mimetype", MIMETYPE),
        deflated("META-INF/container.xml", "<container/>"),
        deflated("OPS/chapter.xhtml", "<html><body>Chapter</body></html>"));
  }

  private static ArchiveEntry stored(String name, byte[] content) {
    return new ArchiveEntry(name, content, true);
  }

  private static ArchiveEntry deflated(String name, String content) {
    return new ArchiveEntry(name, content.getBytes(StandardCharsets.UTF_8), false);
  }

  private record ArchiveEntry(String name, byte[] content, boolean stored) {}
}
