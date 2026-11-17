package com.novelweb.library;

import java.util.Objects;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("postgres")
public class WorkSubscriptionService {

  private final WorkSubscriptionRepository repository;
  private final WorkSubscriptionRateLimiter rateLimiter;

  public WorkSubscriptionService(
      WorkSubscriptionRepository repository, WorkSubscriptionRateLimiter rateLimiter) {
    this.repository = Objects.requireNonNull(repository, "repository is required");
    this.rateLimiter = Objects.requireNonNull(rateLimiter, "rate limiter is required");
  }

  public WorkSubscriptionRateLimiter.Decision acquireMutationLimit(UUID actorUserId) {
    return rateLimiter.acquire(actorUserId);
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
