package com.novelweb.library.imports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.novelweb.library.imports.EpubPackageParser.Preview;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HtmlDocumentParserTests {

  private final HtmlDocumentParser parser = new HtmlDocumentParser();

  @TempDir Path temporaryDirectory;

  @Test
  void extractsNormalizedTextAndMetadataWithoutRetainingMarkupOrPageChrome() throws Exception {
    String html =
        "\ufeff<!doctype html><html lang='vi'><head><title>  Đường   xa </title>"
            + "<meta name='author' content=' Minh An '><meta name='description' content='Một hành trình.'>"
            + "<style>.private { display: none }</style></head><body>"
            + "<nav>Điều hướng riêng</nav><article><h1>Chương một</h1>"
            + "<p>Cafe\u0301 <strong>và</strong> câu chuyện.</p>"
            + "<blockquote><p>Gió thổi qua rừng.</p></blockquote>"
            + "<p hidden>Văn bản ẩn.</p><p style='display:none'>Cũng ẩn.</p>"
            + "<script>window.secret='không được nhập'</script><svg><text>Văn bản SVG.</text></svg>"
            + "<p><a href='https://example.test'>Liên kết không tải mạng.</a></p></article></body></html>";

    Preview preview = parse("story.html", "text/html; charset=UTF-8", html, "html-v1");

    assertEquals("html-v1", preview.parserVersion());
    assertEquals("Đường xa", preview.metadata().title());
    assertEquals("vi", preview.metadata().language());
    assertEquals("Minh An", preview.metadata().creators().getFirst());
    assertEquals("Một hành trình.", preview.metadata().description());
    assertEquals(1, preview.chapters().size());
    assertEquals("Chương một", preview.chapters().getFirst().title());
    assertEquals("source.html", preview.chapters().getFirst().sourcePath());
    assertEquals("heading", preview.chapters().getFirst().segments().get(0).kind());
    assertEquals("Café và câu chuyện.", preview.chapters().getFirst().segments().get(1).text());
    assertEquals("quote", preview.chapters().getFirst().segments().get(2).kind());
    assertEquals(
        "Liên kết không tải mạng.", preview.chapters().getFirst().segments().get(3).text());
    assertFalse(preview.chapters().getFirst().textPreview().contains("không được nhập"));
    assertFalse(preview.chapters().getFirst().textPreview().contains("Điều hướng riêng"));
    assertFalse(preview.chapters().getFirst().textPreview().contains("Văn bản SVG"));
    assertEquals("HTML_SINGLE_DOCUMENT", preview.warnings().getFirst().code());
    assertEquals(1, preview.toc().size());
  }

  @Test
  void fallsBackToFilenameAndSplitsOversizedParagraphAtUnicodeBoundaries() throws Exception {
    Preview preview =
        parse(
            "No title.htm",
            "application/xhtml+xml",
            "<html><body><p>😀".repeat(1) + "😀".repeat(4_001) + "</p></body></html>",
            "html-v1");

    assertEquals("No title", preview.metadata().title());
    assertEquals(2, preview.chapters().getFirst().segments().size());
    assertEquals(
        4_000, preview.chapters().getFirst().segments().getFirst().text().codePointCount(0, 8_000));
    assertEquals(2, preview.chapters().getFirst().segments().get(1).text().codePointCount(0, 4));
  }

  @Test
  void rejectsEmptyMalformedAndOverBudgetHtml() throws Exception {
    assertEquals(
        "IMPORT_HTML_EMPTY",
        assertThrows(
                HtmlDocumentParser.HtmlParseException.class,
                () ->
                    parse(
                        "empty.html",
                        "text/html",
                        "<html><body><nav>only chrome</nav></body></html>",
                        "html-v1"))
            .code());

    Path malformed = temporaryDirectory.resolve("malformed.html");
    Files.write(malformed, new byte[] {'<', 'p', '>', (byte) 0xc3, 0x28, '<', '/', 'p', '>'});
    assertEquals(
        "IMPORT_HTML_ENCODING_INVALID",
        assertThrows(
                HtmlDocumentParser.HtmlParseException.class,
                () -> parser.parse(malformed, "malformed.html", "text/html", "html-v1"))
            .code());

    assertEquals(
        "IMPORT_HTML_SOURCE_LIMIT_EXCEEDED",
        assertThrows(
                HtmlDocumentParser.HtmlParseException.class,
                () ->
                    parse(
                        "large.html",
                        "text/html",
                        "<p>" + "x".repeat(4 * 1024 * 1024) + "</p>",
                        "html-v1"))
            .code());
  }

  @Test
  void rejectsUnsupportedMimeExtensionAndParserVersion() throws Exception {
    assertEquals(
        "IMPORT_FORMAT_UNSUPPORTED",
        assertThrows(
                HtmlDocumentParser.HtmlParseException.class,
                () -> parse("story.md", "text/html", "<p>Story</p>", "html-v1"))
            .code());
    assertEquals(
        "IMPORT_CONTENT_INVALID",
        assertThrows(
                HtmlDocumentParser.HtmlParseException.class,
                () -> parse("story.html", "text/plain", "<p>Story</p>", "html-v1"))
            .code());
    assertEquals(
        "IMPORT_PARSER_VERSION_UNSUPPORTED",
        assertThrows(
                HtmlDocumentParser.HtmlParseException.class,
                () -> parse("story.html", "text/html", "<p>Story</p>", "html-v2"))
            .code());
  }

  @Test
  void enforcesTheNormalizedTextSegmentLimit() throws Exception {
    StringBuilder html = new StringBuilder("<html><body>");
    for (int index = 0; index < 5_001; index++) html.append("<p>x</p>");
    html.append("</body></html>");

    assertEquals(
        "IMPORT_HTML_SEGMENT_LIMIT_EXCEEDED",
        assertThrows(
                HtmlDocumentParser.HtmlParseException.class,
                () -> parse("many.html", "text/html", html.toString(), "html-v1"))
            .code());
  }

  private Preview parse(String filename, String contentType, String content, String version)
      throws Exception {
    Path source = temporaryDirectory.resolve(filename);
    Files.writeString(source, content, StandardCharsets.UTF_8);
    return parser.parse(source, filename, contentType, version);
  }
}
