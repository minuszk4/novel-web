package com.novelweb.payment.credit;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Builds a bounded account-private credit transaction history. */
@Service
@Profile("postgres")
public class CreditHistoryService {

  private final CreditHistoryRepository repository;
  private final CreditHistoryCursorCodec cursorCodec;

  public CreditHistoryService(
      CreditHistoryRepository repository, CreditHistoryCursorCodec cursorCodec) {
    this.repository = Objects.requireNonNull(repository, "credit history repository is required");
    this.cursorCodec = Objects.requireNonNull(cursorCodec, "credit history cursor is required");
  }

  public Optional<HistoryPage> list(UUID actorUserId, String cursor, int requestedLimit) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    if (requestedLimit < 1 || requestedLimit > 50) {
      throw new IllegalArgumentException("limit must be between 1 and 50");
    }
    cursorCodec.requireConfigured();
    CreditHistoryCursorCodec.Position position =
        cursor == null ? null : cursorCodec.decode(actorUserId, cursor);
    CreditHistoryRepository.Page page =
        repository.findRecent(actorUserId, position, requestedLimit + 1);
    if (!page.accountActive()) return Optional.empty();

    boolean hasMore = page.items().size() > requestedLimit;
    List<CreditHistoryRepository.CreditEntry> entries =
        page.items().stream().limit(requestedLimit).toList();
    String nextCursor =
        hasMore
            ? cursorCodec.encode(
                actorUserId,
                new CreditHistoryCursorCodec.Position(
                    entries.getLast().occurredAt(), entries.getLast().id()))
            : null;
    return Optional.of(new HistoryPage(entries, nextCursor));
  }

  public record HistoryPage(List<CreditHistoryRepository.CreditEntry> items, String nextCursor) {
    public HistoryPage {
      items = List.copyOf(items);
    }
  }
}
