package com.novelweb.library.imports;

import com.novelweb.library.imports.EpubPackageParser.Chapter;
import com.novelweb.library.imports.EpubPackageParser.Metadata;
import com.novelweb.library.imports.EpubPackageParser.Preview;
import com.novelweb.library.imports.EpubPackageParser.SourceSegment;
import com.novelweb.library.imports.EpubPackageParser.TocEntry;
import com.novelweb.library.imports.EpubPackageParser.Warning;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.Parser;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Extracts bounded plain text from one untrusted HTML document into private staging. */
@Component
@Profile("postgres")
public class HtmlDocumentParser {

  public static final String PARSER_VERSION = "html-v1";

  public static final long MAX_SOURCE_BYTES = 4 * 1024 * 1024;
  private static final int MAX_SEGMENT_CODE_POINTS = 4_000;
  private static final int MAX_SEGMENT_UTF8_BYTES = 16_384;
  private static final int MAX_SEGMENTS = 5_000;
  private static final int MAX_TOTAL_SOURCE_BYTES = 384 * 1024;
  private static final int PREVIEW_CODE_POINTS = 600;
  private static final int MAX_METADATA_CODE_POINTS = 4096;
  private static final int MAX_VISITED_NODES = 50_000;
  private static final int MAX_TREE_DEPTH = 128;
  private static final int READ_BUFFER_SIZE = 8_192;
  private static final Duration PARSE_TIMEOUT = Duration.ofSeconds(30);
  private static final Pattern COLLAPSIBLE_WHITESPACE = Pattern.compile("[\\p{Z}\\s]+");
  private static final Pattern HIDDEN_STYLE =
      Pattern.compile("(?i)(?:^|;)\\s*(?:display\\s*:\\s*none|visibility\\s*:\\s*hidden)");
  private static final Set<String> BLOCK_TAGS =
      Set.of("p", "h1", "h2", "h3", "h4", "h5", "h6", "li", "blockquote", "pre", "br");

  public Preview parse(Path file, String filename, String declaredContentType, String parserVersion)
      throws IOException {
    if (!PARSER_VERSION.equals(parserVersion)) {
      throw failure("IMPORT_PARSER_VERSION_UNSUPPORTED", "The HTML parser version is unsupported.");
    }
    String extension = extension(filename);
    if (!Set.of("html", "htm").contains(extension)) {
      throw failure("IMPORT_FORMAT_UNSUPPORTED", "Only HTML documents use this parser.");
    }
    if (!matchesHtmlType(declaredContentType)) {
      throw failure("IMPORT_CONTENT_INVALID", "The declared content type does not match HTML.");
    }

    long sourceBytes = Files.size(file);
    if (sourceBytes < 1) throw failure("IMPORT_HTML_EMPTY", "The HTML document is empty.");
    if (sourceBytes > MAX_SOURCE_BYTES) {
      throw failure(
          "IMPORT_HTML_SOURCE_LIMIT_EXCEEDED", "The HTML document exceeds its byte limit.");
    }
    long deadline = System.nanoTime() + PARSE_TIMEOUT.toNanos();
    String source = readUtf8(file, deadline);
    if (!source.isEmpty() && source.charAt(0) == '\ufeff') source = source.substring(1);

    Document document = Jsoup.parse(source, "", Parser.htmlParser());
    checkDeadline(deadline);
    String documentTitle = metadataText(document.title(), 300);
    Element html = document.selectFirst("html");
    String language =
        html == null ? "und" : metadataText(html.attr("lang"), 35).toLowerCase(Locale.ROOT);
    if (language.isBlank()) language = "und";
    String author =
        metadataText(
            document.selectFirst("meta[name=author]") == null
                ? ""
                : document.selectFirst("meta[name=author]").attr("content"),
            200);
    String description =
        metadataText(
            document.selectFirst("meta[name=description]") == null
                ? ""
                : document.selectFirst("meta[name=description]").attr("content"),
            MAX_METADATA_CODE_POINTS);
    Element body = document.body();
    if (body == null) throw failure("IMPORT_HTML_EMPTY", "The HTML document has no readable body.");
    removeNonContent(body);
    String firstHeading = metadataText(body.select("h1, h2, h3, h4, h5, h6").first(), 300);
    if (documentTitle.isBlank()) documentTitle = firstHeading;
    if (documentTitle.isBlank()) documentTitle = titleFromFilename(filename);
    SegmentCollector collector = new SegmentCollector(deadline);
    NodeTraversor.traverse(collector, body);
    List<SourceSegment> segments = collector.segments();
    if (segments.isEmpty()) {
      throw failure("IMPORT_HTML_EMPTY", "The HTML document contains no readable text.");
    }

    String chapterTitle = firstHeading.isBlank() ? "Nội dung" : firstHeading;
    Chapter chapter =
        new Chapter(
            0,
            "source.html",
            chapterTitle,
            true,
            segments.size(),
            previewText(segments),
            false,
            documentTitle,
            firstHeading,
            segments);
    return new Preview(
        PARSER_VERSION,
        new Metadata(
            documentTitle,
            language,
            null,
            null,
            description.isBlank() ? null : description,
            author.isBlank() ? List.of() : List.of(author)),
        List.of(chapter),
        List.of(new TocEntry(chapterTitle, 0, 0, null)),
        List.of(
            new Warning(
                "HTML_SINGLE_DOCUMENT",
                "An HTML upload is kept as one chapter because one document has no reliable chapter boundaries.")));
  }

