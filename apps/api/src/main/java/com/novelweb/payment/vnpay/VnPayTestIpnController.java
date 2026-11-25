package com.novelweb.payment.vnpay;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Receives bounded VNPAY TEST IPN callbacks and returns the documented JSON acknowledgement. */
@RestController
@Profile("postgres")
@ConditionalOnProperty(prefix = "novel.payment.vnpay.test", name = "enabled", havingValue = "true")
public final class VnPayTestIpnController {

  public static final String PATH = VnPayTestGatewaySettings.IPN_PATH;
  private static final int MAX_PARAMETERS = 40;
  private static final int MAX_TOTAL_CHARS = 8_192;
  private static final int MAX_FIELD_CHARS = 1_024;

  private final VnPayTestIpnService ipnService;

  public VnPayTestIpnController(VnPayTestIpnService ipnService) {
    this.ipnService = Objects.requireNonNull(ipnService, "VNPAY TEST IPN service is required");
  }

  @GetMapping(PATH)
  public ResponseEntity<Map<String, String>> receive(HttpServletRequest request) {
    Map<String, String> parameters = extract(request);
    if (parameters == null)
      return response(new VnPayTestIpnService.Acknowledgement("95", "Invalid request"));
    try {
      return response(ipnService.process(parameters));
    } catch (IllegalStateException exception) {
      // VNPAY retries when the merchant does not confirm successful processing.
      return response(new VnPayTestIpnService.Acknowledgement("99", "Internal error"));
    }
  }

  private static Map<String, String> extract(HttpServletRequest request) {
    Map<String, String[]> supplied = request.getParameterMap();
    if (supplied == null || supplied.isEmpty() || supplied.size() > MAX_PARAMETERS) return null;
    Map<String, String> result = new LinkedHashMap<>();
    int total = 0;
    for (Map.Entry<String, String[]> parameter : supplied.entrySet()) {
      String key = parameter.getKey();
      String[] values = parameter.getValue();
      if (key == null || !key.startsWith("vnp_") || values == null || values.length != 1)
        return null;
      String value = values[0];
      if (value == null || value.length() > MAX_FIELD_CHARS) return null;
      total += key.length() + value.length();
      if (total > MAX_TOTAL_CHARS) return null;
      result.put(key, value);
    }
    return result;
  }

  private static ResponseEntity<Map<String, String>> response(
      VnPayTestIpnService.Acknowledgement acknowledgement) {
    Map<String, String> body =
        Map.of("RspCode", acknowledgement.rspCode(), "Message", acknowledgement.message());
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache")
        .body(body);
  }
}
