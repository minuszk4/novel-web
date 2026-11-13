package com.novelweb.translation.glossary;

import static com.novelweb.translation.glossary.GlossaryMatcher.CasePolicy.INSENSITIVE;
import static com.novelweb.translation.glossary.GlossaryMatcher.CasePolicy.SENSITIVE;
import static com.novelweb.translation.glossary.GlossaryMatcher.MatchMode.WHOLE_PHRASE;
import static com.novelweb.translation.glossary.GlossaryMatcher.Scope.CHAPTER;
import static com.novelweb.translation.glossary.GlossaryMatcher.Scope.USER_GLOBAL;
import static com.novelweb.translation.glossary.GlossaryMatcher.Scope.WORK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GlossaryMatcherTests {

  private static final UUID REVISION = id("revision-1");

  private final GlossaryMatcher matcher = new GlossaryMatcher();

  @Test
  void prefersLongestPhraseAndDoesNotMatchInsideLatinWords() {
    GlossaryMatcher.Term shortTerm =
        term("dark", "u tối", List.of(), USER_GLOBAL, 1, INSENSITIVE, false);
    GlossaryMatcher.Term longTerm =
        term("dark core", "hắc hạch", List.of(), USER_GLOBAL, 1, INSENSITIVE, false);

    GlossaryMatcher.Result result =
        matcher.match("The dark core, dark-core, and darkness.", List.of(shortTerm, longTerm));

    assertEquals(
        List.of("dark core", "dark"),
        result.matches().stream().map(GlossaryMatcher.Match::matchedText).toList());
    assertEquals(
        List.of("hắc hạch", "u tối"),
        result.matches().stream().map(GlossaryMatcher.Match::targetTerm).toList());
    assertTrue(result.conflicts().isEmpty());
  }

  @Test
  void appliesScopeBeforePriorityForTheSamePhrase() {
    GlossaryMatcher.Term broad =
        term("spirit root", "linh căn", List.of(), USER_GLOBAL, 100, INSENSITIVE, false);
    GlossaryMatcher.Term chapterOverride =
        term("spirit root", "dịch riêng", List.of(), CHAPTER, 0, INSENSITIVE, false);

    GlossaryMatcher.Result result =
        matcher.match("A spirit root appears.", List.of(broad, chapterOverride));

    assertEquals(1, result.matches().size());
    assertEquals("dịch riêng", result.matches().getFirst().targetTerm());
    assertTrue(result.conflicts().isEmpty());
  }

  @Test
  void usesExplicitPriorityWithinOneScope() {
    GlossaryMatcher.Term low =
        term("spirit root", "linh căn", List.of(), WORK, 10, INSENSITIVE, false);
    GlossaryMatcher.Term high =
        term("spirit root", "linh thể", List.of(), WORK, 20, INSENSITIVE, false);

    GlossaryMatcher.Result result = matcher.match("spirit root", List.of(low, high));

    assertEquals("linh thể", result.matches().getFirst().targetTerm());
    assertTrue(result.conflicts().isEmpty());
  }

  @Test
  void reportsEqualPrecedenceDifferentTargetsInsteadOfChoosingOne() {
    GlossaryMatcher.Term first =
        term("azure flame", "thanh diễm", List.of(), WORK, 30, INSENSITIVE, false);
    GlossaryMatcher.Term second =
        term("azure flame", "lam hỏa", List.of(), WORK, 30, INSENSITIVE, false);

    GlossaryMatcher.Result result = matcher.match("An azure flame burns.", List.of(second, first));

    assertTrue(result.matches().isEmpty());
    assertEquals(1, result.conflicts().size());
    assertEquals("EQUAL_PRECEDENCE_DIFFERENT_OUTCOMES", result.conflicts().getFirst().code());
    assertEquals(
        List.of(first.id(), second.id()), result.conflicts().getFirst().competingTermIds());
    assertEquals("azure flame", result.conflicts().getFirst().matchedText());
  }

  @Test
  void honorsCasePolicyAndTreatsPunctuationAsAWholePhraseBoundary() {
    GlossaryMatcher.Term exactCase = term("ORION", "Orion", List.of(), WORK, 1, SENSITIVE, true);
    GlossaryMatcher.Term insensitive =
        term("dragon core", "long hạch", List.of(), WORK, 1, INSENSITIVE, false);

    GlossaryMatcher.Result result =
        matcher.match("orion ORION; “Dragon Core,”", List.of(exactCase, insensitive));

    assertEquals(
        List.of("ORION", "Dragon Core"),
        result.matches().stream().map(GlossaryMatcher.Match::matchedText).toList());
    assertEquals("ORION", result.matches().getFirst().targetTerm());
    assertTrue(result.matches().getFirst().doNotTranslate());
  }

  @Test
  void recordsWhenAnAliasWasMatched() {
    GlossaryMatcher.Term term =
        term("dragon", "long", List.of("ancient wyrm"), WORK, 1, INSENSITIVE, false);

    GlossaryMatcher.Match match =
        matcher.match("An Ancient Wyrm wakes.", List.of(term)).matches().getFirst();

    assertEquals("Ancient Wyrm", match.matchedText());
    assertEquals("long", match.targetTerm());
    assertTrue(match.alias());
  }

  @Test
  void normalizesNfcAndWhitespaceButReturnsOriginalSourceOffsetsAndText() {
    String source = "x cafe\u0301\t  blanc!";
    GlossaryMatcher.Term term =
        term("café blanc", "cà phê trắng", List.of(), WORK, 1, INSENSITIVE, false);

    GlossaryMatcher.Match match = matcher.match(source, List.of(term)).matches().getFirst();

    assertEquals(source.indexOf("cafe"), match.startUtf16());
    assertEquals(source.indexOf('!'), match.endUtf16());
    assertEquals("cafe\u0301\t  blanc", match.matchedText());
  }

  @Test
  void matchesChineseTermsInsideIdeographicTextAndUsesLongestKnownPhrase() {
    GlossaryMatcher.Term shortTerm =
        term("武魂", "võ hồn", List.of(), WORK, 1, INSENSITIVE, false, "zh-Hans");
    GlossaryMatcher.Term longTerm =
        term("武魂觉醒", "võ hồn thức tỉnh", List.of(), WORK, 1, INSENSITIVE, false, "zh-Hans");

    GlossaryMatcher.Result result = matcher.match("他的武魂觉醒了", List.of(shortTerm, longTerm));

    assertEquals(1, result.matches().size());
    assertEquals("武魂觉醒", result.matches().getFirst().matchedText());
    assertEquals("võ hồn thức tỉnh", result.matches().getFirst().targetTerm());
  }

  @Test
  void respectsSupplementaryUnicodeLettersAtWordBoundariesAndOffsets() {
    GlossaryMatcher.Term term = term("alpha", "anpha", List.of(), WORK, 1, INSENSITIVE, false);

    assertTrue(matcher.match("𐐀alpha", List.of(term)).matches().isEmpty());
    GlossaryMatcher.Match match = matcher.match("𐐀 alpha", List.of(term)).matches().getFirst();

    assertEquals(3, match.startUtf16());
    assertEquals("alpha", match.matchedText());
  }

  @Test
  void treatsAliasesAsTerminalPatternsAndSurfacesCircularDefinitionsAsConflict() {
    GlossaryMatcher.Term alpha =
        term("alpha", "beta", List.of("beta"), WORK, 1, INSENSITIVE, false);
    GlossaryMatcher.Term beta =
        term("beta", "alpha", List.of("alpha"), WORK, 1, INSENSITIVE, false);

    GlossaryMatcher.Result result = matcher.match("alpha", List.of(alpha, beta));

    assertTrue(result.matches().isEmpty());
    assertEquals(1, result.conflicts().size());
    assertEquals(
        List.of(alpha.id(), beta.id()).stream().sorted().toList(),
        result.conflicts().getFirst().competingTermIds());
  }

  @Test
  void choosesTheSameResultRegardlessOfGlossaryInputOrder() {
    GlossaryMatcher.Term first =
        term("silver moon", "bạc nguyệt", List.of("moonbeam"), WORK, 4, INSENSITIVE, false);
    GlossaryMatcher.Term second =
        term("silver moon", "bạc nguyệt", List.of("silvering"), WORK, 4, INSENSITIVE, false);
    List<GlossaryMatcher.Term> terms = new ArrayList<>(List.of(first, second));
    GlossaryMatcher.Result expected = matcher.match("silver moon over the lake", terms);
    Random random = new Random(41);

    for (int attempt = 0; attempt < 100; attempt++) {
      java.util.Collections.shuffle(terms, random);
      assertEquals(expected, matcher.match("silver moon over the lake", terms));
    }

    assertEquals(
        List.of(first.id(), second.id()).stream().min(UUID::compareTo).orElseThrow(),
        expected.matches().getFirst().termId());
    assertFalse(expected.matches().getFirst().alias());
  }

  @Test
  void rejectsDuplicateTermIdsWithinOneRevision() {
    GlossaryMatcher.Term term =
        term("silver moon", "bạc nguyệt", List.of(), WORK, 4, INSENSITIVE, false);

    assertThrows(
        IllegalArgumentException.class, () -> matcher.match("silver moon", List.of(term, term)));
  }

  private static GlossaryMatcher.Term term(
      String source,
      String target,
      List<String> aliases,
      GlossaryMatcher.Scope scope,
      int priority,
      GlossaryMatcher.CasePolicy casePolicy,
      boolean keep) {
    return term(source, target, aliases, scope, priority, casePolicy, keep, "en");
  }

  private static GlossaryMatcher.Term term(
      String source,
      String target,
      List<String> aliases,
      GlossaryMatcher.Scope scope,
      int priority,
      GlossaryMatcher.CasePolicy casePolicy,
      boolean keep,
      String language) {
    return new GlossaryMatcher.Term(
        id(source + "|" + target + "|" + scope + "|" + priority + "|" + aliases),
        REVISION,
        language,
        source,
        keep ? null : target,
        aliases,
        scope,
        priority,
        casePolicy,
        WHOLE_PHRASE,
        keep);
  }

  private static UUID id(String value) {
    return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
  }
}
