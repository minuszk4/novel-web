package com.novelweb.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.novelweb.library.NotificationPreferencesRepository.Schedule;
import com.novelweb.library.NotificationPreferencesRepository.State;
import com.novelweb.library.NotificationPreferencesService.EmailDigestUnavailableException;
import com.novelweb.library.NotificationPreferencesService.InvalidPreferencesException;
import com.novelweb.library.NotificationPreferencesService.Update;
import java.time.LocalTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NotificationPreferencesServiceTests {

  private static final UUID ACTOR_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");

  @Test
  void validatesExplicitScheduleAndStoresOnlyDisabledPreferences() {
    NotificationPreferencesRepository repository = mock(NotificationPreferencesRepository.class);
    NotificationPreferencesService service = new NotificationPreferencesService(repository);
    Schedule schedule = new Schedule("WEEKLY", "Asia/Ho_Chi_Minh", LocalTime.of(8, 30));
    when(repository.save(ACTOR_ID, 0, schedule))
        .thenReturn(new State(true, false, "WEEKLY", "Asia/Ho_Chi_Minh", LocalTime.of(8, 30), 1));

    var view =
        service.update(ACTOR_ID, 0, new Update(false, "WEEKLY", "Asia/Ho_Chi_Minh", "08:30", null));

    assertThat(view.emailDigestAvailable()).isFalse();
    assertThat(view.emailDigestEnabled()).isFalse();
    assertThat(view.timeZone()).isEqualTo("Asia/Ho_Chi_Minh");
    assertThat(view.sendLocalTime()).isEqualTo("08:30");
    assertThat(view.version()).isEqualTo(1);
    verify(repository).save(ACTOR_ID, 0, schedule);
  }

  @Test
  void rejectsInvalidTimeZoneAndPartialScheduleWithoutPersistence() {
    NotificationPreferencesRepository repository = mock(NotificationPreferencesRepository.class);
    NotificationPreferencesService service = new NotificationPreferencesService(repository);

    assertThatThrownBy(
            () ->
                service.update(
                    ACTOR_ID, 0, new Update(false, "DAILY", "Mars/Olympus", "08:30", null)))
        .isInstanceOf(InvalidPreferencesException.class);
    assertThatThrownBy(
            () -> service.update(ACTOR_ID, 0, new Update(false, "DAILY", null, "08:30", null)))
        .isInstanceOf(InvalidPreferencesException.class);
    verifyNoInteractions(repository);
  }

  @Test
  void refusesEmailEnablementBeforeItCanBeHonored() {
    NotificationPreferencesRepository repository = mock(NotificationPreferencesRepository.class);
    NotificationPreferencesService service = new NotificationPreferencesService(repository);

    assertThatThrownBy(
            () ->
                service.update(
                    ACTOR_ID,
                    0,
                    new Update(true, "WEEKLY", "Asia/Ho_Chi_Minh", "08:30", "digest-consent-v1")))
        .isInstanceOf(EmailDigestUnavailableException.class);
    verifyNoInteractions(repository);
  }
}
