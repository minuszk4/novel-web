package com.novelweb.payment.vnpay;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Builds signed VNPAY sandbox redirect URLs; this adapter never calls a live payment API. */
public final class VnPayTestGatewayClient {

  private static final ZoneId VIETNAM_TIME = ZoneId.of("Asia/Ho_Chi_Minh");
  private static final DateTimeFormatter VNPAY_DATE = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
  private static final Duration PAYMENT_WINDOW = Duration.ofMinutes(15);

  private final VnPayTestGatewaySettings settings;
  private final Clock clock;

  public VnPayTestGatewayClient(VnPayTestGatewaySettings settings) {
    this(settings, Clock.systemUTC());
  }

  VnPayTestGatewayClient(VnPayTestGatewaySettings settings, Clock clock) {
    this.settings = java.util.Objects.requireNonNull(settings, "VNPAY TEST settings are required");
    this.clock = java.util.Objects.requireNonNull(clock, "clock is required");
  }

  public String createCreditTopUp(long amountVnd, UUID orderId, String clientIpAddress) {
    return createCreditTopUp(amountVnd, orderId, clientIpAddress, clock.instant());
  }

  public String createCreditTopUp(
      long amountVnd, UUID orderId, String clientIpAddress, Instant paymentRequestCreatedAt) {
    if (amountVnd < 1_000 || amountVnd > 50_000_000) {
      throw new IllegalArgumentException("VNPAY TEST amount is outside the approved range");
    }
    if (orderId == null) throw new IllegalArgumentException("VNPAY order reference is required");
    if (paymentRequestCreatedAt == null) {
      throw new IllegalArgumentException("VNPAY payment request creation time is required");
    }
    String ipAddress = normalizeIpAddress(clientIpAddress);
    var now = paymentRequestCreatedAt.atZone(VIETNAM_TIME);
    Map<String, String> fields = new TreeMap<>();
    fields.put("vnp_Amount", Long.toString(Math.multiplyExact(amountVnd, 100L)));
    fields.put("vnp_Command", "pay");
    fields.put("vnp_CreateDate", VNPAY_DATE.format(now));
    fields.put("vnp_CurrCode", "VND");
    fields.put("vnp_ExpireDate", VNPAY_DATE.format(now.plus(PAYMENT_WINDOW)));
    fields.put("vnp_IpAddr", ipAddress);
    fields.put("vnp_Locale", "vn");
    fields.put("vnp_OrderInfo", "Nap credit " + compactOrderReference(orderId));
    fields.put("vnp_OrderType", "other");
    fields.put("vnp_ReturnUrl", settings.returnUri().toString());
    fields.put("vnp_TmnCode", settings.tmnCode());
    fields.put("vnp_TxnRef", compactOrderReference(orderId));
    fields.put("vnp_Version", "2.1.0");

    StringBuilder query = new StringBuilder();
    for (Map.Entry<String, String> field : fields.entrySet()) {
      if (!query.isEmpty()) query.append('&');
      query
          .append(VnPayTestSignatureCodec.encode(field.getKey()))
          .append('=')
          .append(VnPayTestSignatureCodec.encode(field.getValue()));
    }
    String signature = VnPayTestSignatureCodec.sign(fields, settings.hashSecret());
    URI uri = URI.create(settings.paymentUri() + "?" + query + "&vnp_SecureHash=" + signature);
    String result = uri.toASCIIString();
    if (!VnPayTestCheckoutUrlPolicy.allows(result)) {
      throw new IllegalStateException("Generated VNPAY TEST checkout URL failed validation");
    }
    return result;
  }

  public String createCreditTopUp(
      long amountVnd, UUID orderId, UUID requestId, String clientIpAddress) {
    if (requestId == null) throw new IllegalArgumentException("VNPAY request ID is required");
    return createCreditTopUp(amountVnd, orderId, clientIpAddress);
  }

  public String createCreditTopUp(
      long amountVnd,
      UUID orderId,
      UUID requestId,
      String clientIpAddress,
      Instant paymentRequestCreatedAt) {
    if (requestId == null) throw new IllegalArgumentException("VNPAY request ID is required");
    return createCreditTopUp(amountVnd, orderId, clientIpAddress, paymentRequestCreatedAt);
  }

  public static String compactOrderReference(UUID orderId) {
    return orderId.toString().replace("-", "");
  }

  public static String formatVnPayDate(Instant instant) {
    if (instant == null) throw new IllegalArgumentException("VNPAY timestamp is required");
    return VNPAY_DATE.format(instant.atZone(VIETNAM_TIME));
  }

  private static String normalizeIpAddress(String value) {
    if (value == null || value.isBlank()) return "127.0.0.1";
    String normalized = value.trim();
    if (normalized.length() > 45
        || normalized.indexOf(',') >= 0
        || !normalized.matches("[0-9A-Fa-f:.]+")) {
      return "127.0.0.1";
    }
    return normalized;
  }
}
