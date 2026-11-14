package com.novelweb.translation.glossary;

import java.text.BreakIterator;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Finds glossary terms in source text without rewriting or recursively processing that text. */
@Component
@Profile("postgres")
public final class GlossaryMatcher {

  private static final Comparator<Candidate> CANDIDATE_ORDER =
      Comparator.comparingInt(
              (Candidate candidate) ->
                  candidate.pattern().codePointCount(0, candidate.pattern().length()))
          .reversed()
          .thenComparing(
              Comparator.comparingInt(
                      (Candidate candidate) -> candidate.term().scope().specificity())
                  .reversed())
          .thenComparing(
              Comparator.comparingInt((Candidate candidate) -> candidate.term().priority())
                  .reversed())
          .thenComparing(candidate -> candidate.term().id().toString())
          .thenComparing(Candidate::alias);

  public enum CasePolicy {
    SENSITIVE,
    INSENSITIVE
  }

  public enum MatchMode {
    WHOLE_PHRASE
  }

  public enum Scope {
    USER_GLOBAL(0),
    LIBRARY(1),
    WORK(2),
    VOLUME(3),
    CHAPTER(4);

    private final int specificity;

    Scope(int specificity) {
      this.specificity = specificity;
    }

    int specificity() {
      return specificity;
    }
  }

  /** The caller supplies terms applicable to the current resource and immutable revision. */
  public record Term(
      UUID id,
      UUID revisionId,
      String sourceLanguage,
      String sourceTerm,
      String targetTerm,
      List<String> aliases,
      Scope scope,
      int priority,
      CasePolicy casePolicy,
      MatchMode matchMode,
      boolean doNotTranslate,
      int ordinal,
      String contextNote) {

    public Term(
        UUID id,
        UUID revisionId,
        String sourceLanguage,
        String sourceTerm,
        String targetTerm,
        List<String> aliases,
        Scope scope,
        int priority,
        CasePolicy casePolicy,
        MatchMode matchMode,
        boolean doNotTranslate) {
      this(
          id,
          revisionId,
          sourceLanguage,
          sourceTerm,
          targetTerm,
          aliases,
          scope,
          priority,
          casePolicy,
          matchMode,
          doNotTranslate,
          1,
          null);
    }

    public Term {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(revisionId, "revisionId");
      requireText(sourceLanguage, "sourceLanguage");
      requireText(sourceTerm, "sourceTerm");
      Objects.requireNonNull(aliases, "aliases");
      Objects.requireNonNull(scope, "scope");
      Objects.requireNonNull(casePolicy, "casePolicy");
      Objects.requireNonNull(matchMode, "matchMode");
      if (!doNotTranslate) requireText(targetTerm, "targetTerm");
      if (ordinal < 1) throw new IllegalArgumentException("ordinal must be positive");
      if (contextNote != null && contextNote.codePointCount(0, contextNote.length()) > 2000) {
        throw new IllegalArgumentException("contextNote exceeds 2,000 characters");
      }
      aliases = List.copyOf(aliases);
      aliases.forEach(alias -> requireText(alias, "alias"));
    }

    private static void requireText(String value, String field) {
      if (value == null || value.isBlank()) {
        throw new IllegalArgumentException(field + " must not be blank");
      }
    }
  }

  /** Offsets are UTF-16 indices into the exact, unnormalized input string. */
  public record Match(
      UUID termId,
      UUID revisionId,
      String matchedText,
      String targetTerm,
      int startUtf16,
      int endUtf16,
      boolean alias,
      boolean doNotTranslate,
      Scope scope,
      int priority) {}

  public record Conflict(
      String matchedText, int startUtf16, int endUtf16, List<UUID> competingTermIds, String code) {
    public Conflict {
      competingTermIds = List.copyOf(competingTermIds);
    }
  }

  public record Result(List<Match> matches, List<Conflict> conflicts) {
    public Result {
      matches = List.copyOf(matches);
      conflicts = List.copyOf(conflicts);
    }
  }

