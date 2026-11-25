package com.novelweb.library;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Validates strict actor-owned digest schedule preferences without enabling email delivery. */
@Service
@Profile("postgres")
public final class NotificationPreferencesService {

  private static final DateTimeFormatter LOCAL_TIME = DateTimeFormatter.ofPattern("HH:mm");
  private final NotificationPreferencesRepository repository;

  public NotificationPreferencesService(NotificationPreferencesRepository repository) {
    this.repository = Objects.requireNonNull(repository, "preferences repository is required");
  }

  public View get(UUID actorUserId) {
    return view(repository.find(actorUserId));
  }

  public View update(UUID actorUserId, long expectedVersion, Update update) {
    Objects.requireNonNull(update, "preference update is required");
    NotificationPreferencesRepository.Schedule schedule = validate(update);
    if (update.emailDigestEnabled()) {
      // No provider, reviewed consent version, or unsubscribe operations are installed yet.
      throw new EmailDigestUnavailableException();
    }
    return view(repository.save(actorUserId, expectedVersion, schedule));
  }

  private static NotificationPreferencesRepository.Schedule validate(Update update) {
    if (update.emailDigestEnabled() == null) throw new InvalidPreferencesException();
    boolean allAbsent =
        update.frequency() == null && update.timeZone() == null && update.sendLocalTime() == null;
    boolean allPresent =
        update.frequency() != null && update.timeZone() != null && update.sendLocalTime() != null;
    if (!allAbsent && !allPresent) throw new InvalidPreferencesException();
    if (update.emailDigestEnabled()
        && (!allPresent || !validConsentVersion(update.consentVersion()))) {
      throw new InvalidPreferencesException();
    }
    if (!update.emailDigestEnabled() && update.consentVersion() != null) {
      throw new InvalidPreferencesException();
    }
    if (allAbsent) return new NotificationPreferencesRepository.Schedule(null, null, null);
    if (!"DAILY".equals(update.frequency()) && !"WEEKLY".equals(update.frequency())) {
      throw new InvalidPreferencesException();
    }
    String timeZone = update.timeZone();
    if (timeZone.length() > 64 || !ZoneId.getAvailableZoneIds().contains(timeZone)) {
      throw new InvalidPreferencesException();
    }
    try {
      LocalTime localTime = LocalTime.parse(update.sendLocalTime(), LOCAL_TIME);
      return new NotificationPreferencesRepository.Schedule(
          update.frequency(), timeZone, localTime);
    } catch (DateTimeParseException exception) {
      throw new InvalidPreferencesException();
    }
  }

  private static boolean validConsentVersion(String value) {
    return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
  }

  private static View view(NotificationPreferencesRepository.State state) {
    String localTime =
        state.sendLocalTime() == null ? null : LOCAL_TIME.format(state.sendLocalTime());
    return new View(
        false,
        state.emailDigestEnabled(),
        state.frequency(),
        state.timeZone(),
        localTime,
        state.version());
  }

  public record Update(
      Boolean emailDigestEnabled,
      String frequency,
      String timeZone,
      String sendLocalTime,
      String consentVersion) {}

  public record View(
      boolean emailDigestAvailable,
      boolean emailDigestEnabled,
      String frequency,
      String timeZone,
      String sendLocalTime,
      long version) {}

  public static final class InvalidPreferencesException extends RuntimeException {}

  public static final class EmailDigestUnavailableException extends RuntimeException {}
}
