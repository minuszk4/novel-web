package com.novelweb.library.publication;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls chapter publication notifications only after an operator enables in-app delivery. */
@Component
@Profile("postgres")
@ConditionalOnProperty(
    prefix = "novel.notifications.delivery",
    name = "enabled",
    havingValue = "true")
public class ChapterPublishedNotificationDeliveryWorker {

  private final ChapterPublishedNotificationDeliveryService deliveryService;

  public ChapterPublishedNotificationDeliveryWorker(
      ChapterPublishedNotificationDeliveryService deliveryService) {
    this.deliveryService = deliveryService;
  }

  @Scheduled(fixedDelayString = "${novel.notifications.delivery.poll-delay:PT10S}")
  public void poll() {
    deliveryService.processDueBatch();
  }
}
