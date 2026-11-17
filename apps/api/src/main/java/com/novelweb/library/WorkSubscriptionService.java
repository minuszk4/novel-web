package com.novelweb.library;

import java.util.Objects;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("postgres")
public class WorkSubscriptionService {

  private final WorkSubscriptionRepository repository;

  public WorkSubscriptionService(WorkSubscriptionRepository repository) {
    this.repository = Objects.requireNonNull(repository, "repository is required");
  }

  public WorkSubscriptionRepository.SubscriptionState get(UUID actorUserId, UUID workId) {
    return repository.find(actorUserId, workId);
  }

  public WorkSubscriptionRepository.SubscriptionState setNotifications(
      UUID actorUserId, UUID workId, Boolean enabled, long expectedVersion) {
    if (enabled == null) throw new IllegalArgumentException("notificationsEnabled is required");
    if (expectedVersion < 0) throw new IllegalArgumentException("subscription ETag is invalid");
    return repository.setNotifications(actorUserId, workId, enabled, expectedVersion);
  }

  public WorkSubscriptionRepository.SubscriptionState unfollow(
      UUID actorUserId, UUID workId, Long expectedVersion) {
    return repository.unfollow(actorUserId, workId, expectedVersion);
  }
}
