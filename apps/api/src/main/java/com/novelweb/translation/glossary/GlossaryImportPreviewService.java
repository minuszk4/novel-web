package com.novelweb.translation.glossary;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Compares a validated import against the authorized current snapshot without writing data. */
@Service
@Profile("postgres")
public class GlossaryImportPreviewService {

  private static final int MAX_RETURNED_MATCHES_PER_ROW = 100;
  private static final String TRANSLATION_IMPACT_DETAIL =
      "If applied, this import creates a draft only and starts no retranslation. "
          + "The current fake/local worker does not use glossary terms, so affected units cannot be estimated; "
          + "this does not mean the impact is zero.";

  private final GlossaryImportPreviewRepository repository;
  private final GlossaryImportParser parser;

  public GlossaryImportPreviewService(GlossaryImportPreviewRepository repository) {
    this.repository = Objects.requireNonNull(repository, "import preview repository is required");
    this.parser = new GlossaryImportParser();
  }

  public GlossaryImportPreviewRepository.WritableSnapshot loadCurrent(
      UUID actorUserId, UUID glossaryId, long expectedVersion) {
    GlossaryImportPreviewRepository.WritableSnapshot snapshot =
        loadSnapshot(actorUserId, glossaryId);
    if (snapshot.version() != expectedVersion) {
      throw new VersionConflictException(snapshot.version());
    }
    return snapshot;
  }

  public GlossaryImportPreviewRepository.WritableSnapshot loadSnapshot(
      UUID actorUserId, UUID glossaryId) {
    return repository.loadWritableSnapshot(actorUserId, glossaryId);
  }

  public Preview preview(
      GlossaryImportPreviewRepository.WritableSnapshot snapshot,
      byte[] content,
      GlossaryImportParser.Format format) {
    Objects.requireNonNull(snapshot, "glossary snapshot is required");
    GlossaryImportParser.ParsedImport parsed = parser.parse(content, format);
    PatternIndex patternIndex = patternIndex(snapshot.terms());
    List<ImportCandidate> candidates =
        parsed.terms().stream().map(term -> candidate(term, patternIndex)).toList();
    long addCount =
        candidates.stream().filter(item -> item.suggestedAction() == SuggestedAction.ADD).count();
    long updateCount =
        candidates.stream()
            .filter(item -> item.suggestedAction() == SuggestedAction.UPDATE)
            .count();
    long unchangedCount =
        candidates.stream()
            .filter(item -> item.suggestedAction() == SuggestedAction.UNCHANGED)
            .count();
    long conflictCount =
        candidates.stream()
            .filter(item -> item.suggestedAction() == SuggestedAction.RESOLVE_CONFLICT)
            .count();
    return new Preview(
        snapshot.glossaryId(),
        snapshot.name(),
        snapshot.scope(),
        snapshot.sourceLanguage(),
        snapshot.targetLanguage(),
        snapshot.version(),
        snapshot.activeRevisionId(),
        snapshot.activeRevisionNumber(),
        snapshot.baseRevisionId(),
        snapshot.baseRevisionNumber(),
        snapshot.terms(),
        candidates,
        new Counts(candidates.size(), addCount, updateCount, unchangedCount, conflictCount),
        new TranslationImpact("UNAVAILABLE", null, TRANSLATION_IMPACT_DETAIL));
  }

  ImportCandidate classify(
      GlossaryImportParser.ImportedTerm imported,
      List<GlossaryImportPreviewRepository.CurrentTerm> currentTerms) {
    return candidate(imported, patternIndex(currentTerms));
  }

