package com.novelweb.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "management.server.port=0",
      "novel.auth.google.web-client-id=",
      "novel.auth.google.credential-callback-uri=",
      "novel.auth.google.rate-limit-hmac-key=",
      "novel.payment.momo.test.enabled=false"
    })
@ActiveProfiles("observability")
class ActuatorMetricsExposureTests {

  private static final String FRONTEND_ORIGIN = "http://localhost:4200";
  private static final String ROUTE_SENTINEL = "metric-sensitive-sentinel";

  @Autowired private Environment environment;

  private final HttpClient httpClient = HttpClient.newHttpClient();

  @LocalServerPort private int apiPort;

  @LocalManagementPort private int managementPort;

  @Test
  void metricsAreAvailableOnlyOnTheSeparateManagementPortWithoutFrontendCors() throws Exception {
    URI statusUri = URI.create("http://127.0.0.1:" + apiPort + "/api/v1/system/status");
    URI metricsUri = URI.create("http://127.0.0.1:" + managementPort + "/actuator/metrics");
    URI httpMetricsUri =
        URI.create("http://127.0.0.1:" + managementPort + "/actuator/metrics/http.server.requests");
    URI apiMetricsUri = URI.create("http://127.0.0.1:" + apiPort + "/actuator/metrics");
    URI privateRouteUri =
        URI.create(
            "http://127.0.0.1:"
                + apiPort
                + "/api/v1/works/11111111-1111-4111-8111-111111111111/reader-manifest?sourceText="
                + ROUTE_SENTINEL);

    HttpResponse<String> status = get(statusUri);
    HttpResponse<String> privateRoute = get(privateRouteUri);
    HttpResponse<String> metrics = get(metricsUri);
    HttpResponse<String> httpMetrics = get(httpMetricsUri);
    HttpResponse<String> apiMetrics = get(apiMetricsUri);

    assertEquals(200, status.statusCode());
    assertTrue(privateRoute.statusCode() >= 400 && privateRoute.statusCode() < 500);
    assertEquals(200, metrics.statusCode());
    assertTrue(metrics.body().contains("http.server.requests"));
    assertEquals(200, httpMetrics.statusCode());
    assertTrue(httpMetrics.body().contains("measurements"));
    assertFalse(httpMetrics.body().contains(ROUTE_SENTINEL));
    assertFalse(httpMetrics.body().contains("11111111-1111-4111-8111-111111111111"));
    assertTrue(apiMetrics.statusCode() >= 400 && apiMetrics.statusCode() < 500);

    HttpRequest preflightRequest =
        HttpRequest.newBuilder(metricsUri)
            .header("Origin", FRONTEND_ORIGIN)
            .header("Access-Control-Request-Method", "GET")
            .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
            .build();
    HttpResponse<String> preflight =
        httpClient.send(preflightRequest, HttpResponse.BodyHandlers.ofString());

    assertFalse(
        preflight.headers().firstValue(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN).isPresent());
    assertEquals("127.0.0.1", environment.getProperty("server.address"));
    assertEquals("127.0.0.1", environment.getProperty("management.server.address"));
  }

  private HttpResponse<String> get(URI uri) throws Exception {
    return httpClient.send(
        HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
  }
}
