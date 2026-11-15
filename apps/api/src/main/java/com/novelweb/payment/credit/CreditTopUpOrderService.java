package com.novelweb.payment.credit;

import com.novelweb.payment.credit.CreditPackCatalog.CreditPackQuote;
import com.novelweb.payment.credit.CreditPackCatalog.PaymentEnvironment;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Persists an actor-scoped top-up order using only an immutable server-owned catalog quote. */
@Service
@Profile("postgres")
public class CreditTopUpOrderService {

  private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._~-]{16,128}");
  private static final long MINIMUM_AMOUNT_VND = 1_000;
  private static final long MAXIMUM_AMOUNT_VND = 50_000_000;

  private final DataSource dataSource;

  public CreditTopUpOrderService(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  /**
   * Creates or replays the durable order snapshot. The caller resolves {@code packId} through its
   * server-only catalog; no client-provided amount, credits, currency or provider URL is accepted.
   * This method does not call MoMo or start a provider request.
   */
  public CreateResult createOrReplay(
      UUID userId, String idempotencyKey, CreditPackCatalog catalog, String packId) {
    Objects.requireNonNull(catalog, "server-owned credit pack catalog is required");
    int packVersion = catalog.quote(packId).map(CreditPackQuote::version).orElse(-1);
    return createOrReplay(userId, idempotencyKey, catalog, packId, packVersion);
  }

  /** Creates/replays only the exact server-owned pack version that the user reviewed. */
  public CreateResult createOrReplay(
      UUID userId,
      String idempotencyKey,
      CreditPackCatalog catalog,
      String packId,
      int packVersion) {
    Objects.requireNonNull(userId, "user id is required");
    Objects.requireNonNull(catalog, "server-owned credit pack catalog is required");
    if (idempotencyKey == null || !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
      return CreateResult.outcome(Outcome.INVALID_IDEMPOTENCY_KEY);
    }
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        AccountState account = lockAccount(connection, userId);
        if (account != AccountState.ACTIVE) {
          connection.commit();
          return CreateResult.outcome(account.outcome());
        }

        Optional<TopUpOrder> existing = findByIdempotencyKey(connection, userId, idempotencyKey);
        if (existing.isPresent()) {
          TopUpOrder prior = existing.orElseThrow();
          connection.commit();
          return prior.quote().packId().equals(packId) && prior.quote().version() == packVersion
              ? CreateResult.replayed(prior)
              : CreateResult.outcome(Outcome.IDEMPOTENCY_KEY_REUSED);
        }

        Optional<CreditPackQuote> requestedQuote = catalog.quote(packId);
        if (requestedQuote.isEmpty()) {
          connection.commit();
          return CreateResult.outcome(Outcome.PACK_NOT_FOUND);
        }
        CreditPackQuote quote = requestedQuote.orElseThrow();
        if (quote.version() != packVersion) {
          connection.commit();
          return CreateResult.outcome(Outcome.PACK_VERSION_STALE);
        }
        validateTestQuote(quote);

        TopUpOrder inserted = insertOrder(connection, userId, idempotencyKey, quote);
        connection.commit();
        return CreateResult.created(inserted);
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        throw exception;
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to persist the top-up order snapshot", exception);
    }
  }

  /**
   * Finds an order only inside the active actor's account namespace; URL/provider data is omitted.
   */
  public Optional<TopUpOrder> findForActor(UUID userId, UUID orderId) {
    Objects.requireNonNull(userId, "user id is required");
    Objects.requireNonNull(orderId, "order id is required");
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        AccountState account = lockAccount(connection, userId);
        if (account != AccountState.ACTIVE) {
          connection.commit();
          return Optional.empty();
        }
        Optional<StoredOrder> found = findById(connection, userId, orderId);
        connection.commit();
        return found.map(StoredOrder::order);
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        throw exception;
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to load the actor's top-up order", exception);
    }
  }

  private static void validateTestQuote(CreditPackQuote quote) {
    if (quote.environment() != PaymentEnvironment.TEST
        || !"VND".equals(quote.currency())
        || quote.amountVnd() < MINIMUM_AMOUNT_VND
        || quote.amountVnd() > MAXIMUM_AMOUNT_VND) {
      throw new IllegalArgumentException("credit pack quote is not allowed for MoMo TEST");
    }
  }

  private static AccountState lockAccount(Connection connection, UUID userId) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement("SELECT status FROM users WHERE id = ? FOR UPDATE")) {
      select.setObject(1, userId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) return AccountState.NOT_FOUND;
        return "active".equals(row.getString("status"))
            ? AccountState.ACTIVE
            : AccountState.INACTIVE;
      }
    }
  }

  private static Optional<TopUpOrder> findByIdempotencyKey(
      Connection connection, UUID userId, String idempotencyKey) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT id, user_id, request_id, pack_id, pack_version, provider, environment,
                       currency, amount_vnd, paid_credits, bonus_credits,
                       paid_credit_expires_at, bonus_credit_expires_at, status, checkout_url,
                       created_at
                FROM credit_top_up_orders
                WHERE user_id = ? AND idempotency_key = ?
                FOR UPDATE
                """)) {
      select.setObject(1, userId);
      select.setString(2, idempotencyKey);
      try (ResultSet row = select.executeQuery()) {
        return row.next() ? Optional.of(orderFromRow(row)) : Optional.empty();
      }
    }
  }

  /** Claims the sole provider-create attempt for this actor-owned order. */
  public CheckoutClaim claimProviderCreate(UUID userId, UUID orderId) {
    Objects.requireNonNull(userId, "user id is required");
    Objects.requireNonNull(orderId, "order id is required");
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        AccountState account = lockAccount(connection, userId);
        if (account != AccountState.ACTIVE) {
          connection.commit();
          return CheckoutClaim.outcome(account.checkoutOutcome());
        }

        Optional<StoredOrder> stored = findById(connection, userId, orderId);
        if (stored.isEmpty()) {
          connection.commit();
          return CheckoutClaim.outcome(CheckoutOutcome.ORDER_NOT_FOUND);
        }
        StoredOrder current = stored.orElseThrow();
        if (current.order().status() != OrderStatus.CREATED) {
          connection.commit();
          return checkoutClaimFor(current);
        }

        try (PreparedStatement update =
            connection.prepareStatement(
                """
                    UPDATE credit_top_up_orders
                    SET status = 'INITIATING', updated_at = clock_timestamp()
                    WHERE id = ? AND user_id = ? AND status = 'CREATED'
                    """)) {
          update.setObject(1, orderId);
          update.setObject(2, userId);
          if (update.executeUpdate() != 1) {
            throw new SQLException("Top-up provider-create claim was lost");
          }
        }
        connection.commit();
        return CheckoutClaim.claimed(withStatus(current.order(), OrderStatus.INITIATING));
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        throw exception;
      }
    } catch (SQLException exception) {
      throw new IllegalStateException(
          "Unable to claim the top-up provider-create attempt", exception);
    }
  }

  /**
   * Persists a verified MoMo Test pay URL, or an unresolved result when the provider call is
   * ambiguous. The compare-and-set protects a later IPN/query terminal result from being replaced.
   */
  public CheckoutClaim finishProviderCreate(UUID userId, UUID orderId, String validatedPayUrl) {
    Objects.requireNonNull(userId, "user id is required");
    Objects.requireNonNull(orderId, "order id is required");
    String safePayUrl =
        com.novelweb.payment.momo.MomoTestCheckoutUrlPolicy.allows(validatedPayUrl)
            ? validatedPayUrl
            : null;
    OrderStatus nextStatus = safePayUrl == null ? OrderStatus.UNKNOWN : OrderStatus.PENDING;
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        try (PreparedStatement update =
            connection.prepareStatement(
                """
                    UPDATE credit_top_up_orders
                    SET status = ?, checkout_url = ?, updated_at = clock_timestamp()
                    WHERE id = ? AND user_id = ? AND status = 'INITIATING'
                    """)) {
          update.setString(1, nextStatus.name());
          update.setString(2, safePayUrl);
          update.setObject(3, orderId);
          update.setObject(4, userId);
          if (update.executeUpdate() == 1) {
            Optional<StoredOrder> updated = findById(connection, userId, orderId);
            connection.commit();
            if (updated.isEmpty()) return CheckoutClaim.outcome(CheckoutOutcome.ORDER_NOT_FOUND);
            return checkoutClaimFor(updated.orElseThrow());
          }
        }

        Optional<StoredOrder> current = findById(connection, userId, orderId);
        connection.commit();
        return current.isPresent()
            ? checkoutClaimFor(current.orElseThrow())
            : CheckoutClaim.outcome(CheckoutOutcome.ORDER_NOT_FOUND);
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        throw exception;
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to persist the top-up provider result", exception);
    }
  }

  private static Optional<StoredOrder> findById(Connection connection, UUID userId, UUID orderId)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT id, user_id, request_id, pack_id, pack_version, provider, environment,
                       currency, amount_vnd, paid_credits, bonus_credits,
                       paid_credit_expires_at, bonus_credit_expires_at, status, checkout_url,
                       created_at
                FROM credit_top_up_orders
                WHERE user_id = ? AND id = ?
                FOR UPDATE
                """)) {
      select.setObject(1, userId);
      select.setObject(2, orderId);
      try (ResultSet row = select.executeQuery()) {
        return row.next()
            ? Optional.of(new StoredOrder(orderFromRow(row), row.getString("checkout_url")))
            : Optional.empty();
      }
    }
  }

  private static CheckoutClaim checkoutClaimFor(StoredOrder stored) {
    TopUpOrder order = stored.order();
    return switch (order.status()) {
      case PENDING ->
          stored.checkoutUrl() == null
              ? CheckoutClaim.waiting(order)
              : CheckoutClaim.ready(order, stored.checkoutUrl());
      case INITIATING -> CheckoutClaim.outcome(CheckoutOutcome.PROVIDER_CREATE_IN_PROGRESS, order);
      case UNKNOWN -> CheckoutClaim.outcome(CheckoutOutcome.PROVIDER_RESULT_UNKNOWN, order);
      case CREATED -> CheckoutClaim.outcome(CheckoutOutcome.NOT_STARTED, order);
      case SUCCEEDED, FAILED, CANCELLED, EXPIRED ->
          CheckoutClaim.outcome(CheckoutOutcome.TERMINAL, order);
    };
  }

  private static TopUpOrder withStatus(TopUpOrder order, OrderStatus status) {
    return new TopUpOrder(
        order.id(),
        order.userId(),
        order.requestId(),
        order.provider(),
        order.quote(),
        status,
        order.createdAt());
  }

  private static TopUpOrder insertOrder(
      Connection connection, UUID userId, String idempotencyKey, CreditPackQuote quote)
      throws SQLException {
    UUID orderId = UUID.randomUUID();
    UUID requestId = UUID.randomUUID();
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
                INSERT INTO credit_top_up_orders (
                    id, user_id, request_id, idempotency_key, pack_id, pack_version,
                    provider, environment, currency, amount_vnd, paid_credits, bonus_credits,
                    paid_credit_expires_at, bonus_credit_expires_at, status)
                VALUES (?, ?, ?, ?, ?, ?, 'MOMO', ?, ?, ?, ?, ?, ?, ?, 'CREATED')
                RETURNING created_at
                """)) {
      insert.setObject(1, orderId);
      insert.setObject(2, userId);
      insert.setObject(3, requestId);
      insert.setString(4, idempotencyKey);
      insert.setString(5, quote.packId());
      insert.setInt(6, quote.version());
      insert.setString(7, quote.environment().name());
      insert.setString(8, quote.currency());
      insert.setLong(9, quote.amountVnd());
      insert.setLong(10, quote.paidCredits());
      insert.setLong(11, quote.bonusCredits());
      setInstant(insert, 12, quote.paidExpiresAt());
      setInstant(insert, 13, quote.bonusExpiresAt());
      try (ResultSet row = insert.executeQuery()) {
        if (!row.next()) throw new SQLException("Top-up order insert returned no snapshot");
        return new TopUpOrder(
            orderId,
            userId,
            requestId,
            Provider.MOMO,
            quote,
            OrderStatus.CREATED,
            row.getObject("created_at", OffsetDateTime.class).toInstant());
      }
    }
  }

  private static TopUpOrder orderFromRow(ResultSet row) throws SQLException {
    OffsetDateTime paidExpiresAt = row.getObject("paid_credit_expires_at", OffsetDateTime.class);
    OffsetDateTime bonusExpiresAt = row.getObject("bonus_credit_expires_at", OffsetDateTime.class);
    CreditPackQuote quote =
        new CreditPackQuote(
            row.getString("pack_id"),
            row.getInt("pack_version"),
            PaymentEnvironment.valueOf(row.getString("environment")),
            row.getString("currency"),
            row.getLong("amount_vnd"),
            row.getLong("paid_credits"),
            row.getLong("bonus_credits"),
            paidExpiresAt == null ? null : paidExpiresAt.toInstant(),
            bonusExpiresAt == null ? null : bonusExpiresAt.toInstant());
    return new TopUpOrder(
        row.getObject("id", UUID.class),
        row.getObject("user_id", UUID.class),
        row.getObject("request_id", UUID.class),
        Provider.valueOf(row.getString("provider")),
        quote,
        OrderStatus.valueOf(row.getString("status")),
        row.getObject("created_at", OffsetDateTime.class).toInstant());
  }

  private record StoredOrder(TopUpOrder order, String checkoutUrl) {}

  private static void setInstant(PreparedStatement statement, int index, Instant instant)
      throws SQLException {
    statement.setTimestamp(
        index, instant == null ? null : Timestamp.from(instant.truncatedTo(ChronoUnit.MICROS)));
  }

  private static void rollback(Connection connection, Exception original) {
    try {
      connection.rollback();
    } catch (SQLException rollbackException) {
      original.addSuppressed(rollbackException);
    }
  }

  private enum AccountState {
    ACTIVE,
    INACTIVE,
    NOT_FOUND;

    private Outcome outcome() {
      return switch (this) {
        case INACTIVE -> Outcome.ACCOUNT_INACTIVE;
        case NOT_FOUND -> Outcome.ACCOUNT_NOT_FOUND;
        case ACTIVE -> throw new IllegalStateException("active accounts have no error outcome");
      };
    }

    private CheckoutOutcome checkoutOutcome() {
      return switch (this) {
        case INACTIVE -> CheckoutOutcome.ACCOUNT_INACTIVE;
        case NOT_FOUND -> CheckoutOutcome.ACCOUNT_NOT_FOUND;
        case ACTIVE -> throw new IllegalStateException("active accounts have no error outcome");
      };
    }
  }

  public enum Outcome {
    CREATED,
    REPLAYED,
    IDEMPOTENCY_KEY_REUSED,
    INVALID_IDEMPOTENCY_KEY,
    PACK_NOT_FOUND,
    PACK_VERSION_STALE,
    ACCOUNT_NOT_FOUND,
    ACCOUNT_INACTIVE
  }

  public enum Provider {
    MOMO
  }

  public enum OrderStatus {
    CREATED,
    INITIATING,
    PENDING,
    UNKNOWN,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    EXPIRED
  }

  public enum CheckoutOutcome {
    CLAIMED,
    READY,
    WAITING_FOR_PROVIDER,
    PROVIDER_CREATE_IN_PROGRESS,
    PROVIDER_RESULT_UNKNOWN,
    NOT_STARTED,
    TERMINAL,
    ORDER_NOT_FOUND,
    ACCOUNT_NOT_FOUND,
    ACCOUNT_INACTIVE
  }

  public record CheckoutClaim(CheckoutOutcome outcome, TopUpOrder order, String checkoutUrl) {
    private static CheckoutClaim outcome(CheckoutOutcome outcome) {
      return new CheckoutClaim(outcome, null, null);
    }

    private static CheckoutClaim outcome(CheckoutOutcome outcome, TopUpOrder order) {
      return new CheckoutClaim(outcome, order, null);
    }

    private static CheckoutClaim claimed(TopUpOrder order) {
      return new CheckoutClaim(CheckoutOutcome.CLAIMED, order, null);
    }

    private static CheckoutClaim ready(TopUpOrder order, String url) {
      return new CheckoutClaim(CheckoutOutcome.READY, order, url);
    }

    private static CheckoutClaim waiting(TopUpOrder order) {
      return new CheckoutClaim(CheckoutOutcome.WAITING_FOR_PROVIDER, order, null);
    }

    @Override
    public String toString() {
      return "CheckoutClaim[outcome=" + outcome + ", order=" + order + ", checkoutUrl=[REDACTED]]";
    }
  }

  public record TopUpOrder(
      UUID id,
      UUID userId,
      UUID requestId,
      Provider provider,
      CreditPackQuote quote,
      OrderStatus status,
      Instant createdAt) {}

  public record CreateResult(Outcome outcome, TopUpOrder order) {
    private static CreateResult outcome(Outcome outcome) {
      return new CreateResult(outcome, null);
    }

    private static CreateResult created(TopUpOrder order) {
      return new CreateResult(Outcome.CREATED, order);
    }

    private static CreateResult replayed(TopUpOrder order) {
      return new CreateResult(Outcome.REPLAYED, order);
    }
  }
}
