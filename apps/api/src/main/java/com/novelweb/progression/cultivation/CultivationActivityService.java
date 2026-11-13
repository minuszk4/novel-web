package com.novelweb.progression.cultivation;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("postgres")
public class CultivationActivityService {

  public static final int DEFAULT_PAGE_SIZE = 30;
  public static final int MAX_PAGE_SIZE = 100;

  private final CultivationActivityRepository repository;
  private final CultivationActivityCursorCodec cursorCodec;

  public CultivationActivityService(
      CultivationActivityRepository repository, CultivationActivityCursorCodec cursorCodec) {
    this.repository = repository;
    this.cursorCodec = cursorCodec;
  }

  public ActivityPage list(UUID actorUserId, String cursor, int limit) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    if (limit < 1 || limit > MAX_PAGE_SIZE) {
      throw new IllegalArgumentException("limit must be between 1 and " + MAX_PAGE_SIZE);
    }
    cursorCodec.requireConfigured();
    LocalDate beforeDate = cursor == null ? null : cursorCodec.decode(cursor, actorUserId);
    CultivationActivityRepository.Page page = repository.findRecent(actorUserId, beforeDate, limit);
    String nextCursor = null;
    if (page.hasMore() && !page.items().isEmpty()) {
      nextCursor = cursorCodec.encode(actorUserId, page.items().getLast().activityDate());
    }
    List<DailyActivity> items =
        page.items().stream()
            .map(activity -> new DailyActivity(activity.activityDate(), activity.xpEarned()))
            .toList();
    return new ActivityPage(items, nextCursor);
  }

  public record DailyActivity(LocalDate activityDate, long xpEarned) {}

  public record ActivityPage(List<DailyActivity> items, String nextCursor) {
    public ActivityPage {
      items = List.copyOf(items);
    }
  }
}
