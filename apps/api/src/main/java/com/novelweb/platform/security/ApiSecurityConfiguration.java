package com.novelweb.platform.security;

import java.time.Duration;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration(proxyBeanMethods = false)
public class ApiSecurityConfiguration {

    @Bean
    SecurityFilterChain apiSecurityFilterChain(
            HttpSecurity http, ApiProblemWriter problemWriter, Environment environment) throws Exception {
        return http
                .cors(Customizer.withDefaults())
                .csrf(csrf -> {
                    if (environment.acceptsProfiles(Profiles.of("postgres"))) {
                        csrf.ignoringRequestMatchers("/api/v1/auth/google/credential");
                    }
                })
                .authorizeHttpRequests(authorize -> {
                    authorize.requestMatchers(HttpMethod.GET, "/api/v1/system/status", "/actuator/health",
                            "/actuator/health/**").permitAll();
                    if (environment.acceptsProfiles(Profiles.of("postgres"))) {
                        authorize.requestMatchers(HttpMethod.GET, "/api/v1/auth/google/bootstrap").permitAll();
                        authorize.requestMatchers(HttpMethod.POST, "/api/v1/auth/google/credential").permitAll();
                    }
                    authorize.anyRequest().denyAll();
                })
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, failure) -> problemWriter.write(
                                request, response, 403, "Forbidden", "FORBIDDEN",
                                "This endpoint is not available in the current API slice."))
                        .accessDeniedHandler((request, response, denied) -> problemWriter.write(
                                request, response, 403, "Forbidden", "FORBIDDEN",
                                "This endpoint is not available in the current API slice.")))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(CorsProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.allowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Accept", "Content-Type"));
        configuration.setExposedHeaders(List.of("X-Correlation-Id"));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(Duration.ofMinutes(30));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        source.registerCorsConfiguration("/actuator/**", configuration);
        return source;
    }
}