  private static ImportCandidate candidate(
      GlossaryImportParser.ImportedTerm imported, PatternIndex patternIndex) {
    Map<UUID, MatchReason> matchesByTermId = new LinkedHashMap<>();
    List<String> importedPatterns = patterns(imported);
    for (int patternIndexNumber = 0;
        patternIndexNumber < importedPatterns.size();
        patternIndexNumber++) {
      String value = importedPatterns.get(patternIndexNumber);
      boolean importedCanonical = patternIndexNumber == 0;
      String folded =
          GlossaryMatcher.normalizeForComparison(value, GlossaryMatcher.CasePolicy.INSENSITIVE);
      if (imported.casePolicy() == GlossaryMatcher.CasePolicy.INSENSITIVE) {
        for (PatternReference reference : patternIndex.folded().getOrDefault(folded, List.of())) {
          addMatch(matchesByTermId, reference, importedCanonical);
        }
      } else {
        for (PatternReference reference : patternIndex.folded().getOrDefault(folded, List.of())) {
          if (reference.casePolicy() == GlossaryMatcher.CasePolicy.INSENSITIVE) {
            addMatch(matchesByTermId, reference, importedCanonical);
          }
        }
        String exact =
            GlossaryMatcher.normalizeForComparison(value, GlossaryMatcher.CasePolicy.SENSITIVE);
        for (PatternReference reference :
            patternIndex.sensitiveExact().getOrDefault(exact, List.of())) {
          addMatch(matchesByTermId, reference, importedCanonical);
        }
      }
    }

    List<PatternMatch> matches =
        patternIndex.currentTerms().stream()
            .filter(term -> matchesByTermId.containsKey(term.id()))
            .map(term -> new PatternMatch(term.id(), matchesByTermId.get(term.id())))
            .limit(MAX_RETURNED_MATCHES_PER_ROW + 1L)
            .toList();
    boolean matchesTruncated = matches.size() > MAX_RETURNED_MATCHES_PER_ROW;
    if (matchesTruncated) matches = matches.subList(0, MAX_RETURNED_MATCHES_PER_ROW);

    SuggestedAction action;
    if (matchesByTermId.isEmpty()) {
      action = SuggestedAction.ADD;
    } else if (matchesByTermId.size() == 1
        && matches.getFirst().reason() == MatchReason.CANONICAL_TERM_MATCH) {
      GlossaryImportPreviewRepository.CurrentTerm current =
          patternIndex.termsById().get(matches.getFirst().termId());
      action = sameContent(imported, current) ? SuggestedAction.UNCHANGED : SuggestedAction.UPDATE;
    } else {
      action = SuggestedAction.RESOLVE_CONFLICT;
    }
    return new ImportCandidate(imported.rowNumber(), imported, action, matches, matchesTruncated);
  }

  private static void addMatch(
      Map<UUID, MatchReason> matches, PatternReference reference, boolean importedCanonical) {
    MatchReason reason =
        importedCanonical && reference.canonical()
            ? MatchReason.CANONICAL_TERM_MATCH
            : MatchReason.PATTERN_COLLISION;
    matches.merge(
        reference.termId(),
        reason,
        (previous, current) ->
            previous == MatchReason.CANONICAL_TERM_MATCH
                    || current == MatchReason.CANONICAL_TERM_MATCH
                ? MatchReason.CANONICAL_TERM_MATCH
                : MatchReason.PATTERN_COLLISION);
  }

  private static PatternIndex patternIndex(
      List<GlossaryImportPreviewRepository.CurrentTerm> currentTerms) {
    Map<String, List<PatternReference>> folded = new HashMap<>();
    Map<String, List<PatternReference>> sensitiveExact = new HashMap<>();
    Map<UUID, GlossaryImportPreviewRepository.CurrentTerm> termsById = new HashMap<>();
    for (GlossaryImportPreviewRepository.CurrentTerm term : currentTerms) {
      termsById.put(term.id(), term);
      List<String> termPatterns = patterns(term);
      for (int patternNumber = 0; patternNumber < termPatterns.size(); patternNumber++) {
        String value = termPatterns.get(patternNumber);
        PatternReference reference =
            new PatternReference(term.id(), term.casePolicy(), patternNumber == 0);
        String normalizedFolded =
            GlossaryMatcher.normalizeForComparison(value, GlossaryMatcher.CasePolicy.INSENSITIVE);
        folded.computeIfAbsent(normalizedFolded, ignored -> new ArrayList<>()).add(reference);
        if (term.casePolicy() == GlossaryMatcher.CasePolicy.SENSITIVE) {
          String normalizedExact =
              GlossaryMatcher.normalizeForComparison(value, GlossaryMatcher.CasePolicy.SENSITIVE);
          sensitiveExact
              .computeIfAbsent(normalizedExact, ignored -> new ArrayList<>())
              .add(reference);
        }
      }
    }
    folded.replaceAll((key, value) -> List.copyOf(value));
    sensitiveExact.replaceAll((key, value) -> List.copyOf(value));
    return new PatternIndex(
        currentTerms, termsById, Map.copyOf(folded), Map.copyOf(sensitiveExact));
  }

