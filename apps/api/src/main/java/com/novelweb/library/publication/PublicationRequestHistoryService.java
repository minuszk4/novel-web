package com.novelweb.library.publication;

import com.novelweb.library.LibraryCursorCodec;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Serves a private author's content-free publication request history. */
@Service
@Profile("postgres")
public class PublicationRequestHistoryService {

  private static final int MAX_PAGE_SIZE = 50;

  private final DataSource dataSource;
  private final LibraryCursorCodec cursorCodec;

  public PublicationRequestHistoryService(DataSource dataSource, LibraryCursorCodec cursorCodec) {
    this.dataSource = Objects.requireNonNull(dataSource, "data source is required");
    this.cursorCodec = Objects.requireNonNull(cursorCodec, "cursor codec is required");
  }

  public HistoryResult history(UUID ownerId, UUID workId, String rawCursor, int limit) {
    Objects.requireNonNull(ownerId, "owner id is required");
    Objects.requireNonNull(workId, "work id is required");
    if (limit < 1 || limit > MAX_PAGE_SIZE) return HistoryResult.invalidLimit();

    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      try {
        if (!lockOwnedAuthoredWorkForRead(connection, ownerId, workId)) {
          connection.commit();
          return HistoryResult.notFound();
        }

        LibraryCursorCodec.CursorContext context =
            LibraryCursorCodec.publicationRequestHistoryContext(ownerId, workId);
        LibraryCursorCodec.Position position = null;
        if (rawCursor != null) {
          try {
            cursorCodec.requireConfigured();
            position = cursorCodec.decode(rawCursor, context);
            if (position.rank() != 0) {
              connection.commit();
              return HistoryResult.invalidCursor();
            }
          } catch (LibraryCursorCodec.InvalidCursorException exception) {
            connection.commit();
            return HistoryResult.invalidCursor();
          } catch (LibraryCursorCodec.SigningUnavailableException exception) {
            connection.commit();
            return HistoryResult.cursorUnavailable();
          }
        }

        List<RequestHeader> selected =
            readRequests(connection, ownerId, workId, position, limit + 1);
        boolean hasMore = selected.size() > limit;
        List<RequestHeader> page =
            List.copyOf(selected.subList(0, Math.min(selected.size(), limit)));
        Map<UUID, List<HistoryEvent>> events = readEvents(connection, page);
        if (page.stream()
            .anyMatch(request -> events.getOrDefault(request.id(), List.of()).isEmpty())) {
          throw new SQLException("A publication request is missing its immutable submit event");
        }
        String nextCursor = null;
        if (hasMore) {
          RequestHeader last = page.get(page.size() - 1);
          try {
            cursorCodec.requireConfigured();
            nextCursor =
                cursorCodec.encode(
                    context, new LibraryCursorCodec.Position(0, last.submittedAt(), last.id()));
          } catch (LibraryCursorCodec.SigningUnavailableException exception) {
            connection.commit();
            return HistoryResult.cursorUnavailable();
          }
        }

        List<RequestTimeline> items =
            page.stream()
                .map(
                    request ->
                        new RequestTimeline(
                            request.requestVersion(),
                            request.state(),
                            request.submittedAt(),
                            events.getOrDefault(request.id(), List.of())))
                .toList();
        connection.commit();
        return HistoryResult.available(new HistoryPage(items, nextCursor));
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        throw exception;
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to load publication request history", exception);
    }
  }

