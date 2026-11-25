package com.novelweb.library.imports;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.novelweb.library.imports.EpubPackageParser.Preview;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImportTextParserMutationCorpusTests {

  private static final int CORPUS_SIZE_PER_FORMAT = 128;
  private static final int MAX_MUTATED_BYTES = 512;
  private static final int MAX_CHAPTERS = 2_000;
  private static final int MAX_SEGMENTS = 5_000;
  private static final int MAX_SEGMENT_CODE_POINTS = 4_000;
  private static final int MAX_SEGMENT_UTF8_BYTES = 16_384;
  private static final int MAX_TOTAL_TEXT_BYTES = 384 * 1024;

  @TempDir Path temporaryDirectory;

  @Test
  void deterministicMutationsAcrossUtf8TextFormatsStayInsideParserBoundary() throws IOException {
    PlainTextParser plainTextParser = new PlainTextParser();
    HtmlDocumentParser htmlParser = new HtmlDocumentParser();
    MarkdownDocumentParser markdownParser = new MarkdownDocumentParser();
    List<ParserCase> parserCases =
        List.of(
            new ParserCase(
                "txt",
                "mutation.txt",
                "Chương một — câu chuyện riêng tư.\n\nĐoạn thứ hai."
                    .getBytes(StandardCharsets.UTF_8),
                path ->
                    plainTextParser.parse(
                        path, "mutation.txt", "text/plain", PlainTextParser.PARSER_VERSION)),
            new ParserCase(
                "html",
                "mutation.html",
                ("<!doctype html><html lang=\"vi\"><body><article><h1>Chương một</h1>"
                        + "<p>Câu chuyện riêng tư.</p></article></body></html>")
                    .getBytes(StandardCharsets.UTF_8),
                path ->
                    htmlParser.parse(
                        path, "mutation.html", "text/html", HtmlDocumentParser.PARSER_VERSION)),
            new ParserCase(
                "markdown",
                "mutation.md",
                "# Tác phẩm\n\n## Chương một\n\nCâu chuyện **riêng tư**."
                    .getBytes(StandardCharsets.UTF_8),
                path ->
                    markdownParser.parse(
                        path,
                        "mutation.md",
                        "text/markdown",
                        MarkdownDocumentParser.PARSER_VERSION)));

    for (ParserCase parserCase : parserCases) {
      Random mutations = new Random(0x494d504f5254L + parserCase.format().hashCode());
      for (int sample = 0; sample < CORPUS_SIZE_PER_FORMAT; sample++) {
        byte[] candidate = mutate(parserCase.seed(), mutations);
        Path file =
            temporaryDirectory.resolve(
                parserCase.format() + "-mutation-" + sample + parserCase.extension());
        Files.write(file, candidate);

        assertDoesNotThrow(
            () -> {
              try {
                assertPreviewWithinLimits(parserCase.parser().parse(file));
              } catch (HtmlDocumentParser.HtmlParseException expectedInvalidHtml) {
                // HTML uses its typed domain exception to reject malformed text.
              } catch (IOException expectedInvalidUpload) {
                // Rejection is valid; malformed uploads must not escape as unchecked failures.
              }
            },
            parserCase.format() + " mutation sample " + sample + " escaped its parser boundary");
      }
    }
  }

  @Test
  void htmlAndMarkdownResourceReferencesNeverFetchFromLoopback() throws Exception {
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
      Path htmlFile = temporaryDirectory.resolve("remote-resource.html");
      Files.writeString(
          htmlFile,
          "<html><body><p>Visible HTML text.</p><img src=\""
              + remote
              + "\" alt=\"cover\">"
              + "<iframe src=\""
              + remote
              + "\"></iframe></body></html>",
          StandardCharsets.UTF_8);
      Path markdownFile = temporaryDirectory.resolve("remote-resource.md");
      Files.writeString(
          markdownFile,
          "# Visible Markdown\n\n[link](" + remote + ") ![cover](" + remote + ")",
          StandardCharsets.UTF_8);

      String htmlText =
          allText(
              new HtmlDocumentParser()
                  .parse(
                      htmlFile,
                      "remote-resource.html",
                      "text/html",
                      HtmlDocumentParser.PARSER_VERSION));
      String markdownText =
          allText(
              new MarkdownDocumentParser()
                  .parse(
                      markdownFile,
                      "remote-resource.md",
                      "text/markdown",
                      MarkdownDocumentParser.PARSER_VERSION));

      assertFalse(htmlText.contains(remote));
      assertFalse(markdownText.contains(remote));
      assertFalse(
          receivedRequest.await(250, TimeUnit.MILLISECONDS),
          "an imported HTML/Markdown resource reference triggered an outbound HTTP request");
    } finally {
      server.stop(0);
    }
  }

  private static byte[] mutate(byte[] seed, Random random) {
    byte[] candidate = seed.clone();
    int mutationCount = 1 + random.nextInt(4);
    for (int mutation = 0; mutation < mutationCount; mutation++) {
      switch (random.nextInt(4)) {
        case 0 -> {
          if (candidate.length > 0) {
            int offset = random.nextInt(candidate.length);
            candidate[offset] ^= (byte) (1 << random.nextInt(Byte.SIZE));
          }
        }
        case 1 -> {
          if (candidate.length > 0)
            candidate = Arrays.copyOf(candidate, random.nextInt(candidate.length + 1));
        }
        case 2 -> {
          if (candidate.length < MAX_MUTATED_BYTES) {
            int insertedBytes =
                1 + random.nextInt(Math.min(8, MAX_MUTATED_BYTES - candidate.length));
            int offset = random.nextInt(candidate.length + 1);
            byte[] inserted = new byte[insertedBytes];
            random.nextBytes(inserted);
            ByteArrayOutputStream result =
                new ByteArrayOutputStream(candidate.length + insertedBytes);
            result.write(candidate, 0, offset);
            result.writeBytes(inserted);
            result.write(candidate, offset, candidate.length - offset);
            candidate = result.toByteArray();
          }
        }
        case 3 -> {
          if (candidate.length > 0) {
            int offset = random.nextInt(candidate.length);
            int replacedBytes = Math.min(candidate.length - offset, 1 + random.nextInt(8));
            byte[] replacement = new byte[replacedBytes];
            random.nextBytes(replacement);
            System.arraycopy(replacement, 0, candidate, offset, replacedBytes);
          }
        }
        default -> throw new IllegalStateException("Unreachable mutation operation");
      }
    }
    return candidate;
  }

  private static void assertPreviewWithinLimits(Preview preview) {
    assertFalse(preview.chapters().isEmpty(), "an accepted parser result must contain chapters");
    assertTrue(preview.chapters().size() <= MAX_CHAPTERS);
    int totalSegments = 0;
    int totalBytes = 0;
    for (var chapter : preview.chapters()) {
      totalSegments += chapter.segments().size();
      for (var segment : chapter.segments()) {
        assertTrue(
            segment.text().codePointCount(0, segment.text().length()) <= MAX_SEGMENT_CODE_POINTS);
        int segmentBytes = segment.text().getBytes(StandardCharsets.UTF_8).length;
        assertTrue(segmentBytes <= MAX_SEGMENT_UTF8_BYTES);
        totalBytes += segmentBytes;
      }
    }
    assertTrue(totalSegments <= MAX_SEGMENTS);
    assertTrue(totalBytes <= MAX_TOTAL_TEXT_BYTES);
  }

  private static String allText(Preview preview) {
    return preview.chapters().stream()
        .flatMap(chapter -> chapter.segments().stream())
        .map(EpubPackageParser.SourceSegment::text)
        .collect(Collectors.joining("\n"));
  }

  private record ParserCase(String format, String extension, byte[] seed, PreviewParser parser) {}

  @FunctionalInterface
  private interface PreviewParser {
    Preview parse(Path file) throws IOException;
  }
}
