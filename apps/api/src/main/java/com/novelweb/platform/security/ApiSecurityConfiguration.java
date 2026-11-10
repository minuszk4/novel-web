package com.novelweb.platform.security;

import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.novelweb.identity.session.SessionAuthenticationFilter;
import com.novelweb.identity.session.SessionBoundCsrfFilter;
import com.novelweb.identity.session.SessionCsrfTokenService;

@Configuration(proxyBeanMethods = false)
public class ApiSecurityConfiguration {

    @Bean
    SecurityFilterChain apiSecurityFilterChain(
            HttpSecurity http,
            ApiProblemWriter problemWriter,
            Environment environment,
            ObjectProvider<SessionAuthenticationFilter> sessionAuthenticationFilter,
            ObjectProvider<SessionBoundCsrfFilter> sessionBoundCsrfFilter) throws Exception {
        http.cors(Customizer.withDefaults());
        if (environment.acceptsProfiles(Profiles.of("postgres"))) {
            http.csrf(AbstractHttpConfigurer::disable);
            http.addFilterBefore(sessionAuthenticationFilter.getObject(), AnonymousAuthenticationFilter.class);
            http.addFilterBefore(sessionBoundCsrfFilter.getObject(), SessionAuthenticationFilter.class);
        }

        return http
                .authorizeHttpRequests(authorize -> {
                    authorize.requestMatchers(HttpMethod.GET, "/api/v1/system/status", "/actuator/health",
                            "/actuator/health/**").permitAll();
                    if (environment.acceptsProfiles(Profiles.of("postgres"))) {
                        authorize.requestMatchers(HttpMethod.GET, "/api/v1/libraries", "/api/v1/works").permitAll();
                        authorize.requestMatchers(HttpMethod.GET, "/api/v1/auth/google/bootstrap").permitAll();
                        authorize.requestMatchers(HttpMethod.POST, "/api/v1/auth/google/credential").permitAll();
                        authorize.requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf").permitAll();
                        authorize.requestMatchers(HttpMethod.GET, "/api/v1/auth/me").permitAll();
                        authorize.requestMatchers(HttpMethod.POST, "/api/v1/auth/logout").permitAll();
                        authorize.requestMatchers(HttpMethod.POST,
                                "/api/v1/auth/logout-all/confirmation", "/api/v1/auth/logout-all").permitAll();
                        authorize.requestMatchers("/api/v1/me/library/collections",
                                "/api/v1/me/library/collections/*",
                                "/api/v1/me/works/*/library-state").permitAll();
                        authorize.requestMatchers(HttpMethod.GET, "/api/v1/auth/policies/current",
                                "/api/v1/auth/policies/terms/*",
                                "/api/v1/auth/policies/privacy-notice/*").permitAll();
                        authorize.requestMatchers(HttpMethod.POST, "/api/v1/auth/policies/accept").permitAll();
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
        CorsConfiguration apiConfiguration = new CorsConfiguration();
        apiConfiguration.setAllowedOrigins(properties.allowedOrigins());
        apiConfiguration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        apiConfiguration.setAllowedHeaders(List.of("Accept", "Content-Type", SessionCsrfTokenService.TOKEN_HEADER_NAME));
        apiConfiguration.setExposedHeaders(List.of("X-Correlation-Id"));
        apiConfiguration.setAllowCredentials(true);
        apiConfiguration.setMaxAge(Duration.ofMinutes(30));

        CorsConfiguration actuatorConfiguration = new CorsConfiguration();
        actuatorConfiguration.setAllowedOrigins(properties.allowedOrigins());
        actuatorConfiguration.setAllowedMethods(List.of("GET", "OPTIONS"));
        actuatorConfiguration.setAllowedHeaders(List.of("Accept"));
        actuatorConfiguration.setExposedHeaders(List.of("X-Correlation-Id"));
        actuatorConfiguration.setAllowCredentials(false);
        actuatorConfiguration.setMaxAge(Duration.ofMinutes(30));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", apiConfiguration);
        source.registerCorsConfiguration("/actuator/**", actuatorConfiguration);
        return source;
    }
}
