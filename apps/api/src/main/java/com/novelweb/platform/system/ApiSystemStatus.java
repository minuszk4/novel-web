package com.novelweb.platform.system;

public record ApiSystemStatus(
        String apiVersion,
        String environment,
        Integrations integrations,
        Features features) {

    public record Integrations(
            boolean googleOidcEnabled,
            boolean momoTestEnabled,
            String momoEnvironment) {
    }

    public record Features(
            boolean importProcessingEnabled,
            boolean cultivationEnabled,
            boolean commerceSandboxEnabled) {
    }
}
