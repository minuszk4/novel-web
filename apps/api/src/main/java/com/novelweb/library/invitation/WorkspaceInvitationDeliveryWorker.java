package com.novelweb.library.invitation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls only when an operator explicitly enables invitation mail delivery. */
@Component
@Profile("postgres")
@ConditionalOnProperty(
    prefix = "novel.workspaces.invitations",
    name = {"enabled", "worker-enabled"},
    havingValue = "true")
public class WorkspaceInvitationDeliveryWorker {

  private final WorkspaceInvitationService service;

  public WorkspaceInvitationDeliveryWorker(WorkspaceInvitationService service) {
    this.service = service;
  }

  @Scheduled(fixedDelayString = "${novel.workspaces.invitations.poll-delay:PT15S}")
  public void poll() {
    service.deliverDueBatch();
  }
}
