package com.novelweb.library.publication;

import com.novelweb.library.publication.PublicationPurgeTargetRegistry.Target;
import com.novelweb.library.publication.PublicationPurgeTargetRegistry.TargetCode;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** POST-only, bounded transport for opaque work-level cache purge commands. */
public interface PublicationTakedownPurgeTransport {

  DeliveryResult purge(Target target, PurgeCommand command);

  static PublicationTakedownPurgeTransport jdk(ObjectMapper objectMapper) {
    return new JdkPublicationTakedownPurgeTransport(objectMapper);
  }

  record PurgeCommand(
      UUID eventId,
      UUID workId,
      TargetCode targetCode,
      String idempotencyKey,
      Instant requestedAt) {}

  record DeliveryResult(Outcome outcome) {
    public DeliveryResult {
      Objects.requireNonNull(outcome, "purge delivery outcome is required");
    }

    public static DeliveryResult acknowledged() {
      return new DeliveryResult(Outcome.ACKNOWLEDGED);
    }

    public static DeliveryResult remoteRejected() {
      return new DeliveryResult(Outcome.REMOTE_REJECTED);
    }

    public static DeliveryResult transportFailed() {
      return new DeliveryResult(Outcome.TRANSPORT_FAILED);
    }

    public static DeliveryResult interrupted() {
      return new DeliveryResult(Outcome.INTERRUPTED);
    }
  }

  enum Outcome {
    ACKNOWLEDGED,
    REMOTE_REJECTED,
    TRANSPORT_FAILED,
    INTERRUPTED
  }
}

final class JdkPublicationTakedownPurgeTransport implements PublicationTakedownPurgeTransport {

  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
  private final ObjectMapper objectMapper;
  private final HttpClient client =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(3))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  JdkPublicationTakedownPurgeTransport(ObjectMapper objectMapper) {
    this.objectMapper = Objects.requireNonNull(objectMapper, "object mapper is required");
  }

  @Override
  public DeliveryResult purge(Target target, PurgeCommand command) {
    Objects.requireNonNull(target, "purge target is required");
    Objects.requireNonNull(command, "purge command is required");
    try {
      byte[] body =
          objectMapper.writeValueAsBytes(
              Map.of(
                  "eventId", command.eventId().toString(),
                  "workId", command.workId().toString(),
                  "targetCode", command.targetCode().name(),
                  "requestedAt", command.requestedAt().toString()));
      HttpRequest request =
          HttpRequest.newBuilder(target.endpoint())
              .timeout(REQUEST_TIMEOUT)
              .header("Accept", "application/json")
              .header("Content-Type", "application/json; charset=utf-8")
              .header("Authorization", "Bearer " + target.bearerToken())
              .header("Idempotency-Key", command.idempotencyKey())
              .POST(HttpRequest.BodyPublishers.ofByteArray(body))
              .build();
      HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
      return response.statusCode() == 200 || response.statusCode() == 204
          ? DeliveryResult.acknowledged()
          : DeliveryResult.remoteRejected();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      return DeliveryResult.interrupted();
    } catch (IOException
        | JacksonException
        | IllegalArgumentException
        | CompletionException exception) {
      return DeliveryResult.transportFailed();
    }
  }
}
