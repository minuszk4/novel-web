package com.novelweb.progression.cultivation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CultivationServiceTests {

  private static final Instant FIXED_NOW = Instant.parse("2026-11-13T23:30:00Z");

  private final CultivationRepository repository = mock(CultivationRepository.class);
  private final CultivationService service =
      new CultivationService(repository, Clock.fixed(FIXED_NOW, ZoneOffset.UTC));

  @Test
  void derivesRankProgressAndResetFromThePinnedCatalogAndServerTime() {
    UUID userId = UUID.randomUUID();
    when(repository.find(userId)).thenReturn(snapshot(true, 650));

    CultivationService.CultivationState state = service.get(userId);

    assertEquals("qi-6", state.currentRank().key());
    assertEquals("Luyện Khí tầng 6", state.currentRank().name());
    assertEquals("qi-7", state.nextRank().key());
    assertEquals(50, state.xpToNextRank());
    assertEquals(0.5, state.progressRatio());
    assertEquals(Instant.parse("2026-11-14T00:00:00Z"), state.nextDailyResetAt());
    assertEquals("cultivation-v1", state.rankCatalogVersion());
  }

  @Test
  void acceptsOnlyExplicitBooleanSettingAndRejectsClientOwnedProgressValues() {
    UUID userId = UUID.randomUUID();
    CultivationService.SettingsRequest invalid = new CultivationService.SettingsRequest();
    invalid.setEnabled(true);
    invalid.captureUnknownField("totalXp", 999_999);

    assertThrows(
        CultivationService.InvalidCultivationSettingsException.class,
        () -> service.saveSettings(userId, invalid));
    assertThrows(
        CultivationService.InvalidCultivationSettingsException.class,
        () -> service.saveSettings(userId, new CultivationService.SettingsRequest()));
    verifyNoInteractions(repository);
  }

  @Test
  void sendsOnlyTheExplicitOptInOrPauseCommandToTheRepository() {
    UUID userId = UUID.randomUUID();
    when(repository.setEnabled(userId, true)).thenReturn(snapshot(true, 0));
    CultivationService.SettingsRequest request = new CultivationService.SettingsRequest();
    request.setEnabled(true);

    CultivationService.CultivationState state = service.saveSettings(userId, request);

    assertEquals(true, state.enabled());
    verify(repository).setEnabled(userId, true);
  }

  private static CultivationRepository.CultivationSnapshot snapshot(boolean enabled, long totalXp) {
    List<CultivationRepository.RankRecord> ranks =
        List.of(
            new CultivationRepository.RankRecord("mortal", "Nhập môn", "Phàm nhân", 0, 0),
            new CultivationRepository.RankRecord("qi-1", "Luyện Khí", "Luyện Khí tầng 1", 100, 1),
            new CultivationRepository.RankRecord("qi-2", "Luyện Khí", "Luyện Khí tầng 2", 200, 2),
            new CultivationRepository.RankRecord("qi-3", "Luyện Khí", "Luyện Khí tầng 3", 300, 3),
            new CultivationRepository.RankRecord("qi-4", "Luyện Khí", "Luyện Khí tầng 4", 400, 4),
            new CultivationRepository.RankRecord("qi-5", "Luyện Khí", "Luyện Khí tầng 5", 500, 5),
            new CultivationRepository.RankRecord("qi-6", "Luyện Khí", "Luyện Khí tầng 6", 600, 6),
            new CultivationRepository.RankRecord("qi-7", "Luyện Khí", "Luyện Khí tầng 7", 700, 7),
            new CultivationRepository.RankRecord("qi-8", "Luyện Khí", "Luyện Khí tầng 8", 800, 8),
            new CultivationRepository.RankRecord("qi-9", "Luyện Khí", "Luyện Khí tầng 9", 900, 9),
            new CultivationRepository.RankRecord("foundation", "Tứ đại cảnh", "Trúc Cơ", 1000, 10));
    return new CultivationRepository.CultivationSnapshot(
        enabled, 1, "cultivation-v1", "xp-v1", "vi-VN", 1, 120, totalXp, ranks);
  }
}
