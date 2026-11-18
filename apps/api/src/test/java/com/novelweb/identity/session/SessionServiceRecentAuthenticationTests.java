package com.novelweb.identity.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class SessionServiceRecentAuthenticationTests {

  private static final String SESSION_TOKEN = "A".repeat(43);
  private static final Duration MAX_AGE = Duration.ofMinutes(5);

  @Test
  void requiresPositiveRecentAuthenticationWindow() {
    DataSource dataSource = mock(DataSource.class);
    SessionService service = service(dataSource);

    assertThatIllegalArgumentException()
        .isThrownBy(
            () -> service.wasCreatedWithin(SESSION_TOKEN, UUID.randomUUID(), Duration.ZERO));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                service.wasCreatedWithin(SESSION_TOKEN, UUID.randomUUID(), Duration.ofSeconds(-1)));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () -> service.wasCreatedWithin(SESSION_TOKEN, UUID.randomUUID(), Duration.ofDays(91)));
    verifyNoInteractions(dataSource);
  }

  @Test
  void rejectsMalformedTokenWithoutQueryingTheSessionStore() {
    DataSource dataSource = mock(DataSource.class);

    assertThat(service(dataSource).wasCreatedWithin("not-a-session", UUID.randomUUID(), MAX_AGE))
        .isFalse();
    verifyNoInteractions(dataSource);
  }

  @Test
  void bindsOnlyTheSessionHashExpectedActorAndConfiguredWindows() throws Exception {
    DataSource dataSource = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    ResultSet rows = mock(ResultSet.class);
    UUID actorId = UUID.randomUUID();
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.prepareStatement(anyString())).thenReturn(statement);
    when(statement.executeQuery()).thenReturn(rows);
    when(rows.next()).thenReturn(true);
    when(rows.getBoolean(1)).thenReturn(true);

    boolean recent = service(dataSource).wasCreatedWithin(SESSION_TOKEN, actorId, MAX_AGE);

    assertThat(recent).isTrue();
    verify(statement).setBytes(1, SessionService.hashSessionToken(SESSION_TOKEN));
    verify(statement).setObject(2, actorId);
    verify(statement).setLong(3, MAX_AGE.toMillis());
    verify(statement).setLong(4, Duration.ofMinutes(7).toMillis());
  }

  @Test
  void returnsFalseForRevokedExpiredIdleOrWrongActorSessions() throws Exception {
    DataSource dataSource = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    ResultSet rows = mock(ResultSet.class);
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.prepareStatement(anyString())).thenReturn(statement);
    when(statement.executeQuery()).thenReturn(rows);
    when(rows.next()).thenReturn(true);
    when(rows.getBoolean(1)).thenReturn(false);

    assertThat(service(dataSource).wasCreatedWithin(SESSION_TOKEN, UUID.randomUUID(), MAX_AGE))
        .isFalse();
  }

  @Test
  void failsClosedWhenTheSessionStoreCannotBeChecked() throws Exception {
    DataSource dataSource = mock(DataSource.class);
    when(dataSource.getConnection()).thenThrow(new SQLException("offline"));

    assertThatThrownBy(
            () -> service(dataSource).wasCreatedWithin(SESSION_TOKEN, UUID.randomUUID(), MAX_AGE))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Unable to verify recent account authentication");
  }

  @Test
  void rotationPreservesOriginalAuthenticationTime() throws Exception {
    DataSource dataSource = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    PreparedStatement select = mock(PreparedStatement.class);
    PreparedStatement revoke = mock(PreparedStatement.class);
    PreparedStatement insert = mock(PreparedStatement.class);
    ResultSet rows = mock(ResultSet.class);
    UUID actorId = UUID.randomUUID();
    Instant authenticatedAt = Instant.parse("2026-11-18T00:00:00Z");
    Instant expiresAt = authenticatedAt.plus(Duration.ofDays(30));
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.prepareStatement(anyString()))
        .thenAnswer(
            invocation -> {
              String sql = invocation.getArgument(0);
              if (sql.contains("SELECT s.user_id, s.expires_at, s.authenticated_at")) {
                return select;
              }
              if (sql.startsWith("UPDATE sessions")) return revoke;
              if (sql.startsWith("INSERT INTO sessions")) return insert;
              throw new AssertionError("Unexpected SQL: " + sql);
            });
    when(select.executeQuery()).thenReturn(rows);
    when(rows.next()).thenReturn(true);
    when(rows.getObject("user_id", UUID.class)).thenReturn(actorId);
    when(rows.getObject("expires_at", OffsetDateTime.class))
        .thenReturn(OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC));
    when(rows.getObject("authenticated_at", OffsetDateTime.class))
        .thenReturn(OffsetDateTime.ofInstant(authenticatedAt, ZoneOffset.UTC));
    when(revoke.executeUpdate()).thenReturn(1);
    when(insert.executeUpdate()).thenReturn(1);

    var rotated = service(dataSource).rotate(SESSION_TOKEN);

    assertThat(rotated).isPresent();
    assertThat(rotated.orElseThrow().userId()).isEqualTo(actorId);
    verify(insert).setObject(4, OffsetDateTime.ofInstant(authenticatedAt, ZoneOffset.UTC));
  }

  private static SessionService service(DataSource dataSource) {
    return new SessionService(
        dataSource, Duration.ofDays(30), Duration.ofMinutes(7), Duration.ofMinutes(1));
  }
}
