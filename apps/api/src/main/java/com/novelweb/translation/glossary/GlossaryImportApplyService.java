package com.novelweb.translation.glossary;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Applies explicit import row decisions as one complete immutable draft revision. */
@Service
@Profile("postgres")
public class GlossaryImportApplyService {

  private final GlossaryImportPreviewService previewService;
  private final GlossaryRevisionWriteRepository revisionRepository;

  public GlossaryImportApplyService(
      GlossaryImportPreviewService previewService,
      GlossaryRevisionWriteRepository revisionRepository) {
    this.previewService = Objects.requireNonNull(previewService, "preview service is required");
    this.revisionRepository =
        Objects.requireNonNull(revisionRepository, "revision repository is required");
  }

  public AppliedDraft apply(
      UUID actorUserId,
      GlossaryImportPreviewRepository.WritableSnapshot snapshot,
      long expectedVersion,
      byte[] fileContent,
      GlossaryImportParser.Format format,
      List<RowResolution> requestedResolutions,
      String idempotencyKey,
      byte[] requestSha256) {
    java.util.Optional<GlossaryRevisionWriteRepository.ImportApplyResult> prior =
        revisionRepository.findImportApply(
            actorUserId, snapshot.glossaryId(), idempotencyKey, requestSha256);
    if (prior.isPresent()) return toAppliedDraft(prior.orElseThrow());
    if (snapshot.version() != expectedVersion) {
      throw new GlossaryImportPreviewService.VersionConflictException(snapshot.version());
    }
    GlossaryImportPreviewService.Preview preview =
        previewService.preview(snapshot, fileContent, format);
    if (requestedResolutions == null || requestedResolutions.stream().anyMatch(Objects::isNull)) {
      throw new ApplyException("IMPORT_RESOLUTIONS_INVALID", 0);
    }
    List<RowResolution> resolutions = new ArrayList<>(requestedResolutions);
    Map<Integer, GlossaryImportPreviewService.ImportCandidate> candidatesByRow = new HashMap<>();
    preview
        .importedTerms()
        .forEach(candidate -> candidatesByRow.put(candidate.rowNumber(), candidate));
    if (resolutions.size() != candidatesByRow.size()) {
      throw new ApplyException("IMPORT_RESOLUTIONS_INCOMPLETE", 0);
    }

    LinkedHashMap<UUID, GlossaryRevisionWriteRepository.TermDraft> resolvedTerms =
        new LinkedHashMap<>();
    snapshot.terms().stream()
        .sorted(Comparator.comparingInt(GlossaryImportPreviewRepository.CurrentTerm::ordinal))
        .forEach(term -> resolvedTerms.put(term.id(), toDraft(term)));

    Set<Integer> seenRows = new HashSet<>();
    Set<UUID> selectedCurrentTermIds = new HashSet<>();
    List<RowResult> rowResults = new ArrayList<>();
    Map<UUID, Integer> changedRowsByTermId = new LinkedHashMap<>();
    int skippedRows = 0;
    for (RowResolution resolution :
        resolutions.stream().sorted(Comparator.comparingInt(RowResolution::rowNumber)).toList()) {
      if (resolution == null
          || resolution.rowNumber() < 1
          || !seenRows.add(resolution.rowNumber())) {
        throw new ApplyException("IMPORT_RESOLUTIONS_INVALID", 0);
      }
      GlossaryImportPreviewService.ImportCandidate candidate =
          candidatesByRow.get(resolution.rowNumber());
      if (candidate == null || resolution.action() == null) {
        throw new ApplyException("IMPORT_RESOLUTIONS_INVALID", resolution.rowNumber());
      }
      validateResolutionShape(resolution);
      switch (resolution.action()) {
        case SKIP -> {
          skippedRows++;
          rowResults.add(new RowResult(resolution.rowNumber(), ResolutionAction.SKIP, null));
        }
        case ADD -> {
          UUID newTermId = UUID.randomUUID();
          GlossaryRevisionWriteRepository.TermDraft draft = toDraft(newTermId, resolution.term());
          resolvedTerms.put(newTermId, draft);
          changedRowsByTermId.put(newTermId, resolution.rowNumber());
          rowResults.add(new RowResult(resolution.rowNumber(), ResolutionAction.ADD, newTermId));
        }
        case REPLACE -> {
          UUID targetId = resolution.targetTermId();
          GlossaryImportPreviewService.PatternMatch match =
              singleMatch(
                  candidate,
                  targetId,
                  GlossaryImportPreviewService.MatchReason.CANONICAL_TERM_MATCH);
          if (candidate.suggestedAction() != GlossaryImportPreviewService.SuggestedAction.UPDATE
              || match == null
              || !selectedCurrentTermIds.add(targetId)) {
            throw new ApplyException("IMPORT_RESOLUTION_TARGET_INVALID", resolution.rowNumber());
          }
          resolvedTerms.put(targetId, toDraft(targetId, resolution.term()));
          changedRowsByTermId.put(targetId, resolution.rowNumber());
          rowResults.add(new RowResult(resolution.rowNumber(), ResolutionAction.REPLACE, targetId));
        }
        case MERGE -> {
          UUID targetId = resolution.targetTermId();
          if (candidate.matchesTruncated()
              || candidate.existingMatches().stream()
                  .noneMatch(match -> match.termId().equals(targetId))
              || !resolvedTerms.containsKey(targetId)
              || !selectedCurrentTermIds.add(targetId)) {
            throw new ApplyException(
                candidate.matchesTruncated()
                    ? "IMPORT_MATCHES_TRUNCATED"
                    : "IMPORT_RESOLUTION_TARGET_INVALID",
                resolution.rowNumber());
          }
          resolvedTerms.put(targetId, toDraft(targetId, resolution.term()));
          changedRowsByTermId.put(targetId, resolution.rowNumber());
          rowResults.add(new RowResult(resolution.rowNumber(), ResolutionAction.MERGE, targetId));
        }
      }
    }
    if (seenRows.size() != candidatesByRow.size()) {
      throw new ApplyException("IMPORT_RESOLUTIONS_INCOMPLETE", 0);
    }

    List<GlossaryRevisionWriteRepository.TermDraft> finalTerms =
        List.copyOf(resolvedTerms.values());
    if (changedRowsByTermId.isEmpty() || sameTerms(snapshot.terms(), finalTerms)) {
      throw new ApplyException("IMPORT_NO_CHANGES", 0);
    }
    try {
      GlossaryRevisionWriteRepository.validateTerms(finalTerms);
    } catch (IllegalArgumentException exception) {
      throw new ApplyException("IMPORT_RESOLVED_TERMS_INVALID", 0);
    }
    validateNoNewPatternCollisions(finalTerms, changedRowsByTermId);

    GlossaryRevisionWriteRepository.ImportApplyResult result =
        revisionRepository.createImportDraftRevision(
            actorUserId,
            snapshot.glossaryId(),
            expectedVersion,
            finalTerms,
            idempotencyKey,
            requestSha256,
            rowResults,
            skippedRows);
    return toAppliedDraft(result);
  }

