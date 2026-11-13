package com.novelweb.translation.glossary;

import static com.novelweb.translation.glossary.GlossaryMatcher.CasePolicy.INSENSITIVE;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Builds immutable-revision diffs and pre-publish lint/match previews without persistence. */
public final class GlossaryRevisionPreviewService {

  private final GlossaryMatcher matcher;

  public GlossaryRevisionPreviewService(GlossaryMatcher matcher) {
    this.matcher = Objects.requireNonNull(matcher, "matcher");
  }

  public enum ChangeKind {
    ADDED,
    REMOVED,
    UPDATED
  }

  public enum Severity {
    WARNING,
    ERROR
  }

  /** An immutable snapshot. Terms are revision-scoped and never rebound to a later snapshot. */
  public record GlossaryRevision(
      UUID glossaryId, UUID revisionId, UUID parentRevisionId, List<GlossaryMatcher.Term> terms) {
    public GlossaryRevision {
      Objects.requireNonNull(glossaryId, "glossaryId");
      Objects.requireNonNull(revisionId, "revisionId");
      if (revisionId.equals(parentRevisionId)) {
        throw new IllegalArgumentException("a revision cannot be its own parent");
      }
      Objects.requireNonNull(terms, "terms");
      terms = List.copyOf(terms);
      Set<UUID> termIds = new HashSet<>();
      for (GlossaryMatcher.Term term : terms) {
        Objects.requireNonNull(term, "term");
        if (!revisionId.equals(term.revisionId())) {
          throw new IllegalArgumentException("every term must belong to its immutable revision");
        }
        if (!termIds.add(term.id())) {
          throw new IllegalArgumentException("term IDs must be unique within a revision");
        }
      }
    }
  }

  public record Change(
      ChangeKind kind, UUID termId, GlossaryMatcher.Term before, GlossaryMatcher.Term after) {}

  public record LintIssue(
      String code, Severity severity, List<UUID> termIds, String affectedValue) {
    public LintIssue {
      Objects.requireNonNull(code, "code");
      Objects.requireNonNull(severity, "severity");
      Objects.requireNonNull(affectedValue, "affectedValue");
      termIds = termIds.stream().distinct().sorted().toList();
    }
  }

  public record LintReport(List<LintIssue> issues) {
    public LintReport {
      issues =
          issues.stream()
              .distinct()
              .sorted(
                  Comparator.comparing(LintIssue::code)
                      .thenComparing(issue -> issue.termIds().toString()))
              .toList();
    }

    public boolean canPublish() {
      return issues.stream().noneMatch(issue -> issue.severity() == Severity.ERROR);
    }

    public long errorCount() {
      return issues.stream().filter(issue -> issue.severity() == Severity.ERROR).count();
    }

    public long warningCount() {
      return issues.stream().filter(issue -> issue.severity() == Severity.WARNING).count();
    }
  }

  public record Preview(
      UUID glossaryId,
      UUID baseRevisionId,
      UUID proposedRevisionId,
      List<Change> changes,
      LintReport lint,
      GlossaryMatcher.Result sourceMatches) {
    public Preview {
      changes = List.copyOf(changes);
      Objects.requireNonNull(lint, "lint");
      Objects.requireNonNull(sourceMatches, "sourceMatches");
    }

    public boolean canPublish() {
      return lint.canPublish() && sourceMatches.conflicts().isEmpty();
    }
  }

  /**
   * Previews the proposed immutable revision against its exact parent. A null base is allowed only
   * for a new glossary. This method reports a safe diff and source-span matches; it publishes
   * nothing and does not rewrite source text.
   */
  public Preview preview(
      GlossaryRevision base,
      GlossaryRevision proposed,
      String sampleSource,
      int shortTermWarningCodePoints) {
    Objects.requireNonNull(proposed, "proposed");
    Objects.requireNonNull(sampleSource, "sampleSource");
    if (shortTermWarningCodePoints < 1) {
      throw new IllegalArgumentException("shortTermWarningCodePoints must be positive");
    }
    if (base == null) {
      if (proposed.parentRevisionId() != null) {
        throw new IllegalArgumentException("a new glossary revision cannot reference a parent");
      }
    } else {
      if (!base.glossaryId().equals(proposed.glossaryId())) {
        throw new IllegalArgumentException("revisions must belong to the same glossary");
      }
      if (!base.revisionId().equals(proposed.parentRevisionId())) {
        throw new IllegalArgumentException(
            "proposed revision must reference the supplied base revision");
      }
    }

    return new Preview(
        proposed.glossaryId(),
        base == null ? null : base.revisionId(),
        proposed.revisionId(),
        compare(base, proposed),
        lint(proposed.terms(), shortTermWarningCodePoints),
        matcher.match(sampleSource, proposed.terms()));
  }

  private static List<Change> compare(GlossaryRevision base, GlossaryRevision proposed) {
    Map<UUID, GlossaryMatcher.Term> before = byId(base == null ? List.of() : base.terms());
    Map<UUID, GlossaryMatcher.Term> after = byId(proposed.terms());
    Set<UUID> allIds = new HashSet<>(before.keySet());
    allIds.addAll(after.keySet());

    return allIds.stream()
        .sorted()
        .map(
            id -> {
              GlossaryMatcher.Term oldTerm = before.get(id);
              GlossaryMatcher.Term newTerm = after.get(id);
              if (oldTerm == null) return new Change(ChangeKind.ADDED, id, null, newTerm);
              if (newTerm == null) return new Change(ChangeKind.REMOVED, id, oldTerm, null);
              if (!sameContent(oldTerm, newTerm)) {
                return new Change(ChangeKind.UPDATED, id, oldTerm, newTerm);
              }
              return null;
            })
        .filter(Objects::nonNull)
        .toList();
  }