  private static boolean lockOwnedAuthoredWorkForRead(
      Connection connection, UUID ownerId, UUID workId) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT 1
                  FROM users owner
                  JOIN works work ON work.owner_user_id = owner.id
                  JOIN libraries library
                    ON library.id = work.library_id AND library.owner_user_id = owner.id
                  JOIN authoring_manuscript_creations creation
                    ON creation.work_id = work.id AND creation.actor_user_id = owner.id
                 WHERE owner.id = ? AND owner.status = 'active'
                   AND work.id = ? AND work.source_kind = 'authored'
                   AND work.status = 'active' AND work.deleted_at IS NULL
                   AND library.deleted_at IS NULL AND library.workspace_id IS NULL
                 FOR SHARE OF owner, work, library, creation
                """)) {
      select.setObject(1, ownerId);
      select.setObject(2, workId);
      try (ResultSet row = select.executeQuery()) {
        return row.next();
      }
    }
  }

  private static List<RequestHeader> readRequests(
      Connection connection,
      UUID ownerId,
      UUID workId,
      LibraryCursorCodec.Position position,
      int fetchLimit)
      throws SQLException {
    String cursorPredicate =
        position == null
            ? ""
            : " AND (request.submitted_at < ? OR"
                + " (request.submitted_at = ? AND request.id < ?))";
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT request.id, request.request_version, request.submitted_at,
                       state.current_state
                  FROM publication_requests request
                  JOIN publication_request_current_state state
                    ON state.request_id = request.id
                 WHERE request.owner_user_id = ? AND request.work_id = ?
                """
                + cursorPredicate
                + " ORDER BY request.submitted_at DESC, request.id DESC LIMIT ?")) {
      int parameter = 1;
      select.setObject(parameter++, ownerId);
      select.setObject(parameter++, workId);
      if (position != null) {
        OffsetDateTime cursorTime = OffsetDateTime.ofInstant(position.createdAt(), ZoneOffset.UTC);
        select.setObject(parameter++, cursorTime);
        select.setObject(parameter++, cursorTime);
        select.setObject(parameter++, position.itemId());
      }
      select.setInt(parameter, fetchLimit);
      try (ResultSet rows = select.executeQuery()) {
        List<RequestHeader> requests = new ArrayList<>();
        while (rows.next()) {
          requests.add(
              new RequestHeader(
                  rows.getObject("id", UUID.class),
                  rows.getInt("request_version"),
                  requireState(rows.getString("current_state")),
                  rows.getObject("submitted_at", OffsetDateTime.class).toInstant()));
        }
        return List.copyOf(requests);
      }
    }
  }

  private static Map<UUID, List<HistoryEvent>> readEvents(
      Connection connection, List<RequestHeader> requests) throws SQLException {
    if (requests.isEmpty()) return Map.of();
    UUID[] requestIds = requests.stream().map(RequestHeader::id).toArray(UUID[]::new);
    Array requestIdArray = connection.createArrayOf("uuid", requestIds);
    try {
      try (PreparedStatement select =
          connection.prepareStatement(
              """
                  SELECT request_id, event_sequence, actor_role_code, action_code, from_state,
                         to_state, reason_code, created_at
                    FROM publication_review_events
                   WHERE request_id = ANY (?)
                   ORDER BY request_id, event_sequence ASC
                  """)) {
        select.setArray(1, requestIdArray);
        try (ResultSet rows = select.executeQuery()) {
          Map<UUID, List<HistoryEvent>> events = new HashMap<>();
          while (rows.next()) {
            UUID requestId = rows.getObject("request_id", UUID.class);
            events
                .computeIfAbsent(requestId, ignored -> new ArrayList<>())
                .add(
                    new HistoryEvent(
                        rows.getInt("event_sequence"),
                        actorType(rows.getString("actor_role_code")),
                        requireAction(rows.getString("action_code")),
                        rows.getString("from_state"),
                        requireState(rows.getString("to_state")),
                        requireReason(rows.getString("reason_code")),
                        rows.getObject("created_at", OffsetDateTime.class).toInstant()));
          }
          Map<UUID, List<HistoryEvent>> immutable = new HashMap<>();
          events.forEach(
              (requestId, requestEvents) -> immutable.put(requestId, List.copyOf(requestEvents)));
          return Map.copyOf(immutable);
        }
      }
    } finally {
      requestIdArray.free();
    }
  }

  private static String actorType(String roleCode) throws SQLException {
    return switch (roleCode) {
      case "author" -> "OWNER";
      case "publication_reviewer" -> "REVIEWER";
      default -> throw new SQLException("Stored publication actor role is invalid");
    };
  }

  private static String requireAction(String actionCode) throws SQLException {
    return switch (actionCode) {
      case "SUBMITTED", "NEEDS_INFORMATION", "APPROVED", "REJECTED", "SUPERSEDED", "WITHDRAWN" ->
          actionCode;
      default -> throw new SQLException("Stored publication event action is invalid");
    };
  }

  private static String requireState(String state) throws SQLException {
    return switch (state) {
      case "PENDING_REVIEW",
          "NEEDS_INFORMATION",
          "APPROVED_UNPUBLISHED",
          "REJECTED",
          "SUPERSEDED",
          "WITHDRAWN" ->
          state;
      default -> throw new SQLException("Stored publication request state is invalid");
    };
  }

  private static String requireReason(String reasonCode) throws SQLException {
    if (reasonCode == null) return null;
    return switch (reasonCode) {
      case "INCOMPLETE_CLAIM",
          "MORE_INFORMATION",
          "SCOPE_UNCLEAR",
          "RIGHTS_REVIEWED",
          "SCOPE_CONFIRMED",
          "RIGHTS_NOT_VERIFIED",
          "OUT_OF_SCOPE",
          "POLICY_MISMATCH" ->
          reasonCode;
      default -> throw new SQLException("Stored publication event reason is invalid");
    };
  }

  private static void rollback(Connection connection, Exception original) {
    try {
      connection.rollback();
    } catch (SQLException rollbackException) {
      original.addSuppressed(rollbackException);
    }
  }

  private record RequestHeader(UUID id, int requestVersion, String state, Instant submittedAt) {}

  public record HistoryEvent(
      int sequence,
      String actorType,
      String actionCode,
      String fromState,
      String toState,
      String reasonCode,
      Instant createdAt) {}

  public record RequestTimeline(
      int requestVersion, String state, Instant submittedAt, List<HistoryEvent> events) {
    public RequestTimeline {
      events = List.copyOf(events);
    }
  }

  public record HistoryPage(List<RequestTimeline> items, String nextCursor) {
    public HistoryPage {
      items = List.copyOf(items);
    }
  }

  public enum Outcome {
    AVAILABLE,
    NOT_FOUND,
    INVALID_LIMIT,
    INVALID_CURSOR,
    CURSOR_UNAVAILABLE
  }

  public record HistoryResult(Outcome outcome, HistoryPage page) {
    static HistoryResult available(HistoryPage page) {
      return new HistoryResult(Outcome.AVAILABLE, page);
    }

    static HistoryResult notFound() {
      return new HistoryResult(Outcome.NOT_FOUND, null);
    }

    static HistoryResult invalidLimit() {
      return new HistoryResult(Outcome.INVALID_LIMIT, null);
    }

    static HistoryResult invalidCursor() {
      return new HistoryResult(Outcome.INVALID_CURSOR, null);
    }

    static HistoryResult cursorUnavailable() {
      return new HistoryResult(Outcome.CURSOR_UNAVAILABLE, null);
    }
  }
}
