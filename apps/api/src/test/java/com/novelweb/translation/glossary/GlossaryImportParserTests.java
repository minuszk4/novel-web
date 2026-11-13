package com.novelweb.translation.glossary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class GlossaryImportParserTests {

  private final GlossaryImportParser parser = new GlossaryImportParser();

  @Test
  void parsesVersionedJsonAndAppliesDocumentedDefaults() {
    GlossaryImportParser.ParsedImport parsed =
        parse(
            """
                {"version":1,"terms":[{"sourceTerm":"  qì mén  ","targetTerm":"Kỳ Môn","aliases":["奇門"],"contextNote":"  sect  "}]}
                """,
            GlossaryImportParser.Format.JSON);

    assertEquals(1, parsed.terms().size());
    GlossaryImportParser.ImportedTerm term = parsed.terms().getFirst();
    assertEquals("qì mén", term.sourceTerm());
    assertEquals("Kỳ Môn", term.targetTerm());
    assertEquals(List.of("奇門"), term.aliases());
    assertEquals(GlossaryMatcher.CasePolicy.INSENSITIVE, term.casePolicy());
    assertEquals(GlossaryMatcher.MatchMode.WHOLE_PHRASE, term.matchMode());
    assertEquals(0, term.priority());
    assertFalse(term.doNotTranslate());
    assertEquals("sect", term.contextNote());
  }

  @Test
  void parsesCsvWithQuotedDelimitersMultilineTextAndJsonAliases() {
    String csv =
        "sourceTerm,targetTerm,aliases,priority,casePolicy,matchMode,doNotTranslate,contextNote\r\n"
            + "\"Red, Dragon\",Rồng,\"[\"\"Long Đỏ\"\"]\",7,SENSITIVE,WHOLE_PHRASE,false,\"first line\r\nsecond line\"\r\n";

    GlossaryImportParser.ImportedTerm term =
        parse(csv, GlossaryImportParser.Format.CSV).terms().getFirst();

    assertEquals("Red, Dragon", term.sourceTerm());
    assertEquals(List.of("Long Đỏ"), term.aliases());
    assertEquals(7, term.priority());
    assertEquals(GlossaryMatcher.CasePolicy.SENSITIVE, term.casePolicy());
    assertEquals("first line\nsecond line", term.contextNote());
    assertEquals(2, term.rowNumber());
  }

  @Test
  void parsesTsvAndAcceptsUtf8BomAndReorderedHeaders() {
    String tsv =
        "\uFEFFtargetTerm\tsourceTerm\tdoNotTranslate\taliases\n" + "\tQingxin\ttrue\t[]\n";

    GlossaryImportParser.ImportedTerm term =
        parse(tsv, GlossaryImportParser.Format.TSV).terms().getFirst();

    assertEquals("Qingxin", term.sourceTerm());
    assertEquals(null, term.targetTerm());
    assertTrue(term.doNotTranslate());
  }

  @Test
  void preservesOptionalTargetTextForDoNotTranslateRows() {
    GlossaryImportParser.ImportedTerm term =
        parse(
                "{\"version\":1,\"terms\":[{\"sourceTerm\":\"brand\",\"targetTerm\":\"Brand\",\"doNotTranslate\":true}]}",
                GlossaryImportParser.Format.JSON)
            .terms()
            .getFirst();

    assertEquals("Brand", term.targetTerm());
  }

  @Test
  void rejectsInvalidUtf8AndContentAboveOneMiB() {
    GlossaryImportParser.ParseException invalidUtf8 =
        assertThrows(
            GlossaryImportParser.ParseException.class,
            () -> parser.parse(new byte[] {(byte) 0xC3, 0x28}, GlossaryImportParser.Format.CSV));
    assertIssue(invalidUtf8, "IMPORT_ENCODING_INVALID");

    GlossaryImportParser.ParseException tooLarge =
        assertThrows(
            GlossaryImportParser.ParseException.class,
            () ->
                parser.parse(
                    new byte[GlossaryImportParser.MAX_IMPORT_BYTES + 1],
                    GlossaryImportParser.Format.JSON));
    assertIssue(tooLarge, "IMPORT_FILE_TOO_LARGE");
  }

  @Test
  void rejectsMalformedCsvQuotesAndTrailingContentAfterQuotedCell() {
    assertIssue(
        assertThrows(
            GlossaryImportParser.ParseException.class,
            () -> parse("sourceTerm,targetTerm\n\"open,term\n", GlossaryImportParser.Format.CSV)),
        "IMPORT_DELIMITED_FILE_INVALID");
    assertIssue(
        assertThrows(
            GlossaryImportParser.ParseException.class,
            () ->
                parse(
                    "sourceTerm,targetTerm\n\"term\"x,target\n", GlossaryImportParser.Format.CSV)),
        "IMPORT_DELIMITED_FILE_INVALID");
  }

  @Test
  void rejectsMissingDuplicateAndUnknownHeadersAndWrongCellCounts() {
    assertIssue(
        assertThrows(
            GlossaryImportParser.ParseException.class,
            () -> parse("sourceTerm\nterm\n", GlossaryImportParser.Format.CSV)),
        "IMPORT_REQUIRED_COLUMN_MISSING");
    assertIssue(
        assertThrows(
            GlossaryImportParser.ParseException.class,
            () ->
                parse(
                    "sourceTerm,targetTerm,sourceTerm\na,b,a\n", GlossaryImportParser.Format.CSV)),
        "IMPORT_DUPLICATE_COLUMN");
    assertIssue(
        assertThrows(
            GlossaryImportParser.ParseException.class,
            () -> parse("sourceTerm,targetTerm,extra\na,b,c\n", GlossaryImportParser.Format.CSV)),
        "IMPORT_UNKNOWN_COLUMN");
    assertIssue(
        assertThrows(
            GlossaryImportParser.ParseException.class,
            () -> parse("sourceTerm,targetTerm\na,b,c\n", GlossaryImportParser.Format.CSV)),
        "IMPORT_COLUMN_COUNT_MISMATCH");
  }

  @Test
  void rejectsMalformedAliasJsonInDelimitedCell() {
    GlossaryImportParser.ParseException exception =
        assertThrows(
            GlossaryImportParser.ParseException.class,
            () ->
                parse(
                    "sourceTerm,targetTerm,aliases\nterm,target,not-json\n",
                    GlossaryImportParser.Format.CSV));

    assertIssue(exception, "IMPORT_ALIASES_INVALID_JSON");
  }

  @Test
  void rejectsMalformedJsonDuplicateKeysTrailingTokensAndUnknownFields() {
    assertIssue(
        assertThrows(
            GlossaryImportParser.ParseException.class,
            () ->
                parse(
                    "{\"version\":1,\"version\":1,\"terms\":[]}",
                    GlossaryImportParser.Format.JSON)),
        "IMPORT_JSON_INVALID");
    assertIssue(
        assertThrows(
            GlossaryImportParser.ParseException.class,
            () -> parse("{\"version\":1,\"terms\":[]} {}", GlossaryImportParser.Format.JSON)),
        "IMPORT_JSON_INVALID");
    assertIssue(
        assertThrows(
            GlossaryImportParser.ParseException.class,
            () ->
                parse(
                    "{\"version\":1,\"terms\":[{\"sourceTerm\":\"a\",\"targetTerm\":\"b\",\"typoField\":true}]}",
                    GlossaryImportParser.Format.JSON)),
        "IMPORT_UNKNOWN_FIELD");
  }

  @Test
  void rejectsUnsupportedJsonVersionAndNonObjectTerms() {
    assertIssue(
        assertThrows(
            GlossaryImportParser.ParseException.class,
            () -> parse("{\"version\":2,\"terms\":[{}]}", GlossaryImportParser.Format.JSON)),
        "IMPORT_VERSION_UNSUPPORTED");
    assertIssue(
        assertThrows(
            GlossaryImportParser.ParseException.class,
            () -> parse("{\"version\":1,\"terms\":[null]}", GlossaryImportParser.Format.JSON)),
        "IMPORT_TERM_ROW_INVALID");
  }

  @Test
  void rejectsDuplicateCanonicalAndAliasPatternsWithoutReturningPartialRows() {
    GlossaryImportParser.ParseException exception =
        assertThrows(
            GlossaryImportParser.ParseException.class,
            () ->
                parse(
                    "{\"version\":1,\"terms\":["
                        + "{\"sourceTerm\":\"Red Dragon\",\"targetTerm\":\"Rồng đỏ\",\"aliases\":[\"Long đỏ\"]},"
                        + "{\"sourceTerm\":\" long  ĐỎ \",\"targetTerm\":\"Long đỏ\"}]}",
                    GlossaryImportParser.Format.JSON));

    assertIssue(exception, "IMPORT_DUPLICATE_SOURCE_PATTERN");
    assertEquals(2, exception.issues().getFirst().rowNumber());
  }

  @Test
  void rejectsDuplicatePatternsWithinOneTerm() {
    GlossaryImportParser.ParseException exception =
        assertThrows(
            GlossaryImportParser.ParseException.class,
            () ->
                parse(
                    "{\"version\":1,\"terms\":[{\"sourceTerm\":\"Qi Men\",\"targetTerm\":\"Kỳ Môn\",\"aliases\":[\" qi  men \"]}]}",
                    GlossaryImportParser.Format.JSON));

    assertIssue(exception, "IMPORT_DUPLICATE_PATTERN_IN_ROW");
  }

  @Test
  void rejectsBadTypesEnumsPriorityAndMissingTranslationTarget() {
    String json =
        "{\"version\":1,\"terms\":[{\"sourceTerm\":\"term\",\"targetTerm\":\"\","
            + "\"priority\":1000001,\"casePolicy\":\"MAYBE\",\"matchMode\":\"TOKEN\","
            + "\"doNotTranslate\":\"yes\",\"aliases\":[1]}]}";
    GlossaryImportParser.ParseException exception =
        assertThrows(
            GlossaryImportParser.ParseException.class,
            () -> parse(json, GlossaryImportParser.Format.JSON));

    assertHasIssue(exception, "IMPORT_PRIORITY_OUT_OF_RANGE");
    assertHasIssue(exception, "IMPORT_FIELD_VALUE_INVALID");
    assertHasIssue(exception, "IMPORT_FIELD_TYPE_INVALID");
    assertHasIssue(exception, "IMPORT_ALIAS_INVALID");
    assertHasIssue(exception, "IMPORT_TARGET_REQUIRED");
  }

  @Test
  void countsUnicodeCodePointsForTermLengths() {
    String tooLongTarget = "😀".repeat(4097);
    GlossaryImportParser.ParseException exception =
        assertThrows(
            GlossaryImportParser.ParseException.class,
            () ->
                parse(
                    "{\"version\":1,\"terms\":[{\"sourceTerm\":\"term\",\"targetTerm\":\""
                        + tooLongTarget
                        + "\"}]}",
                    GlossaryImportParser.Format.JSON));

    assertIssue(exception, "IMPORT_FIELD_TOO_LONG");
  }

  @Test
  void enforcesTermAndAliasCaps() {
    StringBuilder tooManyTerms = new StringBuilder("{\"version\":1,\"terms\":[");
    for (int index = 0; index <= GlossaryImportParser.MAX_TERMS; index++) {
      if (index > 0) tooManyTerms.append(',');
      tooManyTerms.append("{\"sourceTerm\":\"s").append(index).append("\",\"targetTerm\":\"t\"}");
    }
    tooManyTerms.append("]}");
    assertIssue(
        assertThrows(
            GlossaryImportParser.ParseException.class,
            () -> parse(tooManyTerms.toString(), GlossaryImportParser.Format.JSON)),
        "IMPORT_TERM_LIMIT_EXCEEDED");

    StringBuilder aliases =
        new StringBuilder(
            "{\"version\":1,\"terms\":[{\"sourceTerm\":\"s\",\"targetTerm\":\"t\",\"aliases\":[");
    for (int index = 0; index <= GlossaryImportParser.MAX_ALIASES_PER_TERM; index++) {
      if (index > 0) aliases.append(',');
      aliases.append("\"a").append(index).append("\"");
    }
    aliases.append("]}]}");
    assertIssue(
        assertThrows(
            GlossaryImportParser.ParseException.class,
            () -> parse(aliases.toString(), GlossaryImportParser.Format.JSON)),
        "IMPORT_ALIAS_LIMIT_EXCEEDED");
  }

  @Test
  void reportsMultipleInvalidRowsTogetherWithoutReturningValidRows() {
    GlossaryImportParser.ParseException exception =
        assertThrows(
            GlossaryImportParser.ParseException.class,
            () ->
                parse(
                    "sourceTerm,targetTerm\nvalid,valid target\n,missing source\nother,\n",
                    GlossaryImportParser.Format.CSV));

    assertHasIssue(exception, "IMPORT_FIELD_REQUIRED");
    assertHasIssue(exception, "IMPORT_TARGET_REQUIRED");
    assertTrue(exception.issues().stream().allMatch(issue -> issue.rowNumber() > 0));
  }

  @Test
  void rejectsEmptyFilesAndEmptyTermLists() {
    assertIssue(
        assertThrows(
            GlossaryImportParser.ParseException.class,
            () -> parser.parse(new byte[0], GlossaryImportParser.Format.JSON)),
        "IMPORT_FILE_EMPTY");
    assertIssue(
        assertThrows(
            GlossaryImportParser.ParseException.class,
            () -> parse("{\"version\":1,\"terms\":[]}", GlossaryImportParser.Format.JSON)),
        "IMPORT_TERMS_REQUIRED");
  }

  private GlossaryImportParser.ParsedImport parse(
      String value, GlossaryImportParser.Format format) {
    return parser.parse(value.getBytes(StandardCharsets.UTF_8), format);
  }

  private static void assertIssue(GlossaryImportParser.ParseException exception, String code) {
    assertTrue(
        exception.issues().stream().anyMatch(issue -> issue.code().equals(code)),
        exception.issues().toString());
  }

  private static void assertHasIssue(GlossaryImportParser.ParseException exception, String code) {
    assertIssue(exception, code);
  }
}
