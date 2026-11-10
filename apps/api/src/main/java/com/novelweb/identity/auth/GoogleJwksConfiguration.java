package com.novelweb.identity.auth;

import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.client.RestOperations;
import org.springframework.web.client.RestTemplate;

@Configuration(proxyBeanMethods = false)
@Profile("postgres")
class GoogleJwksConfiguration {

  static final String GOOGLE_JWKS_URI = "https://www.googleapis.com/oauth2/v3/certs";
  private static final Duration KEY_FETCH_TIMEOUT = Duration.ofSeconds(3);
  private static final Duration ACCEPTABLE_CLOCK_SKEW = Duration.ofSeconds(60);

  @Bean
  JwtDecoder googleJwtDecoder() {
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(KEY_FETCH_TIMEOUT);
    requestFactory.setReadTimeout(KEY_FETCH_TIMEOUT);
    return createDecoder(GOOGLE_JWKS_URI, new RestTemplate(requestFactory));
  }

  static JwtDecoder createDecoder(String jwkSetUri, RestOperations restOperations) {
    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withJwkSetUri(jwkSetUri)
            .jwsAlgorithm(SignatureAlgorithm.RS256)
            .restOperations(restOperations)
            .build();
    decoder.setJwtValidator(new JwtTimestampValidator(ACCEPTABLE_CLOCK_SKEW));
    return decoder;
  }
}
