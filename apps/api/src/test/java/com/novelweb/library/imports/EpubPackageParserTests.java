package com.novelweb.library.imports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EpubPackageParserTests {

  private static final byte[] MIMETYPE = "application/epub+zip".getBytes(StandardCharsets.US_ASCII);

  @TempDir Path temporaryDirectory;

  private EpubPackageParser parser;

  @BeforeEach
  void setUp() {
    ImportStagingProperties properties =
        new ImportStagingProperties(
            temporaryDirectory.resolve("staging"),
            2_097_152,
            Duration.ofMinutes(15),
            Duration.ofHours(24),
            100,
            1_048_576,
            2_097_152,
            100.0,
            16,
            Duration.ofSeconds(5),
            100,
            Duration.ofMinutes(1),
            5,
            10_485_760,
            10,
            Duration.ofSeconds(6),
            5,
            Duration.ofSeconds(10),
            Duration.ofHours(24));
    parser = new EpubPackageParser(new ImportContentValidator(properties), properties);
  }

  @Test
  void extractsPackageMetadataSpineOrderNestedTocAndBoundedChapterPreview() throws IOException {
    Path epub =
        writeEpub(
            "ordered.epub",
            validEntries(
                "<nav epub:type=\"toc\"><ol>"
                    + "<li><a href=\"Text/one.xhtml#opening\">First chapter</a><ol>"
                    + "<li><a href=\"Text/two.xhtml\">Nested chapter</a></li></ol></li>"
                    + "</ol></nav>",
                "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>Document one</title></head>"
                    + "<body><h1 id=\"opening\">Opening heading</h1><p>Private preview text.</p>"
                    + "<script>hidden()</script></body></html>",
                "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>Document two</title></head>"
                    + "<body><p>Second content.</p></body></html>"));

    EpubPackageParser.Preview preview = parser.parse(epub, "ordered.epub", "application/epub+zip");

    assertEquals("epub3-v2", preview.parserVersion());
    assertEquals("A Private Novel", preview.metadata().title());
    assertEquals("vi", preview.metadata().language());
    assertEquals(List.of("A. Author", "B. Author"), preview.metadata().creators());
    assertEquals(2, preview.chapters().size());
    assertEquals("OPS/Text/one.xhtml", preview.chapters().get(0).sourcePath());
    assertEquals("First chapter", preview.chapters().get(0).title());
    assertEquals(2, preview.chapters().get(0).segmentCount());
    assertEquals("Opening heading", preview.chapters().get(0).textPreview());
    assertEquals(
        List.of(
            new EpubPackageParser.SourceSegment(0, "h1", "Opening heading"),
            new EpubPackageParser.SourceSegment(1, "p", "Private preview text.")),
        preview.chapters().get(0).segments());
    assertTrue(preview.chapters().get(0).textPreview().length() <= 280);
    assertEquals("Nested chapter", preview.chapters().get(1).title());
    assertEquals(2, preview.toc().size());
    assertEquals(0, preview.toc().get(0).depth());
    assertEquals(1, preview.toc().get(1).depth());
    assertEquals(0, preview.toc().get(0).chapterIndex());
    assertEquals("opening", preview.toc().get(0).fragment());
    assertTrue(
        preview.warnings().stream()
            .anyMatch(warning -> warning.code().equals("EPUB_SCRIPT_IGNORED")));
  }

  @Test
  void missingOrUnusableNavigationFallsBackToSpineOrder() throws IOException {
    Path noNav =
        writeEpub(
            "no-nav.epub",
            validEntries(
                null,
                "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><p>Chapter text.</p></body></html>",
                "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><p>Next text.</p></body></html>"));

    EpubPackageParser.Preview preview = parser.parse(noNav, "no-nav.epub", "application/epub+zip");

    assertEquals(2, preview.toc().size());
    assertEquals("Chapter 1", preview.toc().get(0).label());
    assertEquals("Chapter 2", preview.toc().get(1).label());
    assertTrue(
        preview.warnings().stream().anyMatch(warning -> warning.code().equals("EPUB_TOC_MISSING")));
  }

  @Test
  void rejectsMalformedXmlUnsupportedEpubVersionAndUnsupportedEncoding() throws IOException {
    Path malformed =
        writeEpub(
            "malformed.epub",
            validEntries(
                null,
                "<html><body><p>Good</p></body></html>",
                null,
                "<container><rootfiles>",
                null,
                null));
    Path epub2 =
        writeEpub(
            "epub2.epub",
            validEntries(null, "<html><body><p>Good</p></body></html>", null, null, null, "2.0"));
    Path wrongEncoding =
        writeEpub(
            "encoding.epub",
            validEntries(
                null,
                "<html><body><p>Good</p></body></html>",
                null,
                null,
                null,
                "3.0",
                "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>"));

    assertEquals(
        "EPUB_XML_INVALID",
        assertThrows(
                EpubPackageParser.EpubParseException.class,
                () -> parser.parse(malformed, "malformed.epub", "application/epub+zip"))
            .code());
    assertEquals(
        "EPUB_VERSION_UNSUPPORTED",
        assertThrows(
                EpubPackageParser.EpubParseException.class,
                () -> parser.parse(epub2, "epub2.epub", "application/epub+zip"))
            .code());
    assertEquals(
        "EPUB_ENCODING_UNSUPPORTED",
        assertThrows(
                EpubPackageParser.EpubParseException.class,
                () -> parser.parse(wrongEncoding, "encoding.epub", "application/epub+zip"))
            .code());
  }

  @Test
  void reportsEmptySpineDocumentAndPreservesItsPosition() throws IOException {
    Path epub =
        writeEpub(
            "empty-chapter.epub",
            validEntries(
                "<nav epub:type=\"toc\"><ol><li><a href=\"Text/one.xhtml\">Empty</a></li>"
                    + "<li><a href=\"Text/two.xhtml\">Readable</a></li></ol></nav>",
                "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><p>   </p></body></html>",
                "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><p>Has text.</p></body></html>"));

    EpubPackageParser.Preview preview =
        parser.parse(epub, "empty-chapter.epub", "application/epub+zip");

    assertEquals(2, preview.chapters().size());
    assertTrue(preview.chapters().get(0).empty());
    assertFalse(preview.chapters().get(1).empty());
    assertEquals(0, preview.chapters().get(0).index());
    assertEquals(1, preview.chapters().get(1).index());
    assertTrue(
        preview.warnings().stream()
            .anyMatch(warning -> warning.code().equals("EPUB_CHAPTER_EMPTY")));
    assertTrue(preview.chapters().get(0).segments().isEmpty());
  }

  @Test
  void normalizesSourceSegmentsToNfcAndCollapsesWhitespace() throws IOException {
    Path epub =
        writeEpub(
            "normalized-segments.epub",
            validEntries(
                null,
                "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><p>  Cafe&#x301;\u00a0  \n text </p></body></html>",
                null));

    EpubPackageParser.Preview preview =
        parser.parse(epub, "normalized-segments.epub", "application/epub+zip");

    assertEquals(
        List.of(new EpubPackageParser.SourceSegment(0, "p", "Café text")),
        preview.chapters().get(0).segments());
  }

  @Test
  void preservesVersionOneBehaviorForAlreadyQueuedParseJobs() throws IOException {
    Path epub =
        writeEpub(
            "legacy-version.epub",
            validEntries(
                null,
                "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><h1>Old heading</h1><p>Old body.</p></body></html>",
                null));

    EpubPackageParser.Preview preview =
        parser.parse(epub, "legacy-version.epub", "application/epub+zip", "epub3-v1");

    assertEquals("epub3-v1", preview.parserVersion());
    assertEquals(2, preview.chapters().get(0).segmentCount());
    assertEquals("Old heading", preview.chapters().get(0).textPreview());
    assertTrue(preview.chapters().get(0).segments().isEmpty());
  }

  @Test
  void rejectsOversizedSegmentAndExcessiveSegmentCountWithoutReturningPartialPreview()
      throws IOException {
    Path oversized =
        writeEpub(
            "oversized-segment.epub",
            validEntries(
                null,
                "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><p>"
                    + variedText(4001, 1)
                    + "</p></body></html>",
                null));
    StringBuilder manyParagraphs =
        new StringBuilder("<html xmlns=\"http://www.w3.org/1999/xhtml\"><body>");
    for (int index = 0; index < 5001; index++) {
      manyParagraphs.append("<p>").append(variedText(12, index)).append("</p>");
    }
    manyParagraphs.append("</body></html>");
    Path excessiveCount =
        writeEpub("excessive-segments.epub", validEntries(null, manyParagraphs.toString(), null));

    assertEquals(
        "EPUB_SEGMENT_TOO_LARGE",
        assertThrows(
                EpubPackageParser.EpubParseException.class,
                () -> parser.parse(oversized, "oversized-segment.epub", "application/epub+zip"))
            .code());
    assertEquals(
        "EPUB_SEGMENT_LIMIT_EXCEEDED",
        assertThrows(
                EpubPackageParser.EpubParseException.class,
                () ->
                    parser.parse(excessiveCount, "excessive-segments.epub", "application/epub+zip"))
            .code());
  }

  @Test
  void rejectsSpineThatExceedsTheChapterBound() throws IOException {
    StringBuilder manifest = new StringBuilder();
    StringBuilder spine = new StringBuilder();
    for (int index = 0; index < 2001; index++) {
      manifest
          .append("<item id=\"chapter-")
          .append(index)
          .append("\" href=\"Text/")
          .append(index)
          .append(".xhtml\" media-type=\"application/xhtml+xml\"/>");
      spine.append("<itemref idref=\"chapter-").append(index).append("\"/>");
    }
    String packageDocument =
        packageXml(metadataXml(), manifest.toString(), spine.toString(), "3.0");
    Path epub =
        writeEpub(
            "excessive-chapters.epub",
            entries(
                containerXml(),
                packageDocument,
                "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><p>Readable.</p></body></html>"));

    assertEquals(
        "EPUB_SPINE_LIMIT_EXCEEDED",
        assertThrows(
                EpubPackageParser.EpubParseException.class,
                () -> parser.parse(epub, "excessive-chapters.epub", "application/epub+zip"))
            .code());
  }

  @Test
  void rejectsSourceSegmentsWhenAggregateNormalizedBytesExceedLimit() throws IOException {
    StringBuilder chapter =
        new StringBuilder("<html xmlns=\"http://www.w3.org/1999/xhtml\"><body>");
    for (int index = 0; index < 99; index++) {
      chapter.append("<p>").append(variedText(4000, index)).append("</p>");
    }
    chapter.append("</body></html>");
    Path epub =
        writeEpub("aggregate-segment-bytes.epub", validEntries(null, chapter.toString(), null));

    assertEquals(
        "EPUB_SEGMENT_BYTES_LIMIT_EXCEEDED",
        assertThrows(
                EpubPackageParser.EpubParseException.class,
                () -> parser.parse(epub, "aggregate-segment-bytes.epub", "application/epub+zip"))
            .code());
  }

  @Test
  void blocksDtdExternalEntitiesAndNeverExtractsArchivePaths() throws IOException {
    Path epub =
        writeEpub(
            "xxe.epub",
            validEntries(
                null,
                "<!DOCTYPE html [<!ENTITY xxe SYSTEM \"file:///Windows/win.ini\">]><html><body><p>&xxe;</p></body></html>",
                null));

    assertThrows(
        EpubPackageParser.EpubParseException.class,
        () -> parser.parse(epub, "xxe.epub", "application/epub+zip"));
    try (var files = Files.list(temporaryDirectory)) {
      assertEquals(
          List.of("xxe.epub"),
          files
              .map(path -> path.getFileName().toString())
              .filter(name -> name.endsWith(".epub"))
              .toList());
    }
  }

  @Test
  void neverFetchesLoopbackForAnExternalEntityInAnEpubChapter() throws Exception {
    CountDownLatch receivedRequest = new CountDownLatch(1);
    var server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/probe",
        exchange -> {
          receivedRequest.countDown();
          exchange.sendResponseHeaders(204, -1);
          exchange.close();
        });
    server.start();

    try {
      String remote = "http://127.0.0.1:" + server.getAddress().getPort() + "/probe";
      String chapter =
          "<!DOCTYPE html [<!ENTITY xxe SYSTEM \""
              + remote
              + "\">]><html><body><p>&xxe;</p></body></html>";
      Path epub = writeEpub("external-http-entity.epub", validEntries(null, chapter, null));

      assertThrows(
          EpubPackageParser.EpubParseException.class,
          () -> parser.parse(epub, "external-http-entity.epub", "application/epub+zip"));
      assertFalse(
          receivedRequest.await(250, TimeUnit.MILLISECONDS),
          "an EPUB external entity triggered an outbound HTTP request");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void returnsOnlyPlainTextForXhtmlContainingExecutableMarkupAndRemoteReferences()
      throws IOException {
    String remote = "https://reader-xss.invalid/resource";
    String chapter =
        "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>Safe chapter</title>"
            + "<link rel=\"stylesheet\" href=\""
            + remote
            + "\"/></head><body><p>Safe <img src=\""
            + remote
            + "\" onerror=\"window.readerInjected=true\"/> text "
            + "<a href=\"javascript:window.readerInjected=true\">link label</a>.</p>"
            + "<script>window.readerInjected=true</script>"
            + "<style>@import url('"
            + remote
            + "');</style><svg onload=\"window.readerInjected=true\"><script>"
            + "window.readerInjected=true</script><image href=\""
            + remote
            + "\"/></svg><iframe src=\""
            + remote
            + "\">frame text must be discarded</iframe></body></html>";
    Path epub = writeEpub("hostile-markup.epub", validEntries(null, chapter, null));

    EpubPackageParser.Preview preview =
        parser.parse(epub, "hostile-markup.epub", "application/epub+zip");

    assertEquals(
        List.of(new EpubPackageParser.SourceSegment(0, "p", "Safe text link label.")),
        preview.chapters().getFirst().segments());
    assertTrue(
        preview.warnings().stream()
            .anyMatch(warning -> warning.code().equals("EPUB_SCRIPT_IGNORED")));
    String serializedText =
        preview.chapters().stream()
            .flatMap(chapterPreview -> chapterPreview.segments().stream())
            .map(EpubPackageParser.SourceSegment::text)
            .reduce("", (left, right) -> left + right);
    assertFalse(serializedText.contains("reader-xss.invalid"));
    assertFalse(serializedText.contains("readerInjected"));
    assertFalse(serializedText.contains("frame text must be discarded"));
  }

  @Test
  void rejectsMissingRequiredContainerMetadataAndSpineResources() throws IOException {
    Path missingTitle =
        writeEpub(
            "missing-title.epub",
            entries(
                "<container><rootfiles><rootfile full-path=\"OPS/package.opf\" media-type=\"application/oebps-package+xml\"/></rootfiles></container>",
                packageXml(
                    "",
                    "<item id=\"chapter\" href=\"Text/one.xhtml\" media-type=\"application/xhtml+xml\"/>",
                    "<itemref idref=\"chapter\"/>",
                    "3.0"),
                "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><p>Text</p></body></html>"));
    Path missingResource =
        writeEpub(
            "missing-resource.epub",
            entries(
                containerXml(),
                packageXml(
                    metadataXml(),
                    "<item id=\"chapter\" href=\"Text/missing.xhtml\" media-type=\"application/xhtml+xml\"/>",
                    "<itemref idref=\"chapter\"/>",
                    "3.0"),
                null));

    assertEquals(
        "EPUB_METADATA_REQUIRED",
        assertThrows(
                EpubPackageParser.EpubParseException.class,
                () -> parser.parse(missingTitle, "missing-title.epub", "application/epub+zip"))
            .code());
    assertEquals(
        "EPUB_SPINE_RESOURCE_MISSING",
        assertThrows(
                EpubPackageParser.EpubParseException.class,
                () ->
                    parser.parse(missingResource, "missing-resource.epub", "application/epub+zip"))
            .code());
  }

  private Path writeEpub(String filename, List<ArchiveEntry> entries) throws IOException {
    Path file = temporaryDirectory.resolve(filename);
    try (ZipOutputStream output =
        new ZipOutputStream(Files.newOutputStream(file), StandardCharsets.UTF_8)) {
      writeStored(output, "mimetype", MIMETYPE);
      for (ArchiveEntry entry : entries) {
        if (entry.content() != null) writeDeflated(output, entry.name(), entry.content());
      }
    }
    return file;
  }

  private static List<ArchiveEntry> validEntries(
      String nav, String firstChapter, String secondChapter) {
    return validEntries(nav, firstChapter, secondChapter, null, null, "3.0");
  }

  private static List<ArchiveEntry> validEntries(
      String nav,
      String firstChapter,
      String secondChapter,
      String containerOverride,
      String metadataOverride,
      String version) {
    return validEntries(
        nav, firstChapter, secondChapter, containerOverride, metadataOverride, version, null);
  }

  private static String variedText(int length, int seed) {
    StringBuilder value = new StringBuilder(length);
    int state = seed + 1;
    for (int index = 0; index < length; index++) {
      state = state * 1_103_515_245 + 12_345;
      value.append((char) ('a' + ((state >>> 16) & 0x7fff) % 26));
    }
    return value.toString();
  }

  private static List<ArchiveEntry> validEntries(
      String nav,
      String firstChapter,
      String secondChapter,
      String containerOverride,
      String metadataOverride,
      String version,
      String chapterPrefix) {
    String manifest =
        "<item id=\"chapter-one\" href=\"Text/one.xhtml\" media-type=\"application/xhtml+xml\"/>"
            + (secondChapter == null
                ? ""
                : "<item id=\"chapter-two\" href=\"Text/two.xhtml\" media-type=\"application/xhtml+xml\"/>");
    String spine =
        "<itemref idref=\"chapter-one\"/>"
            + (secondChapter == null ? "" : "<itemref idref=\"chapter-two\"/>");
    String navManifest =
        nav == null
            ? ""
            : "<item id=\"navigation\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>";
    String packageXml =
        packageXml(
            metadataOverride == null ? metadataXml() : metadataOverride,
            navManifest + manifest,
            spine,
            version);
    List<ArchiveEntry> result = new ArrayList<>();
    result.add(
        deflated(
            "META-INF/container.xml",
            containerOverride == null ? containerXml() : containerOverride));
    result.add(deflated("OPS/package.opf", packageXml));
    if (nav != null)
      result.add(
          deflated(
              "OPS/nav.xhtml",
              "<html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\"><body>"
                  + nav
                  + "</body></html>"));
    if (firstChapter != null)
      result.add(
          deflated(
              "OPS/Text/one.xhtml", (chapterPrefix == null ? "" : chapterPrefix) + firstChapter));
    if (secondChapter != null) result.add(deflated("OPS/Text/two.xhtml", secondChapter));
    return result;
  }

  private static List<ArchiveEntry> entries(String container, String packageXml, String chapter) {
    List<ArchiveEntry> entries = new ArrayList<>();
    entries.add(deflated("META-INF/container.xml", container));
    entries.add(deflated("OPS/package.opf", packageXml));
    if (chapter != null) entries.add(deflated("OPS/Text/one.xhtml", chapter));
    return entries;
  }

  private static String containerXml() {
    return "<container xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\" version=\"1.0\">"
        + "<rootfiles><rootfile full-path=\"OPS/package.opf\" media-type=\"application/oebps-package+xml\"/>"
        + "</rootfiles></container>";
  }

  private static String metadataXml() {
    return "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">"
        + "<dc:title>A Private Novel</dc:title><dc:identifier id=\"uid\">urn:example:private-novel</dc:identifier>"
        + "<dc:language>vi</dc:language><dc:creator>A. Author</dc:creator><dc:creator>B. Author</dc:creator>"
        + "</metadata>";
  }

  private static String packageXml(String metadata, String manifest, String spine, String version) {
    return "<package xmlns=\"http://www.idpf.org/2007/opf\" version=\""
        + version
        + "\" unique-identifier=\"uid\">"
        + metadata
        + "<manifest>"
        + manifest
        + "</manifest><spine>"
        + spine
        + "</spine></package>";
  }

  private static ArchiveEntry deflated(String name, String content) {
    return new ArchiveEntry(name, content.getBytes(StandardCharsets.UTF_8));
  }

  private static void writeStored(ZipOutputStream output, String name, byte[] content)
      throws IOException {
    CRC32 crc = new CRC32();
    crc.update(content);
    ZipEntry entry = new ZipEntry(name);
    entry.setMethod(ZipEntry.STORED);
    entry.setSize(content.length);
    entry.setCompressedSize(content.length);
    entry.setCrc(crc.getValue());
    output.putNextEntry(entry);
    output.write(content);
    output.closeEntry();
  }

  private static void writeDeflated(ZipOutputStream output, String name, byte[] content)
      throws IOException {
    output.putNextEntry(new ZipEntry(name));
    output.write(content);
    output.closeEntry();
  }

  private record ArchiveEntry(String name, byte[] content) {}
}
