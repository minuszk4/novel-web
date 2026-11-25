package com.novelweb.payment.credit;

import com.novelweb.payment.credit.CreditTopUpOrderService.CheckoutClaim;
import com.novelweb.payment.credit.CreditTopUpOrderService.CheckoutOutcome;
import com.novelweb.payment.credit.CreditTopUpOrderService.CreateResult;
import com.novelweb.payment.credit.CreditTopUpOrderService.Provider;
import com.novelweb.payment.credit.CreditTopUpOrderService.TopUpOrder;
import com.novelweb.payment.momo.MomoTestGatewayClient;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

/** Starts at most one provider create attempt for a durable, actor-owned order snapshot. */
public final class CreditTopUpCheckoutService {

  private final CreditTopUpOrderService orders;
  private final Provider provider;
  private final ProviderCreate momoProviderCreate;
  private final VnPayProviderCreate vnPayProviderCreate;
  private final Predicate<String> checkoutUrlPolicy;

  public CreditTopUpCheckoutService(CreditTopUpOrderService orders, ProviderCreate providerCreate) {
    this.orders = Objects.requireNonNull(orders, "top-up order service is required");
    this.provider = Provider.MOMO;
    this.momoProviderCreate =
        Objects.requireNonNull(providerCreate, "MoMo TEST gateway is required");
    this.vnPayProviderCreate = null;
    this.checkoutUrlPolicy = com.novelweb.payment.momo.MomoTestCheckoutUrlPolicy::allows;
  }

  public CreditTopUpCheckoutService(
      CreditTopUpOrderService orders,
      Provider provider,
      VnPayProviderCreate providerCreate,
      Predicate<String> checkoutUrlPolicy) {
    this.orders = Objects.requireNonNull(orders, "top-up order service is required");
    if (provider != Provider.VNPAY) {
      throw new IllegalArgumentException("this checkout adapter requires the VNPAY provider");
    }
    this.provider = provider;
    this.momoProviderCreate = null;
    this.vnPayProviderCreate =
        Objects.requireNonNull(providerCreate, "VNPAY TEST gateway is required");
    this.checkoutUrlPolicy =
        Objects.requireNonNull(checkoutUrlPolicy, "checkout URL policy is required");
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
    return createOrReplay(userId, idempotencyKey, catalog, packId, packVersion, "127.0.0.1");
  }

  /** Creates/replays a quote and passes the request's resolved peer address to VNPAY. */
  public CheckoutResult createOrReplay(
      UUID userId,
      String idempotencyKey,
      CreditPackCatalog catalog,
      String packId,
      int packVersion,
      String clientIpAddress) {
    CreateResult creation =
        orders.createOrReplay(userId, idempotencyKey, catalog, packId, packVersion, provider);
    if (creation.order() == null) {
      return new CheckoutResult(creation.outcome(), null, null, null);
    }
    return start(userId, creation.order(), creation.outcome(), clientIpAddress);
  }

  private CheckoutResult start(
      UUID userId,
      TopUpOrder createdOrReplayedOrder,
      CreditTopUpOrderService.Outcome creationOutcome,
      String clientIpAddress) {
    CheckoutClaim claim = orders.claimProviderCreate(userId, createdOrReplayedOrder.id());
    if (claim.outcome() != CheckoutOutcome.CLAIMED) {
      return new CheckoutResult(
          creationOutcome, claim.outcome(), claim.order(), claim.checkoutUrl());
    }

    String checkoutUrl;
    try {
      if (provider == Provider.VNPAY) {
        checkoutUrl =
            vnPayProviderCreate.create(
                claim.order().quote().amountVnd(),
                claim.order().id(),
                claim.order().requestId(),
                clientIpAddress);
      } else {
        MomoTestGatewayClient.CreateResult providerResult =
            momoProviderCreate.create(
                claim.order().quote().amountVnd(), claim.order().id(), claim.order().requestId());
        checkoutUrl =
            providerResult != null
                    && providerResult.state() == MomoTestGatewayClient.CreateState.READY
                ? providerResult.payUrl()
                : null;
      }
    } catch (RuntimeException exception) {
      // Provider transport or signing failure is ambiguous; never retry the create blindly.
      CheckoutClaim unresolved = orders.finishProviderCreate(userId, claim.order().id(), null);
      return new CheckoutResult(
          creationOutcome, unresolved.outcome(), unresolved.order(), unresolved.checkoutUrl());
    }

    CheckoutClaim finished =
        orders.finishProviderCreate(userId, claim.order().id(), checkoutUrl, checkoutUrlPolicy);
    return new CheckoutResult(
        creationOutcome, finished.outcome(), finished.order(), finished.checkoutUrl());
  }

  @FunctionalInterface
  public interface ProviderCreate {
    MomoTestGatewayClient.CreateResult create(long amountVnd, UUID orderId, UUID requestId);
  }

  @FunctionalInterface
  public interface VnPayProviderCreate {
    String create(long amountVnd, UUID orderId, UUID requestId, String clientIpAddress);
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