  /**
   * Matches one immutable glossary snapshot. Longer phrases win first; for equal spans, narrower
   * scope wins, followed by explicit priority. Equal-precedence different outcomes are conflicts.
   *
   * <p>Aliases are terminal source patterns. Matches are never recursively applied to targets, so
   * circular alias definitions cannot cause rewrite loops. Punctuation is preserved exactly;
   * Unicode NFC and runs of whitespace are normalized for matching only.
   */
  public Result match(String sourceText, List<Term> terms) {
    Objects.requireNonNull(sourceText, "sourceText");
    Objects.requireNonNull(terms, "terms");
    if (sourceText.isEmpty() || terms.isEmpty()) return new Result(List.of(), List.of());

    NormalizedText sensitiveText = normalize(sourceText, false);
    NormalizedText insensitiveText = normalize(sourceText, true);
    TrieNode sensitiveRoot = new TrieNode();
    TrieNode insensitiveRoot = new TrieNode();
    List<Candidate> candidates = new ArrayList<>();
    Set<UUID> termIds = new HashSet<>();
    UUID revisionId = null;
    for (Term term : terms) {
      Objects.requireNonNull(term, "term");
      if (!termIds.add(term.id())) {
        throw new IllegalArgumentException("term IDs must be unique within one glossary revision");
      }
      if (revisionId == null) revisionId = term.revisionId();
      if (!revisionId.equals(term.revisionId())) {
        throw new IllegalArgumentException("all terms must belong to the same glossary revision");
      }
      TrieNode root = term.casePolicy() == CasePolicy.INSENSITIVE ? insensitiveRoot : sensitiveRoot;
      for (PatternSpec pattern : patterns(term)) {
        insert(root, pattern);
      }
    }
    collectMatches(sourceText, sensitiveText, sensitiveRoot, candidates);
    collectMatches(sourceText, insensitiveText, insensitiveRoot, candidates);

    candidates.sort(
        Comparator.comparingInt(Candidate::startUtf16)
            .thenComparing(CANDIDATE_ORDER)
            .thenComparingInt(Candidate::endUtf16));
    return selectNonOverlapping(candidates);
  }

  private static List<PatternSpec> patterns(Term term) {
    List<PatternSpec> result = new ArrayList<>();
    String canonical = normalizeForComparison(term.sourceTerm(), term.casePolicy());
    result.add(new PatternSpec(term, canonical, false));
    for (String alias : term.aliases()) {
      String normalized = normalizeForComparison(alias, term.casePolicy());
      if (result.stream().noneMatch(pattern -> pattern.pattern().equals(normalized))) {
        result.add(new PatternSpec(term, normalized, true));
      }
    }
    return result;
  }

  static String normalizeForComparison(String source, CasePolicy casePolicy) {
    return normalize(source, casePolicy == CasePolicy.INSENSITIVE).text().strip();
  }

  private static void insert(TrieNode root, PatternSpec pattern) {
    TrieNode node = root;
    for (int index = 0; index < pattern.pattern().length(); index++) {
      node =
          node.children()
              .computeIfAbsent(pattern.pattern().charAt(index), ignored -> new TrieNode());
    }
    node.patterns().add(pattern);
  }

  private static void collectMatches(
      String original, NormalizedText text, TrieNode root, List<Candidate> matches) {
    for (int start = 0; start < text.text().length(); start++) {
      if (!text.clusterStart()[start]) continue;
      TrieNode node = root;
      for (int index = start; index < text.text().length(); index++) {
        node = node.children().get(text.text().charAt(index));
        if (node == null) break;
        if (!text.clusterEnd()[index]) continue;
        for (PatternSpec pattern : node.patterns()) {
          if (!isWholePhrase(text.text(), start, index + 1, pattern.term())) continue;
          int originalStart = text.originalStart()[start];
          int originalEnd = text.originalEnd()[index];
          String matchedText = original.substring(originalStart, originalEnd);
          String resolvedTarget =
              pattern.term().doNotTranslate() ? matchedText : pattern.term().targetTerm();
          matches.add(
              new Candidate(
                  pattern.term(),
                  pattern.pattern(),
                  pattern.alias(),
                  originalStart,
                  originalEnd,
                  matchedText,
                  resolvedTarget));
        }
      }
    }
  }

