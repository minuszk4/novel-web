package com.novelweb.payment.momo;

import java.util.Objects;

/** Accepts a checkout URL only after signature, order snapshot and MoMo Test host checks pass. */
public final class MomoTestCheckoutResponseValidator {

  private MomoTestCheckoutResponseValidator() {}

  public static boolean accepts(
      MomoSignatureCodec.CreateWalletResponse response,
      ExpectedOrder expectedOrder,
      String accessKey,
      String secretKey) {
    if (response == null
        || expectedOrder == null
        || accessKey == null
        || accessKey.isBlank()
        || secretKey == null
        || secretKey.isBlank()
        || !MomoSignatureCodec.verifyCreateWalletResponse(response, accessKey, secretKey)) {
      return false;
    }

    return response.resultCode() == 0
        && expectedOrder.partnerCode().equals(response.partnerCode())
        && expectedOrder.requestId().equals(response.requestId())
        && expectedOrder.orderId().equals(response.orderId())
        && expectedOrder.amountVnd().equals(response.amountVnd())
        && MomoTestCheckoutUrlPolicy.allows(response.payUrl());
  }

  public record ExpectedOrder(
      String partnerCode, String requestId, String orderId, Long amountVnd) {

    public ExpectedOrder {
      requireNonBlank(partnerCode, "partnerCode is required");
      requireNonBlank(requestId, "requestId is required");
      requireNonBlank(orderId, "orderId is required");
      Objects.requireNonNull(amountVnd, "amountVnd is required");
      if (amountVnd < 1_000L || amountVnd > 50_000_000L) {
        throw new IllegalArgumentException(
            "MoMo wallet amount must be between 1,000 and 50,000,000 VND");
      }
    }

    private static void requireNonBlank(String value, String message) {
      if (value == null || value.isBlank()) throw new IllegalArgumentException(message);
    }
  }
}
