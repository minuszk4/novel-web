package com.novelweb.library.publication;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.novelweb.library.publication.PublicationScheduleRepository.ScheduleClaim;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class PublicationScheduleServiceLogTests {

  private static final String PRIVATE_SENTINEL = "scheduled-work-detail-ae712f";

  @Test
  void scheduledReleaseFailureLogsOnlyGenericLeaseWarning(CapturedOutput output) {
    PublicationReleaseService releaseService = mock(PublicationReleaseService.class);
    PublicationScheduleRepository repository = mock(PublicationScheduleRepository.class);
    ScheduleClaim claim =
        new ScheduleClaim(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "\"readiness-v4\"",
            UUID.randomUUID());
    when(repository.claimDueBatch()).thenReturn(List.of(claim));
    when(releaseService.publishNow(any(), any(), anyString(), anyString()))
        .thenThrow(new IllegalStateException("Private database details " + PRIVATE_SENTINEL));

    PublicationScheduleService service = new PublicationScheduleService(releaseService, repository);

    service.processDueBatch();

    String logs = output.getAll();
    assertTrue(
        logs.contains("Scheduled publication attempt will retry after its database lease expires"));
    assertFalse(logs.contains(PRIVATE_SENTINEL));
    assertFalse(logs.contains("Private database details"));
    assertFalse(logs.contains("IllegalStateException"));
    assertFalse(logs.contains("at com.novelweb"));
    assertFalse(logs.contains(claim.workId().toString()));
    assertFalse(logs.contains(claim.scheduleId().toString()));
  }
}
