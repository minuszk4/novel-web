package com.novelweb.translation.glossary;

import static com.novelweb.translation.glossary.GlossaryMatcher.CasePolicy.INSENSITIVE;
import static com.novelweb.translation.glossary.GlossaryMatcher.MatchMode.WHOLE_PHRASE;
import static com.novelweb.translation.glossary.GlossaryMatcher.Scope.CHAPTER;
import static com.novelweb.translation.glossary.GlossaryMatcher.Scope.USER_GLOBAL;
import static com.novelweb.translation.glossary.GlossaryMatcher.Scope.WORK;
import static com.novelweb.translation.glossary.GlossaryRevisionPreviewService.ChangeKind.ADDED;
import static com.novelweb.translation.glossary.GlossaryRevisionPreviewService.ChangeKind.REMOVED;
import static com.novelweb.translation.glossary.GlossaryRevisionPreviewService.ChangeKind.UPDATED;
import static com.novelweb.translation.glossary.GlossaryRevisionPreviewService.Severity.ERROR;
import static com.novelweb.translation.glossary.GlossaryRevisionPreviewService.Severity.WARNING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class GlossaryRevisionPreviewServiceTests {

  private final GlossaryRevisionPreviewService service =
      new GlossaryRevisionPreviewService(new GlossaryMatcher());

  @Test
  void previewDiffsAddedUpdatedAndRemovedTermsAndKeepsRevisionReferences() {
    UUID glossaryId = id("glossary");
    UUID baseId = id("revision-base");
    UUID proposedId = id("revision-proposed");
    UUID updatedId = id("updated-term");
    UUID removedId = id("removed-term");
    UUID unchangedId = id("unchanged-term");
    UUID addedId = id("added-term");
    GlossaryRevisionPreviewService.GlossaryRevision base =
        revision(
            glossaryId,
            baseId,
            null,
            term(updatedId, baseId, "red dragon", "long đỏ"),
            term(removedId, baseId, "silver sword", "kiếm bạc"),
            term(unchangedId, baseId, "quiet lake", "hồ yên"));
    GlossaryRevisionPreviewService.GlossaryRevision proposed =
        revision(
            glossaryId,
            proposedId,
            baseId,
            term(updatedId, proposedId, "red dragon", "rồng đỏ"),
            term(unchangedId, proposedId, "quiet lake", "hồ yên"),
            term(addedId, proposedId, "immortal realm", "tiên giới"));

    GlossaryRevisionPreviewService.Preview preview =
        service.preview(base, proposed, "A red dragon enters the immortal realm.", 3);

    Map<UUID, GlossaryRevisionPreviewService.ChangeKind> changeKinds =
        preview.changes().stream()
            .collect(
                Collectors.toMap(
                    GlossaryRevisionPreviewService.Change::termId,
                    GlossaryRevisionPreviewService.Change::kind));
    assertEquals(baseId, preview.baseRevisionId());
    assertEquals(proposedId, preview.proposedRevisionId());
    assertEquals(Map.of(updatedId, UPDATED, removedId, REMOVED, addedId, ADDED), changeKinds);
    assertTrue(preview.canPublish());
    assertEquals(
        List.of("red dragon", "immortal realm"),
        preview.sourceMatches().matches().stream()
            .map(GlossaryMatcher.Match::matchedText)
            .toList());
    assertTrue(
        preview.sourceMatches().matches().stream()
            .allMatch(match -> match.revisionId().equals(proposedId)));
  }

  @Test
  void rejectsWrongParentOrDifferentGlossaryInsteadOfPreviewingAStaleBase() {
    UUID glossaryId = id("glossary");
    UUID baseId = id("revision-base");
    UUID proposedId = id("revision-proposed");
    GlossaryRevisionPreviewService.GlossaryRevision base = revision(glossaryId, baseId, null);
    GlossaryRevisionPreviewService.GlossaryRevision wrongParent =
        revision(glossaryId, proposedId, id("unrelated-parent"));
    GlossaryRevisionPreviewService.GlossaryRevision otherGlossary =
        revision(id("other-glossary"), proposedId, baseId);

    assertThrows(
        IllegalArgumentException.class, () -> service.preview(base, wrongParent, "sample", 3));
    assertThrows(
        IllegalArgumentException.class, () -> service.preview(base, otherGlossary, "sample", 3));
  }

  @Test
  void previewsAllTermsAsAddedForAValidFirstRevision() {
    UUID glossaryId = id("new-glossary");
    UUID revisionId = id("first-revision");
    UUID termId = id("first-term");
    GlossaryRevisionPreviewService.GlossaryRevision first =
        revision(glossaryId, revisionId, null, term(termId, revisionId, "star gate", "tinh môn"));

    GlossaryRevisionPreviewService.Preview preview = service.preview(null, first, "star gate", 3);

    assertEquals(null, preview.baseRevisionId());
    assertEquals(revisionId, preview.proposedRevisionId());
    assertEquals(ADDED, preview.changes().getFirst().kind());
  }

  @Test
  void lintWarnsAboutShortTermsDuplicateAliasesAndOddTargetWhitespace() {
    UUID revisionId = id("lint-revision");
    GlossaryMatcher.Term term =
        new GlossaryMatcher.Term(
            id("short-term"),
            revisionId,
            "en",
            "qigong",
            "  qigong  ",
            List.of("qigong", "q"),
            WORK,
            1,
            INSENSITIVE,
            WHOLE_PHRASE,
            false);
    GlossaryRevisionPreviewService.GlossaryRevision revision =
        revision(id("lint-glossary"), revisionId, null, term);

    GlossaryRevisionPreviewService.Preview preview = service.preview(null, revision, "qigong", 3);

    Map<String, GlossaryRevisionPreviewService.Severity> issueSeverities =
        preview.lint().issues().stream()
            .collect(
                Collectors.toMap(
                    GlossaryRevisionPreviewService.LintIssue::code,
                    GlossaryRevisionPreviewService.LintIssue::severity,
                    (first, ignored) -> first));
    assertEquals(WARNING, issueSeverities.get("SOURCE_TERM_SHORT"));
    assertEquals(WARNING, issueSeverities.get("DUPLICATE_ALIAS_PATTERN"));
    assertEquals(WARNING, issueSeverities.get("TARGET_WHITESPACE_UNUSUAL"));
    assertEquals(ERROR, issueSeverities.get("SOURCE_EQUALS_TARGET"));
    assertEquals(
        "q",
        preview.lint().issues().stream()
            .filter(issue -> issue.code().equals("SOURCE_TERM_SHORT"))
            .findFirst()
            .orElseThrow()
            .affectedValue());
    assertEquals(
        "  qigong  ",
        preview.lint().issues().stream()
            .filter(issue -> issue.code().equals("TARGET_WHITESPACE_UNUSUAL"))
            .findFirst()
            .orElseThrow()
            .affectedValue());
    assertFalse(preview.canPublish());
  }

  @Test
  void diffIncludesTermOrderAndContextNoteChanges() {
    UUID glossaryId = id("editorial-diff-glossary");
    UUID baseId = id("editorial-diff-base");
    UUID proposedId = id("editorial-diff-proposed");
    UUID termId = id("editorial-diff-term");
    GlossaryMatcher.Term before =
        new GlossaryMatcher.Term(
            termId,
            baseId,
            "zh-Hans",
            "金丹",
            "Kim Đan",
            List.of("Golden Core"),
            WORK,
            0,
            INSENSITIVE,
            WHOLE_PHRASE,
            false,
            1,
            "Tên cảnh giới trong tu luyện");
    GlossaryMatcher.Term after =
        new GlossaryMatcher.Term(
            termId,
            proposedId,
            "zh-Hans",
            "金丹",
            "Kim Đan",
            List.of("Golden Core"),
            WORK,
            0,
            INSENSITIVE,
            WHOLE_PHRASE,
            false,
            2,
            "Tên một cảnh giới tiên đạo");

    GlossaryRevisionPreviewService.Preview preview =
        service.preview(
            revision(glossaryId, baseId, null, before),
            revision(glossaryId, proposedId, baseId, after),
            "",
            3);

    assertEquals(1, preview.changes().size());
    assertEquals(UPDATED, preview.changes().getFirst().kind());
    assertEquals(1, preview.changes().getFirst().before().ordinal());
    assertEquals(
        "Tên cảnh giới trong tu luyện", preview.changes().getFirst().before().contextNote());
    assertEquals(2, preview.changes().getFirst().after().ordinal());
    assertEquals("Tên một cảnh giới tiên đạo", preview.changes().getFirst().after().contextNote());
  }

  @Test
  void lintAndPreviewExposeEqualPrecedenceConflictsButAllowExplicitScopeOverride() {
    UUID revisionId = id("conflict-revision");
    GlossaryMatcher.Term global =
        term(id("global-term"), revisionId, "spirit root", "linh căn", USER_GLOBAL, 10);
    GlossaryMatcher.Term conflicting =
        term(id("work-term-one"), revisionId, "spirit root", "linh thể", WORK, 10);
    GlossaryMatcher.Term secondConflict =
        term(id("work-term-two"), revisionId, "spirit root", "căn linh", WORK, 10);
    GlossaryRevisionPreviewService.GlossaryRevision proposed =
        revision(id("conflict-glossary"), revisionId, null, global, conflicting, secondConflict);

    GlossaryRevisionPreviewService.Preview preview =
        service.preview(null, proposed, "A spirit root appears.", 3);

    assertFalse(preview.lint().canPublish());
    assertTrue(
        preview.lint().issues().stream()
            .anyMatch(issue -> issue.code().equals("EQUAL_PRECEDENCE_DIFFERENT_OUTCOMES")));
    assertTrue(preview.sourceMatches().conflicts().size() > 0);

    GlossaryRevisionPreviewService.GlossaryRevision scoped =
        revision(
            id("override-glossary"),
            revisionId,
            null,
            term(id("broad"), revisionId, "spirit root", "linh căn", USER_GLOBAL, 100),
            term(id("specific"), revisionId, "spirit root", "linh thể", CHAPTER, 0));
    GlossaryRevisionPreviewService.Preview scopedPreview =
        service.preview(null, scoped, "A spirit root appears.", 3);

    assertTrue(scopedPreview.canPublish());
    assertEquals("linh thể", scopedPreview.sourceMatches().matches().getFirst().targetTerm());
  }

  @Test
  void snapshotsRejectTermsBoundToAnotherRevision() {
    UUID revisionId = id("snapshot-revision");
    GlossaryMatcher.Term foreignTerm =
        term(id("foreign-term"), id("foreign-revision"), "blue star", "sao xanh");

    assertThrows(
        IllegalArgumentException.class,
        () -> revision(id("snapshot-glossary"), revisionId, null, foreignTerm));
  }

  private static GlossaryRevisionPreviewService.GlossaryRevision revision(
      UUID glossaryId, UUID revisionId, UUID parentRevisionId, GlossaryMatcher.Term... terms) {
    return new GlossaryRevisionPreviewService.GlossaryRevision(
        glossaryId, revisionId, parentRevisionId, List.of(terms));
  }

  private static GlossaryMatcher.Term term(
      UUID termId, UUID revisionId, String source, String target) {
    return term(termId, revisionId, source, target, WORK, 10);
  }

  private static GlossaryMatcher.Term term(
      UUID termId,
      UUID revisionId,
      String source,
      String target,
      GlossaryMatcher.Scope scope,
      int priority) {
    return new GlossaryMatcher.Term(
        termId,
        revisionId,
        "en",
        source,
        target,
        List.of(),
        scope,
        priority,
        INSENSITIVE,
        WHOLE_PHRASE,
        false);
  }

  private static UUID id(String value) {
    return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
  }
}
