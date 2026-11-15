package com.novelweb.payment.credit;

import com.novelweb.payment.credit.CreditTopUpOrderService.CheckoutClaim;
import com.novelweb.payment.credit.CreditTopUpOrderService.CheckoutOutcome;
import com.novelweb.payment.credit.CreditTopUpOrderService.CreateResult;
import com.novelweb.payment.credit.CreditTopUpOrderService.TopUpOrder;
import com.novelweb.payment.momo.MomoTestGatewayClient;
import java.util.Objects;
import java.util.UUID;

/** Starts at most one MoMo TEST create request for a durable, actor-owned order snapshot. */
public final class CreditTopUpCheckoutService {

  private final CreditTopUpOrderService orders;
  private final ProviderCreate providerCreate;

  public CreditTopUpCheckoutService(CreditTopUpOrderService orders, ProviderCreate providerCreate) {
    this.orders = Objects.requireNonNull(orders, "top-up order service is required");
    this.providerCreate = Objects.requireNonNull(providerCreate, "MoMo TEST gateway is required");
  }

  /**
   * Persists/replays the order first, claims the one create attempt, then calls MoMo
   * off-transaction.
   */
  public CheckoutResult createOrReplay(
      UUID userId, String idempotencyKey, CreditPackCatalog catalog, String packId) {
    int packVersion =
        catalog.quote(packId).map(CreditPackCatalog.CreditPackQuote::version).orElse(-1);
    return createOrReplay(userId, idempotencyKey, catalog, packId, packVersion);
  }

  public CheckoutResult createOrReplay(
      UUID userId,
      String idempotencyKey,
      CreditPackCatalog catalog,
      String packId,
      int packVersion) {
    CreateResult creation =
        orders.createOrReplay(userId, idempotencyKey, catalog, packId, packVersion);
    if (creation.order() == null) {
      return new CheckoutResult(creation.outcome(), null, null, null);
    }
    return start(userId, creation.order(), creation.outcome());
  }

  private CheckoutResult start(
      UUID userId,
      TopUpOrder createdOrReplayedOrder,
      CreditTopUpOrderService.Outcome creationOutcome) {
    CheckoutClaim claim = orders.claimProviderCreate(userId, createdOrReplayedOrder.id());
    if (claim.outcome() != CheckoutOutcome.CLAIMED) {
      return new CheckoutResult(
          creationOutcome, claim.outcome(), claim.order(), claim.checkoutUrl());
    }

    MomoTestGatewayClient.CreateResult providerResult;
    try {
      providerResult =
          providerCreate.create(
              claim.order().quote().amountVnd(), claim.order().id(), claim.order().requestId());
    } catch (RuntimeException exception) {
      // A transport failure may happen after MoMo received the request; never retry create blindly.
      CheckoutClaim unresolved = orders.finishProviderCreate(userId, claim.order().id(), null);
      return new CheckoutResult(
          creationOutcome, unresolved.outcome(), unresolved.order(), unresolved.checkoutUrl());
    }

    String payUrl =
        providerResult != null && providerResult.state() == MomoTestGatewayClient.CreateState.READY
            ? providerResult.payUrl()
            : null;
    CheckoutClaim finished = orders.finishProviderCreate(userId, claim.order().id(), payUrl);
    return new CheckoutResult(
        creationOutcome, finished.outcome(), finished.order(), finished.checkoutUrl());
  }

  @FunctionalInterface
  public interface ProviderCreate {
    MomoTestGatewayClient.CreateResult create(long amountVnd, UUID orderId, UUID requestId);
  }

  /** The URL is available for the initial/replayed create result only; never log this value. */
  public record CheckoutResult(
      CreditTopUpOrderService.Outcome creationOutcome,
      CheckoutOutcome checkoutOutcome,
      TopUpOrder order,
      String checkoutUrl) {

    @Override
    public String toString() {
      return "CheckoutResult[creationOutcome="
          + creationOutcome
          + ", checkoutOutcome="
          + checkoutOutcome
          + ", order="
          + order
          + ", checkoutUrl=[REDACTED]]";
    }
  }
}
