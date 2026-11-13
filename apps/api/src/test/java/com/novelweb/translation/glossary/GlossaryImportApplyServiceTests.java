package com.novelweb.translation.glossary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.novelweb.translation.glossary.GlossaryImportApplyService.ResolutionAction;
import com.novelweb.translation.glossary.GlossaryImportApplyService.ResolvedTerm;
import com.novelweb.translation.glossary.GlossaryImportApplyService.RowResolution;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GlossaryImportApplyServiceTests {

  private static final UUID ACTOR_ID = id("actor");
  private static final UUID GLOSSARY_ID = id("glossary");
  private static final UUID REVISION_ID = id("active-revision");
  private static final UUID EXISTING_TERM_ID = id("existing-term");

  @Mock private GlossaryImportPreviewRepository previewRepository;
  @Mock private GlossaryRevisionWriteRepository revisionRepository;

  @Test
  void createsOneCompleteDraftAndPreservesTheSelectedMergeId() {
    GlossaryImportPreviewService previewService =
        new GlossaryImportPreviewService(previewRepository);
    GlossaryImportApplyService service =
        new GlossaryImportApplyService(previewService, revisionRepository);
    GlossaryImportPreviewRepository.WritableSnapshot snapshot =
        snapshot(
            8, List.of(term(EXISTING_TERM_ID, 1, "red dragon", "rồng đỏ", List.of("long đỏ"))));
    when(revisionRepository.createImportDraftRevision(
            eq(ACTOR_ID),
            eq(GLOSSARY_ID),
            eq(8L),
            anyList(),
            eq("import-apply-test-key-01"),
            any(byte[].class),
            anyList(),
            eq(0)))
        .thenAnswer(
            invocation ->
                new GlossaryRevisionWriteRepository.ImportApplyResult(
                    new GlossaryRevisionWriteRepository.DraftCreated(
                        GLOSSARY_ID, id("new-revision"), 2, REVISION_ID, 9),
                    invocation.getArgument(6),
                    0,
                    false));
    String file =
        "{\"version\":1,\"terms\":["
            + "{\"sourceTerm\":\"new jade\",\"targetTerm\":\"ngọc mới\"},"
            + "{\"sourceTerm\":\"long đỏ\",\"targetTerm\":\"rồng dài\"}]}";

    GlossaryImportApplyService.AppliedDraft applied =
        service.apply(
            ACTOR_ID,
            snapshot,
            8,
            file.getBytes(StandardCharsets.UTF_8),
            GlossaryImportParser.Format.JSON,
            List.of(
                new RowResolution(
                    1, ResolutionAction.ADD, null, resolved("new jade", "ngọc mới", List.of())),
                new RowResolution(
                    2,
                    ResolutionAction.MERGE,
                    EXISTING_TERM_ID,
                    resolved("long dragon", "rồng dài", List.of("dragon long")))),
            "import-apply-test-key-01",
            new byte[32]);

    ArgumentCaptor<List<GlossaryRevisionWriteRepository.TermDraft>> terms =
        ArgumentCaptor.forClass(List.class);
    verify(revisionRepository)
        .createImportDraftRevision(
            eq(ACTOR_ID),
            eq(GLOSSARY_ID),
            eq(8L),
            terms.capture(),
            eq("import-apply-test-key-01"),
            any(byte[].class),
            anyList(),
            eq(0));
    assertEquals(2, terms.getValue().size());
    assertEquals(EXISTING_TERM_ID, terms.getValue().get(0).id());
    assertEquals("long dragon", terms.getValue().get(0).sourceTerm());
    assertEquals("red dragon", snapshot.terms().getFirst().sourceTerm());
    assertEquals(2, applied.rows().size());
    assertEquals(ResolutionAction.MERGE, applied.rows().get(1).action());
    assertEquals(EXISTING_TERM_ID, applied.rows().get(1).termId());
  }

  @Test
  void rejectsUnresolvedPatternCollisionsWithoutCallingTheWriter() {
    GlossaryImportApplyService service =
        new GlossaryImportApplyService(
            new GlossaryImportPreviewService(previewRepository), revisionRepository);
    GlossaryImportPreviewRepository.WritableSnapshot snapshot =
        snapshot(
            8,
            List.of(term(EXISTING_TERM_ID, 1, "red dragon", "rồng đỏ", List.of("shared alias"))));
    String file =
        "{\"version\":1,\"terms\":[{\"sourceTerm\":\"new term\",\"targetTerm\":\"từ mới\",\"aliases\":[\"shared alias\"]}]}";

    GlossaryImportApplyService.ApplyException error =
        assertThrows(
            GlossaryImportApplyService.ApplyException.class,
            () ->
                service.apply(
                    ACTOR_ID,
                    snapshot,
                    8,
                    file.getBytes(StandardCharsets.UTF_8),
                    GlossaryImportParser.Format.JSON,
                    List.of(
                        new RowResolution(
                            1,
                            ResolutionAction.ADD,
                            null,
                            resolved("new term", "từ mới", List.of("shared alias")))),
                    "import-apply-test-key-02",
                    new byte[32]));

    assertEquals("IMPORT_CONFLICT_UNRESOLVED", error.code());
    verify(revisionRepository, never())
        .createImportDraftRevision(
            eq(ACTOR_ID),
            eq(GLOSSARY_ID),
            eq(8L),
            anyList(),
            eq("import-apply-test-key-02"),
            any(byte[].class),
            anyList(),
            eq(0));
  }

  @Test
  void requiresExactlyOneDecisionForEveryParsedRow() {
    GlossaryImportApplyService service =
        new GlossaryImportApplyService(
            new GlossaryImportPreviewService(previewRepository), revisionRepository);
    String file =
        "{\"version\":1,\"terms\":[{\"sourceTerm\":\"first\",\"targetTerm\":\"đầu\"},{\"sourceTerm\":\"second\",\"targetTerm\":\"hai\"}]}";

    GlossaryImportApplyService.ApplyException error =
        assertThrows(
            GlossaryImportApplyService.ApplyException.class,
            () ->
                service.apply(
                    ACTOR_ID,
                    snapshot(8, List.of()),
                    8,
                    file.getBytes(StandardCharsets.UTF_8),
                    GlossaryImportParser.Format.JSON,
                    List.of(
                        new RowResolution(
                            1, ResolutionAction.ADD, null, resolved("first", "đầu", List.of()))),
                    "import-apply-test-key-03",
                    new byte[32]));

    assertEquals("IMPORT_RESOLUTIONS_INCOMPLETE", error.code());
    verify(revisionRepository, never())
        .createImportDraftRevision(
            eq(ACTOR_ID),
            eq(GLOSSARY_ID),
            eq(8L),
            anyList(),
            eq("import-apply-test-key-03"),
            any(byte[].class),
            anyList(),
            eq(0));
  }

  @Test
  void replaysTheStoredResultBeforeCheckingTheNowStaleVersion() {
    GlossaryImportApplyService service =
        new GlossaryImportApplyService(
            new GlossaryImportPreviewService(previewRepository), revisionRepository);
    UUID revisionId = id("already-created-revision");
    GlossaryRevisionWriteRepository.ImportApplyResult stored =
        new GlossaryRevisionWriteRepository.ImportApplyResult(
            new GlossaryRevisionWriteRepository.DraftCreated(
                GLOSSARY_ID, revisionId, 2, REVISION_ID, 9),
            List.of(new GlossaryImportApplyService.RowResult(1, ResolutionAction.ADD, id("term"))),
            0,
            true);
    when(revisionRepository.findImportApply(
            eq(ACTOR_ID), eq(GLOSSARY_ID), eq("import-apply-replay-key-01"), any(byte[].class)))
        .thenReturn(Optional.of(stored));

    GlossaryImportApplyService.AppliedDraft replay =
        service.apply(
            ACTOR_ID,
            snapshot(9, List.of()),
            8,
            "not parsed on replay".getBytes(StandardCharsets.UTF_8),
            GlossaryImportParser.Format.JSON,
            List.of(),
            "import-apply-replay-key-01",
            new byte[32]);

    assertEquals(true, replay.replayed());
    assertEquals(revisionId, replay.draft().revisionId());
    verify(revisionRepository, never())
        .createImportDraftRevision(
            eq(ACTOR_ID),
            eq(GLOSSARY_ID),
            eq(8L),
            anyList(),
            eq("import-apply-replay-key-01"),
            any(byte[].class),
            anyList(),
            eq(0));
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
        REVISION_ID,
        1,
        REVISION_ID,
        1,
        terms);
  }

  private static GlossaryImportPreviewRepository.CurrentTerm term(
      UUID id, int ordinal, String source, String target, List<String> aliases) {
    return new GlossaryImportPreviewRepository.CurrentTerm(
        id,
        ordinal,
        source,
        target,
        0,
        GlossaryMatcher.CasePolicy.INSENSITIVE,
        GlossaryMatcher.MatchMode.WHOLE_PHRASE,
        false,
        null,
        aliases);
  }

  private static ResolvedTerm resolved(String source, String target, List<String> aliases) {
    return new ResolvedTerm(
        source,
        target,
        0,
        GlossaryMatcher.CasePolicy.INSENSITIVE,
        GlossaryMatcher.MatchMode.WHOLE_PHRASE,
        false,
        null,
        aliases);
  }

  private static UUID id(String name) {
    return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
  }
}
