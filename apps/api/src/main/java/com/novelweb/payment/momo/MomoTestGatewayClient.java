package com.novelweb.payment.momo;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Calls only MoMo's TEST create/query endpoints. This adapter verifies responses but never changes
 * an order, grants credit, unlocks content, retries a create call, or follows an HTTP redirect.
 */
public final class MomoTestGatewayClient {

  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(35);
  private static final int MAX_RESPONSE_BYTES = 65_536;
  private static final Pattern ORDER_ID = Pattern.compile("[0-9A-Za-z]([-_.]*[0-9A-Za-z]+)*");
  private static final Pattern REQUEST_ID = Pattern.compile("[0-9A-Za-z][0-9A-Za-z_.-]{0,49}");

  private final MomoTestGatewaySettings settings;
  private final ObjectMapper objectMapper;
  private final MomoTestHttpTransport transport;

  public MomoTestGatewayClient(
      MomoTestGatewaySettings settings,
      ObjectMapper objectMapper,
      MomoTestHttpTransport transport) {
    this.settings = Objects.requireNonNull(settings, "validated MoMo TEST settings are required");
    this.objectMapper = Objects.requireNonNull(objectMapper, "JSON mapper is required");
    this.transport = Objects.requireNonNull(transport, "HTTP transport is required");
  }

  /** Builds a fixed, non-PII top-up request from an immutable server-side order snapshot. */
  public CreateResult createCreditTopUp(long amountVnd, UUID orderId, UUID requestId) {
    Objects.requireNonNull(orderId, "order id is required");
    Objects.requireNonNull(requestId, "request id is required");
    return createWallet(
        new MomoSignatureCodec.CreateWalletRequest(
            amountVnd,
            "",
            settings.ipnUri().toString(),
            orderId.toString(),
            "Novel Web credit top-up",
            settings.partnerCode(),
            settings.redirectUri().toString(),
            requestId.toString()));
  }

  /**
   * Creates a hosted checkout link. A timeout, HTTP error or unverifiable reply stays UNKNOWN;
   * callers must reconcile the same order by query instead of sending a second create request.
   */
  public CreateResult createWallet(MomoSignatureCodec.CreateWalletRequest request) {
    validateCreateRequest(request);
    String signature =
        MomoSignatureCodec.signCreateWallet(request, settings.accessKey(), settings.secretKey());
    byte[] body =
        writeJson(
            fields(
                "partnerCode",
                request.partnerCode(),
                "requestId",
                request.requestId(),
                "orderId",
                request.orderId(),
                "amount",
                request.amountVnd(),
                "orderInfo",
                request.orderInfo(),
                "redirectUrl",
                request.redirectUrl(),
                "ipnUrl",
                request.ipnUrl(),
                "requestType",
                "captureWallet",
                "extraData",
                request.extraData(),
                "lang",
                "vi",
                "signature",
                signature));
    MomoTestHttpTransport.Response raw = post(settings.createEndpoint(), body);
    if (raw == null) return CreateResult.unknown(FailureCode.TRANSPORT);
    if (raw.statusCode() != 200) return CreateResult.unknown(FailureCode.HTTP_STATUS);
    byte[] responseBody = raw.body();
    if (responseBody.length > MAX_RESPONSE_BYTES) {
      return CreateResult.unknown(FailureCode.RESPONSE_TOO_LARGE);
    }
    MomoSignatureCodec.CreateWalletResponse response = parseCreateResponse(responseBody);
    if (response == null) return CreateResult.unknown(FailureCode.INVALID_RESPONSE);

    var expected =
        new MomoTestCheckoutResponseValidator.ExpectedOrder(
            request.partnerCode(), request.requestId(), request.orderId(), request.amountVnd());
    if (!MomoTestCheckoutResponseValidator.accepts(
        response, expected, settings.accessKey(), settings.secretKey())) {
      return CreateResult.unknown(FailureCode.UNVERIFIED_RESPONSE);
    }
    return new CreateResult(CreateState.READY, response.payUrl(), FailureCode.NONE);
  }

  /**
   * Queries an existing order using a fresh query request ID and the saved amount snapshot.
   * Transport errors remain UNKNOWN; only a matching response can classify success or failure.
   */
  public QueryResult queryOrder(MomoSignatureCodec.QueryOrderRequest request, long savedAmountVnd) {
    validateQueryRequest(request, savedAmountVnd);
    byte[] body =
        writeJson(
            fields(
                "partnerCode",
                request.partnerCode(),
                "requestId",
                request.requestId(),
                "orderId",
                request.orderId(),
                "lang",
                "vi",
                "signature",
                MomoSignatureCodec.signQueryOrder(
                    request, settings.accessKey(), settings.secretKey())));
    MomoTestHttpTransport.Response raw = post(settings.queryEndpoint(), body);
    if (raw == null) return QueryResult.unknown(FailureCode.TRANSPORT);
    if (raw.statusCode() != 200) return QueryResult.unknown(FailureCode.HTTP_STATUS);
    byte[] responseBody = raw.body();
    if (responseBody.length > MAX_RESPONSE_BYTES) {
      return QueryResult.unknown(FailureCode.RESPONSE_TOO_LARGE);
    }
    MomoQueryResponseValidator.QueryResponse response = parseQueryResponse(responseBody);
    if (response == null) return QueryResult.unknown(FailureCode.INVALID_RESPONSE);

    var expected =
        new MomoQueryResponseValidator.ExpectedOrder(
            request.partnerCode(), request.requestId(), request.orderId(), savedAmountVnd);
    MomoQueryResponseValidator.Outcome outcome =
        MomoQueryResponseValidator.classify(response, expected);
    return outcome == MomoQueryResponseValidator.Outcome.INVALID_RESPONSE
        ? QueryResult.unknown(FailureCode.ORDER_MISMATCH)
        : new QueryResult(outcome, response.transactionId(), FailureCode.NONE);
  }

