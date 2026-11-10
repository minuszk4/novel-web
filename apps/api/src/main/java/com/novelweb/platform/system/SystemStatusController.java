package com.novelweb.platform.system;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system")
public class SystemStatusController {

    private final String environment;

    public SystemStatusController(@Value("${novel.environment:local}") String environment) {
        this.environment = environment;
    }

    @GetMapping("/status")
    public ApiSystemStatus status() {
        return new ApiSystemStatus(
                "v1",
                environment,
                new ApiSystemStatus.Integrations(false, false, "TEST"),
                new ApiSystemStatus.Features(false, false, false));
    }
}
