package com.novelweb.platform;

import static org.hamcrest.Matchers.matchesPattern;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "novel.auth.google.firebase-api-key=",
      "novel.auth.google.firebase-auth-domain=",
      "novel.auth.google.firebase-project-id=",
      "novel.auth.google.firebase-app-id=",
      "novel.auth.google.rate-limit-hmac-key=",
      "novel.payment.vnpay.test.enabled=false"
    })
@AutoConfigureMockMvc
class ApiFoundationTests {

  private static final String ALLOWED_ORIGIN = "http://localhost:4200";
  private static final String API_CONTENT_SECURITY_POLICY =
      "default-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'; object-src 'none'";

  @Autowired private MockMvc mockMvc;

  @Autowired private Environment environment;

  @Autowired private ApplicationContext applicationContext;

  @Test
  void statusIsPublicAndKeepsUnimplementedCapabilitiesDisabled() throws Exception {
    mockMvc
        .perform(get("/api/v1/system/status").accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(
            header()
                .string(
                    "X-Correlation-Id",
                    matchesPattern(
                        "[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")))
        .andExpect(header().string("Content-Security-Policy", API_CONTENT_SECURITY_POLICY))
        .andExpect(header().string("X-Frame-Options", "DENY"))
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andExpect(header().string("Referrer-Policy", "no-referrer"))
        .andExpect(jsonPath("$.apiVersion").value("v1"))
        .andExpect(jsonPath("$.environment").value("local"))
        .andExpect(jsonPath("$.integrations.googleOidcEnabled").value(false))
        .andExpect(jsonPath("$.integrations.momoTestEnabled").value(false))
        .andExpect(jsonPath("$.integrations.momoEnvironment").value("OFF"))
        .andExpect(jsonPath("$.integrations.vnpayTestEnabled").value(false))
        .andExpect(jsonPath("$.integrations.vnpayEnvironment").value("OFF"))
        .andExpect(jsonPath("$.features.importProcessingEnabled").value(false))
        .andExpect(jsonPath("$.features.cultivationEnabled").value(false))
        .andExpect(jsonPath("$.features.commerceSandboxEnabled").value(false));
    assertTrue(
        applicationContext
            .getBeansOfType(com.novelweb.payment.momo.MomoTestIpnController.class)
            .isEmpty());
  }

  @Test
  void healthIsPublic() throws Exception {
    mockMvc
        .perform(get("/actuator/health").accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Security-Policy", API_CONTENT_SECURITY_POLICY))
        .andExpect(header().string("X-Frame-Options", "DENY"))
        .andExpect(jsonPath("$.status").value("UP"));
  }

  @Test
  void actuatorMetricsAreNotExposedByTheDefaultApiProfile() throws Exception {
    var response = mockMvc.perform(get("/actuator/metrics")).andReturn().getResponse();

    assertTrue(response.getStatus() >= 400);
  }

  @Test
  void nonAllowlistedRouteIsDeniedWithSafeProblemAndCorrelationId() throws Exception {
    mockMvc
        .perform(get("/api/v1/private"))
        .andExpect(status().isForbidden())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(header().string("Content-Security-Policy", API_CONTENT_SECURITY_POLICY))
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andExpect(header().string("Referrer-Policy", "no-referrer"))
        .andExpect(header().exists("X-Correlation-Id"))
        .andExpect(jsonPath("$.status").value(403))
        .andExpect(jsonPath("$.code").value("FORBIDDEN"))
        .andExpect(jsonPath("$.correlationId").isNotEmpty());
  }

  @Test
  void firebaseGoogleBootstrapRouteFailsClosedWhenConfigurationIsUnavailable() throws Exception {
    var result = mockMvc.perform(get("/api/v1/auth/firebase/bootstrap")).andReturn();
    if (environment.acceptsProfiles(org.springframework.core.env.Profiles.of("postgres"))) {
      assertEquals(503, result.getResponse().getStatus());
      assertEquals("application/problem+json", result.getResponse().getContentType());
      assertTrue(result.getResponse().getContentAsString().contains("AUTH_PROVIDER_UNAVAILABLE"));
    } else {
      assertEquals(403, result.getResponse().getStatus());
      assertTrue(result.getResponse().getContentAsString().contains("FORBIDDEN"));
    }
  }

  @Test
  void corsAllowsConfiguredFrontendOriginForReadOnlyApi() throws Exception {
    mockMvc
        .perform(
            options("/api/v1/system/status")
                .header("Origin", ALLOWED_ORIGIN)
                .header("Access-Control-Request-Method", "GET"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED_ORIGIN))
        .andExpect(
            header()
                .string(
                    "Access-Control-Allow-Methods", org.hamcrest.Matchers.containsString("GET")))
        .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
  }

  @Test
  void corsReturnsExactOriginOnActualApiResponse() throws Exception {
    mockMvc
        .perform(get("/api/v1/system/status").header("Origin", ALLOWED_ORIGIN))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED_ORIGIN))
        .andExpect(header().string("Access-Control-Allow-Credentials", "true"))
        .andExpect(header().string("Vary", org.hamcrest.Matchers.containsString("Origin")));
  }

  @Test
  void corsPreflightAllowsSessionCsrfHeaderFromExactConfiguredOrigin() throws Exception {
    mockMvc
        .perform(
            options("/api/v1/auth/csrf")
                .header("Origin", ALLOWED_ORIGIN)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "X-CSRF-Token"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED_ORIGIN))
        .andExpect(header().string("Access-Control-Allow-Credentials", "true"))
        .andExpect(
            header()
                .string(
                    "Access-Control-Allow-Headers",
                    org.hamcrest.Matchers.containsString("X-CSRF-Token")))
        .andExpect(
            header()
                .string(
                    "Access-Control-Allow-Methods", org.hamcrest.Matchers.containsString("POST")));
  }

  @Test
  void corsDoesNotGrantUnconfiguredOrigins() throws Exception {
    mockMvc
        .perform(
            options("/api/v1/system/status")
                .header("Origin", "https://attacker.example")
                .header("Access-Control-Request-Method", "GET"))
        .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
  }

  @Test
  void corsRejectsUnconfiguredMethodsEvenForAnAllowedOrigin() throws Exception {
    mockMvc
        .perform(
            options("/api/v1/system/status")
                .header("Origin", ALLOWED_ORIGIN)
                .header("Access-Control-Request-Method", "TRACE"))
        .andExpect(status().isForbidden())
        .andExpect(header().doesNotExist("Access-Control-Allow-Methods"));
  }

  @Test
  void corsRejectsUnconfiguredRequestHeadersEvenForAnAllowedOrigin() throws Exception {
    mockMvc
        .perform(
            options("/api/v1/auth/csrf")
                .header("Origin", ALLOWED_ORIGIN)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "Authorization"))
        .andExpect(status().isForbidden())
        .andExpect(header().doesNotExist("Access-Control-Allow-Headers"));
  }

  @Test
  void actuatorCorsAllowsReadOnlyAccessWithoutCredentials() throws Exception {
    mockMvc
        .perform(
            options("/actuator/health")
                .header("Origin", ALLOWED_ORIGIN)
                .header("Access-Control-Request-Method", "GET"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED_ORIGIN))
        .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"))
        .andExpect(
            header()
                .string(
                    "Access-Control-Allow-Methods", org.hamcrest.Matchers.containsString("GET")));
  }
}
