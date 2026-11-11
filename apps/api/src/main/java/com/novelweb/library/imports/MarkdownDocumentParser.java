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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.Code;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.Link;
import org.commonmark.node.Node;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Extracts bounded, normalized text from a private CommonMark upload. */
@Component
@Profile("postgres")
public class MarkdownDocumentParser {

  public static final String PARSER_VERSION = "markdown-v1";

  public static final long MAX_SOURCE_BYTES = 16 * 1024 * 1024;
  private static final int MAX_CHAPTERS = 2_000;
  private static final int MAX_SEGMENTS = 5_000;
  private static final int MAX_SEGMENT_CODE_POINTS = 4_000;
  private static final int MAX_SEGMENT_UTF8_BYTES = 16_384;
  private static final int MAX_TOTAL_TEXT_BYTES = 384 * 1024;
  private static final int PREVIEW_CODE_POINTS = 600;
  private static final int MAX_NODES = 50_000;
  private static final int MAX_TREE_DEPTH = 128;
  private static final int READ_BUFFER_SIZE = 8_192;
  private static final Duration PARSE_TIMEOUT = Duration.ofSeconds(30);
  private static final Pattern COLLAPSIBLE_WHITESPACE = Pattern.compile("[\\p{Z}\\s]+");
  private static final Pattern ACTIVE_HTML_TAG =
      Pattern.compile(
          "(?is)<\\s*(/?)\\s*(script|style|iframe|object|svg|math|template|noscript|textarea|xmp|noembed|noframes)\\b[^>]*>");
  private static final Set<String> MARKDOWN_EXTENSIONS = Set.of("md", "markdown");
  private static final Set<String> MARKDOWN_TYPES =
      Set.of("text/markdown", "text/x-markdown", "text/plain", "application/octet-stream");
  private static final Parser PARSER =
      Parser.builder().maxOpenBlockParsers(64).maxInlineNesting(64).build();

  public Preview parse(Path file, String filename, String declaredContentType, String parserVersion)
      throws IOException {
    if (!PARSER_VERSION.equals(parserVersion)) {
      throw failure(
          "IMPORT_PARSER_VERSION_UNSUPPORTED", "The Markdown parser version is unsupported.");
    }
    if (!MARKDOWN_EXTENSIONS.contains(extension(filename))) {
      throw failure("IMPORT_FORMAT_UNSUPPORTED", "Only Markdown files use this parser.");
    }
    if (!matchesMarkdownType(declaredContentType)) {
      throw failure("IMPORT_CONTENT_INVALID", "The declared content type does not match Markdown.");
    }

    long sourceBytes = Files.size(file);
    if (sourceBytes < 1) throw failure("IMPORT_MARKDOWN_EMPTY", "The Markdown document is empty.");
    if (sourceBytes > MAX_SOURCE_BYTES) {
      throw failure(
          "IMPORT_MARKDOWN_SOURCE_LIMIT_EXCEEDED", "The Markdown document exceeds its byte limit.");
    }
    long deadline = System.nanoTime() + PARSE_TIMEOUT.toNanos();
    String source = readUtf8(file, deadline);
    if (!source.isEmpty() && source.charAt(0) == '\ufeff') source = source.substring(1);
    checkDeadline(deadline);

    Node document;
    try {
      document = PARSER.parse(source);
    } catch (RuntimeException exception) {
      throw failure(
          "IMPORT_MARKDOWN_PARSE_INVALID", "The Markdown document could not be parsed.", exception);
    }
    checkTreeBounds(document, deadline);
    List<Node> blocks = topLevelBlocks(document);
    List<Heading> headings = topLevelHeadings(blocks);
    int chapterHeadingLevel = repeatedHeadingLevel(headings);
    String firstHeading = headings.isEmpty() ? "" : headingText(headings.getFirst());
    Heading titleHeading =
        chapterHeadingLevel == 0
            ? (headings.isEmpty() ? null : headings.getFirst())
            : leadingWorkTitle(blocks, chapterHeadingLevel);
    String title = titleHeading == null ? "" : headingText(titleHeading);
    if (title.isBlank()) title = titleFromFilename(filename);

    List<ChapterDraft> drafts =
        chapterHeadingLevel == 0
            ? singleChapter(blocks, firstHeading, deadline)
            : splitChapters(blocks, chapterHeadingLevel, titleHeading, deadline);
    if (drafts.isEmpty() || drafts.stream().noneMatch(draft -> !draft.segments().isEmpty())) {
      throw failure("IMPORT_MARKDOWN_EMPTY", "The Markdown document contains no readable text.");
    }
    if (drafts.size() > MAX_CHAPTERS) {
      throw failure(
          "IMPORT_MARKDOWN_CHAPTER_LIMIT_EXCEEDED",
          "The Markdown document contains too many chapters.");
    }

    List<Chapter> chapters = new ArrayList<>(drafts.size());
    List<TocEntry> toc = new ArrayList<>(drafts.size());
    for (int index = 0; index < drafts.size(); index++) {
      ChapterDraft draft = drafts.get(index);
      String chapterTitle = draft.title().isBlank() ? "Chương " + (index + 1) : draft.title();
      String chapterFirstHeading =
          draft.firstHeading().isBlank() ? chapterTitle : draft.firstHeading();
      chapters.add(
          new Chapter(
              index,
              "source.md",
              chapterTitle,
              true,
              draft.segments().size(),
              previewText(draft.segments()),
              draft.segments().isEmpty(),
              title,
              chapterFirstHeading,
              draft.segments()));
      toc.add(new TocEntry(chapterTitle, 0, index, null));
    }

    List<Warning> warnings = new ArrayList<>();
    warnings.add(
        new Warning(
            "MARKDOWN_FORMATTING_NORMALIZED",
            "Markdown formatting becomes private text; link labels and image alt text are kept, destinations and raw HTML are discarded."));
    if (chapterHeadingLevel == 0) {
      warnings.add(
          new Warning(
              "MARKDOWN_SINGLE_DOCUMENT",
              "No repeated top-level heading level defines chapter boundaries, so the document is kept as one chapter."));
    } else {
      warnings.add(
          new Warning(
              "MARKDOWN_CHAPTERS_FROM_HEADINGS",
              "Repeated top-level headings at the selected level were used as chapter boundaries."));
    }

    return new Preview(
        PARSER_VERSION,
        new Metadata(title, "und", null, null, null, List.of()),
        chapters,
        toc,
        List.copyOf(warnings));
  }

