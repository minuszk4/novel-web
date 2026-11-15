package com.novelweb.payment.credit;

import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/** Reads the actor's available paid and non-expired bonus credit balance. */
@Repository
@Profile("postgres")
public class CreditBalanceRepository {

  private final DataSource dataSource;

  public CreditBalanceRepository(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  public Optional<CreditBalance> findAvailable(UUID userId) {
    Objects.requireNonNull(userId, "user id is required");
    String query =
        """
        WITH snapshot AS (SELECT statement_timestamp() AS as_of)
        SELECT snapshot.as_of,
               COALESCE(SUM(projection.balance)
                 FILTER (WHERE projection.bucket = 'PAID'), 0) AS paid_credits,
               COALESCE(SUM(projection.balance)
                 FILTER (WHERE projection.bucket = 'BONUS'), 0) AS bonus_credits
        FROM users AS account
        CROSS JOIN snapshot
        LEFT JOIN credit_balance_projection AS projection
          ON projection.user_id = account.id
         AND projection.balance > 0
         AND (projection.bucket = 'PAID'
              OR projection.expires_at IS NULL
              OR projection.expires_at > snapshot.as_of)
        WHERE account.id = ? AND account.status = 'active'
        GROUP BY snapshot.as_of
        """;
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select = connection.prepareStatement(query)) {
      select.setObject(1, userId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) return Optional.empty();
        BigInteger paid = row.getBigDecimal("paid_credits").toBigIntegerExact();
        BigInteger bonus = row.getBigDecimal("bonus_credits").toBigIntegerExact();
        return Optional.of(
            new CreditBalance(
                paid,
                bonus,
                paid.add(bonus),
                row.getObject("as_of", OffsetDateTime.class).toInstant()));
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to read the credit balance", exception);
    }
  }

  public record CreditBalance(
      BigInteger paidCredits, BigInteger bonusCredits, BigInteger totalCredits, Instant asOf) {}
}
