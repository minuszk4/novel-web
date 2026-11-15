package com.novelweb.payment.momo;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Receives only bounded, signed MoMo TEST IPN requests. */
@RestController
@Profile("postgres")
@ConditionalOnProperty(prefix = "novel.payment.momo.test", name = "enabled", havingValue = "true")
public final class MomoTestIpnController {

  public static final String PATH = "/api/v1/payments/momo/ipn";
  private static final int MAX_BODY_BYTES = 16_384;

  private final MomoTestIpnService ipnService;
  private final ObjectMapper objectMapper;

  public MomoTestIpnController(MomoTestIpnService ipnService, ObjectMapper objectMapper) {
    this.ipnService = Objects.requireNonNull(ipnService, "MoMo TEST IPN service is required");
    this.objectMapper = Objects.requireNonNull(objectMapper, "JSON mapper is required");
  }

  @PostMapping(PATH)
  public ResponseEntity<Void> receive(HttpServletRequest request) {
    if (!isJson(request.getContentType())) {
      return empty(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }
    if (request.getContentLengthLong() > MAX_BODY_BYTES) {
      return empty(HttpStatus.PAYLOAD_TOO_LARGE);
    }

    byte[] body;
    try {
      body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
    } catch (IOException exception) {
      return empty(HttpStatus.BAD_REQUEST);
    }
    if (body.length == 0) return empty(HttpStatus.BAD_REQUEST);
    if (body.length > MAX_BODY_BYTES) return empty(HttpStatus.PAYLOAD_TOO_LARGE);

    ParsedNotification parsed;
    try {
      parsed = parse(body);
    } catch (JacksonException exception) {
      return empty(HttpStatus.BAD_REQUEST);
    }
    if (parsed == null) return empty(HttpStatus.BAD_REQUEST);

    MomoTestIpnService.Result result;
    try {
      result = ipnService.process(parsed.notification(), parsed.signature());
    } catch (IllegalStateException exception) {
      return empty(HttpStatus.SERVICE_UNAVAILABLE);
    }
    if (result == MomoTestIpnService.Result.INVALID_SIGNATURE) {
      return empty(HttpStatus.UNAUTHORIZED);
    }
    if (result == MomoTestIpnService.Result.INVALID_NOTIFICATION) {
      return empty(HttpStatus.BAD_REQUEST);
    }
    return empty(HttpStatus.NO_CONTENT);
  }

  private static boolean isJson(String contentType) {
    if (contentType == null) return false;
    try {
      return MediaType.parseMediaType(contentType).isCompatibleWith(MediaType.APPLICATION_JSON);
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }

  private ParsedNotification parse(byte[] body) throws JacksonException {
    JsonNode root = objectMapper.readTree(body);
    if (root == null || !root.isObject()) return null;
    MomoSignatureCodec.PaymentNotification notification =
        new MomoSignatureCodec.PaymentNotification(
            longValue(root, "amount"),
            text(root, "extraData"),
            text(root, "message"),
            text(root, "orderId"),
            text(root, "orderInfo"),
            text(root, "orderType"),
            text(root, "partnerCode"),
            text(root, "payType"),
            text(root, "requestId"),
            longValue(root, "responseTime"),
            intValue(root, "resultCode"),
            longValue(root, "transId"));
    String signature = text(root, "signature");
    return notification.hasAllSignedFields() && signature != null
        ? new ParsedNotification(notification, signature)
        : null;
  }

  private static String text(JsonNode root, String name) {
    JsonNode value = root.get(name);
    return value != null && value.isTextual() ? value.stringValue() : null;
  }

  private static Long longValue(JsonNode root, String name) {
    JsonNode value = root.get(name);
    return value != null && value.isIntegralNumber() && value.canConvertToLong()
        ? value.longValue()
        : null;
  }

  private static Integer intValue(JsonNode root, String name) {
    JsonNode value = root.get(name);
    return value != null && value.isIntegralNumber() && value.canConvertToInt()
        ? value.intValue()
        : null;
  }

  private static ResponseEntity<Void> empty(HttpStatus status) {
    return ResponseEntity.status(status)
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache")
        .build();
  }

  private record ParsedNotification(
      MomoSignatureCodec.PaymentNotification notification, String signature) {}
}
