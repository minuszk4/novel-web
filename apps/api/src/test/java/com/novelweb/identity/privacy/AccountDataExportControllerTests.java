package com.novelweb.identity.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.novelweb.identity.auth.PolicyService;
import com.novelweb.identity.auth.PolicyService.RequiredAcceptance;
import com.novelweb.identity.privacy.AccountDataExportRepository.CreateOutcome;
import com.novelweb.identity.privacy.AccountDataExportRepository.CreateResult;
import com.novelweb.identity.privacy.AccountDataExportRepository.ExportJob;
import com.novelweb.identity.privacy.AccountDataExportRepository.ExportStatus;
import com.novelweb.identity.session.SessionCookieFactory;
import com.novelweb.identity.session.SessionService;
import jakarta.servlet.http.Cookie;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class AccountDataExportControllerTests {

  private static final UUID ACTOR_ID = UUID.randomUUID();
  private static final UUID EXPORT_ID = UUID.randomUUID();
  private static final String SESSION_TOKEN = "session-token-never-returned";
  private static final String IDEMPOTENCY_KEY = "account-export-key-0001";

  @Test
  void returnsUnavailableBeforeLookingUpPolicyWhenDisabled() {
    var exportService = mock(AccountDataExportService.class);
    var sessionService = mock(SessionService.class);
    var policyService = mock(PolicyService.class);
    var controller = controller(false, exportService, sessionService, policyService);
    var response = controller.create(authentication(), IDEMPOTENCY_KEY, requestWithSession());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    org.mockito.Mockito.verifyNoInteractions(exportService, sessionService, policyService);
  }

  @Test
  void requiresCurrentPoliciesAndRecentSessionAuthentication() {
    var enabledService = mock(AccountDataExportService.class);
    var sessionService = mock(SessionService.class);
    var policyService = mock(PolicyService.class);
    when(enabledService.isAvailable()).thenReturn(true);
    when(policyService.requiredAcceptance(ACTOR_ID)).thenReturn(RequiredAcceptance.COMPLETE);
    when(sessionService.wasCreatedWithin(SESSION_TOKEN, ACTOR_ID, Duration.ofMinutes(10)))
        .thenReturn(false);
    var controller = controller(true, enabledService, sessionService, policyService);

    var response = controller.create(authentication(), IDEMPOTENCY_KEY, requestWithSession());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(((ProblemDetail) response.getBody()).getProperties())
        .containsEntry("code", "RECENT_AUTHENTICATION_REQUIRED");
    verify(enabledService, never()).createOrReplay(ACTOR_ID, IDEMPOTENCY_KEY);
  }

  @Test
  void queuesAnExportOnlyForTheFreshlyAuthenticatedAcceptedActor() {
    var enabledService = mock(AccountDataExportService.class);
    var sessionService = mock(SessionService.class);
    var policyService = mock(PolicyService.class);
    when(enabledService.isAvailable()).thenReturn(true);
    when(policyService.requiredAcceptance(ACTOR_ID)).thenReturn(RequiredAcceptance.COMPLETE);
    when(sessionService.wasCreatedWithin(SESSION_TOKEN, ACTOR_ID, Duration.ofMinutes(10)))
        .thenReturn(true);
    when(enabledService.createOrReplay(ACTOR_ID, IDEMPOTENCY_KEY))
        .thenReturn(
            new CreateResult(
                CreateOutcome.CREATED,
                new ExportJob(
                    EXPORT_ID,
                    ACTOR_ID,
                    ExportStatus.QUEUED,
                    null,
                    null,
                    null,
                    Instant.parse("2026-11-18T00:00:00Z"),
                    null,
                    null,
                    null,
                    null)));
    var controller = controller(true, enabledService, sessionService, policyService);

    var response = controller.create(authentication(), IDEMPOTENCY_KEY, requestWithSession());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(response.getHeaders().getCacheControl()).contains("no-store");
    verify(sessionService).wasCreatedWithin(SESSION_TOKEN, ACTOR_ID, Duration.ofMinutes(10));
    verify(enabledService).createOrReplay(ACTOR_ID, IDEMPOTENCY_KEY);
  }

  @Test
  void masksAnotherActorsExportAsNotFound() {
    var enabledService = mock(AccountDataExportService.class);
    when(enabledService.isAvailable()).thenReturn(true);
    when(enabledService.status(ACTOR_ID, EXPORT_ID)).thenReturn(java.util.Optional.empty());
    var controller =
        controller(true, enabledService, mock(SessionService.class), mock(PolicyService.class));

    var response = controller.status(authentication(), EXPORT_ID, requestWithSession());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getHeaders().getCacheControl()).contains("no-store");
  }

  @Test
  void restoresLatestExportWithoutExposingAnotherActorsData() {
    var enabledService = mock(AccountDataExportService.class);
    when(enabledService.isAvailable()).thenReturn(true);
    when(enabledService.latest(ACTOR_ID)).thenReturn(java.util.Optional.empty());
    var controller =
        controller(true, enabledService, mock(SessionService.class), mock(PolicyService.class));

    var response = controller.latest(authentication(), requestWithSession());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getHeaders().getCacheControl()).contains("no-store");
    verify(enabledService).latest(ACTOR_ID);
  }

  @Test
  void servesOnlyAReadyOwnerArtifactWithSafeDownloadHeaders(@TempDir Path directory)
      throws Exception {
    var enabledService = mock(AccountDataExportService.class);
    when(enabledService.isAvailable()).thenReturn(true);
    when(enabledService.status(ACTOR_ID, EXPORT_ID))
        .thenReturn(java.util.Optional.of(readyJob(Instant.now().plusSeconds(600))));
    Path artifact = directory.resolve("synthetic-export.zip");
    Files.write(artifact, new byte[] {1, 2, 3});
    when(enabledService.downloadableArtifact(ACTOR_ID, EXPORT_ID))
        .thenReturn(java.util.Optional.of(artifact));
    var controller =
        controller(true, enabledService, mock(SessionService.class), mock(PolicyService.class));

    var response = controller.download(authentication(), EXPORT_ID, requestWithSession());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getCacheControl()).contains("no-store");
    assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
    assertThat(response.getHeaders().getFirst("Referrer-Policy")).isEqualTo("no-referrer");
    assertThat(response.getHeaders().getFirst("Content-Disposition"))
        .contains("novel-web-account-data.zip");
    assertThat(response.getHeaders().getContentLength()).isEqualTo(3);
    verify(enabledService).downloadableArtifact(ACTOR_ID, EXPORT_ID);
  }

  @Test
  void deniesAnExpiredArtifactBeforeCheckingItsFile() {
    var enabledService = mock(AccountDataExportService.class);
    when(enabledService.isAvailable()).thenReturn(true);
    when(enabledService.status(ACTOR_ID, EXPORT_ID))
        .thenReturn(java.util.Optional.of(readyJob(Instant.now().minusSeconds(1))));
    var controller =
        controller(true, enabledService, mock(SessionService.class), mock(PolicyService.class));

    var response = controller.download(authentication(), EXPORT_ID, requestWithSession());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GONE);
    verify(enabledService, never()).downloadableArtifact(ACTOR_ID, EXPORT_ID);
  }

  private static ExportJob readyJob(Instant expiresAt) {
    return new ExportJob(
        EXPORT_ID,
        ACTOR_ID,
        ExportStatus.READY,
        UUID.randomUUID(),
        new byte[32],
        3L,
        Instant.now().minusSeconds(60),
        Instant.now().minusSeconds(30),
        Instant.now().minusSeconds(20),
        expiresAt,
        null);
  }

  private static AccountDataExportController controller(
      boolean enabled,
      AccountDataExportService exportService,
      SessionService sessionService,
      PolicyService policyService) {
    return new AccountDataExportController(
        exportService, sessionService, policyService, enabled, Duration.ofMinutes(10));
  }

  private static UsernamePasswordAuthenticationToken authentication() {
    return UsernamePasswordAuthenticationToken.authenticated(
        new SessionService.SessionPrincipal(ACTOR_ID, Instant.parse("2026-11-19T00:00:00Z")),
        null,
        java.util.List.of());
  }

  private static MockHttpServletRequest requestWithSession() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setCookies(new Cookie(SessionCookieFactory.COOKIE_NAME, SESSION_TOKEN));
    return request;
  }
}
