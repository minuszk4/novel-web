package com.novelweb.payment.vnpay;

import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Sends and verifies VNPAY TEST QueryDR calls; it never mutates local order or credit state. */
public final class VnPayTestQueryDrClient {

  private static final URI QUERY_URI =
      URI.create("https://sandbox.vnpayment.vn/merchant_webapi/api/transaction");
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(35);
  private static final int MAX_RESPONSE_BYTES = 65_536;
  private static final Pattern DATE = Pattern.compile("[0-9]{14}");
  private static final DateTimeFormatter VNPAY_DATE =
      DateTimeFormatter.ofPattern("uuuuMMddHHmmss").withResolverStyle(ResolverStyle.STRICT);
  private static final Pattern TXN_REF = Pattern.compile("[0-9a-f]{32}");

  private final VnPayTestGatewaySettings settings;
  private final ObjectMapper objectMapper;
  private final VnPayTestQueryDrTransport transport;

  public VnPayTestQueryDrClient(
      VnPayTestGatewaySettings settings,
      ObjectMapper objectMapper,
      VnPayTestQueryDrTransport transport) {
    this.settings = Objects.requireNonNull(settings, "validated VNPAY TEST settings are required");
    this.objectMapper = Objects.requireNonNull(objectMapper, "JSON mapper is required");
    this.transport = Objects.requireNonNull(transport, "QueryDR transport is required");
  }

  /** Queries one saved payment attempt using the exact vnp_CreateDate sent in the pay redirect. */
  public QueryResult query(QueryRequest request) {
    validate(request);
    Map<String, String> fields = new LinkedHashMap<>();
    fields.put("vnp_RequestId", request.requestId());
    fields.put("vnp_Version", "2.1.0");
    fields.put("vnp_Command", "querydr");
    fields.put("vnp_TmnCode", settings.tmnCode());
    fields.put("vnp_TxnRef", VnPayTestGatewayClient.compactOrderReference(request.orderId()));
    fields.put("vnp_TransactionDate", request.transactionDate());
    fields.put("vnp_CreateDate", request.queryDate());
    fields.put("vnp_IpAddr", request.serverIpAddress());
    fields.put("vnp_OrderInfo", orderInfo(request.orderId()));
    fields.put(
        "vnp_SecureHash",
        VnPayTestSignatureCodec.signQueryDrRequest(fields, settings.hashSecret()));

    byte[] body = writeJson(fields);
    if (body == null) return QueryResult.unknown(FailureCode.INVALID_REQUEST);
    VnPayTestQueryDrTransport.Response raw;
    try {
      raw = transport.post(QUERY_URI, body, REQUEST_TIMEOUT);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      return QueryResult.unknown(FailureCode.TRANSPORT);
    } catch (IOException | RuntimeException exception) {
      return QueryResult.unknown(FailureCode.TRANSPORT);
    }
    if (raw == null) return QueryResult.unknown(FailureCode.TRANSPORT);
    if (raw.statusCode() != 200) return QueryResult.unknown(FailureCode.HTTP_STATUS);
    byte[] responseBody = raw.body();
    if (responseBody.length > MAX_RESPONSE_BYTES) {
      return QueryResult.unknown(FailureCode.RESPONSE_TOO_LARGE);
    }
    Map<String, String> response = parseResponse(responseBody);
    if (response == null) return QueryResult.unknown(FailureCode.INVALID_RESPONSE);
    String signature = response.remove("vnp_SecureHash");
    if (!VnPayTestSignatureCodec.verifyQueryDrResponse(
        response, settings.hashSecret(), signature)) {
      return QueryResult.unknown(FailureCode.UNVERIFIED_RESPONSE);
    }
    if (!matches(request, response)) return QueryResult.unknown(FailureCode.ORDER_MISMATCH);

    String apiCode = response.get("vnp_ResponseCode");
    if (!"00".equals(apiCode)) return QueryResult.unknown(FailureCode.PROVIDER_REJECTED);
    String transactionStatus = response.get("vnp_TransactionStatus");
    String transactionType = response.get("vnp_TransactionType");
    Long transactionId = positiveLong(response.get("vnp_TransactionNo"));
    if ("00".equals(transactionStatus) && "01".equals(transactionType) && transactionId != null) {
      return new QueryResult(Outcome.SUCCEEDED, transactionId, FailureCode.NONE);
    }
    if ("01".equals(transactionStatus)) {
      return new QueryResult(Outcome.PENDING, transactionId, FailureCode.NONE);
    }
    if ("02".equals(transactionStatus) || "09".equals(transactionStatus)) {
      return new QueryResult(Outcome.FAILED, transactionId, FailureCode.NONE);
    }
    return new QueryResult(Outcome.UNKNOWN, transactionId, FailureCode.NONE);
  }