  public GlossaryImportPreviewRepository.WritableSnapshot loadSnapshot(
      UUID actorUserId, UUID glossaryId) {
    return previewService.loadSnapshot(actorUserId, glossaryId);
  }

  private static AppliedDraft toAppliedDraft(
      GlossaryRevisionWriteRepository.ImportApplyResult result) {
    return new AppliedDraft(result.draft(), result.rows(), result.skippedRows(), result.replayed());
  }

  private void validateNoNewPatternCollisions(
      List<GlossaryRevisionWriteRepository.TermDraft> finalTerms,
      Map<UUID, Integer> changedRowsByTermId) {
    List<GlossaryImportPreviewRepository.CurrentTerm> finalSnapshot = toCurrentTerms(finalTerms);
    Map<UUID, GlossaryRevisionWriteRepository.TermDraft> termsById = new HashMap<>();
    finalTerms.forEach(term -> termsById.put(term.id(), term));
    for (Map.Entry<UUID, Integer> changedRow : changedRowsByTermId.entrySet()) {
      UUID changedTermId = changedRow.getKey();
      GlossaryRevisionWriteRepository.TermDraft term = termsById.get(changedTermId);
      Set<String> ownPatterns = new HashSet<>();
      List<String> allPatterns = new ArrayList<>(term.aliases().size() + 1);
      allPatterns.add(term.sourceTerm());
      allPatterns.addAll(term.aliases());
      for (String pattern : allPatterns) {
        String normalized =
            GlossaryMatcher.normalizeForComparison(pattern, GlossaryMatcher.CasePolicy.INSENSITIVE);
        if (!ownPatterns.add(normalized)) {
          throw new ApplyException("IMPORT_RESOLVED_PATTERN_DUPLICATE", changedRow.getValue());
        }
      }
      GlossaryImportParser.ImportedTerm candidateTerm =
          new GlossaryImportParser.ImportedTerm(
              changedRow.getValue(),
              term.sourceTerm(),
              term.targetTerm(),
              term.priority(),
              term.casePolicy(),
              term.matchMode(),
              term.doNotTranslate(),
              term.contextNote(),
              term.aliases());
      List<GlossaryImportPreviewRepository.CurrentTerm> otherTerms =
          finalSnapshot.stream().filter(current -> !current.id().equals(changedTermId)).toList();
      if (!previewService.classify(candidateTerm, otherTerms).existingMatches().isEmpty()) {
        throw new ApplyException("IMPORT_CONFLICT_UNRESOLVED", changedRow.getValue());
      }
    }
  }

