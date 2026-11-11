package com.novelweb.library.imports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.novelweb.library.imports.EpubPackageParser.Preview;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlainTextParserTests {

  private final PlainTextParser parser = new PlainTextParser();

  @TempDir Path temporaryDirectory;

  @Test
  void parsesUtf8LinesAsOneChapterWithNormalizedSegmentsAndSafeMetadata() throws Exception {
    Path source =
        write("A Book.txt", "\ufeffFirst  line.\r\n\r\ne\u0301 and 😀 on the second line.\n");

    Preview preview = parser.parse(source, "A Book.txt", "text/plain; charset=UTF-8", "txt-v1");

    assertEquals("txt-v1", preview.parserVersion());
    assertEquals("A Book", preview.metadata().title());
    assertEquals("und", preview.metadata().language());
    assertTrue(preview.metadata().creators().isEmpty());
    assertEquals(1, preview.chapters().size());
    assertEquals("Nội dung", preview.chapters().get(0).title());
    assertEquals(2, preview.chapters().get(0).segmentCount());
    assertEquals("First line.", preview.chapters().get(0).segments().get(0).text());
    assertEquals(
        "é and 😀 on the second line.", preview.chapters().get(0).segments().get(1).text());
    assertEquals(1, preview.toc().size());
    assertEquals("TXT_SINGLE_CHAPTER", preview.warnings().get(0).code());
  }

  @Test
  void splitsLongLinesAtUnicodeCodePointBoundariesWithoutLosingContent() throws Exception {
    String content = "😀".repeat(4_001);
    Preview preview = parser.parse(write("Long.txt", content), "Long.txt", "text/plain", "txt-v1");

    assertEquals(2, preview.chapters().get(0).segments().size());
    assertEquals(
        4_000, preview.chapters().get(0).segments().get(0).text().codePointCount(0, 8_000));
    assertEquals(1, preview.chapters().get(0).segments().get(1).text().codePointCount(0, 2));
    assertEquals(
        content,
        preview.chapters().get(0).segments().stream()
            .map(s -> s.text())
            .reduce("", String::concat));
  }

  @Test
  void rejectsEmptyMalformedAndOverBudgetText() throws Exception {
    Path empty = temporaryDirectory.resolve("empty.txt");
    Files.writeString(empty, " \r\n\t\n", StandardCharsets.UTF_8);
    assertEquals(
        "IMPORT_TEXT_EMPTY",
        assertThrows(
                PlainTextParser.TextParseException.class,
                () -> parser.parse(empty, "empty.txt", "text/plain", "txt-v1"))
            .code());

    Path malformed = temporaryDirectory.resolve("malformed.txt");
    Files.write(malformed, new byte[] {(byte) 0xc3, 0x28});
    assertEquals(
        "IMPORT_TEXT_ENCODING_INVALID",
        assertThrows(
                PlainTextParser.TextParseException.class,
                () -> parser.parse(malformed, "malformed.txt", "text/plain", "txt-v1"))
            .code());

    Path oversized = temporaryDirectory.resolve("oversized.txt");
    Files.writeString(oversized, ("x".repeat(4_000) + "\n").repeat(100), StandardCharsets.UTF_8);
    assertEquals(
        "IMPORT_TEXT_SOURCE_LIMIT_EXCEEDED",
        assertThrows(
                PlainTextParser.TextParseException.class,
                () -> parser.parse(oversized, "oversized.txt", "text/plain", "txt-v1"))
            .code());
  }

  @Test
  void rejectsUnsupportedExtensionsMimeTypesAndParserVersions() throws Exception {
    Path source = write("book.txt", "Text");
    assertEquals(
        "IMPORT_FORMAT_UNSUPPORTED",
        assertThrows(
                PlainTextParser.TextParseException.class,
                () -> parser.parse(source, "book.html", "text/plain", "txt-v1"))
            .code());
    assertEquals(
        "IMPORT_CONTENT_INVALID",
        assertThrows(
                PlainTextParser.TextParseException.class,
                () -> parser.parse(source, "book.txt", "text/html", "txt-v1"))
            .code());
    assertEquals(
        "IMPORT_PARSER_VERSION_UNSUPPORTED",
        assertThrows(
                PlainTextParser.TextParseException.class,
                () -> parser.parse(source, "book.txt", "text/plain", "txt-v2"))
            .code());
  }

  private Path write(String filename, String content) throws Exception {
    Path source = temporaryDirectory.resolve(filename);
    Files.writeString(source, content, StandardCharsets.UTF_8);
    return source;
  }
}
