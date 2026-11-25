package com.novelweb.payment.vnpay;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;

/** Narrow POST-only transport restricted to the official VNPAY sandbox QueryDR endpoint. */
public interface VnPayTestQueryDrTransport {

  URI QUERY_ENDPOINT = URI.create("https://sandbox.vnpayment.vn/merchant_webapi/api/transaction");

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

  static VnPayTestQueryDrTransport jdk() {
    return new JdkVnPayTestQueryDrTransport();
  }
}

final class JdkVnPayTestQueryDrTransport implements VnPayTestQueryDrTransport {

  private static final int MAX_RESPONSE_BYTES = 65_536;
  private final HttpClient client =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(10))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  @Override
  public Response post(URI endpoint, byte[] jsonBody, Duration timeout)
      throws IOException, InterruptedException {
    Objects.requireNonNull(jsonBody, "JSON request body is required");
    Objects.requireNonNull(timeout, "request timeout is required");
    if (!QUERY_ENDPOINT.equals(endpoint) || timeout.compareTo(Duration.ofSeconds(30)) < 0) {
      throw new IllegalArgumentException("HTTP transport is restricted to VNPAY TEST QueryDR");
    }
    HttpRequest request =
        HttpRequest.newBuilder(endpoint)
            .timeout(timeout)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json; charset=utf-8")
            .POST(HttpRequest.BodyPublishers.ofByteArray(jsonBody))
            .build();
    HttpResponse<byte[]> response = client.send(request, ignored -> new BoundedBodySubscriber());
    return new Response(response.statusCode(), response.body());
  }

  private static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {

    private final CompletableFuture<byte[]> body = new CompletableFuture<>();
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
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
          body.completeExceptionally(new IOException("VNPAY TEST response exceeds the limit"));
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
