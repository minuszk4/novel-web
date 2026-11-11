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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Parses bounded UTF-8 plain-text uploads into one private, editable chapter. */
@Component
@Profile("postgres")
public class PlainTextParser {

  public static final String PARSER_VERSION = "txt-v1";

  private static final int MAX_SEGMENT_CODE_POINTS = 4_000;
  private static final int MAX_SEGMENT_UTF8_BYTES = 16_384;
  private static final int MAX_SEGMENTS = 5_000;
  private static final int MAX_TOTAL_SOURCE_BYTES = 384 * 1024;
  private static final int PREVIEW_CODE_POINTS = 600;
  private static final int READ_BUFFER_SIZE = 8_192;
  private static final Duration PARSE_TIMEOUT = Duration.ofSeconds(30);
  private static final Pattern COLLAPSIBLE_WHITESPACE = Pattern.compile("[\\p{Z}\\s]+");

  public Preview parse(Path file, String filename, String declaredContentType, String parserVersion)
      throws IOException {
    if (!PARSER_VERSION.equals(parserVersion)) {
      throw failure("IMPORT_PARSER_VERSION_UNSUPPORTED", "The text parser version is unsupported.");
    }
    if (filename == null || !filename.toLowerCase(Locale.ROOT).endsWith(".txt")) {
      throw failure("IMPORT_FORMAT_UNSUPPORTED", "Only plain-text TXT files use this parser.");
    }
    if (!matchesPlainTextType(declaredContentType)) {
      throw failure("IMPORT_CONTENT_INVALID", "The declared content type does not match TXT.");
    }

    long deadline = System.nanoTime() + PARSE_TIMEOUT.toNanos();
    List<SourceSegment> segments = new ArrayList<>();
    int[] totalBytes = {0};
    StringBuilder line = new StringBuilder();
    CharsetDecoder decoder =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    boolean firstCharacter = true;
    boolean previousWasCarriageReturn = false;
    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(Files.newInputStream(file), decoder), READ_BUFFER_SIZE)) {
      char[] buffer = new char[READ_BUFFER_SIZE];
      int count;
      while ((count = reader.read(buffer)) != -1) {
        checkDeadline(deadline);
        for (int index = 0; index < count; index++) {
          char character = buffer[index];
          if (firstCharacter) {
            firstCharacter = false;
            if (character == '\ufeff') continue;
          }
          if (character == '\r' || character == '\n') {
            if (!(character == '\n' && previousWasCarriageReturn)) {
              addLineSegments(line, segments, totalBytes);
            }
            line.setLength(0);
            previousWasCarriageReturn = character == '\r';
            continue;
          }
          previousWasCarriageReturn = false;
          if (Character.isISOControl(character) && character != '\t') {
            throw failure(
                "IMPORT_TEXT_INVALID_CONTROL", "The text file contains unsupported control data.");
          }
          line.append(character);
          if (line.length() > MAX_SEGMENT_CODE_POINTS * 2) {
            int splitAt = line.offsetByCodePoints(0, MAX_SEGMENT_CODE_POINTS);
            addSegment(line.substring(0, splitAt), segments, totalBytes);
            line.delete(0, splitAt);
          }
        }
      }
      addLineSegments(line, segments, totalBytes);
    } catch (TextParseException exception) {
      throw exception;
    } catch (CharacterCodingException exception) {
      throw failure("IMPORT_TEXT_ENCODING_INVALID", "The text file is not valid UTF-8.", exception);
    }

    if (segments.isEmpty()) {
      throw failure("IMPORT_TEXT_EMPTY", "The text file does not contain readable text.");
    }

    String title = titleFromFilename(filename);
    String previewText = previewText(segments);
    Chapter chapter =
        new Chapter(
            0,
            "source.txt",
            "Nội dung",
            true,
            segments.size(),
            previewText,
            false,
            title,
            "",
            segments);
    return new Preview(
        PARSER_VERSION,
        new Metadata(title, "und", null, null, null, List.of()),
        List.of(chapter),
        List.of(new TocEntry(chapter.title(), 0, chapter.index(), null)),
        List.of(
            new Warning(
                "TXT_SINGLE_CHAPTER",
                "Plain-text imports are kept as one chapter because the file has no reliable chapter structure.")));
  }

  private static void addLineSegments(
      StringBuilder line, List<SourceSegment> segments, int[] totalBytes)
      throws TextParseException {
    if (line.length() == 0) return;
    addSegment(line.toString(), segments, totalBytes);
  }

  private static void addSegment(String rawText, List<SourceSegment> segments, int[] totalBytes)
      throws TextParseException {
    String normalized =
        COLLAPSIBLE_WHITESPACE
            .matcher(Normalizer.normalize(rawText, Normalizer.Form.NFC))
            .replaceAll(" ")
            .trim();
    if (normalized.isEmpty()) return;
    int codePoints = normalized.codePointCount(0, normalized.length());
    int utf8Bytes = normalized.getBytes(StandardCharsets.UTF_8).length;
    if (codePoints > MAX_SEGMENT_CODE_POINTS || utf8Bytes > MAX_SEGMENT_UTF8_BYTES) {
      throw failure(
          "IMPORT_TEXT_SEGMENT_TOO_LARGE", "A normalized text segment exceeds its limit.");
    }
    if (segments.size() >= MAX_SEGMENTS) {
      throw failure("IMPORT_TEXT_SEGMENT_LIMIT_EXCEEDED", "The text file has too many segments.");
    }
    if (utf8Bytes > MAX_TOTAL_SOURCE_BYTES - totalBytes[0]) {
      throw failure(
          "IMPORT_TEXT_SOURCE_LIMIT_EXCEEDED", "The normalized text exceeds its storage limit.");
    }
    segments.add(new SourceSegment(segments.size(), "text", normalized));
    totalBytes[0] += utf8Bytes;
  }

  private static String previewText(List<SourceSegment> segments) {
    StringBuilder preview = new StringBuilder();
    for (SourceSegment segment : segments) {
      if (!preview.isEmpty()) preview.append('\n');
      int remaining = PREVIEW_CODE_POINTS - preview.codePointCount(0, preview.length());
      if (remaining <= 0) break;
      String text = segment.text();
      int codePoints = text.codePointCount(0, text.length());
      preview.append(
          codePoints <= remaining
              ? text
              : text.substring(0, text.offsetByCodePoints(0, remaining)));
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

  private static boolean matchesPlainTextType(String contentType) {
    if (contentType == null || contentType.isBlank()) return true;
    String normalized = contentType.trim().toLowerCase(Locale.ROOT);
    int parameters = normalized.indexOf(';');
    if (parameters >= 0) normalized = normalized.substring(0, parameters).trim();
    return "text/plain".equals(normalized) || "application/octet-stream".equals(normalized);
  }

  private static void checkDeadline(long deadline) throws TextParseException {
    if (System.nanoTime() > deadline) {
      throw failure("IMPORT_TEXT_PARSE_TIMEOUT", "The text file took too long to parse.");
    }
  }

  private static TextParseException failure(String code, String message) {
    return new TextParseException(code, message);
  }

  private static TextParseException failure(String code, String message, Throwable cause) {
    return new TextParseException(code, message, cause);
  }

  static final class TextParseException extends IOException {
    private final String code;

    TextParseException(String code, String message) {
      super(message);
      this.code = code;
    }

    TextParseException(String code, String message, Throwable cause) {
      super(message, cause);
      this.code = code;
    }

    String code() {
      return code;
    }
  }
}
