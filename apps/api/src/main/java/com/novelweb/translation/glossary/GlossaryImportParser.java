package com.novelweb.translation.glossary;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Strict, side-effect-free parser for user supplied glossary interchange files. */
public final class GlossaryImportParser {

  public static final int MAX_IMPORT_BYTES = 1_048_576;
  public static final int MAX_TERMS = GlossaryRevisionWriteRepository.MAX_DRAFT_TERMS;
  public static final int MAX_ALIASES_PER_TERM =
      GlossaryRevisionWriteRepository.MAX_ALIASES_PER_TERM;
  public static final int MAX_ALIASES = GlossaryRevisionWriteRepository.MAX_DRAFT_ALIASES;

  private static final Set<String> ALLOWED_FIELDS =
      Set.of(
          "sourceTerm",
          "targetTerm",
          "aliases",
          "priority",
          "casePolicy",
          "matchMode",
          "doNotTranslate",
          "contextNote");
  private static final List<String> CSV_HEADER_ORDER =
      List.of(
          "sourceTerm",
          "targetTerm",
          "aliases",
          "priority",
          "casePolicy",
          "matchMode",
          "doNotTranslate",
          "contextNote");
  private static final ObjectMapper JSON = createJsonMapper();

  public enum Format {
    JSON,
    CSV,
    TSV
  }

  public record ImportedTerm(
      int rowNumber,
      String sourceTerm,
      String targetTerm,
      int priority,
      GlossaryMatcher.CasePolicy casePolicy,
      GlossaryMatcher.MatchMode matchMode,
      boolean doNotTranslate,
      String contextNote,
      List<String> aliases) {
    public ImportedTerm {
      aliases = List.copyOf(aliases);
    }
  }

  public record ParsedImport(Format format, List<ImportedTerm> terms) {
    public ParsedImport {
      Objects.requireNonNull(format, "format is required");
      terms = List.copyOf(terms);
    }
  }

  public record ParseIssue(String code, String field, int rowNumber, String message) {}

  public static final class ParseException extends RuntimeException {
    private final List<ParseIssue> issues;

    private ParseException(List<ParseIssue> issues) {
      super("Glossary import file is invalid");
      this.issues = List.copyOf(issues);
    }

    public List<ParseIssue> issues() {
      return issues;
    }
  }

  public ParsedImport parse(byte[] content, Format format) {
    Objects.requireNonNull(content, "content is required");
    Objects.requireNonNull(format, "format is required");
    if (content.length > MAX_IMPORT_BYTES) {
      throw issue("IMPORT_FILE_TOO_LARGE", null, 0, "File exceeds the 1 MiB limit.");
    }
    if (content.length == 0) {
      throw issue(
          "IMPORT_FILE_EMPTY", null, 0, "File must contain a header and at least one term.");
    }

    String text = decodeUtf8(content);
    if (text.startsWith("\uFEFF")) text = text.substring(1);
    List<Row> rows =
        switch (format) {
          case JSON -> jsonRows(text);
          case CSV -> delimitedRows(text, ',');
          case TSV -> delimitedRows(text, '\t');
        };
    return validateRows(format, rows);
  }

  private static ParsedImport validateRows(Format format, List<Row> rows) {
    List<ParseIssue> issues = new ArrayList<>();
    if (rows.isEmpty()) {
      if (format == Format.JSON) {
        throw issue("IMPORT_TERMS_REQUIRED", "terms", 0, "JSON must contain at least one term.");
      }
      throw issue(
          "IMPORT_FILE_EMPTY", null, 0, "File must contain a header and at least one term.");
    }
    if (rows.size() > MAX_TERMS + 1) {
      throw issue(
          "IMPORT_TERM_LIMIT_EXCEEDED",
          "terms",
          rows.get(MAX_TERMS + 1).number(),
          "A glossary import may contain at most 1,000 terms.");
    }

    Map<String, JsonNode> header = format == Format.JSON ? null : header(rows.getFirst(), issues);
    if (!issues.isEmpty()) throw new ParseException(issues);

    List<ImportedTerm> terms = new ArrayList<>();
    List<PatternOccurrence> patterns = new ArrayList<>();
    long aliasCount = 0;
    int firstDataRow = format == Format.JSON ? 0 : 1;
    for (int index = firstDataRow; index < rows.size(); index++) {
      Row row = rows.get(index);
      Map<String, JsonNode> values =
          format == Format.JSON ? row.jsonValues() : valuesFromDelimited(row, header, issues);
      if (values == null) continue;
      ImportedTerm term = parseTerm(values, row.number(), issues);
      if (term == null) continue;
      aliasCount += term.aliases().size();
      if (term.aliases().size() > MAX_ALIASES_PER_TERM) {
        addIssue(
            issues,
            "IMPORT_ALIAS_LIMIT_EXCEEDED",
            "aliases",
            row.number(),
            "A term may contain at most 25 aliases.");
      }
      if (aliasCount > MAX_ALIASES) {
        addIssue(
            issues,
            "IMPORT_ALIAS_TOTAL_EXCEEDED",
            "aliases",
            row.number(),
            "A glossary import may contain at most 5,000 aliases.");
      }
      detectPatternCollisions(term, patterns, issues);
      terms.add(term);
    }

    if (terms.isEmpty() && issues.isEmpty()) {
      addIssue(issues, "IMPORT_TERMS_REQUIRED", "terms", 0, "File must contain at least one term.");
    }
    if (!issues.isEmpty()) throw new ParseException(issues);
    return new ParsedImport(format, terms);
  }

