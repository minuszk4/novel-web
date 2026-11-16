package com.novelweb.library.publication;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls the purge outbox only after an operator explicitly enables the worker. */
@Component
@Profile("postgres")
@ConditionalOnProperty(
    prefix = "novel.publication.purge",
    name = "worker-enabled",
    havingValue = "true")
public class PublicationTakedownPurgeWorker {

  private final PublicationTakedownPurgeService purgeService;

  public PublicationTakedownPurgeWorker(PublicationTakedownPurgeService purgeService) {
    this.purgeService = purgeService;
  }

  @Scheduled(fixedDelayString = "${novel.publication.purge.poll-delay:30s}")
  public void poll() {
    purgeService.processDueBatch();
  }
}
