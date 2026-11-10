package com.novelweb.library;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("postgres")
public class LibraryUserStateService {

  public static final int MAX_COLLECTIONS = LibraryUserStateRepository.MAX_COLLECTIONS;
  public static final int MAX_COLLECTION_NAME_CODE_POINTS = 40;

  private static final Set<String> READING_STATUSES =
      Set.of("reading", "want-to-read", "paused", "completed", "dropped");

  private final LibraryUserStateRepository repository;

  public LibraryUserStateService(LibraryUserStateRepository repository) {
    this.repository = repository;
  }

  public List<LibraryUserStateRepository.CollectionRecord> listCollections(UUID actorUserId) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    return repository.listCollections(actorUserId);
  }

  public LibraryUserStateRepository.CollectionRecord createCollection(
      UUID actorUserId, String requestedName) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    String name = requiredCollectionName(requestedName);
    return repository.createCollection(actorUserId, name);
  }

  public LibraryUserStateRepository.CollectionRecord deleteCollection(
      UUID actorUserId, UUID collectionId) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    Objects.requireNonNull(collectionId, "collection id is required");
    return repository.deleteCollection(actorUserId, collectionId);
  }

  public LibraryUserStateRepository.WorkStateRecord getWorkState(UUID actorUserId, UUID workId) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    Objects.requireNonNull(workId, "work id is required");
    return repository.findWorkState(actorUserId, workId);
  }

  public LibraryUserStateRepository.WorkStateRecord replaceWorkState(
      UUID actorUserId,
      UUID workId,
      long expectedVersion,
      String requestedStatus,
      List<UUID> requestedCollectionIds) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    Objects.requireNonNull(workId, "work id is required");
    if (expectedVersion < 0)
      throw new IllegalArgumentException("expectedVersion must be zero or greater");
    String status = normalizeStatus(requestedStatus);
    if (requestedCollectionIds == null || requestedCollectionIds.size() > MAX_COLLECTIONS) {
      throw new IllegalArgumentException("collectionIds must contain at most 12 collection IDs");
    }
    if (requestedCollectionIds.stream().anyMatch(Objects::isNull)) {
      throw new IllegalArgumentException("collectionIds must contain unique non-null IDs");
    }
    List<UUID> collectionIds = List.copyOf(requestedCollectionIds);
    if (new HashSet<>(collectionIds).size() != collectionIds.size()) {
      throw new IllegalArgumentException("collectionIds must contain unique non-null IDs");
    }
    return repository.replaceWorkState(actorUserId, workId, expectedVersion, status, collectionIds);
  }

  private static String requiredCollectionName(String requestedName) {
    String name = requestedName == null ? "" : requestedName.trim();
    int codePoints = name.codePointCount(0, name.length());
    if (codePoints < 1 || codePoints > MAX_COLLECTION_NAME_CODE_POINTS) {
      throw new IllegalArgumentException(
          "collection name must contain between 1 and 40 characters");
    }
    return name;
  }

  private static String normalizeStatus(String requestedStatus) {
    String status = requestedStatus == null ? "" : requestedStatus.trim().toLowerCase(Locale.ROOT);
    if (!READING_STATUSES.contains(status)) {
      throw new IllegalArgumentException(
          "readingStatus must be a supported personal reading status");
    }
    return status;
  }
}
