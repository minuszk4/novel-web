package com.novelweb.progression.cultivation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CultivationReadingSessionServiceTests {

  private final CultivationReadingSessionRepository repository =
      mock(CultivationReadingSessionRepository.class);
  private final CultivationReadingSessionService service =
      new CultivationReadingSessionService(repository);

  @Test
  void creditsOnlyServerReceiptGapAndCapsOneHeartbeatAtSixtySeconds() {
    Instant previous = Instant.parse("2026-11-01T00:00:00Z");

    assertEquals(
        30,
        CultivationReadingSessionService.creditableSeconds(
            previous, previous.plusSeconds(30), true));
    assertEquals(
        60,
        CultivationReadingSessionService.creditableSeconds(
            previous, previous.plusSeconds(60), true));
    assertEquals(
        60,
        CultivationReadingSessionService.creditableSeconds(
            previous, previous.plusSeconds(121), true));
  }

  @Test
  void doesNotCreditPausedOrNonMonotonicIntervals() {
    Instant previous = Instant.parse("2026-11-01T00:00:00Z");

    assertEquals(
        0,
        CultivationReadingSessionService.creditableSeconds(
            previous, previous.plusSeconds(45), false));
    assertEquals(
        0,
        CultivationReadingSessionService.creditableSeconds(
            previous, previous.minusSeconds(1), true));
    assertEquals(0, CultivationReadingSessionService.creditableSeconds(null, previous, true));
  }

  @Test
  void preservesFractionalActiveTimeAndSplitsItAtUtcMidnight() {
    Instant previous = Instant.parse("2026-11-01T23:59:30Z");
    assertEquals(
        1_234,
        CultivationReadingSessionService.creditableMicroseconds(
            previous, previous.plusNanos(1_234_999), true));

    var slices = CultivationReadingSessionRepository.splitByUtcDay(previous, 60_000_000L);
    assertEquals(2, slices.size());
    assertEquals(LocalDate.of(2026, 11, 1), slices.getFirst().activityDate());
    assertEquals(30_000_000L, slices.getFirst().durationMicroseconds());
    assertEquals(LocalDate.of(2026, 11, 2), slices.getLast().activityDate());
    assertEquals(30_000_000L, slices.getLast().durationMicroseconds());
  }

  @Test
  void expiresAtNinetySecondsWithoutHeartbeat() {
    Instant previous = Instant.parse("2026-11-01T00:00:00Z");

    assertFalse(CultivationReadingSessionService.isExpired(previous, previous.plusSeconds(89)));
    assertTrue(CultivationReadingSessionService.isExpired(previous, previous.plusSeconds(90)));
    assertTrue(CultivationReadingSessionService.isExpired(previous, null));
  }

  @Test
  void rejectsStartWhenReaderIsNotVisibleOrBodyContainsUnrecognizedFields() {
    UUID userId = UUID.randomUUID();
    var hiddenRequest = new CultivationReadingSessionService.StartRequest();
    hiddenRequest.setWorkId(UUID.randomUUID());
    hiddenRequest.setChapterId(UUID.randomUUID());
    hiddenRequest.setActive(false);

    assertThrows(
        CultivationReadingSessionService.InvalidReadingSessionRequestException.class,
        () -> service.start(userId, hiddenRequest, "start-idempotency-0001"));

    var unknownFieldRequest = new CultivationReadingSessionService.StartRequest();
    unknownFieldRequest.setWorkId(UUID.randomUUID());
    unknownFieldRequest.setChapterId(UUID.randomUUID());
    unknownFieldRequest.setActive(true);
    unknownFieldRequest.captureUnknownField("elapsedSeconds", 3600);
    assertThrows(
        CultivationReadingSessionService.InvalidReadingSessionRequestException.class,
        () -> service.start(userId, unknownFieldRequest, "start-idempotency-0002"));
    verifyNoInteractions(repository);
  }

  @Test
  void rejectsClientDurationAndMalformedHeartbeatKeys() {
    UUID userId = UUID.randomUUID();
    var unknownFieldRequest = new CultivationReadingSessionService.HeartbeatRequest();
    unknownFieldRequest.setSequence(1L);
    unknownFieldRequest.setActive(true);
    unknownFieldRequest.captureUnknownField("elapsedSeconds", 3600);

    assertThrows(
        CultivationReadingSessionService.InvalidReadingSessionRequestException.class,
        () ->
            service.heartbeat(
                userId, UUID.randomUUID(), unknownFieldRequest, "heartbeat-idempotency-0001"));

    var validRequest = new CultivationReadingSessionService.HeartbeatRequest();
    validRequest.setSequence(1L);
    validRequest.setActive(true);
    assertThrows(
        CultivationReadingSessionService.InvalidReadingSessionRequestException.class,
        () -> service.heartbeat(userId, UUID.randomUUID(), validRequest, "bad key"));
    verifyNoInteractions(repository);
  }
}
