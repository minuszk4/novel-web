package com.novelweb.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LibraryUserStateServiceTests {

    @Mock
    private LibraryUserStateRepository repository;

    @InjectMocks
    private LibraryUserStateService service;

    private UUID actorUserId;
    private UUID workId;

    @BeforeEach
    void setUp() {
        actorUserId = UUID.randomUUID();
        workId = UUID.randomUUID();
    }

    @Test
    void collectionNamesAreTrimmedAndLimitedByUnicodeCharacters() {
        UUID collectionId = UUID.randomUUID();
        LibraryUserStateRepository.CollectionRecord record = new LibraryUserStateRepository.CollectionRecord(
                collectionId, "Weekend Reads", 0, java.time.Instant.EPOCH, java.time.Instant.EPOCH);
        when(repository.createCollection(actorUserId, "Weekend Reads")).thenReturn(record);

        assertEquals(record, service.createCollection(actorUserId, "  Weekend Reads  "));
        verify(repository).createCollection(actorUserId, "Weekend Reads");
        assertThrows(IllegalArgumentException.class, () -> service.createCollection(actorUserId, "   "));
        assertThrows(IllegalArgumentException.class,
                () -> service.createCollection(actorUserId, "😀".repeat(41)));
    }

    @Test
    void replacementAcceptsOnlySupportedStatusesAndPassesExpectedVersion() {
        List<String> statuses = List.of("reading", "want-to-read", "paused", "completed", "dropped");
        when(repository.replaceWorkState(eq(actorUserId), eq(workId), eq(7L), anyString(), anyList()))
                .thenAnswer(invocation -> new LibraryUserStateRepository.WorkStateRecord(
                        workId, invocation.getArgument(3), invocation.getArgument(4), 8, java.time.Instant.EPOCH));

        for (String status : statuses) {
            LibraryUserStateRepository.WorkStateRecord saved = service.replaceWorkState(
                    actorUserId, workId, 7, status, List.of());
            assertEquals(status, saved.readingStatus());
        }
        verify(repository, org.mockito.Mockito.times(statuses.size()))
                .replaceWorkState(eq(actorUserId), eq(workId), eq(7L), anyString(), anyList());
        assertThrows(IllegalArgumentException.class,
                () -> service.replaceWorkState(actorUserId, workId, 7, "published", List.of()));
    }

    @Test
    void replacementRejectsInvalidVersionAndCollectionSetsBeforeStorage() {
        assertThrows(IllegalArgumentException.class,
                () -> service.replaceWorkState(actorUserId, workId, -1, "reading", List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> service.replaceWorkState(actorUserId, workId, 0, "reading", null));
        assertThrows(IllegalArgumentException.class,
                () -> service.replaceWorkState(actorUserId, workId, 0, "reading",
                        java.util.Collections.singletonList(null)));
        UUID duplicate = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class,
                () -> service.replaceWorkState(actorUserId, workId, 0, "reading", List.of(duplicate, duplicate)));
        assertThrows(IllegalArgumentException.class,
                () -> service.replaceWorkState(actorUserId, workId, 0, "reading",
                        java.util.stream.IntStream.range(0, 13).mapToObj(ignored -> UUID.randomUUID()).toList()));
    }
}
