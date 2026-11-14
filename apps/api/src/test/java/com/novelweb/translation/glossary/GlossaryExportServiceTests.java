package com.novelweb.translation.glossary;

import static com.novelweb.translation.glossary.GlossaryMatcher.CasePolicy.INSENSITIVE;
import static com.novelweb.translation.glossary.GlossaryMatcher.MatchMode.WHOLE_PHRASE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class GlossaryExportServiceTests {

  private final GlossaryImportParser parser = new GlossaryImportParser();
  private final GlossaryExportService service =
      new GlossaryExportService(JsonMapper.builder().build());

  @Test
  void jsonExportIsVersionedLosslessAndAcceptedByTheExistingImportParser() {
    GlossaryRepository.TermRecord source =
        term("dragon, \"elder\"\nname", "rồng cổ", List.of("wyrm", "long tộc"), "Keep exact");

    byte[] export = service.encode(List.of(source), GlossaryExportService.Format.JSON);
    GlossaryImportParser.ParsedImport parsed =
        parser.parse(export, GlossaryImportParser.Format.JSON);

    assertEquals(1, parsed.terms().size());
    assertEquals(source.sourceTerm(), parsed.terms().getFirst().sourceTerm());
    assertEquals(source.targetTerm(), parsed.terms().getFirst().targetTerm());
    assertEquals(source.aliases(), parsed.terms().getFirst().aliases());
    assertEquals(source.contextNote(), parsed.terms().getFirst().contextNote());
    assertEquals(source.priority(), parsed.terms().getFirst().priority());
    assertEquals(source.doNotTranslate(), parsed.terms().getFirst().doNotTranslate());
  }

  @Test
  void csvAndTsvExportsUseImportHeaderAndQuoteDelimiterAndFormulaCells() {
    GlossaryRepository.TermRecord source =
        term("=HYPERLINK(\"https://example.invalid\")", "+cmd", List.of("@alias"), null);

    for (GlossaryExportService.Format exportFormat :
        List.of(GlossaryExportService.Format.CSV, GlossaryExportService.Format.TSV)) {
      GlossaryImportParser.Format importFormat =
          exportFormat == GlossaryExportService.Format.CSV
              ? GlossaryImportParser.Format.CSV
              : GlossaryImportParser.Format.TSV;
      String text =
          new String(service.encode(List.of(source), exportFormat), StandardCharsets.UTF_8);
      GlossaryImportParser.ParsedImport parsed =
          parser.parse(text.getBytes(StandardCharsets.UTF_8), importFormat);

      assertEquals(
          "\"sourceTerm\"" + (exportFormat == GlossaryExportService.Format.CSV ? "," : "\t"),
          text.substring(0, "\"sourceTerm\"".length() + 1));
      assertEquals(
          "'=HYPERLINK(\"https://example.invalid\")", parsed.terms().getFirst().sourceTerm());
      assertEquals("'+cmd", parsed.terms().getFirst().targetTerm());
      assertEquals(List.of("@alias"), parsed.terms().getFirst().aliases());
    }
  }

  @Test
  void rejectsUnknownExportFormat() {
    assertThrows(IllegalArgumentException.class, () -> GlossaryExportService.Format.parse("xml"));
  }

  @Test
  void formulaAfterUnicodeWhitespaceIsStillEscapedForSpreadsheetFormats() {
    GlossaryRepository.TermRecord source = term("\u2003=1+1", "text", List.of(), null);
    byte[] csv = service.encode(List.of(source), GlossaryExportService.Format.CSV);

    GlossaryImportParser.ParsedImport parsed = parser.parse(csv, GlossaryImportParser.Format.CSV);

    assertEquals("'\u2003=1+1", parsed.terms().getFirst().sourceTerm());
  }

  private static GlossaryRepository.TermRecord term(
      String sourceTerm, String targetTerm, List<String> aliases, String contextNote) {
    return new GlossaryRepository.TermRecord(
        UUID.randomUUID(),
        0,
        sourceTerm,
        targetTerm,
        10,
        INSENSITIVE,
        WHOLE_PHRASE,
        false,
        contextNote,
        aliases);
  }
}