  private static List<GlossaryImportPreviewRepository.CurrentTerm> toCurrentTerms(
      List<GlossaryRevisionWriteRepository.TermDraft> terms) {
    List<GlossaryImportPreviewRepository.CurrentTerm> current = new ArrayList<>(terms.size());
    for (int index = 0; index < terms.size(); index++) {
      GlossaryRevisionWriteRepository.TermDraft term = terms.get(index);
      current.add(
          new GlossaryImportPreviewRepository.CurrentTerm(
              term.id(),
              index + 1,
              term.sourceTerm(),
              term.targetTerm(),
              term.priority(),
              term.casePolicy(),
              term.matchMode(),
              term.doNotTranslate(),
              term.contextNote(),
              term.aliases()));
    }
    return List.copyOf(current);
  }

  private static void validateResolutionShape(RowResolution resolution) {
    if (resolution.action() == ResolutionAction.SKIP) {
      if (resolution.targetTermId() != null || resolution.term() != null) {
        throw new ApplyException("IMPORT_RESOLUTION_SHAPE_INVALID", resolution.rowNumber());
      }
      return;
    }
    if (resolution.term() == null) {
      throw new ApplyException("IMPORT_RESOLUTION_SHAPE_INVALID", resolution.rowNumber());
    }
    if (resolution.term().sourceTerm() == null
        || resolution.term().casePolicy() == null
        || resolution.term().matchMode() == null
        || resolution.term().aliases() == null
        || resolution.term().aliases().stream().anyMatch(Objects::isNull)) {
      throw new ApplyException("IMPORT_RESOLUTION_SHAPE_INVALID", resolution.rowNumber());
    }
    boolean targetRequired =
        resolution.action() == ResolutionAction.REPLACE
            || resolution.action() == ResolutionAction.MERGE;
    if (targetRequired != (resolution.targetTermId() != null)) {
      throw new ApplyException("IMPORT_RESOLUTION_SHAPE_INVALID", resolution.rowNumber());
    }
  }

  private static GlossaryImportPreviewService.PatternMatch singleMatch(
      GlossaryImportPreviewService.ImportCandidate candidate,
      UUID targetTermId,
      GlossaryImportPreviewService.MatchReason reason) {
    if (candidate.matchesTruncated() || candidate.existingMatches().size() != 1) return null;
    GlossaryImportPreviewService.PatternMatch match = candidate.existingMatches().getFirst();
    return match.termId().equals(targetTermId) && match.reason() == reason ? match : null;
  }

  private static boolean sameTerms(
      List<GlossaryImportPreviewRepository.CurrentTerm> existing,
      List<GlossaryRevisionWriteRepository.TermDraft> requested) {
    if (existing.size() != requested.size()) return false;
    for (int index = 0; index < existing.size(); index++) {
      if (!toDraft(existing.get(index)).equals(requested.get(index))) return false;
    }
    return true;
  }

  private static GlossaryRevisionWriteRepository.TermDraft toDraft(
      GlossaryImportPreviewRepository.CurrentTerm term) {
    return new GlossaryRevisionWriteRepository.TermDraft(
        term.id(),
        term.sourceTerm(),
        term.targetTerm(),
        term.priority(),
        term.casePolicy(),
        term.matchMode(),
        term.doNotTranslate(),
        term.contextNote(),
        term.aliases());
  }

  private static GlossaryRevisionWriteRepository.TermDraft toDraft(UUID id, ResolvedTerm term) {
    return new GlossaryRevisionWriteRepository.TermDraft(
        id,
        term.sourceTerm(),
        term.targetTerm(),
        term.priority(),
        term.casePolicy(),
        term.matchMode(),
        term.doNotTranslate(),
        term.contextNote(),
        term.aliases() == null ? List.of() : term.aliases());
  }

  public enum ResolutionAction {
    ADD,
    REPLACE,
    MERGE,
    SKIP
  }

  public record ResolvedTerm(
      String sourceTerm,
      String targetTerm,
      int priority,
      GlossaryMatcher.CasePolicy casePolicy,
      GlossaryMatcher.MatchMode matchMode,
      boolean doNotTranslate,
      String contextNote,
      List<String> aliases) {}

  public record RowResolution(
      int rowNumber, ResolutionAction action, UUID targetTermId, ResolvedTerm term) {}

  public record RowResult(int rowNumber, ResolutionAction action, UUID termId) {}

  public record AppliedDraft(
      GlossaryRevisionWriteRepository.DraftCreated draft,
      List<RowResult> rows,
      int skippedRows,
      boolean replayed) {
    public AppliedDraft {
      rows = List.copyOf(rows);
    }
  }

  public static final class ApplyException extends RuntimeException {
    private final String code;
    private final int rowNumber;

    public ApplyException(String code, int rowNumber) {
      this.code = code;
      this.rowNumber = rowNumber;
    }

    public String code() {
      return code;
    }

    public int rowNumber() {
      return rowNumber;
    }
  }
}
