package com.novelweb.progression.cultivation;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("postgres")
public class CultivationService {

  private final CultivationRepository repository;
  private final Clock clock;

  @Autowired
  public CultivationService(CultivationRepository repository) {
    this(repository, Clock.systemUTC());
  }

  CultivationService(CultivationRepository repository, Clock clock) {
    this.repository = repository;
    this.clock = clock;
  }

  public CultivationState get(UUID actorUserId) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    return toState(repository.find(actorUserId));
  }

  public CultivationState saveSettings(UUID actorUserId, SettingsRequest request) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    if (request == null || request.enabled() == null || request.hasUnknownFields()) {
      throw new InvalidCultivationSettingsException(
          "Provide only the required enabled boolean setting.");
    }
    return toState(repository.setEnabled(actorUserId, request.enabled()));
  }

  private CultivationState toState(CultivationRepository.CultivationSnapshot snapshot) {
    List<Rank> ranks =
        snapshot.ranks().stream()
            .map(
                rank ->
                    new Rank(
                        rank.key(),
                        rank.group(),
                        rank.name(),
                        rank.thresholdXp(),
                        rank.sortOrder()))
            .toList();

    int currentRankIndex = 0;
    for (int index = 0; index < ranks.size(); index++) {
      if (ranks.get(index).thresholdXp() <= snapshot.totalXp()) currentRankIndex = index;
    }
    Rank currentRank = ranks.get(currentRankIndex);
    Rank nextRank = currentRankIndex + 1 < ranks.size() ? ranks.get(currentRankIndex + 1) : null;
    long xpToNextRank =
        nextRank == null ? 0 : Math.max(0, nextRank.thresholdXp() - snapshot.totalXp());
    double progressRatio =
        nextRank == null
            ? 1.0
            : Math.min(
                1.0,
                Math.max(
                    0.0,
                    (double) (snapshot.totalXp() - currentRank.thresholdXp())
                        / (nextRank.thresholdXp() - currentRank.thresholdXp())));
    Instant nextDailyResetAt =
        LocalDate.now(clock).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

    return new CultivationState(
        snapshot.enabled(),
        snapshot.profileVersion(),
        snapshot.totalXp(),
        currentRank,
        nextRank,
        xpToNextRank,
        progressRatio,
        snapshot.rankCatalogVersion(),
        snapshot.policyVersion(),
        snapshot.locale(),
        snapshot.xpPerActiveMinute(),
        snapshot.dailyXpCap(),
        nextDailyResetAt,
        ranks);
  }

  public record CultivationState(
      boolean enabled,
      long profileVersion,
      long totalXp,
      Rank currentRank,
      Rank nextRank,
      long xpToNextRank,
      double progressRatio,
      String rankCatalogVersion,
      String policyVersion,
      String locale,
      int xpPerActiveMinute,
      int dailyXpCap,
      Instant nextDailyResetAt,
      List<Rank> rankCatalog) {}

  public record Rank(String key, String group, String name, long thresholdXp, int sortOrder) {}

  public static final class SettingsRequest {
    private Boolean enabled;
    private boolean unknownFields;

    public SettingsRequest() {}

    public Boolean enabled() {
      return enabled;
    }

    public void setEnabled(Boolean enabled) {
      this.enabled = enabled;
    }

    boolean hasUnknownFields() {
      return unknownFields;
    }

    @JsonAnySetter
    public void captureUnknownField(String name, Object ignoredValue) {
      unknownFields = true;
    }
  }

  public static final class InvalidCultivationSettingsException extends RuntimeException {
    public InvalidCultivationSettingsException(String message) {
      super(message);
    }
  }
}