  private static List<ChapterDraft> singleChapter(
      List<Node> blocks, String firstHeading, long deadline) throws IOException {
    ChapterBuilder chapter = new ChapterBuilder(firstHeading.isBlank() ? "Nội dung" : firstHeading);
    int[] limits = {0, 0};
    for (Node block : blocks) {
      appendBlock(chapter, block, limits, deadline);
    }
    return chapter.segments().isEmpty() ? List.of() : List.of(chapter.toDraft());
  }

  private static List<ChapterDraft> splitChapters(
      List<Node> blocks, int chapterHeadingLevel, Heading titleHeading, long deadline)
      throws IOException {
    List<ChapterDraft> chapters = new ArrayList<>();
    ChapterBuilder current = null;
    int[] limits = {0, 0};
    int chapterOrdinal = 0;
    for (Node block : blocks) {
      checkDeadline(deadline);
      if (block == titleHeading) continue;
      if (block instanceof Heading heading && heading.getLevel() == chapterHeadingLevel) {
        if (current != null && (current.keepWhenEmpty() || !current.segments().isEmpty())) {
          chapters.add(current.toDraft());
        }
        String chapterTitle = headingText(heading);
        chapterOrdinal++;
        current =
            new ChapterBuilder(chapterTitle.isBlank() ? "Chương " + chapterOrdinal : chapterTitle);
        current.keepWhenEmpty(true);
        current.firstHeading(chapterTitle);
        appendBlock(current, block, limits, deadline);
        continue;
      }
      if (current == null) current = new ChapterBuilder("Mở đầu");
      appendBlock(current, block, limits, deadline);
    }
    if (current != null && (current.keepWhenEmpty() || !current.segments().isEmpty())) {
      chapters.add(current.toDraft());
    }
    return List.copyOf(chapters);
  }

  private static void appendBlock(ChapterBuilder chapter, Node block, int[] limits, long deadline)
      throws IOException {
    checkDeadline(deadline);
    String kind =
        block instanceof Heading ? "heading" : block instanceof BlockQuote ? "quote" : "paragraph";
    if (block instanceof Heading heading && chapter.firstHeading().isBlank()) {
      chapter.firstHeading(headingText(heading));
    }
    String plainText = block instanceof HtmlBlock ? "" : plainText(block);
    for (String line : plainText.split("\\R")) {
      String normalized = normalize(line);
      if (!normalized.isEmpty()) addText(chapter, kind, normalized, limits);
    }
  }

