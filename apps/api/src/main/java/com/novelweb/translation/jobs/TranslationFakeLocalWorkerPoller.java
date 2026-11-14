package com.novelweb.translation.jobs;

import com.novelweb.translation.provider.TranslationProviderException;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Optional local poller; disabled unless its dedicated fake-worker property is enabled. */
@Component
@Profile("postgres & translation-fake")
@ConditionalOnProperty(
    prefix = "novel.translation.fake.worker",
    name = "enabled",
    havingValue = "true")
public class TranslationFakeLocalWorkerPoller {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(TranslationFakeLocalWorkerPoller.class);
  private static final Duration LEASE_DURATION = Duration.ofSeconds(60);

  private final TranslationFakeLocalWorkerService workerService;
  private final String workerId;

  public TranslationFakeLocalWorkerPoller(
      TranslationFakeLocalWorkerService workerService,
      @Value("${novel.translation.fake.worker.id:local-fake-worker-1}") String workerId) {
    this.workerService = workerService;
    this.workerId = workerId;
  }

  @Scheduled(fixedDelayString = "${novel.translation.fake.worker.poll-delay:2s}")
  public void poll() {
    try {
      workerService.processNext(workerId, LEASE_DURATION);
    } catch (TranslationProviderException exception) {
      LOGGER.warn("Offline fake translation attempt stopped with category {}", exception.failure());
    } catch (RuntimeException exception) {
      LOGGER.error(
          "Offline fake translation worker stopped after internal failure type {}",
          exception.getClass().getSimpleName());
    }
  }
}