  private static Map<String, JsonNode> header(Row row, List<ParseIssue> issues) {
    Map<String, JsonNode> fields = new LinkedHashMap<>();
    for (int index = 0; index < row.cells().size(); index++) {
      String name = row.cells().get(index).strip();
      if (!ALLOWED_FIELDS.contains(name)) {
        addIssue(
            issues,
            "IMPORT_UNKNOWN_COLUMN",
            null,
            row.number(),
            "Header contains an unsupported column.");
      } else if (fields.putIfAbsent(name, JSON.getNodeFactory().numberNode(index)) != null) {
        addIssue(issues, "IMPORT_DUPLICATE_COLUMN", name, row.number(), "Header repeats a column.");
      }
    }
    for (String required : List.of("sourceTerm", "targetTerm")) {
      if (!fields.containsKey(required)) {
        addIssue(
            issues,
            "IMPORT_REQUIRED_COLUMN_MISSING",
            required,
            row.number(),
            "Header must include sourceTerm and targetTerm.");
      }
    }
    return fields;
  }

  private static Map<String, JsonNode> valuesFromDelimited(
      Row row, Map<String, JsonNode> header, List<ParseIssue> issues) {
    if (row.cells().stream().allMatch(String::isBlank)) return null;
    if (row.cells().size() != header.size()) {
      addIssue(
          issues,
          "IMPORT_COLUMN_COUNT_MISMATCH",
          null,
          row.number(),
          "Each data row must have the same number of cells as the header.");
      return null;
    }
    Map<String, JsonNode> result = new LinkedHashMap<>();
    header.forEach(
        (name, indexNode) -> {
          int index = indexNode.intValue();
          String value = row.cells().get(index);
          if (name.equals("aliases") && !value.isBlank()) {
            try {
              result.put(name, JSON.readTree(value));
            } catch (JacksonException exception) {
              addIssue(
                  issues,
                  "IMPORT_ALIASES_INVALID_JSON",
                  name,
                  row.number(),
                  "The aliases cell must be a JSON array of strings.");
            }
          } else {
            result.put(name, JSON.getNodeFactory().textNode(value));
          }
        });
    return result;
  }

  private static ImportedTerm parseTerm(
      Map<String, JsonNode> values, int rowNumber, List<ParseIssue> issues) {
    int issueStart = issues.size();
    String source = text(values.get("sourceTerm"), "sourceTerm", rowNumber, issues, true);
    String target = text(values.get("targetTerm"), "targetTerm", rowNumber, issues, false);
    String note = text(values.get("contextNote"), "contextNote", rowNumber, issues, false);
    int priority = integer(values.get("priority"), "priority", rowNumber, issues, 0);
    boolean doNotTranslate =
        bool(values.get("doNotTranslate"), "doNotTranslate", rowNumber, issues, false);
    GlossaryMatcher.CasePolicy casePolicy =
        enumValue(
            values.get("casePolicy"),
            "casePolicy",
            rowNumber,
            issues,
            GlossaryMatcher.CasePolicy.class,
            GlossaryMatcher.CasePolicy.INSENSITIVE);
    GlossaryMatcher.MatchMode matchMode =
        enumValue(
            values.get("matchMode"),
            "matchMode",
            rowNumber,
            issues,
            GlossaryMatcher.MatchMode.class,
            GlossaryMatcher.MatchMode.WHOLE_PHRASE);
    List<String> aliases = aliases(values.get("aliases"), rowNumber, issues);

    source = normalized(source);
    target = normalized(target);
    note = normalized(note);
    if (source != null) requireLength(source, 512, "sourceTerm", rowNumber, issues);
    if (target != null) requireLength(target, 4096, "targetTerm", rowNumber, issues);
    if (note != null) requireLength(note, 2000, "contextNote", rowNumber, issues);
    if (priority < -1_000_000 || priority > 1_000_000) {
      addIssue(
          issues,
          "IMPORT_PRIORITY_OUT_OF_RANGE",
          "priority",
          rowNumber,
          "Priority must be between -1,000,000 and 1,000,000.");
    }
    if (doNotTranslate) {
      if (target != null && target.isBlank()) target = null;
    } else if (target == null || target.isBlank()) {
      addIssue(
          issues,
          "IMPORT_TARGET_REQUIRED",
          "targetTerm",
          rowNumber,
          "targetTerm is required unless doNotTranslate is true.");
    }
    if (issues.size() != issueStart) return null;
    return new ImportedTerm(
        rowNumber, source, target, priority, casePolicy, matchMode, doNotTranslate, note, aliases);
  }

