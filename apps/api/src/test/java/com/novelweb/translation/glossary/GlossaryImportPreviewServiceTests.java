package com.novelweb.translation.glossary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.novelweb.translation.glossary.GlossaryImportPreviewService.MatchReason;
import com.novelweb.translation.glossary.GlossaryImportPreviewService.SuggestedAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GlossaryImportPreviewServiceTests {

  private static final UUID ACTOR_ID = id("actor");
  private static final UUID GLOSSARY_ID = id("glossary");
  private static final UUID REVISION_ID = id("revision");
  private static final UUID EXISTING_TERM_ID = id("existing-term");

  @Mock private GlossaryImportPreviewRepository repository;

  @Test
  void loadsOnlyMatchingGlossaryVersionAndRejectsStalePreviews() {
    GlossaryImportPreviewRepository.WritableSnapshot snapshot = snapshot(4, List.of());
    when(repository.loadWritableSnapshot(ACTOR_ID, GLOSSARY_ID)).thenReturn(snapshot);
    GlossaryImportPreviewService service = new GlossaryImportPreviewService(repository);

    assertEquals(snapshot, service.loadCurrent(ACTOR_ID, GLOSSARY_ID, 4));
    GlossaryImportPreviewService.VersionConflictException stale =
        assertThrows(
            GlossaryImportPreviewService.VersionConflictException.class,
            () -> service.loadCurrent(ACTOR_ID, GLOSSARY_ID, 3));

    assertEquals(4, stale.currentVersion());
    verify(repository, org.mockito.Mockito.times(2)).loadWritableSnapshot(ACTOR_ID, GLOSSARY_ID);
  }

  @Test
  void classifiesNewTermsAndSurfacesScopeAndVersionForEmptyGlossaries() {
    GlossaryImportPreviewService service = new GlossaryImportPreviewService(repository);

    GlossaryImportPreviewService.Preview preview =
        service.preview(
            snapshot(1, List.of()),
            json(
                "{\"version\":1,\"terms\":[{\"sourceTerm\":\"new term\",\"targetTerm\":\"new translation\"}]}"),
            GlossaryImportParser.Format.JSON);

    assertEquals(GlossaryMatcher.Scope.USER_GLOBAL, preview.scope());
    assertEquals(1, preview.glossaryVersion());
    assertEquals(null, preview.activeRevisionId());
    assertEquals(SuggestedAction.ADD, preview.importedTerms().getFirst().suggestedAction());
    assertEquals(1, preview.counts().add());
    assertEquals("UNAVAILABLE", preview.translationImpact().status());
    assertEquals(null, preview.translationImpact().affectedTranslationUnitCount());
  }

  @Test
  void classifiesCanonicalSameAndChangedRowsWithoutApplyingAnyChanges() {
    GlossaryImportPreviewService service = new GlossaryImportPreviewService(repository);
    UUID secondTermId = id("second-term");
    GlossaryImportPreviewRepository.WritableSnapshot snapshot =
        snapshot(
            5,
            List.of(
                term(EXISTING_TERM_ID, "red dragon", "rồng đỏ", List.of("long đỏ")),
                term(secondTermId, "jade palace", "ngọc điện", List.of("green court"))));

    GlossaryImportPreviewService.Preview preview =
        service.preview(
            snapshot,
            json(
                "{\"version\":1,\"terms\":["
                    + "{\"sourceTerm\":\"red dragon\",\"targetTerm\":\"rồng đỏ\",\"aliases\":[\"long đỏ\"]},"
                    + "{\"sourceTerm\":\"jade palace\",\"targetTerm\":\"cung điện ngọc\",\"aliases\":[\"green court\"]}]}"),
            GlossaryImportParser.Format.JSON);

    assertEquals(SuggestedAction.UNCHANGED, preview.importedTerms().getFirst().suggestedAction());
    GlossaryImportPreviewService.ImportCandidate changed = preview.importedTerms().get(1);
    assertEquals(SuggestedAction.UPDATE, changed.suggestedAction());
    assertEquals(secondTermId, changed.existingMatches().getFirst().termId());
    assertEquals(MatchReason.CANONICAL_TERM_MATCH, changed.existingMatches().getFirst().reason());
    assertEquals(1, preview.counts().update());
    assertEquals("rồng đỏ", preview.currentTerms().getFirst().targetTerm());
  }

  @Test
  void classifiesPatternCollisionsWithOtherTermsAsExplicitResolution() {
    GlossaryImportPreviewService service = new GlossaryImportPreviewService(repository);
    UUID secondTermId = id("second-term");
    GlossaryImportPreviewRepository.WritableSnapshot snapshot =
        snapshot(
            6,
            List.of(
                term(EXISTING_TERM_ID, "red dragon", "rồng đỏ", List.of("long đỏ")),
                term(secondTermId, "jade palace", "ngọc điện", List.of("green court"))));

    GlossaryImportPreviewService.Preview preview =
        service.preview(
            snapshot,
            json(
                "{\"version\":1,\"terms\":[{\"sourceTerm\":\"new name\",\"targetTerm\":\"tên mới\",\"aliases\":[\"green court\"]}]}"),
            GlossaryImportParser.Format.JSON);

    GlossaryImportPreviewService.ImportCandidate candidate = preview.importedTerms().getFirst();
    assertEquals(SuggestedAction.RESOLVE_CONFLICT, candidate.suggestedAction());
    assertEquals(secondTermId, candidate.existingMatches().getFirst().termId());
    assertEquals(MatchReason.PATTERN_COLLISION, candidate.existingMatches().getFirst().reason());
    assertEquals(1, preview.counts().requiresResolution());
  }

  @Test
  void truncatesLargeCollisionListsAndPreservesCurrentTermOrder() {
    GlossaryImportPreviewService service = new GlossaryImportPreviewService(repository);
    List<GlossaryImportPreviewRepository.CurrentTerm> currentTerms =
        java.util.stream.IntStream.range(0, 101)
            .mapToObj(
                index ->
                    term(
                        id("collision-" + index),
                        "term " + index,
                        "translation " + index,
                        List.of("shared alias")))
            .toList();

    GlossaryImportPreviewService.Preview preview =
        service.preview(
            snapshot(7, currentTerms),
            json(
                "{\"version\":1,\"terms\":[{\"sourceTerm\":\"shared alias\",\"targetTerm\":\"translation\"}]}"),
            GlossaryImportParser.Format.JSON);

    GlossaryImportPreviewService.ImportCandidate candidate = preview.importedTerms().getFirst();
    assertEquals(SuggestedAction.RESOLVE_CONFLICT, candidate.suggestedAction());
    assertEquals(100, candidate.existingMatches().size());
    assertTrue(candidate.matchesTruncated());
    assertEquals(currentTerms.getFirst().id(), candidate.existingMatches().getFirst().termId());
  }

  @Test
  void honorsCasePolicyWhenFindingPotentialCollisions() {
    GlossaryImportPreviewService service = new GlossaryImportPreviewService(repository);
    GlossaryImportPreviewRepository.CurrentTerm sensitive =
        new GlossaryImportPreviewRepository.CurrentTerm(
            EXISTING_TERM_ID,
            1,
            "Red Dragon",
            "rồng đỏ",
            0,
            GlossaryMatcher.CasePolicy.SENSITIVE,
            GlossaryMatcher.MatchMode.WHOLE_PHRASE,
            false,
            null,
            List.of());

    GlossaryImportPreviewService.Preview exactCase =
        service.preview(
            snapshot(8, List.of(sensitive)),
            json(
                "{\"version\":1,\"terms\":[{\"sourceTerm\":\"red dragon\",\"targetTerm\":\"rồng đỏ\",\"casePolicy\":\"SENSITIVE\"}]}"),
            GlossaryImportParser.Format.JSON);
    GlossaryImportPreviewService.Preview insensitive =
        service.preview(
            snapshot(8, List.of(sensitive)),
            json(
                "{\"version\":1,\"terms\":[{\"sourceTerm\":\"red dragon\",\"targetTerm\":\"rồng đỏ\"}]}"),
            GlossaryImportParser.Format.JSON);

    assertEquals(SuggestedAction.ADD, exactCase.importedTerms().getFirst().suggestedAction());
    assertEquals(SuggestedAction.UPDATE, insensitive.importedTerms().getFirst().suggestedAction());
  }

  @Test
  void returnsNoPreviewForMalformedFiles() {
    GlossaryImportPreviewService service = new GlossaryImportPreviewService(repository);

    GlossaryImportParser.ParseException exception =
        assertThrows(
            GlossaryImportParser.ParseException.class,
            () ->
                service.preview(
                    snapshot(2, List.of()),
                    json("{\"version\":1,\"terms\":[{}]}"),
                    GlossaryImportParser.Format.JSON));

    assertTrue(
        exception.issues().stream()
            .anyMatch(issue -> issue.code().equals("IMPORT_FIELD_REQUIRED")));
  }

  private static GlossaryImportPreviewRepository.WritableSnapshot snapshot(
      long version, List<GlossaryImportPreviewRepository.CurrentTerm> terms) {
    return new GlossaryImportPreviewRepository.WritableSnapshot(
        GLOSSARY_ID,
        "Cultivation terms",
        GlossaryMatcher.Scope.USER_GLOBAL,
        "en",
        "vi",
        version,
        version == 1 ? null : REVISION_ID,
        version == 1 ? null : 2,
        version == 1 ? null : REVISION_ID,
        version == 1 ? null : 2,
        terms);
  }

  private static GlossaryImportPreviewRepository.CurrentTerm term(
      UUID id, String source, String target, List<String> aliases) {
    return new GlossaryImportPreviewRepository.CurrentTerm(
        id,
        1,
        source,
        target,
        0,
        GlossaryMatcher.CasePolicy.INSENSITIVE,
        GlossaryMatcher.MatchMode.WHOLE_PHRASE,
        false,
        null,
        aliases);
  }

  private static byte[] json(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  private static UUID id(String name) {
    return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
  }
}