  private static boolean sameContent(
      GlossaryImportParser.ImportedTerm imported,
      GlossaryImportPreviewRepository.CurrentTerm current) {
    return imported.sourceTerm().equals(current.sourceTerm())
        && Objects.equals(imported.targetTerm(), current.targetTerm())
        && imported.priority() == current.priority()
        && imported.casePolicy() == current.casePolicy()
        && imported.matchMode() == current.matchMode()
        && imported.doNotTranslate() == current.doNotTranslate()
        && Objects.equals(imported.contextNote(), current.contextNote())
        && imported.aliases().equals(current.aliases());
  }

  private static List<String> patterns(GlossaryImportParser.ImportedTerm term) {
    List<String> patterns = new ArrayList<>(term.aliases().size() + 1);
    patterns.add(term.sourceTerm());
    patterns.addAll(term.aliases());
    return patterns;
  }

  private static List<String> patterns(GlossaryImportPreviewRepository.CurrentTerm term) {
    List<String> patterns = new ArrayList<>(term.aliases().size() + 1);
    patterns.add(term.sourceTerm());
    patterns.addAll(term.aliases());
    return patterns;
  }

  private record PatternReference(
      UUID termId, GlossaryMatcher.CasePolicy casePolicy, boolean canonical) {}

  private record PatternIndex(
      List<GlossaryImportPreviewRepository.CurrentTerm> currentTerms,
      Map<UUID, GlossaryImportPreviewRepository.CurrentTerm> termsById,
      Map<String, List<PatternReference>> folded,
      Map<String, List<PatternReference>> sensitiveExact) {}

  public enum SuggestedAction {
    ADD,
    UPDATE,
    UNCHANGED,
    RESOLVE_CONFLICT
  }

  public enum MatchReason {
    CANONICAL_TERM_MATCH,
    PATTERN_COLLISION
  }

  public record PatternMatch(UUID termId, MatchReason reason) {}

  public record ImportCandidate(
      int rowNumber,
      GlossaryImportParser.ImportedTerm importedTerm,
      SuggestedAction suggestedAction,
      List<PatternMatch> existingMatches,
      boolean matchesTruncated) {
    public ImportCandidate {
      existingMatches = List.copyOf(existingMatches);
    }
  }

  public record Counts(
      long imported, long add, long update, long unchanged, long requiresResolution) {}

  public record TranslationImpact(
      String status, Integer affectedTranslationUnitCount, String detail) {}

  public record Preview(
      UUID glossaryId,
      String glossaryName,
      GlossaryMatcher.Scope scope,
      String sourceLanguage,
      String targetLanguage,
      long glossaryVersion,
      UUID activeRevisionId,
      Integer activeRevisionNumber,
      UUID baseRevisionId,
      Integer baseRevisionNumber,
      List<GlossaryImportPreviewRepository.CurrentTerm> currentTerms,
      List<ImportCandidate> importedTerms,
      Counts counts,
      TranslationImpact translationImpact) {
    public Preview {
      currentTerms = List.copyOf(currentTerms);
      importedTerms = List.copyOf(importedTerms);
    }
  }

  public static final class VersionConflictException extends RuntimeException {
    private final long currentVersion;

    public VersionConflictException(long currentVersion) {
      this.currentVersion = currentVersion;
    }

    public long currentVersion() {
      return currentVersion;
    }
  }
}