  private static String readUtf8(Path file, long deadline) throws IOException {
    CharsetDecoder decoder =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    StringBuilder source = new StringBuilder();
    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(Files.newInputStream(file), decoder), READ_BUFFER_SIZE)) {
      char[] buffer = new char[READ_BUFFER_SIZE];
      int count;
      while ((count = reader.read(buffer)) != -1) {
        checkDeadline(deadline);
        if (count > MAX_SOURCE_BYTES - source.length()) {
          throw failure(
              "IMPORT_HTML_SOURCE_LIMIT_EXCEEDED", "The HTML document exceeds its byte limit.");
        }
        source.append(buffer, 0, count);
      }
    } catch (CharacterCodingException exception) {
      throw failure(
          "IMPORT_HTML_ENCODING_INVALID", "The HTML document is not valid UTF-8.", exception);
    }
    return source.toString();
  }

  private static void removeNonContent(Element body) {
    body.select(
            "script, style, noscript, template, svg, math, iframe, object, embed, audio, video, canvas, nav, form, button, input, select, textarea, option")
        .remove();
    List<Element> elements = new ArrayList<>(body.getAllElements());
    for (Element element : elements) {
      String style = element.attr("style");
      if (element.hasAttr("hidden")
          || "true".equalsIgnoreCase(element.attr("aria-hidden"))
          || HIDDEN_STYLE.matcher(style).find()) {
        element.remove();
      }
    }
  }

  private static String metadataText(Element element, int maximumCodePoints) {
    return element == null ? "" : metadataText(element.text(), maximumCodePoints);
  }

  private static String metadataText(String rawText, int maximumCodePoints) {
    String normalized = normalize(rawText);
    int length = normalized.codePointCount(0, normalized.length());
    return length <= maximumCodePoints
        ? normalized
        : normalized.substring(0, normalized.offsetByCodePoints(0, maximumCodePoints));
  }

  private static String normalize(String rawText) {
    return COLLAPSIBLE_WHITESPACE
        .matcher(Normalizer.normalize(rawText, Normalizer.Form.NFC))
        .replaceAll(" ")
        .trim();
  }

  private static String previewText(List<SourceSegment> segments) {
    StringBuilder preview = new StringBuilder();
    for (SourceSegment segment : segments) {
      int remaining = PREVIEW_CODE_POINTS - preview.codePointCount(0, preview.length());
      if (remaining <= 0) break;
      if (!preview.isEmpty()) preview.append('\n');
      String text = segment.text();
      int count = text.codePointCount(0, text.length());
      preview.append(
          count <= remaining ? text : text.substring(0, text.offsetByCodePoints(0, remaining)));
    }
    return preview.toString();
  }

  private static String titleFromFilename(String filename) {
    int separator = Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\'));
    String basename = filename.substring(separator + 1);
    int extension = basename.lastIndexOf('.');
    String title = (extension > 0 ? basename.substring(0, extension) : basename).trim();
    return title.isBlank() ? "Tác phẩm chưa có tên" : title;
  }

  private static String extension(String filename) {
    int dot = filename == null ? -1 : filename.lastIndexOf('.');
    return dot < 0 || dot == filename.length() - 1
        ? ""
        : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
  }

  private static boolean matchesHtmlType(String contentType) {
    if (contentType == null || contentType.isBlank()) return true;
    String normalized = contentType.trim().toLowerCase(Locale.ROOT);
    int parameters = normalized.indexOf(';');
    if (parameters >= 0) normalized = normalized.substring(0, parameters).trim();
    return Set.of("text/html", "application/xhtml+xml", "application/octet-stream")
        .contains(normalized);
  }

  private static void checkDeadline(long deadline) throws HtmlParseException {
    if (System.nanoTime() - deadline >= 0) {
      throw failure("IMPORT_HTML_PARSE_TIMEOUT", "The HTML document took too long to parse.");
    }
  }

  private static HtmlParseException failure(String code, String message) {
    return new HtmlParseException(code, message);
  }

  private static HtmlParseException failure(String code, String message, Throwable cause) {
    return new HtmlParseException(code, message, cause);
  }

  private static String segmentKind(Deque<String> blocks) {
    for (String tag : blocks) {
      if (tag.matches("h[1-6]")) return "heading";
      if ("blockquote".equals(tag)) return "quote";
    }
    return "paragraph";
  }

  static final class HtmlParseException extends RuntimeException {
    private final String code;

    HtmlParseException(String code, String message) {
      super(message);
      this.code = code;
    }

    HtmlParseException(String code, String message, Throwable cause) {
      super(message, cause);
      this.code = code;
    }

    String code() {
      return code;
    }
  }

  private static final class SegmentCollector implements NodeVisitor {
    private final long deadline;
    private final Deque<String> blocks = new ArrayDeque<>();
    private final List<SourceSegment> result = new ArrayList<>();
    private final StringBuilder active = new StringBuilder();
    private String activeKind;
    private int totalBytes;
    private int nodeCount;

    private SegmentCollector(long deadline) {
      this.deadline = deadline;
    }

    @Override
    public void head(Node node, int depth) {
      checkTraversal(depth);
      if (node instanceof Element element && BLOCK_TAGS.contains(element.normalName())) {
        flush();
        blocks.push(element.normalName());
      }
      if (node instanceof TextNode textNode) append(textNode.getWholeText());
    }

    @Override
    public void tail(Node node, int depth) {
      if (node instanceof Element element && BLOCK_TAGS.contains(element.normalName())) {
        flush();
        if (!blocks.isEmpty()) blocks.pop();
      }
    }

    private void append(String text) {
      if (active.isEmpty()) activeKind = segmentKind(blocks);
      active.append(text);
    }

    private void flush() {
      String normalized = normalize(active.toString());
      active.setLength(0);
      if (normalized.isEmpty()) {
        activeKind = null;
        return;
      }
      int offset = 0;
      int length = normalized.codePointCount(0, normalized.length());
      while (offset < length) {
        int count = Math.min(MAX_SEGMENT_CODE_POINTS, length - offset);
        int end = normalized.offsetByCodePoints(0, count);
        String value = normalized.substring(0, end);
        addSegment(activeKind == null ? "paragraph" : activeKind, value);
        normalized = normalized.substring(end);
        length -= count;
      }
      activeKind = null;
    }

    private void addSegment(String kind, String text) {
      int bytes = text.getBytes(StandardCharsets.UTF_8).length;
      if (bytes > MAX_SEGMENT_UTF8_BYTES) {
        throw failure(
            "IMPORT_HTML_SEGMENT_TOO_LARGE", "An HTML text segment exceeds its byte limit.");
      }
      if (result.size() >= MAX_SEGMENTS) {
        throw failure(
            "IMPORT_HTML_SEGMENT_LIMIT_EXCEEDED",
            "The HTML document contains too many text segments.");
      }
      if (bytes > MAX_TOTAL_SOURCE_BYTES - totalBytes) {
        throw failure(
            "IMPORT_HTML_SOURCE_LIMIT_EXCEEDED",
            "The normalized HTML text exceeds its storage limit.");
      }
      result.add(new SourceSegment(result.size(), kind, text));
      totalBytes += bytes;
    }

    private void checkTraversal(int depth) {
      checkDeadline(deadline);
      if (++nodeCount > MAX_VISITED_NODES) {
        throw failure(
            "IMPORT_HTML_NODE_LIMIT_EXCEEDED", "The HTML document contains too many nodes.");
      }
      if (depth > MAX_TREE_DEPTH) {
        throw failure(
            "IMPORT_HTML_DEPTH_LIMIT_EXCEEDED", "The HTML document is nested too deeply.");
      }
    }

    private List<SourceSegment> segments() {
      flush();
      return List.copyOf(result);
    }
  }
}
