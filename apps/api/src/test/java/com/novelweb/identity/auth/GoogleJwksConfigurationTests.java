package com.novelweb.identity.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.client.RestTemplate;

class GoogleJwksConfigurationTests {

  private HttpServer jwksServer;
  private AtomicReference<String> jwksJson;
  private KeyPair firstKey;
  private KeyPair rotatedKey;
  private JwtDecoder decoder;

  @BeforeEach
  void setUp() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    firstKey = generator.generateKeyPair();
    rotatedKey = generator.generateKeyPair();
    jwksJson = new AtomicReference<>(jwkSet(firstKey, "key-1"));

    jwksServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    jwksServer.createContext(
        "/jwks",
        exchange -> {
          byte[] response = jwksJson.get().getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.getResponseHeaders().set("Cache-Control", "no-cache");
          exchange.sendResponseHeaders(200, response.length);
          try (var responseBody = exchange.getResponseBody()) {
            responseBody.write(response);
          }
        });
    jwksServer.start();

    String jwksUri = "http://127.0.0.1:" + jwksServer.getAddress().getPort() + "/jwks";
    decoder = GoogleJwksConfiguration.createDecoder(jwksUri, new RestTemplate());
  }

  @AfterEach
  void tearDown() {
    if (jwksServer != null) {
      jwksServer.stop(0);
    }
  }

  @Test
  void verifiesGoogleStyleRsaJwksAndRefreshesWhenSigningKeyRotates() throws Exception {
    assertEquals("subject-1", decoder.decode(signedToken(firstKey, "key-1")).getSubject());

    jwksJson.set(jwkSet(rotatedKey, "key-2"));
    assertEquals("subject-2", decoder.decode(signedToken(rotatedKey, "key-2")).getSubject());
  }

  @Test
  void rejectsTokenSignedByKeyThatIsNotInTheJwksSet() throws Exception {
    assertThrows(JwtException.class, () -> decoder.decode(signedToken(rotatedKey, "unknown-key")));
  }

  private static String jwkSet(KeyPair keyPair, String keyId) {
    RSAKey publicJwk = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic()).keyID(keyId).build();
    return new JWKSet(List.of(publicJwk)).toString();
  }

  private static String signedToken(KeyPair keyPair, String keyId) throws Exception {
    Instant now = Instant.now();
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .issuer("https://accounts.google.com")
            .audience("novel-web-test.apps.googleusercontent.com")
            .subject("subject-" + ("key-1".equals(keyId) ? "1" : "2"))
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(300)))
            .build();
    SignedJWT signed =
        new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(keyId).build(), claims);
    signed.sign(new RSASSASigner(keyPair.getPrivate()));
    return signed.serialize();
  }
}