  private static void addText(ChapterBuilder chapter, String kind, String normalized, int[] limits)
      throws MarkdownParseException {
    int length = normalized.codePointCount(0, normalized.length());
    int offset = 0;
    while (offset < length) {
      int count = Math.min(MAX_SEGMENT_CODE_POINTS, length - offset);
      int nextOffset = offset + count;
      int charStart = normalized.offsetByCodePoints(0, offset);
      int charEnd = normalized.offsetByCodePoints(0, nextOffset);
      String value = normalized.substring(charStart, charEnd);
      int bytes = value.getBytes(StandardCharsets.UTF_8).length;
      if (bytes > MAX_SEGMENT_UTF8_BYTES) {
        throw failure(
            "IMPORT_MARKDOWN_SEGMENT_TOO_LARGE", "A Markdown text segment exceeds its byte limit.");
      }
      if (limits[0] >= MAX_SEGMENTS) {
        throw failure(
            "IMPORT_MARKDOWN_SEGMENT_LIMIT_EXCEEDED",
            "The Markdown document contains too many segments.");
      }
      if (bytes > MAX_TOTAL_TEXT_BYTES - limits[1]) {
        throw failure(
            "IMPORT_MARKDOWN_TEXT_LIMIT_EXCEEDED",
            "The normalized Markdown text exceeds its storage limit.");
      }
      chapter.add(new SourceSegment(chapter.segments().size(), kind, value));
      limits[0]++;
      limits[1] += bytes;
      offset = nextOffset;
    }
  }

  private static List<Node> topLevelBlocks(Node document) {
    List<Node> blocks = new ArrayList<>();
    for (Node node = document.getFirstChild(); node != null; node = node.getNext()) {
      blocks.add(node);
    }
    return List.copyOf(blocks);
  }

  private static List<Heading> topLevelHeadings(List<Node> blocks) {
    List<Heading> headings = new ArrayList<>();
    for (Node block : blocks) {
      if (block instanceof Heading heading) headings.add(heading);
    }
    return List.copyOf(headings);
  }

  private static int repeatedHeadingLevel(List<Heading> headings) {
    Map<Integer, Integer> counts = new HashMap<>();
    for (Heading heading : headings) counts.merge(heading.getLevel(), 1, Integer::sum);
    for (int level = 1; level <= 6; level++) {
      if (counts.getOrDefault(level, 0) >= 2) return level;
    }
    return 0;
  }

  private static Heading leadingWorkTitle(List<Node> blocks, int chapterHeadingLevel) {
    for (Node block : blocks) {
      if (block instanceof Heading heading && heading.getLevel() == chapterHeadingLevel)
        return null;
      if (block instanceof Heading heading && heading.getLevel() < chapterHeadingLevel)
        return heading;
    }
    return null;
  }

  private static void checkTreeBounds(Node root, long deadline) throws MarkdownParseException {
    Deque<NodeDepth> pending = new ArrayDeque<>();
    pending.push(new NodeDepth(root, 0));
    int count = 0;
    while (!pending.isEmpty()) {
      checkDeadline(deadline);
      NodeDepth current = pending.pop();
      if (++count > MAX_NODES) {
        throw failure(
            "IMPORT_MARKDOWN_NODE_LIMIT_EXCEEDED",
            "The Markdown document contains too many nodes.");
      }
      if (current.depth() > MAX_TREE_DEPTH) {
        throw failure(
            "IMPORT_MARKDOWN_DEPTH_LIMIT_EXCEEDED", "The Markdown document is nested too deeply.");
      }
      for (Node child = current.node().getFirstChild(); child != null; child = child.getNext()) {
        pending.push(new NodeDepth(child, current.depth() + 1));
      }
    }
  }

  private static String headingText(Heading heading) {
    return normalize(plainText(heading));
  }

  private static String plainText(Node block) {
    StringBuilder output = new StringBuilder();
    Deque<String> suppressedHtmlElements = new ArrayDeque<>();
    block.accept(
        new AbstractVisitor() {
          @Override
          public void visit(Text text) {
            if (suppressedHtmlElements.isEmpty()) output.append(text.getLiteral());
          }

          @Override
          public void visit(Code code) {
            if (suppressedHtmlElements.isEmpty()) output.append(code.getLiteral());
          }

          @Override
          public void visit(SoftLineBreak softLineBreak) {
            if (suppressedHtmlElements.isEmpty()) output.append('\n');
          }

          @Override
          public void visit(HardLineBreak hardLineBreak) {
            if (suppressedHtmlElements.isEmpty()) output.append('\n');
          }

          @Override
          public void visit(HtmlInline htmlInline) {
            Matcher matcher = ACTIVE_HTML_TAG.matcher(htmlInline.getLiteral());
            while (matcher.find()) {
              String tag = matcher.group(2).toLowerCase(Locale.ROOT);
              if (matcher.group(1).isEmpty()) {
                if (!matcher.group().matches("(?is).*/\\s*>")) suppressedHtmlElements.push(tag);
              } else if (!suppressedHtmlElements.isEmpty()
                  && suppressedHtmlElements.peek().equals(tag)) {
                suppressedHtmlElements.pop();
              }
            }
          }

          @Override
          public void visit(Image image) {
            visitChildren(image);
          }

          @Override
          public void visit(Link link) {
            visitChildren(link);
          }
        });
    return output.toString();
  }

