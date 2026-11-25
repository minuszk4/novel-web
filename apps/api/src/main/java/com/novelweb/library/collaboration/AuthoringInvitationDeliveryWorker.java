package com.novelweb.library.collaboration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls the invitation outbox only when both operator gates are explicitly enabled. */
@Component
@Profile("postgres")
@ConditionalOnProperty(
    prefix = "novel.authoring.collaboration",
    name = {"enabled", "invitation-worker-enabled"},
    havingValue = "true")
public class AuthoringInvitationDeliveryWorker {

  private final AuthoringCollaborationService service;

  public AuthoringInvitationDeliveryWorker(AuthoringCollaborationService service) {
    this.service = service;
  }

  @Scheduled(fixedDelayString = "${novel.authoring.collaboration.poll-delay:PT15S}")
  public void poll() {
    service.deliverDueBatch();
  }
}