  private static boolean matches(QueryRequest request, Map<String, String> response) {
    return "querydr".equals(response.get("vnp_Command"))
        && response.getOrDefault("vnp_TmnCode", "").equals(request.tmnCode())
        && response
            .getOrDefault("vnp_TxnRef", "")
            .equals(VnPayTestGatewayClient.compactOrderReference(request.orderId()))
        && response.getOrDefault("vnp_Amount", "").equals(Long.toString(request.amountVnd() * 100L))
        && response.getOrDefault("vnp_OrderInfo", "").equals(orderInfo(request.orderId()))
        && response.containsKey("vnp_ResponseId")
        && response.containsKey("vnp_ResponseCode")
        && response.containsKey("vnp_TransactionStatus");
  }

  private void validate(QueryRequest request) {
    Objects.requireNonNull(request, "VNPAY QueryDR request is required");
    if (!settings.tmnCode().equals(request.tmnCode())
        || request.requestId() == null
        || !request.requestId().matches("[A-Za-z0-9]{1,32}")
        || request.orderId() == null
        || !TXN_REF
            .matcher(VnPayTestGatewayClient.compactOrderReference(request.orderId()))
            .matches()
        || request.amountVnd() < 1_000L
        || request.amountVnd() > 50_000_000L
        || request.amountVnd() > Long.MAX_VALUE / 100L
        || !isValidDate(request.transactionDate())
        || !isValidDate(request.queryDate())
        || !isValidIpAddress(request.serverIpAddress())) {
      throw new IllegalArgumentException("VNPAY TEST QueryDR request is invalid");
    }
  }

  private byte[] writeJson(Map<String, String> fields) {
    try {
      return objectMapper.writeValueAsBytes(fields);
    } catch (JacksonException exception) {
      return null;
    }
  }

  private Map<String, String> parseResponse(byte[] body) {
    try {
      JsonNode root = objectMapper.readTree(body);
      if (root == null || !root.isObject()) return null;
      Map<String, String> fields = new LinkedHashMap<>();
      for (String name : VnPayTestQueryDrResponseFields.NAMES) {
        JsonNode value = root.get(name);
        if (value != null && value.isTextual()) {
          fields.put(name, value.stringValue());
        } else if (value != null && value.isIntegralNumber()) {
          fields.put(name, value.toString());
        }
      }
      return fields.containsKey("vnp_SecureHash") ? fields : null;
    } catch (JacksonException exception) {
      return null;
    }
  }

  private static Long positiveLong(String value) {
    if (value == null || !value.matches("[0-9]{1,15}")) return null;
    try {
      long result = Long.parseLong(value);
      return result > 0 ? result : null;
    } catch (NumberFormatException exception) {
      return null;
    }
  }

  private static boolean isValidDate(String value) {
    if (value == null || !DATE.matcher(value).matches()) return false;
    try {
      LocalDateTime.parse(value, VNPAY_DATE);
      return true;
    } catch (DateTimeParseException exception) {
      return false;
    }
  }

  private static boolean isValidIpAddress(String value) {
    if (value == null || value.length() < 7 || value.length() > 45) return false;
    if (!value.contains(":")) {
      String[] octets = value.split("\\.", -1);
      if (octets.length != 4) return false;
      for (String octet : octets) {
        if (octet.isEmpty() || !octet.matches("[0-9]{1,3}")) return false;
        try {
          if (Integer.parseInt(octet) > 255) return false;
        } catch (NumberFormatException exception) {
          return false;
        }
      }
      return true;
    }
    try {
      return InetAddress.getByName(value) instanceof Inet6Address;
    } catch (UnknownHostException exception) {
      return false;
    }
  }

  public static String orderInfo(UUID orderId) {
    return "Nap credit " + VnPayTestGatewayClient.compactOrderReference(orderId);
  }

  public record QueryRequest(
      String tmnCode,
      String requestId,
      UUID orderId,
      long amountVnd,
      String transactionDate,
      String queryDate,
      String serverIpAddress) {}

  public enum Outcome {
    SUCCEEDED,
    PENDING,
    FAILED,
    UNKNOWN
  }

  public enum FailureCode {
    NONE,
    INVALID_REQUEST,
    TRANSPORT,
    HTTP_STATUS,
    RESPONSE_TOO_LARGE,
    INVALID_RESPONSE,
    UNVERIFIED_RESPONSE,
    ORDER_MISMATCH,
    PROVIDER_REJECTED
  }

  public record QueryResult(Outcome outcome, Long transactionId, FailureCode failureCode) {
    private static QueryResult unknown(FailureCode failure) {
      return new QueryResult(Outcome.UNKNOWN, null, failure);
    }
  }
}

final class VnPayTestQueryDrResponseFields {
  static final java.util.List<String> NAMES =
      java.util.List.of(
          "vnp_ResponseId",
          "vnp_Command",
          "vnp_ResponseCode",
          "vnp_Message",
          "vnp_TmnCode",
          "vnp_TxnRef",
          "vnp_Amount",
          "vnp_BankCode",
          "vnp_PayDate",
          "vnp_TransactionNo",
          "vnp_TransactionType",
          "vnp_TransactionStatus",
          "vnp_OrderInfo",
          "vnp_PromotionCode",
          "vnp_PromotionAmount",
          "vnp_SecureHash");

  private VnPayTestQueryDrResponseFields() {}
}