  private static String previewText(List<SourceSegment> segments) {
    StringBuilder preview = new StringBuilder();
    for (SourceSegment segment : segments) {
      int remaining = PREVIEW_CODE_POINTS - preview.codePointCount(0, preview.length());
      if (remaining <= 0) break;
      if (!preview.isEmpty()) preview.append('\n');
      int count = segment.text().codePointCount(0, segment.text().length());
      preview.append(
          count <= remaining
              ? segment.text()
              : segment.text().substring(0, segment.text().offsetByCodePoints(0, remaining)));
    }
    return preview.toString();
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
      boolean firstCharacter = true;
      while ((count = reader.read(buffer)) != -1) {
        checkDeadline(deadline);
        if (count > MAX_SOURCE_BYTES - source.length()) {
          throw failure(
              "IMPORT_MARKDOWN_SOURCE_LIMIT_EXCEEDED",
              "The Markdown document exceeds its byte limit.");
        }
        for (int index = 0; index < count; index++) {
          char character = buffer[index];
          if (firstCharacter) {
            firstCharacter = false;
            if (character == '\ufeff') continue;
          }
          if (Character.isISOControl(character)
              && character != '\t'
              && character != '\n'
              && character != '\r'
              && character != '\f') {
            throw failure(
                "IMPORT_MARKDOWN_CONTROL_INVALID",
                "The Markdown document contains unsupported control data.");
          }
        }
        source.append(buffer, 0, count);
      }
    } catch (CharacterCodingException exception) {
      throw failure(
          "IMPORT_MARKDOWN_ENCODING_INVALID",
          "The Markdown document is not valid UTF-8.",
          exception);
    }
    return source.toString();
  }

  private static String normalize(String text) {
    return COLLAPSIBLE_WHITESPACE
        .matcher(Normalizer.normalize(text, Normalizer.Form.NFC))
        .replaceAll(" ")
        .trim();
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

  private static boolean matchesMarkdownType(String contentType) {
    if (contentType == null || contentType.isBlank()) return true;
    String normalized = contentType.trim().toLowerCase(Locale.ROOT);
    int parameters = normalized.indexOf(';');
    if (parameters >= 0) normalized = normalized.substring(0, parameters).trim();
    return MARKDOWN_TYPES.contains(normalized);
  }

  private static void checkDeadline(long deadline) throws MarkdownParseException {
    if (System.nanoTime() - deadline >= 0) {
      throw failure(
          "IMPORT_MARKDOWN_PARSE_TIMEOUT", "The Markdown document took too long to parse.");
    }
  }

  private static MarkdownParseException failure(String code, String message) {
    return new MarkdownParseException(code, message);
  }

  private static MarkdownParseException failure(String code, String message, Throwable cause) {
    return new MarkdownParseException(code, message, cause);
  }

  private record NodeDepth(Node node, int depth) {}

  private record ChapterDraft(String title, String firstHeading, List<SourceSegment> segments) {
    private ChapterDraft {
      segments = List.copyOf(segments);
    }
  }

  private static final class ChapterBuilder {
    private final String title;
    private final List<SourceSegment> segments = new ArrayList<>();
    private String firstHeading = "";
    private boolean keepWhenEmpty;

    private ChapterBuilder(String title) {
      this.title = title;
    }

    private String title() {
      return title;
    }

    private List<SourceSegment> segments() {
      return segments;
    }

    private String firstHeading() {
      return firstHeading;
    }

    private void firstHeading(String value) {
      firstHeading = value;
    }

    private boolean keepWhenEmpty() {
      return keepWhenEmpty;
    }

    private void keepWhenEmpty(boolean value) {
      keepWhenEmpty = value;
    }

    private void add(SourceSegment segment) {
      segments.add(segment);
    }

    private ChapterDraft toDraft() {
      return new ChapterDraft(title, firstHeading, segments);
    }
  }

  static final class MarkdownParseException extends IOException {
    private final String code;

    MarkdownParseException(String code, String message) {
      super(message);
      this.code = code;
    }

    MarkdownParseException(String code, String message, Throwable cause) {
      super(message, cause);
      this.code = code;
    }

    String code() {
      return code;
    }
  }
}
