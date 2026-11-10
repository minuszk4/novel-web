package com.novelweb.platform;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ApiFoundationTests {

    private static final String ALLOWED_ORIGIN = "http://localhost:4200";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void statusIsPublicAndKeepsUnimplementedCapabilitiesDisabled() throws Exception {
        mockMvc.perform(get("/api/v1/system/status").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Correlation-Id", matchesPattern(
                        "[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")))
                .andExpect(jsonPath("$.apiVersion").value("v1"))
                .andExpect(jsonPath("$.environment").value("local"))
                .andExpect(jsonPath("$.integrations.googleOidcEnabled").value(false))
                .andExpect(jsonPath("$.integrations.momoTestEnabled").value(false))
                .andExpect(jsonPath("$.integrations.momoEnvironment").value("TEST"))
                .andExpect(jsonPath("$.features.importProcessingEnabled").value(false))
                .andExpect(jsonPath("$.features.cultivationEnabled").value(false))
                .andExpect(jsonPath("$.features.commerceSandboxEnabled").value(false));
    }

    @Test
    void healthIsPublic() throws Exception {
        mockMvc.perform(get("/actuator/health").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void nonAllowlistedRouteIsDeniedWithSafeProblemAndCorrelationId() throws Exception {
        mockMvc.perform(get("/api/v1/private"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(header().exists("X-Correlation-Id"))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    void plannedGoogleBootstrapRouteRemainsUnavailableUntilTheAuthFlowIsComplete() throws Exception {
        mockMvc.perform(get("/api/v1/auth/google/bootstrap"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void corsAllowsConfiguredFrontendOriginForReadOnlyApi() throws Exception {
        mockMvc.perform(options("/api/v1/system/status")
                        .header("Origin", ALLOWED_ORIGIN)
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED_ORIGIN))
                .andExpect(header().string("Access-Control-Allow-Methods", org.hamcrest.Matchers.containsString("GET")))
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
    }

    @Test
    void corsDoesNotGrantUnconfiguredOrigins() throws Exception {
        mockMvc.perform(options("/api/v1/system/status")
                        .header("Origin", "https://attacker.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
}
