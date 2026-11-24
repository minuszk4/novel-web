package com.novelweb.identity.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.novelweb.identity.privacy.AccountPrivacyPreferencesRepository.Preferences;
import com.novelweb.identity.session.SessionService;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import tools.jackson.databind.json.JsonMapper;

class AccountPrivacyPreferencesControllerTests {

  private static final UUID ACTOR_ID = UUID.randomUUID();

  @Test
  void getReturnsSafeDefaultsWithoutCreatingAStoredRow() {
    var repository = mock(AccountPrivacyPreferencesRepository.class);
    when(repository.find(ACTOR_ID)).thenReturn(new Preferences(false, false, 0, null));
    var controller = controller(repository);

    var response = controller.get(authentication(), request());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getETag()).isEqualTo("\"privacy-preferences-v0\"");
    assertThat(response.getHeaders().getCacheControl()).contains("no-store");
    assertThat(response.getBody()).isEqualTo(new Preferences(false, false, 0, null));
    verify(repository, never()).update(ACTOR_ID, false, false, 0);
  }

  @Test
  void putAcceptsBothIndependentValuesAndReturnsNextVersion() throws Exception {
    var repository = mock(AccountPrivacyPreferencesRepository.class);
    var saved = new Preferences(true, false, 1, Instant.parse("2026-11-25T00:00:00Z"));
    when(repository.update(ACTOR_ID, true, false, 0)).thenReturn(Optional.of(saved));
    var controller = controller(repository);

    var response =
        controller.put(
            authentication(),
            "\"privacy-preferences-v0\"",
            JsonMapper.builder()
                .build()
                .readTree("{\"analyticsEnabled\":true,\"contentProviderSharingEnabled\":false}"),
            request());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getETag()).isEqualTo("\"privacy-preferences-v1\"");
    assertThat(response.getBody()).isEqualTo(saved);
    verify(repository).update(ACTOR_ID, true, false, 0);
  }

  @Test
  void refusesMissingPreconditionBeforeReadingTheBodyOrWriting() throws Exception {
    var repository = mock(AccountPrivacyPreferencesRepository.class);
    var controller = controller(repository);

    var response =
        controller.put(
            authentication(),
            null,
            JsonMapper.builder()
                .build()
                .readTree("{\"analyticsEnabled\":true,\"contentProviderSharingEnabled\":false}"),
            request());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.valueOf(428));
    assertThat(((ProblemDetail) response.getBody()).getProperties())
        .containsEntry("code", "IF_MATCH_REQUIRED");
    verify(repository, never()).update(ACTOR_ID, true, false, 0);
  }

  @Test
  void rejectsUnknownAndNonBooleanFields() throws Exception {
    var repository = mock(AccountPrivacyPreferencesRepository.class);
    var controller = controller(repository);
    var mapper = JsonMapper.builder().build();

    var unknown =
        controller.put(
            authentication(),
            "\"privacy-preferences-v0\"",
            mapper.readTree(
                "{\"analyticsEnabled\":true,\"contentProviderSharingEnabled\":false,\"userId\":\"other\"}"),
            request());
    var wrongType =
        controller.put(
            authentication(),
            "\"privacy-preferences-v0\"",
            mapper.readTree(
                "{\"analyticsEnabled\":\"true\",\"contentProviderSharingEnabled\":false}"),
            request());

    assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(wrongType.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    verify(repository, never()).update(ACTOR_ID, true, false, 0);
  }

  @Test
  void unauthenticatedRequestsNeverResolveAnotherActor() {
    var repository = mock(AccountPrivacyPreferencesRepository.class);
    var controller = controller(repository);

    var response = controller.get(null, request());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    verify(repository, never()).find(ACTOR_ID);
  }

  private static AccountPrivacyPreferencesController controller(
      AccountPrivacyPreferencesRepository repository) {
    return new AccountPrivacyPreferencesController(repository);
  }

  private static UsernamePasswordAuthenticationToken authentication() {
    return UsernamePasswordAuthenticationToken.authenticated(
        new SessionService.SessionPrincipal(ACTOR_ID, Instant.parse("2026-11-25T00:00:00Z")),
        null,
        java.util.List.of());
  }

  private static MockHttpServletRequest request() {
    return new MockHttpServletRequest();
  }
}
