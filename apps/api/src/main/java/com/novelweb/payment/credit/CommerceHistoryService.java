package com.novelweb.payment.credit;

import com.novelweb.payment.credit.CommerceHistoryCursorCodec.Position;
import com.novelweb.payment.credit.CommerceHistoryCursorCodec.Scope;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Builds separate, bounded payment-order and chapter-entitlement history pages. */
@Service
@Profile("postgres")
public class CommerceHistoryService {

  private static final int MAX_PAGE_SIZE = 50;

  private final CommerceHistoryRepository repository;
  private final CommerceHistoryCursorCodec cursorCodec;

  public CommerceHistoryService(
      CommerceHistoryRepository repository, CommerceHistoryCursorCodec cursorCodec) {
    this.repository = Objects.requireNonNull(repository, "commerce history repository is required");
    this.cursorCodec = Objects.requireNonNull(cursorCodec, "commerce history cursor is required");
  }

  public Optional<HistoryPage<CommerceHistoryRepository.PaymentOrder>> paymentOrders(
      UUID actorId, String state, String cursor, int requestedLimit) {
    requireActor(actorId);
    requirePageSize(requestedLimit);
    String filter = state == null ? "" : state;
    cursorCodec.requireConfigured();
    Position position =
        cursor == null ? null : cursorCodec.decode(actorId, Scope.PAYMENT_ORDERS, filter, cursor);
    CommerceHistoryRepository.Page<CommerceHistoryRepository.PaymentOrder> page =
        repository.findPaymentOrders(actorId, state, position, requestedLimit + 1);
    if (!page.accountActive()) return Optional.empty();
    List<CommerceHistoryRepository.PaymentOrder> items =
        page.items().stream().limit(requestedLimit).toList();
    return Optional.of(
        new HistoryPage<>(
            items,
            nextCursor(
                actorId,
                Scope.PAYMENT_ORDERS,
                filter,
                items,
                page.items().size() > requestedLimit,
                CommerceHistoryRepository.PaymentOrder::createdAt,
                CommerceHistoryRepository.PaymentOrder::id)));
  }

  public Optional<HistoryPage<CommerceHistoryRepository.ChapterPurchase>> purchases(
      UUID actorId, String cursor, int requestedLimit) {
    requireActor(actorId);
    requirePageSize(requestedLimit);
    cursorCodec.requireConfigured();
    Position position =
        cursor == null ? null : cursorCodec.decode(actorId, Scope.CHAPTER_PURCHASES, "", cursor);
    CommerceHistoryRepository.Page<CommerceHistoryRepository.ChapterPurchase> page =
        repository.findChapterPurchases(actorId, position, requestedLimit + 1);
    if (!page.accountActive()) return Optional.empty();
    List<CommerceHistoryRepository.ChapterPurchase> items =
        page.items().stream().limit(requestedLimit).toList();
    return Optional.of(
        new HistoryPage<>(
            items, nextPurchaseCursor(actorId, items, page.items().size() > requestedLimit)));
  }

  private String nextPurchaseCursor(
      UUID actorId, List<CommerceHistoryRepository.ChapterPurchase> items, boolean hasMore) {
    if (!hasMore || items.isEmpty()) return null;
    CommerceHistoryRepository.ChapterPurchase last = items.getLast();
    return cursorCodec.encode(
        actorId, Scope.CHAPTER_PURCHASES, "", new Position(last.grantedAt(), last.entitlementId()));
  }

  private <T> String nextCursor(
      UUID actorId,
      Scope scope,
      String filter,
      List<T> items,
      boolean hasMore,
      Timestamp<T> timestamp,
      Identifier<T> identifier) {
    if (!hasMore || items.isEmpty()) return null;
    T last = items.getLast();
    return cursorCodec.encode(
        actorId, scope, filter, new Position(timestamp.get(last), identifier.get(last)));
  }

  private static void requireActor(UUID actorId) {
    Objects.requireNonNull(actorId, "actor id is required");
  }

  private static void requirePageSize(int requestedLimit) {
    if (requestedLimit < 1 || requestedLimit > MAX_PAGE_SIZE) {
      throw new IllegalArgumentException("limit must be between 1 and 50");
    }
  }

  @FunctionalInterface
  private interface Timestamp<T> {
    java.time.Instant get(T value);
  }

  @FunctionalInterface
  private interface Identifier<T> {
    UUID get(T value);
  }

  public record HistoryPage<T>(List<T> items, String nextCursor) {
    public HistoryPage {
      items = List.copyOf(items);
    }
  }
}
