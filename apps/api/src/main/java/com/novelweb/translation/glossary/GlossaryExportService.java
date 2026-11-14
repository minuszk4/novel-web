package com.novelweb.translation.glossary;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Produces deterministic, import-compatible snapshots for glossary downloads. */
@Service
public class GlossaryExportService {

  private static final List<String> HEADER =
      List.of(
          "sourceTerm",
          "targetTerm",
          "aliases",
          "priority",
          "casePolicy",
          "matchMode",
          "doNotTranslate",
          "contextNote");

  private final ObjectMapper objectMapper;

  public GlossaryExportService(ObjectMapper objectMapper) {
    this.objectMapper = Objects.requireNonNull(objectMapper, "object mapper is required");
  }

  public byte[] encode(List<GlossaryRepository.TermRecord> terms, Format format) {
    Objects.requireNonNull(terms, "terms are required");
    Objects.requireNonNull(format, "format is required");
    if (terms.size() > GlossaryRevisionWriteRepository.MAX_DRAFT_TERMS) {
      throw new IllegalArgumentException("too many terms to export");
    }
    return switch (format) {
      case JSON -> json(terms);
      case CSV -> delimited(terms, ',');
      case TSV -> delimited(terms, '\t');
    };
  }

  private byte[] json(List<GlossaryRepository.TermRecord> terms) {
    List<ExportTerm> rows = terms.stream().map(ExportTerm::from).toList();
    try {
      return objectMapper.writeValueAsBytes(new ExportDocument(1, rows));
    } catch (JacksonException exception) {
      throw new IllegalStateException("Unable to encode glossary JSON export", exception);
    }
  }

  private byte[] delimited(List<GlossaryRepository.TermRecord> terms, char delimiter) {
    StringBuilder output = new StringBuilder();
    appendRow(output, HEADER, delimiter);
    for (GlossaryRepository.TermRecord term : terms) {
      String aliases;
      try {
        aliases = objectMapper.writeValueAsString(term.aliases());
      } catch (JacksonException exception) {
        throw new IllegalStateException("Unable to encode glossary aliases", exception);
      }
      appendRow(
          output,
          List.of(
              spreadsheetSafe(term.sourceTerm()),
              spreadsheetSafe(term.targetTerm() == null ? "" : term.targetTerm()),
              spreadsheetSafe(aliases),
              Integer.toString(term.priority()),
              term.casePolicy().name(),
              term.matchMode().name(),
              Boolean.toString(term.doNotTranslate()),
              spreadsheetSafe(term.contextNote() == null ? "" : term.contextNote())),
          delimiter);
    }
    return output.toString().getBytes(StandardCharsets.UTF_8);
  }

  private static void appendRow(StringBuilder output, List<String> cells, char delimiter) {
    for (int index = 0; index < cells.size(); index++) {
      if (index > 0) output.append(delimiter);
      output.append('"').append(cells.get(index).replace("\"", "\"\"")).append('"');
    }
    output.append("\r\n");
  }

  /** Prefixes formula-like cells so opening CSV/TSV in common spreadsheets treats them as text. */
  private static String spreadsheetSafe(String value) {
    int index = 0;
    while (index < value.length()) {
      char current = value.charAt(index);
      if (Character.isWhitespace(current)
          || Character.isSpaceChar(current)
          || Character.isISOControl(current)
          || current == '\uFEFF') {
        index++;
      } else {
        break;
      }
    }
    if (index < value.length() && "=+-@".indexOf(value.charAt(index)) >= 0) return "'" + value;
    return value;
  }

  public enum Format {
    JSON("application/json;charset=UTF-8", "json"),
    CSV("text/csv;charset=UTF-8", "csv"),
    TSV("text/tab-separated-values;charset=UTF-8", "tsv");

    private final String contentType;
    private final String extension;

    Format(String contentType, String extension) {
      this.contentType = contentType;
      this.extension = extension;
    }

    public String contentType() {
      return contentType;
    }

    public String extension() {
      return extension;
    }

    public static Format parse(String value) {
      return switch (value) {
        case "json" -> JSON;
        case "csv" -> CSV;
        case "tsv" -> TSV;
        default -> throw new IllegalArgumentException("format must be json, csv or tsv");
      };
    }
  }

  private record ExportDocument(int version, List<ExportTerm> terms) {}

  private record ExportTerm(
      String sourceTerm,
      String targetTerm,
      List<String> aliases,
      int priority,
      GlossaryMatcher.CasePolicy casePolicy,
      GlossaryMatcher.MatchMode matchMode,
      boolean doNotTranslate,
      String contextNote) {
    private static ExportTerm from(GlossaryRepository.TermRecord term) {
      return new ExportTerm(
          term.sourceTerm(),
          term.targetTerm(),
          term.aliases(),
          term.priority(),
          term.casePolicy(),
          term.matchMode(),
          term.doNotTranslate(),
          term.contextNote());
    }
  }
}