  private static boolean isWholePhrase(String text, int start, int end, Term term) {
    int first = text.codePointAt(start);
    int last = text.codePointBefore(end);
    int before = start == 0 ? -1 : text.codePointBefore(start);
    int after = end == text.length() ? -1 : text.codePointAt(end);
    return !blocksWordBoundary(before, first, term.sourceLanguage())
        && !blocksWordBoundary(after, last, term.sourceLanguage());
  }

  private static boolean blocksWordBoundary(int neighbor, int edge, String language) {
    if (neighbor < 0) return false;
    if (isCjkLanguage(language) && isCjkLetter(neighbor) && isCjkLetter(edge)) return false;
    return isWordCodePoint(neighbor) && isWordCodePoint(edge);
  }

  private static boolean isWordCodePoint(int codePoint) {
    int type = Character.getType(codePoint);
    return Character.isLetterOrDigit(codePoint)
        || type == Character.NON_SPACING_MARK
        || type == Character.COMBINING_SPACING_MARK
        || type == Character.ENCLOSING_MARK
        || type == Character.CONNECTOR_PUNCTUATION;
  }

  private static boolean isCjkLanguage(String language) {
    String primary = language.split("-", 2)[0].toLowerCase(Locale.ROOT);
    return primary.equals("zh") || primary.equals("ja");
  }

  private static boolean isCjkLetter(int codePoint) {
    Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
    return Character.isLetter(codePoint)
        && (script == Character.UnicodeScript.HAN
            || script == Character.UnicodeScript.HIRAGANA
            || script == Character.UnicodeScript.KATAKANA);
  }

  private static Result selectNonOverlapping(List<Candidate> candidates) {
    List<Match> matches = new ArrayList<>();
    List<Conflict> conflicts = new ArrayList<>();
    int cursor = 0;
    int index = 0;
    while (index < candidates.size()) {
      int endOfStartGroup = index + 1;
      int start = candidates.get(index).startUtf16();
      while (endOfStartGroup < candidates.size()
          && candidates.get(endOfStartGroup).startUtf16() == start) {
        endOfStartGroup++;
      }

      Candidate best = candidates.get(index);
      if (best.startUtf16() >= cursor) {
        List<Candidate> tied = topPrecedence(candidates.subList(index, endOfStartGroup), best);
        if (hasConflictingOutcomes(tied)) {
          List<UUID> ids =
              tied.stream().map(candidate -> candidate.term().id()).distinct().sorted().toList();
          conflicts.add(
              new Conflict(
                  best.matchedText(),
                  best.startUtf16(),
                  best.endUtf16(),
                  ids,
                  "EQUAL_PRECEDENCE_DIFFERENT_OUTCOMES"));
          cursor = best.endUtf16();
        } else {
          Candidate selected =
              tied.stream()
                  .min(Comparator.comparing(candidate -> candidate.term().id()))
                  .orElse(best);
          matches.add(toMatch(selected));
          cursor = selected.endUtf16();
        }
      }
      index = endOfStartGroup;
    }
    return new Result(matches, conflicts);
  }

  private static List<Candidate> topPrecedence(List<Candidate> candidates, Candidate best) {
    int bestLength = best.pattern().codePointCount(0, best.pattern().length());
    return candidates.stream()
        .filter(
            candidate ->
                candidate.pattern().codePointCount(0, candidate.pattern().length()) == bestLength)
        .filter(candidate -> candidate.term().scope() == best.term().scope())
        .filter(candidate -> candidate.term().priority() == best.term().priority())
        .filter(candidate -> candidate.endUtf16() == best.endUtf16())
        .toList();
  }

