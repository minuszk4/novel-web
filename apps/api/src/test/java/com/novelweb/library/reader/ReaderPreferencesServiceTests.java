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
        new ReaderPreferencesRepository.PreferencesRecord(1, "sepia", "sans", 24, 1.9, 840);
    when(repository.save(userId, expected)).thenReturn(expected);

    var saved =
        service.save(
            userId,
            new ReaderPreferencesService.SavePreferencesRequest(1, "sepia", "sans", 24, 1.9, 840));

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
                    2, "light", "serif", 20, 1.8, 720)));
    assertThrows(
        ReaderPreferencesService.InvalidPreferencesException.class,
        () ->
            service.save(
                userId,
                new ReaderPreferencesService.SavePreferencesRequest(
                    1, "sepia", "serif", 29, 1.8, 720)));
    assertThrows(
        ReaderPreferencesService.InvalidPreferencesException.class,
        () ->
            service.save(
                userId,
                new ReaderPreferencesService.SavePreferencesRequest(
                    1, "dark", "serif", 20, Double.NaN, 720)));
    assertThrows(
        ReaderPreferencesService.InvalidPreferencesException.class,
        () ->
            service.save(
                userId,
                new ReaderPreferencesService.SavePreferencesRequest(
                    1, "light", "sans", 20, 1.8, 559)));
  }

  @Test
  void rejectsUnknownPreferenceFields() {
    var request =
        new ReaderPreferencesService.SavePreferencesRequest(1, "light", "serif", 20, 1.8, 720);
    request.captureUnknownField("trackingId", "must-not-be-accepted");

    assertThrows(
        ReaderPreferencesService.InvalidPreferencesException.class,
        () -> service.save(UUID.randomUUID(), request));
  }
}
