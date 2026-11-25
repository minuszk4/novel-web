package com.novelweb.library.reader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReaderPreferencesServiceTests {

  private final ReaderPreferencesRepository repository = mock(ReaderPreferencesRepository.class);
  private final ReaderPreferencesService service = new ReaderPreferencesService(repository);

  @Test
  void acceptsAndPersistsEverySupportedPreference() {
    UUID userId = UUID.randomUUID();
    var expected =
        new ReaderPreferencesRepository.PreferencesRecord(2, "sepia", "sans", 24, 1.9, 840, true);
    when(repository.save(userId, expected)).thenReturn(expected);

    var saved =
        service.save(
            userId,
            new ReaderPreferencesService.SavePreferencesRequest(
                2, "sepia", "sans", 24, 1.9, 840, true));

    assertEquals(expected, saved);
    verify(repository).save(userId, expected);
  }

  @Test
  void rejectsUnsupportedSchemaValuesAndOutOfRangeTypography() {
    UUID userId = UUID.randomUUID();
    assertThrows(
        ReaderPreferencesService.InvalidPreferencesException.class,
        () ->
            service.save(
                userId,
                new ReaderPreferencesService.SavePreferencesRequest(
                    1, "light", "serif", 20, 1.8, 720, false)));
    assertThrows(
        ReaderPreferencesService.InvalidPreferencesException.class,
        () ->
            service.save(
                userId,
                new ReaderPreferencesService.SavePreferencesRequest(
                    2, "sepia", "serif", 29, 1.8, 720, false)));
    assertThrows(
        ReaderPreferencesService.InvalidPreferencesException.class,
        () ->
            service.save(
                userId,
                new ReaderPreferencesService.SavePreferencesRequest(
                    2, "dark", "serif", 20, Double.NaN, 720, false)));
    assertThrows(
        ReaderPreferencesService.InvalidPreferencesException.class,
        () ->
            service.save(
                userId,
                new ReaderPreferencesService.SavePreferencesRequest(
                    2, "light", "sans", 20, 1.8, 559, false)));
  }

  @Test
  void rejectsUnknownPreferenceFields() {
    var request =
        new ReaderPreferencesService.SavePreferencesRequest(
            2, "light", "serif", 20, 1.8, 720, false);
    request.captureUnknownField("trackingId", "must-not-be-accepted");

    assertThrows(
        ReaderPreferencesService.InvalidPreferencesException.class,
        () -> service.save(UUID.randomUUID(), request));
  }

  @Test
  void requiresThePairedModePreferenceInSchemaVersionTwo() {
    var request =
        new ReaderPreferencesService.SavePreferencesRequest(
            2, "light", "serif", 20, 1.8, 720, null);

    assertThrows(
        ReaderPreferencesService.InvalidPreferencesException.class,
        () -> service.save(UUID.randomUUID(), request));
  }
}