  private static boolean hasConflictingOutcomes(List<Candidate> candidates) {
    return candidates.stream()
            .map(candidate -> candidate.term().doNotTranslate() + "\u0000" + candidate.target())
            .distinct()
            .count()
        > 1;
  }

  private static Match toMatch(Candidate candidate) {
    return new Match(
        candidate.term().id(),
        candidate.term().revisionId(),
        candidate.matchedText(),
        candidate.target(),
        candidate.startUtf16(),
        candidate.endUtf16(),
        candidate.alias(),
        candidate.term().doNotTranslate(),
        candidate.term().scope(),
        candidate.term().priority());
  }

  private static NormalizedText normalize(String source, boolean caseInsensitive) {
    StringBuilder normalized = new StringBuilder();
    List<Integer> originalStarts = new ArrayList<>();
    List<Integer> originalEnds = new ArrayList<>();
    List<Boolean> clusterStarts = new ArrayList<>();
    List<Boolean> clusterEnds = new ArrayList<>();
    BreakIterator iterator = BreakIterator.getCharacterInstance(Locale.ROOT);
    iterator.setText(source);
    int clusterStart = iterator.first();
    for (int clusterEnd = iterator.next();
        clusterEnd != BreakIterator.DONE;
        clusterStart = clusterEnd, clusterEnd = iterator.next()) {
      String cluster =
          Normalizer.normalize(source.substring(clusterStart, clusterEnd), Normalizer.Form.NFC);
      if (caseInsensitive) cluster = cluster.toLowerCase(Locale.ROOT);
      if (cluster.codePoints().allMatch(Character::isWhitespace)) {
        if (normalized.isEmpty() || normalized.charAt(normalized.length() - 1) == ' ') {
          if (!normalized.isEmpty()) originalEnds.set(originalEnds.size() - 1, clusterEnd);
          continue;
        }
        appendCluster(
            " ",
            clusterStart,
            clusterEnd,
            normalized,
            originalStarts,
            originalEnds,
            clusterStarts,
            clusterEnds);
      } else {
        appendCluster(
            cluster,
            clusterStart,
            clusterEnd,
            normalized,
            originalStarts,
            originalEnds,
            clusterStarts,
            clusterEnds);
      }
    }
    return new NormalizedText(
        normalized.toString(),
        toIntArray(originalStarts),
        toIntArray(originalEnds),
        toBooleanArray(clusterStarts),
        toBooleanArray(clusterEnds));
  }

  private static void appendCluster(
      String cluster,
      int originalStart,
      int originalEnd,
      StringBuilder normalized,
      List<Integer> originalStarts,
      List<Integer> originalEnds,
      List<Boolean> clusterStarts,
      List<Boolean> clusterEnds) {
    for (int index = 0; index < cluster.length(); index++) {
      normalized.append(cluster.charAt(index));
      originalStarts.add(originalStart);
      originalEnds.add(originalEnd);
      clusterStarts.add(index == 0);
      clusterEnds.add(index == cluster.length() - 1);
    }
  }

  private static int[] toIntArray(List<Integer> values) {
    int[] result = new int[values.size()];
    for (int index = 0; index < values.size(); index++) result[index] = values.get(index);
    return result;
  }

  private static boolean[] toBooleanArray(List<Boolean> values) {
    boolean[] result = new boolean[values.size()];
    for (int index = 0; index < values.size(); index++) result[index] = values.get(index);
    return result;
  }

  private record Candidate(
      Term term,
      String pattern,
      boolean alias,
      int startUtf16,
      int endUtf16,
      String matchedText,
      String target) {}

  private record PatternSpec(Term term, String pattern, boolean alias) {}

  private static final class TrieNode {
    private final Map<Character, TrieNode> children = new HashMap<>();
    private final List<PatternSpec> patterns = new ArrayList<>();

    Map<Character, TrieNode> children() {
      return children;
    }

    List<PatternSpec> patterns() {
      return patterns;
    }
  }

  private record NormalizedText(
      String text,
      int[] originalStart,
      int[] originalEnd,
      boolean[] clusterStart,
      boolean[] clusterEnd) {}
}
