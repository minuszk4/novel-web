package com.novelweb.progression.cultivation;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/** Reads only daily XP totals; story and Reader resource identifiers are never joined. */
@Repository
@Profile("postgres")
public class CultivationActivityRepository {

  private final DataSource dataSource;

  public CultivationActivityRepository(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  public Page findRecent(UUID actorUserId, LocalDate beforeDate, int limit) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    if (limit < 1) throw new IllegalArgumentException("limit must be positive");
    String datePredicate = beforeDate == null ? "" : " AND activity_date < ?";
    String sql =
        "SELECT activity_date, xp_earned FROM cultivation_daily_xp_aggregates "
            + "WHERE user_id = ?"
            + datePredicate
            + " ORDER BY activity_date DESC LIMIT ?";
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select = connection.prepareStatement(sql)) {
      int parameter = 1;
      select.setObject(parameter++, actorUserId);
      if (beforeDate != null) select.setObject(parameter++, beforeDate);
      select.setInt(parameter, limit + 1);
      try (ResultSet rows = select.executeQuery()) {
        List<DailyActivity> result = new ArrayList<>(limit + 1);
        while (rows.next()) {
          result.add(
              new DailyActivity(
                  rows.getObject("activity_date", LocalDate.class), rows.getLong("xp_earned")));
        }
        boolean hasMore = result.size() > limit;
        List<DailyActivity> items =
            hasMore ? List.copyOf(result.subList(0, limit)) : List.copyOf(result);
        return new Page(items, hasMore);
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to load private cultivation activity", exception);
    }
  }

  public record DailyActivity(LocalDate activityDate, long xpEarned) {}

  public record Page(List<DailyActivity> items, boolean hasMore) {
    public Page {
      items = List.copyOf(items);
    }
  }
}
