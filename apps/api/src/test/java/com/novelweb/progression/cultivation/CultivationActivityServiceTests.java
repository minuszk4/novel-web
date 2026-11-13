package com.novelweb.progression.cultivation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CultivationActivityServiceTests {

  private static final String HMAC_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

  private final CultivationActivityRepository repository =
      mock(CultivationActivityRepository.class);
  private final CultivationActivityCursorCodec cursorCodec =
      new CultivationActivityCursorCodec(HMAC_KEY);
  private final CultivationActivityService service =
      new CultivationActivityService(repository, cursorCodec);

  @Test
  void returnsDailyXpOnlyAndSignsTheNextActorBoundDate() {
    UUID actorId = UUID.randomUUID();
    LocalDate newest = LocalDate.of(2026, 11, 13);
    when(repository.findRecent(actorId, null, 1))
        .thenReturn(
            new CultivationActivityRepository.Page(
                List.of(new CultivationActivityRepository.DailyActivity(newest, 42)), true));

    CultivationActivityService.ActivityPage page = service.list(actorId, null, 1);

    assertEquals(List.of(new CultivationActivityService.DailyActivity(newest, 42)), page.items());
    assertEquals(newest, cursorCodec.decode(page.nextCursor(), actorId));
    verify(repository).findRecent(actorId, null, 1);
  }

  @Test
  void decodesTheAccountScopedDateAndRejectsOutOfRangePageSizes() {
    UUID actorId = UUID.randomUUID();
    LocalDate beforeDate = LocalDate.of(2026, 11, 1);
    String cursor = cursorCodec.encode(actorId, beforeDate);
    when(repository.findRecent(actorId, beforeDate, 30))
        .thenReturn(new CultivationActivityRepository.Page(List.of(), false));

    CultivationActivityService.ActivityPage page = service.list(actorId, cursor, 30);

    assertEquals(List.of(), page.items());
    assertNull(page.nextCursor());
    verify(repository).findRecent(actorId, beforeDate, 30);
    assertThrows(IllegalArgumentException.class, () -> service.list(actorId, null, 0));
    assertThrows(IllegalArgumentException.class, () -> service.list(actorId, null, 101));
  }
}
