package com.novelweb.payment.momo;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;

/** Narrow POST-only transport so the MoMo gateway client can be tested without network access. */
public interface MomoTestHttpTransport {

  Response post(URI endpoint, byte[] jsonBody, Duration timeout)
      throws IOException, InterruptedException;

  record Response(int statusCode, byte[] body) {

    public Response {
      body = body == null ? new byte[0] : body.clone();
    }

    @Override
    public byte[] body() {
      return body.clone();
    }
  }

  static MomoTestHttpTransport jdk() {
    return new JdkMomoTestHttpTransport();
  }
}

final class JdkMomoTestHttpTransport implements MomoTestHttpTransport {

  private static final int MAX_RESPONSE_BYTES = 65_536;
  private final java.net.http.HttpClient client =
      java.net.http.HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(10))
          .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
          .build();

  @Override
  public Response post(URI endpoint, byte[] jsonBody, Duration timeout)
      throws IOException, InterruptedException {
    Objects.requireNonNull(endpoint, "MoMo Test endpoint is required");
    Objects.requireNonNull(jsonBody, "JSON request body is required");
    Objects.requireNonNull(timeout, "request timeout is required");
    if (!MomoTestCreateUrlPolicy.allows(endpoint.toString())
        && !MomoTestQueryUrlPolicy.allows(endpoint.toString())) {
      throw new IllegalArgumentException("HTTP transport is restricted to the MoMo TEST API");
    }
    if (timeout.compareTo(Duration.ofSeconds(30)) < 0) {
      throw new IllegalArgumentException("MoMo TEST API timeout must be at least 30 seconds");
    }
    var request =
        java.net.http.HttpRequest.newBuilder(endpoint)
            .timeout(timeout)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json; charset=utf-8")
            .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(jsonBody))
            .build();
    var response = client.send(request, ignored -> new BoundedBodySubscriber());
    return new Response(response.statusCode(), response.body());
  }

  private static final class BoundedBodySubscriber
      implements java.net.http.HttpResponse.BodySubscriber<byte[]> {

    private final CompletableFuture<byte[]> body = new CompletableFuture<>();
    private final java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
    private Flow.Subscription subscription;
    private int receivedBytes;

    @Override
    public java.util.concurrent.CompletionStage<byte[]> getBody() {
      return body;
    }

    @Override
    public void onSubscribe(Flow.Subscription currentSubscription) {
      subscription = currentSubscription;
      subscription.request(1);
    }

    @Override
    public void onNext(List<ByteBuffer> buffers) {
      for (ByteBuffer buffer : buffers) {
        int remaining = buffer.remaining();
        if (remaining > MAX_RESPONSE_BYTES - receivedBytes) {
          subscription.cancel();
          body.completeExceptionally(new IOException("MoMo TEST response exceeds the limit"));
          return;
        }
        byte[] chunk = new byte[remaining];
        buffer.get(chunk);
        output.writeBytes(chunk);
        receivedBytes += remaining;
      }
      subscription.request(1);
    }

    @Override
    public void onError(Throwable failure) {
      body.completeExceptionally(failure);
    }

    @Override
    public void onComplete() {
      body.complete(output.toByteArray());
    }
  }
}