  private static List<String> aliases(JsonNode value, int row, List<ParseIssue> issues) {
    if (value == null || value.isNull()) return List.of();
    if (!value.isArray()) {
      addIssue(
          issues, "IMPORT_ALIASES_INVALID", "aliases", row, "aliases must be an array of strings.");
      return List.of();
    }
    if (value.size() > MAX_ALIASES_PER_TERM) {
      addIssue(
          issues,
          "IMPORT_ALIAS_LIMIT_EXCEEDED",
          "aliases",
          row,
          "A term may contain at most 25 aliases.");
    }
    List<String> result = new ArrayList<>();
    for (JsonNode item : value) {
      if (!item.isTextual()) {
        addIssue(issues, "IMPORT_ALIAS_INVALID", "aliases", row, "Every alias must be a string.");
        continue;
      }
      String alias = normalized(item.textValue());
      if (alias == null || alias.isBlank()) {
        addIssue(issues, "IMPORT_ALIAS_REQUIRED", "aliases", row, "Aliases must not be blank.");
        continue;
      }
      requireLength(alias, 512, "aliases", row, issues);
      result.add(alias);
    }
    return result;
  }

  private static String text(
      JsonNode value, String field, int row, List<ParseIssue> issues, boolean required) {
    if (value == null || value.isNull()) {
      if (required) addIssue(issues, "IMPORT_FIELD_REQUIRED", field, row, field + " is required.");
      return null;
    }
    if (!value.isTextual()) {
      addIssue(issues, "IMPORT_FIELD_TYPE_INVALID", field, row, field + " must be a string.");
      return null;
    }
    String result = normalized(value.textValue());
    if (required && (result == null || result.isBlank())) {
      addIssue(issues, "IMPORT_FIELD_REQUIRED", field, row, field + " must not be blank.");
    }
    return result;
  }

  private static String normalized(String value) {
    if (value == null) return null;
    return Normalizer.normalize(value, Normalizer.Form.NFC).strip();
  }

  private static int integer(
      JsonNode value, String field, int row, List<ParseIssue> issues, int defaultValue) {
    if (value == null || value.isNull() || (value.isTextual() && value.textValue().isBlank())) {
      return defaultValue;
    }
    if (value.isIntegralNumber() && value.canConvertToInt()) return value.intValue();
    if (value.isTextual()) {
      try {
        return Integer.parseInt(value.textValue().strip());
      } catch (NumberFormatException ignored) {
        // Report the same safe validation issue as any other malformed value.
      }
    }
    addIssue(issues, "IMPORT_FIELD_TYPE_INVALID", field, row, field + " must be a 32-bit integer.");
    return defaultValue;
  }

  private static boolean bool(
      JsonNode value, String field, int row, List<ParseIssue> issues, boolean defaultValue) {
    if (value == null || value.isNull() || (value.isTextual() && value.textValue().isBlank())) {
      return defaultValue;
    }
    if (value.isBoolean()) return value.booleanValue();
    if (value.isTextual()) {
      return switch (value.textValue().strip()) {
        case "true" -> true;
        case "false" -> false;
        default -> {
          addIssue(
              issues, "IMPORT_FIELD_TYPE_INVALID", field, row, field + " must be true or false.");
          yield defaultValue;
        }
      };
    }
    addIssue(issues, "IMPORT_FIELD_TYPE_INVALID", field, row, field + " must be true or false.");
    return defaultValue;
  }