  private static Map<UUID, GlossaryMatcher.Term> byId(List<GlossaryMatcher.Term> terms) {
    Map<UUID, GlossaryMatcher.Term> result = new HashMap<>();
    terms.forEach(term -> result.put(term.id(), term));
    return result;
  }

  private static boolean sameContent(GlossaryMatcher.Term before, GlossaryMatcher.Term after) {
    return before.sourceLanguage().equals(after.sourceLanguage())
        && before.sourceTerm().equals(after.sourceTerm())
        && Objects.equals(before.targetTerm(), after.targetTerm())
        && before.aliases().equals(after.aliases())
        && before.scope() == after.scope()
        && before.priority() == after.priority()
        && before.casePolicy() == after.casePolicy()
        && before.matchMode() == after.matchMode()
        && before.doNotTranslate() == after.doNotTranslate();
  }

  private static LintReport lint(List<GlossaryMatcher.Term> terms, int shortTermWarningCodePoints) {
    List<LintIssue> issues = new ArrayList<>();
    Map<PatternKey, List<GlossaryMatcher.Term>> patternTerms = new HashMap<>();

    for (GlossaryMatcher.Term term : terms) {
      List<String> sourcePatterns = new ArrayList<>();
      sourcePatterns.add(term.sourceTerm());
      sourcePatterns.addAll(term.aliases());
      String shortPattern =
          sourcePatterns.stream()
              .filter(pattern -> isShort(pattern, shortTermWarningCodePoints))
              .findFirst()
              .orElse(null);
      if (shortPattern != null) {
        issues.add(
            new LintIssue("SOURCE_TERM_SHORT", Severity.WARNING, List.of(term.id()), shortPattern));
      }
      if (!term.doNotTranslate()
          && GlossaryMatcher.normalizeForComparison(term.sourceTerm(), term.casePolicy())
              .equals(
                  GlossaryMatcher.normalizeForComparison(term.targetTerm(), term.casePolicy()))) {
        issues.add(
            new LintIssue(
                "SOURCE_EQUALS_TARGET", Severity.ERROR, List.of(term.id()), term.sourceTerm()));
      }
      if (hasUnusualWhitespace(term.targetTerm())) {
        issues.add(
            new LintIssue(
                "TARGET_WHITESPACE_UNUSUAL",
                Severity.WARNING,
                List.of(term.id()),
                term.targetTerm()));
      }

      Set<String> seenPatterns = new HashSet<>();
      boolean canonicalPattern = true;
      for (String sourcePattern : sourcePatterns) {
        String normalized = GlossaryMatcher.normalizeForComparison(sourcePattern, INSENSITIVE);
        if (!seenPatterns.add(normalized) && !canonicalPattern) {
          issues.add(
              new LintIssue(
                  "DUPLICATE_ALIAS_PATTERN", Severity.WARNING, List.of(term.id()), sourcePattern));
        }
        canonicalPattern = false;
        PatternKey key = new PatternKey(normalized, term.scope(), term.priority());
        patternTerms.computeIfAbsent(key, ignored -> new ArrayList<>()).add(term);
      }
    }

    patternTerms.forEach(
        (key, collisions) -> {
          List<GlossaryMatcher.Term> uniqueTerms = collisions.stream().distinct().toList();
          long outcomes =
              uniqueTerms.stream().map(GlossaryRevisionPreviewService::outcome).distinct().count();
          if (uniqueTerms.size() > 1 && outcomes > 1) {
            issues.add(
                new LintIssue(
                    "EQUAL_PRECEDENCE_DIFFERENT_OUTCOMES",
                    Severity.ERROR,
                    uniqueTerms.stream().map(GlossaryMatcher.Term::id).toList(),
                    key.sourcePattern()));
          }
        });
    return new LintReport(issues);
  }

  private static String outcome(GlossaryMatcher.Term term) {
    return term.doNotTranslate() ? "KEEP" : "TARGET:" + term.targetTerm();
  }

  private static boolean isShort(String pattern, int minimumCodePoints) {
    String normalized = GlossaryMatcher.normalizeForComparison(pattern, INSENSITIVE);
    return normalized.codePointCount(0, normalized.length()) < minimumCodePoints;
  }

  private static boolean hasUnusualWhitespace(String value) {
    if (value == null || !value.equals(value.strip())) return value != null;
    boolean previousWasWhitespace = false;
    for (int index = 0; index < value.length(); ) {
      int codePoint = value.codePointAt(index);
      boolean whitespace = Character.isWhitespace(codePoint);
      if (whitespace && previousWasWhitespace) return true;
      previousWasWhitespace = whitespace;
      index += Character.charCount(codePoint);
    }
    return false;
  }

  private record PatternKey(String sourcePattern, GlossaryMatcher.Scope scope, int priority) {}
}
