package com.novelweb.library;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Returns only the current actor's followed-work controls and privacy-safe unavailable rows. */
@Service
@Profile("postgres")
public class WorkSubscriptionListService {

  private static final int MAX_PAGE_SIZE = 50;

  private final WorkSubscriptionRepository repository;
  private final LibraryCursorCodec cursorCodec;

  public WorkSubscriptionListService(
      WorkSubscriptionRepository repository, LibraryCursorCodec cursorCodec) {
    this.repository = Objects.requireNonNull(repository, "repository is required");
    this.cursorCodec = Objects.requireNonNull(cursorCodec, "cursor codec is required");
  }

  public Page list(UUID actorUserId, String rawCursor, int limit) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    if (limit < 1 || limit > MAX_PAGE_SIZE) throw new InvalidLimitException();

    LibraryCursorCodec.CursorContext context =
        LibraryCursorCodec.workSubscriptionsContext(actorUserId);
    LibraryCursorCodec.Position position = null;
    if (rawCursor != null) {
      cursorCodec.requireConfigured();
      position = cursorCodec.decode(rawCursor, context);
      if (position.rank() != 0) throw new LibraryCursorCodec.InvalidCursorException();
    }

    WorkSubscriptionRepository.SubscriptionSlice slice =
        repository.listActive(actorUserId, position, limit + 1);
    String nextCursor =
        slice.nextPosition() == null ? null : cursorCodec.encode(context, slice.nextPosition());
    return new Page(slice.items(), nextCursor);
  }

  public record Page(List<WorkSubscriptionRepository.SubscriptionItem> items, String nextCursor) {
    public Page {
      items = List.copyOf(items);
    }
  }

  public static class InvalidLimitException extends RuntimeException {}
}
