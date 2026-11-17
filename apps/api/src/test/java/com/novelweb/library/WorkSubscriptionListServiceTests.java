package com.novelweb.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkSubscriptionListServiceTests {

  private static final String TEST_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

  @Test
  void encodesNextPositionWithActorBoundSubscriptionContext() {
    WorkSubscriptionRepository repository =
        org.mockito.Mockito.mock(WorkSubscriptionRepository.class);
    LibraryCursorCodec codec = new LibraryCursorCodec(TEST_KEY);
    UUID actorId = UUID.randomUUID();
    UUID subscriptionId = UUID.randomUUID();
    Instant followedAt = Instant.parse("2026-11-17T12:00:00.123456Z");
    LibraryCursorCodec.Position position =
        new LibraryCursorCodec.Position(0, followedAt, subscriptionId);
    when(repository.listActive(actorId, null, 3))
        .thenReturn(new WorkSubscriptionRepository.SubscriptionSlice(List.of(), position));

    WorkSubscriptionListService.Page page =
        new WorkSubscriptionListService(repository, codec).list(actorId, null, 2);

    assertEquals(0, page.items().size());
    assertEquals(
        position,
        codec.decode(page.nextCursor(), LibraryCursorCodec.workSubscriptionsContext(actorId)));
    assertThrows(
        LibraryCursorCodec.InvalidCursorException.class,
        () ->
            codec.decode(
                page.nextCursor(), LibraryCursorCodec.workSubscriptionsContext(UUID.randomUUID())));
    verify(repository).listActive(actorId, null, 3);
  }

  @Test
  void rejectsInvalidLimitsBeforeReadingRepository() {
    WorkSubscriptionRepository repository =
        org.mockito.Mockito.mock(WorkSubscriptionRepository.class);
    WorkSubscriptionListService service =
        new WorkSubscriptionListService(repository, new LibraryCursorCodec(TEST_KEY));

    assertThrows(
        WorkSubscriptionListService.InvalidLimitException.class,
        () -> service.list(UUID.randomUUID(), null, 51));
    org.mockito.Mockito.verifyNoInteractions(repository);
  }

  @Test
  void rejectsCursorFromAnotherListContext() {
    LibraryCursorCodec codec = new LibraryCursorCodec(TEST_KEY);
    UUID actorId = UUID.randomUUID();
    String cursor =
        codec.encode(
            LibraryCursorCodec.workContext(actorId, UUID.randomUUID(), "other", "", "active"),
            new LibraryCursorCodec.Position(0, Instant.now(), UUID.randomUUID()));
    WorkSubscriptionRepository repository =
        org.mockito.Mockito.mock(WorkSubscriptionRepository.class);

    assertThrows(
        LibraryCursorCodec.InvalidCursorException.class,
        () -> new WorkSubscriptionListService(repository, codec).list(actorId, cursor, 20));
    org.mockito.Mockito.verifyNoInteractions(repository);
  }
}
