package com.novelweb.translation.jobs;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.novelweb.translation.provider.TranslationProviderException;
import com.novelweb.translation.provider.TranslationProviderFailure;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class TranslationFakeLocalWorkerPollerTests {

  private static final Duration LEASE_DURATION = Duration.ofSeconds(60);

  @Test
  void providerFailureLogsOnlyTheSafeFailureCategory(CapturedOutput output) throws Exception {
    TranslationFakeLocalWorkerService workerService = mock(TranslationFakeLocalWorkerService.class);
    TranslationProviderException failure =
        new TranslationProviderException(
            TranslationProviderFailure.RATE_LIMITED, Duration.ofSeconds(5));
    when(workerService.processNext(eq("worker-1"), eq(LEASE_DURATION))).thenThrow(failure);
    TranslationFakeLocalWorkerPoller poller =
        new TranslationFakeLocalWorkerPoller(workerService, "worker-1");

    poller.poll();

    String logOutput = output.getAll();
    assertTrue(logOutput.contains("category RATE_LIMITED"));
    assertFalse(logOutput.contains(failure.getMessage()));
    assertFalse(logOutput.contains("at com.novelweb"));
  }

  @Test
  void unexpectedWorkerFailureLogsTypeWithoutMessageOrStackTrace(CapturedOutput output)
      throws Exception {
    TranslationFakeLocalWorkerService workerService = mock(TranslationFakeLocalWorkerService.class);
    String privateSentinel = "provider-response-secret-a4810f2d";
    when(workerService.processNext(eq("worker-1"), eq(LEASE_DURATION)))
        .thenThrow(new IllegalStateException("Unsafe provider response " + privateSentinel));
    TranslationFakeLocalWorkerPoller poller =
        new TranslationFakeLocalWorkerPoller(workerService, "worker-1");

    poller.poll();

    String logOutput = output.getAll();
    assertTrue(logOutput.contains("internal failure type IllegalStateException"));
    assertFalse(logOutput.contains(privateSentinel));
    assertFalse(logOutput.contains("Unsafe provider response"));
    assertFalse(logOutput.contains("at com.novelweb"));
  }
}