  private static <E extends Enum<E>> E enumValue(
      JsonNode value,
      String field,
      int row,
      List<ParseIssue> issues,
      Class<E> enumType,
      E defaultValue) {
    if (value == null || value.isNull() || (value.isTextual() && value.textValue().isBlank())) {
      return defaultValue;
    }
    if (!value.isTextual()) {
      addIssue(
          issues,
          "IMPORT_FIELD_TYPE_INVALID",
          field,
          row,
          field + " must be a supported string value.");
      return defaultValue;
    }
    try {
      return Enum.valueOf(enumType, value.textValue().strip());
    } catch (IllegalArgumentException exception) {
      addIssue(
          issues,
          "IMPORT_FIELD_VALUE_INVALID",
          field,
          row,
          field + " contains an unsupported value.");
      return defaultValue;
    }
  }

  private static void requireLength(
      String value, int maxCodePoints, String field, int row, List<ParseIssue> issues) {
    if (value.codePointCount(0, value.length()) > maxCodePoints) {
      addIssue(
          issues,
          "IMPORT_FIELD_TOO_LONG",
          field,
          row,
          field + " exceeds " + maxCodePoints + " Unicode code points.");
    }
  }

  private static void detectPatternCollisions(
      ImportedTerm term, List<PatternOccurrence> seen, List<ParseIssue> issues) {
    List<String> values = new ArrayList<>();
    values.add(term.sourceTerm());
    values.addAll(term.aliases());
    for (int index = 0; index < values.size(); index++) {
      String pattern = values.get(index);
      String exact =
          GlossaryMatcher.normalizeForComparison(pattern, GlossaryMatcher.CasePolicy.SENSITIVE);
      String folded =
          GlossaryMatcher.normalizeForComparison(pattern, GlossaryMatcher.CasePolicy.INSENSITIVE);
      String field = index == 0 ? "sourceTerm" : "aliases";
      PatternOccurrence collision =
          seen.stream()
              .filter(
                  prior ->
                      term.casePolicy() == GlossaryMatcher.CasePolicy.INSENSITIVE
                              || prior.casePolicy() == GlossaryMatcher.CasePolicy.INSENSITIVE
                          ? prior.folded().equals(folded)
                          : prior.exact().equals(exact))
              .findFirst()
              .orElse(null);
      if (collision != null) {
        addIssue(
            issues,
            collision.rowNumber() == term.rowNumber()
                ? "IMPORT_DUPLICATE_PATTERN_IN_ROW"
                : "IMPORT_DUPLICATE_SOURCE_PATTERN",
            field,
            term.rowNumber(),
            collision.rowNumber() == term.rowNumber()
                ? "A term repeats a source pattern in its canonical value or aliases."
                : "A source pattern conflicts with an earlier import row.");
      }
      seen.add(new PatternOccurrence(term.rowNumber(), term.casePolicy(), exact, folded));
    }
  }

  private static List<Row> jsonRows(String text) {
    JsonNode root;
    try {
      root = JSON.readTree(text);
    } catch (JacksonException exception) {
      throw issue(
          "IMPORT_JSON_INVALID", null, 0, "File must be valid UTF-8 JSON without duplicate keys.");
    }
    if (root == null || !root.isObject()) {
      throw issue(
          "IMPORT_JSON_INVALID",
          null,
          0,
          "JSON root must be an object containing version and terms.");
    }
    List<ParseIssue> issues = new ArrayList<>();
    for (String name : root.propertyNames()) {
      if (!Set.of("version", "terms").contains(name)) {
        addIssue(
            issues, "IMPORT_UNKNOWN_FIELD", null, 0, "JSON contains an unsupported root field.");
      }
    }
    JsonNode version = root.get("version");
    if (version == null
        || !version.isIntegralNumber()
        || !version.canConvertToInt()
        || version.intValue() != 1) {
      addIssue(
          issues, "IMPORT_VERSION_UNSUPPORTED", "version", 0, "JSON import version must be 1.");
    }
    JsonNode terms = root.get("terms");
    if (terms == null || !terms.isArray()) {
      addIssue(issues, "IMPORT_TERMS_REQUIRED", "terms", 0, "JSON must contain a terms array.");
    } else if (terms.size() > MAX_TERMS) {
      addIssue(
          issues,
          "IMPORT_TERM_LIMIT_EXCEEDED",
          "terms",
          0,
          "A glossary import may contain at most 1,000 terms.");
    }
    if (!issues.isEmpty()) throw new ParseException(issues);
    List<Row> rows = new ArrayList<>();
    int rowNumber = 1;
    for (JsonNode item : terms) {
      if (!item.isObject()) {
        addIssue(
            issues,
            "IMPORT_TERM_ROW_INVALID",
            "terms",
            rowNumber,
            "Each terms item must be an object.");
        rowNumber++;
        continue;
      }
      int currentRow = rowNumber;
      Map<String, JsonNode> values = new LinkedHashMap<>();
      for (Map.Entry<String, JsonNode> field : item.properties()) {
        if (!ALLOWED_FIELDS.contains(field.getKey())) {
          addIssue(
              issues,
              "IMPORT_UNKNOWN_FIELD",
              null,
              currentRow,
              "Term row contains an unsupported field.");
        } else {
          values.put(field.getKey(), field.getValue());
        }
      }
      rows.add(new Row(rowNumber++, List.of(), Map.copyOf(values)));
    }
    if (!issues.isEmpty()) throw new ParseException(issues);
    return rows;
  }

