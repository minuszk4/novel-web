package com.novelweb.platform.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class CorsPropertiesTests {

  @Test
  void bindsCommaSeparatedEnvironmentOriginsAsExactEntries() {
    var source =
        new MapConfigurationPropertySource(
            Map.of(
                "novel.web.cors.allowed-origins",
                "https://reader.example.test,http://localhost:8088"));

    CorsProperties properties =
        new Binder(source)
            .bind("novel.web.cors", Bindable.of(CorsProperties.class))
            .orElseThrow(() -> new IllegalStateException("CORS origins did not bind."));

    assertEquals(
        List.of("https://reader.example.test", "http://localhost:8088"),
        properties.allowedOrigins());
  }
}
