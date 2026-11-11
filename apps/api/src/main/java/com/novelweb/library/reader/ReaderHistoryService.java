package com.novelweb.library.reader;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("postgres")
public class ReaderHistoryService {

  private static final int MAX_PAGE_SIZE = 100;

  private final ReaderHistoryRepository repository;
  private final ReaderCursorCodec cursorCodec;

  public ReaderHistoryService(ReaderHistoryRepository repository, ReaderCursorCodec cursorCodec) {
    this.repository = repository;
    this.cursorCodec = cursorCodec;
  }

  public HistoryList list(UUID actorUserId, String cursor, int limit) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    if (limit < 1 || limit > MAX_PAGE_SIZE) {
      throw new IllegalArgumentException("limit must be between 1 and " + MAX_PAGE_SIZE);
    }
    cursorCodec.requireConfigured();
    ReaderCursorCodec.Context context = ReaderCursorCodec.readingHistoryContext(actorUserId);
    ReaderCursorCodec.KeysetPosition after =
        cursor == null ? null : cursorCodec.decodeKeyset(cursor, context);
    ReaderHistoryRepository.Page page = repository.findRecent(actorUserId, after, limit);
    String nextCursor = null;
    if (page.hasMore() && !page.items().isEmpty()) {
      ReaderHistoryRepository.HistoryRecord last = page.items().get(page.items().size() - 1);
      nextCursor =
          cursorCodec.encodeKeyset(
              context,
              new ReaderCursorCodec.KeysetPosition(last.progress().updatedAt(), last.work().id()));
    }
    return new HistoryList(page.items(), nextCursor);
  }

  public void deleteWork(UUID actorUserId, UUID workId) {
    repository.deleteWork(
        Objects.requireNonNull(actorUserId, "actor user id is required"),
        Objects.requireNonNull(workId, "work id is required"));
  }

  public void deleteAll(UUID actorUserId) {
    repository.deleteAll(Objects.requireNonNull(actorUserId, "actor user id is required"));
  }

  public record HistoryList(List<ReaderHistoryRepository.HistoryRecord> items, String nextCursor) {
    public HistoryList {
      items = List.copyOf(items);
    }
  }
}