  private static List<Row> delimitedRows(String text, char delimiter) {
    List<Row> rows = new ArrayList<>();
    List<String> cells = new ArrayList<>();
    StringBuilder cell = new StringBuilder();
    boolean inQuotes = false;
    boolean afterQuote = false;
    boolean atCellStart = true;
    int line = 1;
    int rowLine = 1;

    for (int index = 0; index < text.length(); index++) {
      char current = text.charAt(index);
      if (inQuotes) {
        if (current == '"') {
          if (index + 1 < text.length() && text.charAt(index + 1) == '"') {
            cell.append('"');
            index++;
          } else {
            inQuotes = false;
            afterQuote = true;
          }
        } else if (current == '\r' || current == '\n') {
          if (current == '\r' && index + 1 < text.length() && text.charAt(index + 1) == '\n')
            index++;
          cell.append('\n');
          line++;
        } else {
          cell.append(current);
        }
        continue;
      }

      if (afterQuote && current != delimiter && current != '\r' && current != '\n') {
        throw issue(
            "IMPORT_DELIMITED_FILE_INVALID",
            null,
            rowLine,
            "Quoted cells may only be followed by a delimiter or line break.");
      }
      if (current == '"') {
        if (!atCellStart) {
          throw issue(
              "IMPORT_DELIMITED_FILE_INVALID",
              null,
              rowLine,
              "Quote characters inside cells must be escaped by doubling them.");
        }
        inQuotes = true;
        atCellStart = false;
      } else if (current == delimiter) {
        cells.add(cell.toString());
        cell.setLength(0);
        atCellStart = true;
        afterQuote = false;
      } else if (current == '\r' || current == '\n') {
        cells.add(cell.toString());
        addRowIfNotBlank(rows, rowLine, cells);
        cells = new ArrayList<>();
        cell.setLength(0);
        afterQuote = false;
        atCellStart = true;
        if (current == '\r' && index + 1 < text.length() && text.charAt(index + 1) == '\n') index++;
        line++;
        rowLine = line;
      } else {
        cell.append(current);
        atCellStart = false;
      }
    }
    if (inQuotes) {
      throw issue(
          "IMPORT_DELIMITED_FILE_INVALID", null, rowLine, "File ends inside a quoted cell.");
    }
    if (!cells.isEmpty() || cell.length() > 0 || afterQuote) {
      cells.add(cell.toString());
      addRowIfNotBlank(rows, rowLine, cells);
    }
    return rows;
  }

  private static void addRowIfNotBlank(List<Row> rows, int rowNumber, List<String> cells) {
    if (cells.stream().anyMatch(value -> !value.isBlank())) {
      rows.add(new Row(rowNumber, List.copyOf(cells), null));
    }
  }

  private static String decodeUtf8(byte[] content) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(content))
          .toString();
    } catch (CharacterCodingException exception) {
      throw issue("IMPORT_ENCODING_INVALID", null, 0, "File must use valid UTF-8 encoding.");
    }
  }

  private static ObjectMapper createJsonMapper() {
    return JsonMapper.builder(
            JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build();
  }

  private static ParseException issue(String code, String field, int row, String message) {
    return new ParseException(List.of(new ParseIssue(code, field, row, message)));
  }

  private static void addIssue(
      List<ParseIssue> issues, String code, String field, int row, String message) {
    issues.add(new ParseIssue(code, field, row, message));
  }

  private record Row(int number, List<String> cells, Map<String, JsonNode> jsonValues) {}

  private record PatternOccurrence(
      int rowNumber, GlossaryMatcher.CasePolicy casePolicy, String exact, String folded) {}
}