  private MomoTestHttpTransport.Response post(URI endpoint, byte[] body) {
    try {
      return transport.post(endpoint, body, REQUEST_TIMEOUT);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      return null;
    } catch (IOException exception) {
      return null;
    }
  }

  private byte[] writeJson(Map<String, Object> fields) {
    try {
      return objectMapper.writeValueAsBytes(fields);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Unable to encode MoMo TEST request", exception);
    }
  }

  private MomoSignatureCodec.CreateWalletResponse parseCreateResponse(byte[] body) {
    try {
      JsonNode root = objectMapper.readTree(body);
      if (root == null || !root.isObject()) return null;
      return new MomoSignatureCodec.CreateWalletResponse(
          text(root, "partnerCode"),
          text(root, "requestId"),
          text(root, "orderId"),
          longValue(root, "amount"),
          text(root, "message"),
          longValue(root, "responseTime"),
          intValue(root, "resultCode"),
          text(root, "payUrl"),
          text(root, "signature"));
    } catch (JacksonException exception) {
      return null;
    }
  }

  private MomoQueryResponseValidator.QueryResponse parseQueryResponse(byte[] body) {
    try {
      JsonNode root = objectMapper.readTree(body);
      if (root == null || !root.isObject()) return null;
      return new MomoQueryResponseValidator.QueryResponse(
          text(root, "partnerCode"),
          text(root, "requestId"),
          text(root, "orderId"),
          longValue(root, "amount"),
          longValue(root, "transId"),
          intValue(root, "resultCode"));
    } catch (JacksonException exception) {
      return null;
    }
  }

  private void validateCreateRequest(MomoSignatureCodec.CreateWalletRequest request) {
    Objects.requireNonNull(request, "MoMo TEST create request is required");
    if (!settings.partnerCode().equals(request.partnerCode())
        || !REQUEST_ID.matcher(request.requestId()).matches()
        || !ORDER_ID.matcher(request.orderId()).matches()
        || request.partnerCode().length() > 50
        || request.orderId().length() > 200
        || request.orderInfo().length() > 255
        || request.extraData().length() > 1_000
        || !settings.ipnUri().toString().equals(request.ipnUrl())
        || !settings.redirectUri().toString().equals(request.redirectUrl())) {
      throw new IllegalArgumentException("MoMo TEST create request is invalid");
    }
  }

  private void validateQueryRequest(MomoSignatureCodec.QueryOrderRequest request, long amount) {
    Objects.requireNonNull(request, "MoMo TEST query request is required");
    if (!settings.partnerCode().equals(request.partnerCode())
        || !REQUEST_ID.matcher(request.requestId()).matches()
        || !ORDER_ID.matcher(request.orderId()).matches()
        || amount < 1_000L
        || amount > 50_000_000L) {
      throw new IllegalArgumentException("MoMo TEST query request is invalid");
    }
  }

  private static String text(JsonNode root, String field) {
    JsonNode value = root.get(field);
    return value != null && value.isTextual() ? value.stringValue() : null;
  }

  private static Long longValue(JsonNode root, String field) {
    JsonNode value = root.get(field);
    return value != null && value.isIntegralNumber() && value.canConvertToLong()
        ? value.longValue()
        : null;
  }

  private static Integer intValue(JsonNode root, String field) {
    JsonNode value = root.get(field);
    return value != null && value.isIntegralNumber() && value.canConvertToInt()
        ? value.intValue()
        : null;
  }

  private static Map<String, Object> fields(Object... pairs) {
    Map<String, Object> fields = new LinkedHashMap<>();
    for (int index = 0; index < pairs.length; index += 2) {
      fields.put((String) pairs[index], pairs[index + 1]);
    }
    return fields;
  }

  public enum CreateState {
    READY,
    UNKNOWN
  }

  public enum FailureCode {
    NONE,
    TRANSPORT,
    HTTP_STATUS,
    RESPONSE_TOO_LARGE,
    INVALID_RESPONSE,
    UNVERIFIED_RESPONSE,
    ORDER_MISMATCH
  }

  public record CreateResult(CreateState state, String payUrl, FailureCode failureCode) {
    private static CreateResult unknown(FailureCode failure) {
      return new CreateResult(CreateState.UNKNOWN, null, failure);
    }

    @Override
    public String toString() {
      return "CreateResult[state="
          + state
          + ", payUrl=[REDACTED], failureCode="
          + failureCode
          + "]";
    }
  }

  public record QueryResult(
      MomoQueryResponseValidator.Outcome outcome, Long transactionId, FailureCode failureCode) {
    private static QueryResult unknown(FailureCode failure) {
      return new QueryResult(MomoQueryResponseValidator.Outcome.UNKNOWN, null, failure);
    }
  }
}
