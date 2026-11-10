package com.novelweb.platform.security;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "novel.web.cors")
public record CorsProperties(List<String> allowedOrigins) {
}
