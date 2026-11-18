package com.novelweb.identity.privacy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Processes durable export jobs and removes artifacts only after their configured expiry. */
@Component
@Profile("postgres")
public class AccountDataExportWorker {

  private final AccountDataExportService exportService;
  private final boolean enabled;

  public AccountDataExportWorker(
      AccountDataExportService exportService,
      @Value("${novel.account-data-export.worker-enabled:false}") boolean enabled) {
    this.exportService = exportService;
    this.enabled = enabled;
  }

  @Scheduled(fixedDelayString = "${novel.account-data-export.poll-delay:30s}")
  public void processNext() {
    if (enabled) exportService.claimNext().ifPresent(exportService::generate);
  }

  @Scheduled(fixedDelayString = "${novel.account-data-export.cleanup-delay:5m}")
  public void cleanExpired() {
    if (enabled) exportService.cleanupExpiredArtifacts();
  }
}
