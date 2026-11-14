package com.novelweb.platform.security;

import com.novelweb.identity.session.SessionAuthenticationFilter;
import com.novelweb.identity.session.SessionBoundCsrfFilter;
import com.novelweb.identity.session.SessionCsrfTokenService;
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
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration(proxyBeanMethods = false)
public class ApiSecurityConfiguration {

  @Bean
  SecurityFilterChain apiSecurityFilterChain(
      HttpSecurity http,
      ApiProblemWriter problemWriter,
      Environment environment,
      ObjectProvider<SessionAuthenticationFilter> sessionAuthenticationFilter,
      ObjectProvider<SessionBoundCsrfFilter> sessionBoundCsrfFilter)
      throws Exception {
    http.cors(Customizer.withDefaults());
    if (environment.acceptsProfiles(Profiles.of("postgres"))) {
      http.csrf(AbstractHttpConfigurer::disable);
      http.addFilterBefore(
          sessionAuthenticationFilter.getObject(), AnonymousAuthenticationFilter.class);
      http.addFilterBefore(sessionBoundCsrfFilter.getObject(), SessionAuthenticationFilter.class);
    }

    return http.authorizeHttpRequests(
            authorize -> {
              authorize
                  .requestMatchers(
                      HttpMethod.GET,
                      "/api/v1/system/status",
                      "/actuator/health",
                      "/actuator/health/**")
                  .permitAll();
              if (environment.acceptsProfiles(Profiles.of("postgres"))) {
                authorize
                    .requestMatchers(HttpMethod.GET, "/api/v1/libraries", "/api/v1/works")
                    .permitAll();
                authorize
                    .requestMatchers(
                        HttpMethod.GET,
                        "/api/v1/works/*/reader-manifest",
                        "/api/v1/works/*/progress",
                        "/api/v1/works/*/bookmarks",
                        "/api/v1/works/*/annotations",
                        "/api/v1/me/reader-preferences",
                        "/api/v1/me/reading-history",
                        "/api/v1/chapters/*/content")
                    .permitAll();
                authorize.requestMatchers(HttpMethod.PUT, "/api/v1/works/*/progress").permitAll();
                authorize.requestMatchers(HttpMethod.POST, "/api/v1/works/*/bookmarks").permitAll();
                authorize
                    .requestMatchers(HttpMethod.POST, "/api/v1/works/*/annotations")
                    .permitAll();
                authorize.requestMatchers(HttpMethod.DELETE, "/api/v1/bookmarks/*").permitAll();
                authorize.requestMatchers(HttpMethod.PATCH, "/api/v1/annotations/*").permitAll();
                authorize.requestMatchers(HttpMethod.DELETE, "/api/v1/annotations/*").permitAll();
                authorize
                    .requestMatchers(HttpMethod.DELETE, "/api/v1/works/*/annotations")
                    .permitAll();
                authorize
                    .requestMatchers(
                        HttpMethod.DELETE,
                        "/api/v1/me/reading-history",
                        "/api/v1/me/reading-history/*")
                    .permitAll();
                authorize
                    .requestMatchers(HttpMethod.PUT, "/api/v1/me/reader-preferences")
                    .permitAll();
                authorize.requestMatchers(HttpMethod.GET, "/api/v1/me/cultivation").permitAll();
                authorize
                    .requestMatchers(HttpMethod.GET, "/api/v1/me/cultivation/activity")
                    .permitAll();
                authorize
                    .requestMatchers(HttpMethod.PUT, "/api/v1/me/cultivation/settings")
                    .permitAll();
                authorize
                    .requestMatchers(
                        HttpMethod.POST,
                        "/api/v1/me/reading-sessions",
                        "/api/v1/me/reading-sessions/*:heartbeat",
                        "/api/v1/me/reading-sessions/*:close")
                    .permitAll();
                authorize
                    .requestMatchers(HttpMethod.GET, "/api/v1/auth/google/bootstrap")
                    .permitAll();
                authorize
                    .requestMatchers(HttpMethod.POST, "/api/v1/auth/google/credential")
                    .permitAll();
                authorize.requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf").permitAll();
                authorize.requestMatchers(HttpMethod.GET, "/api/v1/auth/me").permitAll();
                authorize.requestMatchers(HttpMethod.POST, "/api/v1/auth/logout").permitAll();
                authorize
                    .requestMatchers(
                        HttpMethod.POST,
                        "/api/v1/auth/logout-all/confirmation",
                        "/api/v1/auth/logout-all")
                    .permitAll();
                authorize
                    .requestMatchers(
                        "/api/v1/me/library/collections",
                        "/api/v1/me/library/collections/*",
                        "/api/v1/me/works/*/library-state")
                    .permitAll();
                authorize
                    .requestMatchers(
                        HttpMethod.GET,
                        "/api/v1/auth/policies/current",
                        "/api/v1/auth/policies/terms/*",
                        "/api/v1/auth/policies/privacy-notice/*")
                    .permitAll();
                authorize
                    .requestMatchers(HttpMethod.POST, "/api/v1/auth/policies/accept")
                    .permitAll();
                authorize
                    .requestMatchers(HttpMethod.GET, "/api/v1/imports/rights-statement/current")
                    .permitAll();
                authorize
                    .requestMatchers(HttpMethod.POST, "/api/v1/imports/rights-statement/acceptance")
                    .permitAll();
                authorize.requestMatchers(HttpMethod.POST, "/api/v1/imports").permitAll();
                authorize
                    .requestMatchers(
                        HttpMethod.GET,
                        "/api/v1/authoring/manuscripts/*/chapters",
                        "/api/v1/authoring/manuscripts/*/chapters/*/draft")
                    .permitAll();
                authorize
                    .requestMatchers(
                        HttpMethod.POST,
                        "/api/v1/authoring/manuscripts",
                        "/api/v1/authoring/manuscripts/*/chapters",
                        "/api/v1/authoring/manuscripts/*/chapters/*/revisions")
                    .permitAll();
                authorize.requestMatchers(HttpMethod.PUT, "/api/v1/imports/*/content").permitAll();
                authorize.requestMatchers(HttpMethod.GET, "/api/v1/imports/*").permitAll();
                authorize.requestMatchers(HttpMethod.POST, "/api/v1/imports/*/cancel").permitAll();
                authorize.requestMatchers(HttpMethod.POST, "/api/v1/imports/*/parse").permitAll();
                authorize.requestMatchers(HttpMethod.GET, "/api/v1/imports/*/preview").permitAll();
                authorize
                    .requestMatchers(HttpMethod.PATCH, "/api/v1/imports/*/preview")
                    .permitAll();
                authorize.requestMatchers(HttpMethod.POST, "/api/v1/imports/*/commit").permitAll();
                authorize
                    .requestMatchers(HttpMethod.POST, "/api/v1/glossaries/*/revisions")
                    .permitAll();
                authorize
                    .requestMatchers(HttpMethod.POST, "/api/v1/glossaries/*/revisions/*/publish")
                    .permitAll();
                authorize
                    .requestMatchers(HttpMethod.POST, "/api/v1/glossaries/*/revisions/*/preview")
                    .permitAll();
                authorize
                    .requestMatchers(HttpMethod.POST, "/api/v1/glossaries/*/terms/import-preview")
                    .permitAll();
                authorize
                    .requestMatchers(HttpMethod.POST, "/api/v1/glossaries/*/terms/import-apply")
                    .permitAll();
                authorize
                    .requestMatchers(HttpMethod.GET, "/api/v1/glossaries/*/active-revision")
                    .permitAll();
                authorize
                    .requestMatchers(HttpMethod.GET, "/api/v1/glossaries/*/latest-revision")
                    .permitAll();
                authorize
                    .requestMatchers(HttpMethod.GET, "/api/v1/glossaries/*/export")
                    .permitAll();
                authorize.requestMatchers(HttpMethod.GET, "/api/v1/glossaries/*").permitAll();
                authorize.requestMatchers(HttpMethod.GET, "/api/v1/glossaries").permitAll();
                authorize.requestMatchers(HttpMethod.POST, "/api/v1/glossaries").permitAll();
                authorize
                    .requestMatchers(
                        HttpMethod.GET,
                        "/api/v1/translation/jobs",
                        "/api/v1/translation/jobs/*",
                        "/api/v1/translation/jobs/*/candidate")
                    .permitAll();
                authorize
                    .requestMatchers(
                        HttpMethod.POST,
                        "/api/v1/translation/jobs",
                        "/api/v1/translation/jobs/*/retry",
                        "/api/v1/translation/jobs/*/cancel")
                    .permitAll();
                authorize
                    .requestMatchers(
                        HttpMethod.PUT, "/api/v1/translation/jobs/*/candidate/segments/*/edit")
                    .permitAll();
              }
              authorize.anyRequest().denyAll();
            })
        .exceptionHandling(
            exceptions ->
                exceptions
                    .authenticationEntryPoint(
                        (request, response, failure) ->
                            problemWriter.write(
                                request,
                                response,
                                403,
                                "Forbidden",
                                "FORBIDDEN",
                                "This endpoint is not available in the current API slice."))
                    .accessDeniedHandler(
                        (request, response, denied) ->
                            problemWriter.write(
                                request,
                                response,
                                403,
                                "Forbidden",
                                "FORBIDDEN",
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
    apiConfiguration.setAllowedHeaders(
        List.of(
            "Accept",
            "Content-Type",
            "Idempotency-Key",
            "If-Match",
            "X-Import-Upload-Token",
            SessionCsrfTokenService.TOKEN_HEADER_NAME));
    apiConfiguration.setExposedHeaders(
        List.of("X-Correlation-Id", "Location", "Retry-After", "ETag"));
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
