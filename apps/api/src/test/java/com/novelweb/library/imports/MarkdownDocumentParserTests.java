package com.novelweb.library.imports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.novelweb.library.imports.EpubPackageParser.Preview;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MarkdownDocumentParserTests {

  private final MarkdownDocumentParser parser = new MarkdownDocumentParser();

  @TempDir Path temporaryDirectory;

  @Test
  void splitsRepeatedTopLevelHeadingsAndKeepsOnlySafeVisibleText() throws Exception {
    String markdown =
        "# The Rain City\n\n"
            + "## Chapter One\n\n"
            + "A **quiet** story with [visible words](https://private.example/secret) and `code`.\n\n"
            + "![cover description](https://private.example/cover.png)\n\n"
            + "> A line from the narrator.\n\n"
            + "<script>alert('do not retain')</script>\n\n"
            + "## Chapter Two\n\n"
            + "The rain returned.\n";

    Preview preview = parse("rain.md", "text/markdown; charset=utf-8", markdown, "markdown-v1");

    assertEquals("markdown-v1", preview.parserVersion());
    assertEquals("The Rain City", preview.metadata().title());
    assertEquals("und", preview.metadata().language());
    assertEquals(2, preview.chapters().size());
    assertEquals("Chapter One", preview.chapters().get(0).title());
    assertEquals("Chapter Two", preview.chapters().get(1).title());
    assertEquals("source.md", preview.chapters().get(0).sourcePath());
    assertEquals(2, preview.toc().size());
    assertEquals("heading", preview.chapters().get(0).segments().get(0).kind());
    assertTrue(
        preview.chapters().get(0).segments().stream()
            .anyMatch(
                segment -> segment.text().contains("quiet story with visible words and code")));
    assertTrue(
        preview.chapters().get(0).segments().stream()
            .anyMatch(segment -> segment.text().contains("cover description")));
    assertTrue(
        preview.chapters().get(0).segments().stream()
            .anyMatch(segment -> "quote".equals(segment.kind())));
    assertFalse(allText(preview).contains("private.example"));
    assertFalse(allText(preview).contains("<script>"));
    assertFalse(allText(preview).contains("alert('do not retain')"));
    assertEquals("MARKDOWN_CHAPTERS_FROM_HEADINGS", preview.warnings().get(1).code());
  }

  @Test
  void splitsSetextHeadingsAndPreservesAnIntroductionChapter() throws Exception {
    Preview preview =
        parse(
            "setext.markdown",
            "text/x-markdown",
            "# The book\n\nOpening note.\n\nChapter One\n-----------\n\nFirst.\n\nChapter Two\n-----------\n\nSecond.",
            "markdown-v1");

    assertEquals("The book", preview.metadata().title());
    assertEquals(3, preview.chapters().size());
    assertEquals("Mở đầu", preview.chapters().get(0).title());
    assertEquals("Chapter One", preview.chapters().get(1).title());
    assertEquals("Chapter Two", preview.chapters().get(2).title());
    assertEquals("Opening note.", preview.chapters().get(0).segments().getFirst().text());
    assertEquals(3, preview.toc().size());
  }

  @Test
  void keepsDocumentsWithoutRepeatedTopLevelHeadingsInOneChapter() throws Exception {
    Preview preview =
        parse(
            "single-title.md",
            "text/plain",
            "# The Quiet Road\n\nA *single* chapter with [words](https://private.example).",
            "markdown-v1");

    assertEquals("The Quiet Road", preview.metadata().title());
    assertEquals(1, preview.chapters().size());
    assertEquals("The Quiet Road", preview.chapters().getFirst().title());
    assertEquals("MARKDOWN_SINGLE_DOCUMENT", preview.warnings().get(1).code());
    assertFalse(allText(preview).contains("https://"));
  }

  @Test
  void normalizesBOMAndUnicodeAndSplitsSegmentsAtCodePointBoundaries() throws Exception {
    Preview preview =
        parse(
            "unicode.markdown",
            "application/octet-stream",
            "\ufeff" + "😀".repeat(4_001),
            "markdown-v1");

    assertEquals(1, preview.chapters().size());
    assertEquals(2, preview.chapters().getFirst().segments().size());
    assertEquals(
        4_000, preview.chapters().getFirst().segments().getFirst().text().codePointCount(0, 8_000));
    assertEquals(1, preview.chapters().getFirst().segments().get(1).text().codePointCount(0, 2));
  }

  @Test
  void rejectsEmptyMalformedEncodingUnsupportedMimeExtensionVersionAndOversize() throws Exception {
    assertEquals(
        "IMPORT_MARKDOWN_EMPTY",
        assertThrows(
                MarkdownDocumentParser.MarkdownParseException.class,
                () -> parse("empty.md", "text/markdown", " \n\t ", "markdown-v1"))
            .code());

    Path malformed = temporaryDirectory.resolve("malformed.md");
    Files.write(malformed, new byte[] {'#', ' ', (byte) 0xc3, 0x28});
    assertEquals(
        "IMPORT_MARKDOWN_ENCODING_INVALID",
        assertThrows(
                MarkdownDocumentParser.MarkdownParseException.class,
                () -> parser.parse(malformed, "malformed.md", "text/markdown", "markdown-v1"))
            .code());

    Path controls = temporaryDirectory.resolve("controls.md");
    Files.write(controls, new byte[] {'#', ' ', 'S', 't', 'o', 'r', 'y', 0});
    assertEquals(
        "IMPORT_MARKDOWN_CONTROL_INVALID",
        assertThrows(
                MarkdownDocumentParser.MarkdownParseException.class,
                () -> parser.parse(controls, "controls.md", "text/markdown", "markdown-v1"))
            .code());

    assertEquals(
        "IMPORT_FORMAT_UNSUPPORTED",
        assertThrows(
                MarkdownDocumentParser.MarkdownParseException.class,
                () -> parse("story.html", "text/markdown", "Story", "markdown-v1"))
            .code());
    assertEquals(
        "IMPORT_CONTENT_INVALID",
        assertThrows(
                MarkdownDocumentParser.MarkdownParseException.class,
                () -> parse("story.md", "image/png", "Story", "markdown-v1"))
            .code());
    assertEquals(
        "IMPORT_PARSER_VERSION_UNSUPPORTED",
        assertThrows(
                MarkdownDocumentParser.MarkdownParseException.class,
                () -> parse("story.md", "text/markdown", "Story", "markdown-v2"))
            .code());

    Path oversized = temporaryDirectory.resolve("oversized.md");
    Files.write(oversized, new byte[(int) MarkdownDocumentParser.MAX_SOURCE_BYTES + 1]);
    assertEquals(
        "IMPORT_MARKDOWN_SOURCE_LIMIT_EXCEEDED",
        assertThrows(
                MarkdownDocumentParser.MarkdownParseException.class,
                () -> parser.parse(oversized, "oversized.md", "text/markdown", "markdown-v1"))
            .code());
  }

  @Test
  void rejectsAstChapterSegmentAndNormalizedTextLimitViolations() throws Exception {
    assertCodeForContent(
        "node-limit.md", "x\n\n".repeat(25_001), "IMPORT_MARKDOWN_NODE_LIMIT_EXCEEDED");

    StringBuilder manyChapters = new StringBuilder();
    for (int index = 0; index <= 2_000; index++) {
      manyChapters.append("## Chapter ").append(index).append("\n\nBody.\n\n");
    }
    assertCodeForContent(
        "chapter-limit.md", manyChapters.toString(), "IMPORT_MARKDOWN_CHAPTER_LIMIT_EXCEEDED");

    StringBuilder manySegments = new StringBuilder();
    for (int index = 0; index <= 5_000; index++) {
      manySegments.append("Paragraph ").append(index).append(".\n\n");
    }
    assertCodeForContent(
        "segment-limit.md", manySegments.toString(), "IMPORT_MARKDOWN_SEGMENT_LIMIT_EXCEEDED");

    assertCodeForContent(
        "text-limit.md", "x".repeat(384 * 1024 + 1), "IMPORT_MARKDOWN_TEXT_LIMIT_EXCEEDED");
  }

  private Preview parse(String filename, String contentType, String content, String version)
      throws Exception {
    Path source = temporaryDirectory.resolve(filename);
    Files.writeString(source, content, StandardCharsets.UTF_8);
    return parser.parse(source, filename, contentType, version);
  }

  private void assertCodeForContent(String filename, String content, String expectedCode)
      throws Exception {
    Path source = temporaryDirectory.resolve(filename);
    Files.writeString(source, content, StandardCharsets.UTF_8);
    assertEquals(
        expectedCode,
        assertThrows(
                MarkdownDocumentParser.MarkdownParseException.class,
                () -> parser.parse(source, filename, "text/markdown", "markdown-v1"))
            .code());
  }

  private static String allText(Preview preview) {
    return preview.chapters().stream()
        .flatMap(chapter -> chapter.segments().stream())
        .map(segment -> segment.text())
        .reduce("", (left, right) -> left + "\n" + right);
  }
}
