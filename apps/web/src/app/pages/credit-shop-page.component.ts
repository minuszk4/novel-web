import { Component, ElementRef, ViewChild, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import {
  CommerceCreditBalance,
  CommerceHistoryApiError,
  CommerceHistoryApiService,
} from '../core/commerce-history-api.service';
import {
  CreditPack,
  CreditShopApiError,
  CreditShopApiService,
  TopUpOrderState,
  TopUpPaymentOrder,
} from '../core/credit-shop-api.service';
import type { ApiSystemStatus } from '../core/models';
import { SystemStatusService } from '../core/system-status.service';

type CatalogPhase = 'loading' | 'disabled' | 'environment-error' | 'ready' | 'error';
type BalancePhase = 'loading' | 'ready' | 'error';

interface CheckoutAttempt {
  pack: CreditPack;
  idempotencyKey: string;
}

@Component({
  selector: 'app-credit-shop-page',
  imports: [RouterLink],
  templateUrl: './credit-shop-page.component.html',
})
export class CreditShopPageComponent {
  private readonly route = inject(ActivatedRoute);
  private readonly shopApi = inject(CreditShopApiService);
  private readonly historyApi = inject(CommerceHistoryApiService);
  private readonly systemStatus = inject(SystemStatusService);

  @ViewChild('confirmDialog') private confirmDialog?: ElementRef<HTMLDialogElement>;
  @ViewChild('confirmHeading') private confirmHeading?: ElementRef<HTMLElement>;
  @ViewChild('resultHeading') private resultHeading?: ElementRef<HTMLElement>;

  protected readonly catalogPhase = signal<CatalogPhase>('loading');
  protected readonly offers = signal<CreditPack[]>([]);
  protected readonly catalogError = signal<string | null>(null);
  protected readonly recovery = signal<'sign-in' | 'settings' | null>(null);
  protected readonly balancePhase = signal<BalancePhase>('loading');
  protected readonly balance = signal<CommerceCreditBalance | null>(null);
  protected readonly balanceError = signal<string | null>(null);
  protected readonly selectedPack = signal<CreditPack | null>(null);
  protected readonly creatingOrder = signal(false);
  protected readonly checkingStatus = signal(false);
  protected readonly checkoutError = signal<string | null>(null);
  protected readonly uncertainCreate = signal(false);
  protected readonly paymentOrder = signal<TopUpPaymentOrder | null>(null);
  protected readonly checkoutUrl = signal<string | null>(null);
  protected readonly replayedOrder = signal(false);
  protected readonly statusMessage = signal<string | null>(null);
  protected readonly status = this.systemStatus.status;
  protected readonly sandboxEnabled = computed(() => isTestCommerceEnabled(this.status()));
  protected readonly returnTo = safeReaderReturnPath(
    this.route.snapshot.queryParamMap.get('returnTo'),
  );

  constructor() {
    this.restorePendingAttempt();
    void this.loadBalance();
    void this.loadEnvironmentAndCatalog();
  }

  protected async retryBalance(): Promise<void> {
    await this.loadBalance();
  }

  protected async retryCatalog(): Promise<void> {
    if (this.catalogPhase() === 'environment-error') {
      this.catalogPhase.set('loading');
      this.systemStatus.refresh();
      await this.loadEnvironmentAndCatalog();
      return;
    }
    if (this.sandboxEnabled()) await this.loadCatalog();
  }

  protected choosePack(pack: CreditPack): void {
    if (
      this.catalogPhase() !== 'ready' ||
      this.creatingOrder() ||
      this.uncertainCreate() ||
      !this.offers().some((offer) => samePack(offer, pack))
    )
      return;
    this.checkoutError.set(null);
    this.recovery.set(null);
    this.selectedPack.set(pack);
    this.confirmDialog?.nativeElement.showModal();
    this.confirmHeading?.nativeElement.focus();
  }

  protected cancelConfirmation(): void {
    if (this.creatingOrder() || this.uncertainCreate()) return;
    this.confirmDialog?.nativeElement.close();
    this.selectedPack.set(null);
    this.checkoutError.set(null);
    this.uncertainCreate.set(false);
    this.attempt = null;
  }

  protected preventCloseWhileCreating(event: Event): void {
    if (this.creatingOrder() || this.uncertainCreate()) event.preventDefault();
  }

  protected async confirmOrder(): Promise<void> {
    const pack = this.selectedPack();
    if (!pack || this.creatingOrder()) return;
    if (!this.sandboxEnabled()) {
      this.checkoutError.set('MoMo TEST hiện không được xác nhận là đang bật. Chưa gửi lệnh nạp.');
      return;
    }
    if (!this.attempt || !samePack(this.attempt.pack, pack)) {
      this.attempt = { pack, idempotencyKey: globalThis.crypto.randomUUID() };
    }
    if (!this.storeAttempt(this.attempt)) {
      this.checkoutError.set(
        'Trình duyệt chưa lưu được mã an toàn cho yêu cầu. Chưa gửi lệnh nạp; bật bộ nhớ phiên hoặc thử lại.',
      );
      return;
    }
    await this.submitAttempt(this.attempt);
  }

  protected async retrySameOrder(): Promise<void> {
    if (!this.attempt || !this.uncertainCreate() || this.creatingOrder()) return;
    if (!this.sandboxEnabled()) {
      this.checkoutError.set(
        'MoMo TEST hiện không được xác nhận là đang bật. Giữ nguyên yêu cầu và thử lại khi môi trường sẵn sàng.',
      );
      return;
    }
    await this.submitAttempt(this.attempt);
  }

  protected async checkOrderStatus(): Promise<void> {
    const current = this.paymentOrder();
    if (!current || this.checkingStatus()) return;
    this.checkingStatus.set(true);
    this.checkoutError.set(null);
    try {
      const updated = await this.shopApi.getOrderStatus(current.id);
      if (!sameOrderSnapshot(current, updated)) {
        this.checkoutError.set(
          'Máy chủ trả snapshot không khớp với lệnh đã tạo. Không tiếp tục thanh toán từ liên kết này.',
        );
        this.checkoutUrl.set(null);
        return;
      }
      this.paymentOrder.set(updated);
      if (updated.status === 'SUCCEEDED') {
        this.checkoutUrl.set(null);
        await this.loadBalance();
        this.statusMessage.set('Máy chủ đã xác nhận giao dịch; số credit đã được cập nhật.');
      } else {
        this.statusMessage.set(
          'Đã tải lại trạng thái do máy chủ lưu. Việc tải lại không gửi truy vấn thanh toán tới MoMo.',
        );
      }
    } catch (error) {
      this.recovery.set(
        error instanceof CreditShopApiError && error.status === 401
          ? 'sign-in'
          : error instanceof CreditShopApiError && error.status === 403
            ? 'settings'
            : null,
      );
      this.checkoutError.set(
        error instanceof CreditShopApiError && error.status === 401
          ? 'Phiên đăng nhập đã hết hạn. Đăng nhập lại để kiểm tra trạng thái lệnh.'
          : error instanceof CreditShopApiError && error.status === 403
            ? 'Tài khoản cần xác nhận chính sách trước khi xem lệnh.'
            : 'Chưa tải được trạng thái máy chủ. Thử lại sau.',
      );
    } finally {
      this.checkingStatus.set(false);
    }
  }

  protected amount(value: number): string {
    return new Intl.NumberFormat('vi-VN', { maximumFractionDigits: 0 }).format(value);
  }

  protected dateTime(value: string): string {
    return new Intl.DateTimeFormat('vi-VN', {
      dateStyle: 'medium',
      timeStyle: 'short',
    }).format(new Date(value));
  }

  protected orderReference(id: string): string {
    return id.slice(0, 8).toLocaleUpperCase('en-US');
  }

  protected orderStatusLabel(status: TopUpOrderState): string {
    const labels: Record<TopUpOrderState, string> = {
      CREATED: 'Đã tạo lệnh',
      INITIATING: 'Đang tạo phiên thanh toán',
      PENDING: 'Đang chờ MoMo xác nhận',
      UNKNOWN: 'Đang đối soát',
      SUCCEEDED: 'Đã xác nhận',
      FAILED: 'Thất bại',
      CANCELLED: 'Đã hủy',
      EXPIRED: 'Hết hạn',
    };
    return labels[status];
  }

  protected orderStatusShortLabel(status: TopUpOrderState): string {
    const labels: Record<TopUpOrderState, string> = {
      CREATED: 'Mới tạo',
      INITIATING: 'Đang tạo',
      PENDING: 'Đang chờ',
      UNKNOWN: 'Đối soát',
      SUCCEEDED: 'Thành công',
      FAILED: 'Thất bại',
      CANCELLED: 'Đã hủy',
      EXPIRED: 'Hết hạn',
    };
    return labels[status];
  }

  protected isOrderPending(status: TopUpOrderState): boolean {
    return (
      status === 'CREATED' ||
      status === 'INITIATING' ||
      status === 'PENDING' ||
      status === 'UNKNOWN'
    );
  }

  protected returnLink(): string {
    return this.returnTo;
  }

  private attempt: CheckoutAttempt | null = null;

  private async loadEnvironmentAndCatalog(): Promise<void> {
    this.catalogPhase.set('loading');
    const currentStatus = await this.systemStatus.waitForStatus();
    if (!currentStatus) {
      this.catalogPhase.set('environment-error');
      return;
    }
    if (!isTestCommerceEnabled(currentStatus)) {
      this.offers.set([]);
      this.catalogPhase.set('disabled');
      return;
    }
    await this.loadCatalog();
  }

  private async loadCatalog(): Promise<void> {
    if (!this.sandboxEnabled()) {
      this.catalogPhase.set('disabled');
      return;
    }
    this.catalogPhase.set('loading');
    this.catalogError.set(null);
    this.recovery.set(null);
    try {
      const catalog = await this.shopApi.getCatalog();
      if (catalog.environment !== 'TEST')
        throw new CreditShopApiError('INVALID_RESPONSE', 0, 'INVALID_RESPONSE');
      const offers = [...catalog.offers].sort(
        (first, second) =>
          first.paidCredits + first.bonusCredits - (second.paidCredits + second.bonusCredits) ||
          first.amountVnd - second.amountVnd ||
          first.packId.localeCompare(second.packId),
      );
      this.offers.set(offers);
      this.catalogPhase.set('ready');
    } catch (error) {
      const apiError = error instanceof CreditShopApiError ? error : null;
      this.recovery.set(
        apiError?.status === 401 ? 'sign-in' : apiError?.status === 403 ? 'settings' : null,
      );
      this.catalogError.set(
        apiError?.code === 'POLICY_NOT_CONFIGURED'
          ? 'Chính sách tài khoản hiện chưa sẵn sàng. Hãy thử lại sau.'
          : apiError?.status === 401
            ? 'Đăng nhập để xem gói credit TEST.'
            : apiError?.status === 403
              ? 'Cần xác nhận chính sách tài khoản để xem gói credit.'
              : 'Không tải được danh sách gói. Kiểm tra kết nối rồi thử lại.',
      );
      this.catalogPhase.set('error');
    }
  }

  private async submitAttempt(attempt: CheckoutAttempt): Promise<void> {
    this.creatingOrder.set(true);
    this.checkoutError.set(null);
    this.statusMessage.set(null);
    try {
      const result = await this.shopApi.createOrder(attempt.pack, attempt.idempotencyKey);
      this.paymentOrder.set(result.order);
      this.checkoutUrl.set(result.checkoutUrl);
      this.replayedOrder.set(result.replayed);
      this.uncertainCreate.set(false);
      this.attempt = null;
      this.clearStoredAttempt();
      this.confirmDialog?.nativeElement.close();
      this.selectedPack.set(null);
      this.resultHeading?.nativeElement.focus();
      if (result.order.status === 'SUCCEEDED') {
        await this.loadBalance();
        this.statusMessage.set('Máy chủ đã xác nhận giao dịch; số credit đã được cập nhật.');
      } else {
        this.statusMessage.set(
          'Đã tạo hoặc tải lại lệnh. Credit chỉ được cộng sau khi máy chủ xác nhận thanh toán.',
        );
      }
    } catch (error) {
      const apiError = error instanceof CreditShopApiError ? error : null;
      if (
        apiError?.code === 'CREDIT_PACK_VERSION_STALE' ||
        apiError?.code === 'CREDIT_PACK_NOT_FOUND'
      ) {
        this.confirmDialog?.nativeElement.close();
        this.selectedPack.set(null);
        this.attempt = null;
        this.clearStoredAttempt();
        this.uncertainCreate.set(false);
        this.statusMessage.set(
          'Gói đã thay đổi hoặc không còn bán. Danh sách đã được làm mới; hãy chọn và xác nhận lại.',
        );
        await this.loadCatalog();
      } else if (apiError?.status === 401) {
        this.attempt = null;
        this.clearStoredAttempt();
        this.uncertainCreate.set(false);
        this.checkoutError.set('Phiên đăng nhập đã hết hạn. Đăng nhập lại trước khi tạo lệnh.');
        this.recovery.set('sign-in');
      } else if (apiError?.status === 403) {
        this.attempt = null;
        this.clearStoredAttempt();
        this.uncertainCreate.set(false);
        this.checkoutError.set('Tài khoản cần xác nhận chính sách trước khi tạo lệnh.');
        this.recovery.set('settings');
      } else if (apiError?.code === 'IDEMPOTENCY_KEY_REUSED') {
        this.attempt = null;
        this.clearStoredAttempt();
        this.uncertainCreate.set(false);
        this.checkoutError.set(
          'Mã yêu cầu đã được dùng cho một gói khác. Không tạo lệnh mới; hãy kiểm tra lịch sử.',
        );
      } else if (
        apiError !== null &&
        apiError.status >= 400 &&
        apiError.status < 500 &&
        apiError.status !== 408 &&
        apiError.status !== 429
      ) {
        this.attempt = null;
        this.clearStoredAttempt();
        this.uncertainCreate.set(false);
        this.checkoutError.set(
          'Máy chủ từ chối yêu cầu này. Kiểm tra lịch sử trước khi chọn lại gói.',
        );
      } else {
        this.uncertainCreate.set(true);
        this.checkoutError.set(
          'Chưa xác nhận được kết quả lệnh. Có thể máy chủ đã lưu lệnh. Thử lại bằng cùng một mã an toàn; không chọn gói khác khi chưa làm rõ.',
        );
      }
    } finally {
      this.creatingOrder.set(false);
    }
  }

  private async loadBalance(): Promise<void> {
    this.balancePhase.set('loading');
    this.balanceError.set(null);
    try {
      this.balance.set(await this.historyApi.getCreditBalance());
      this.balancePhase.set('ready');
    } catch (error) {
      this.balance.set(null);
      this.balancePhase.set('error');
      this.balanceError.set(
        error instanceof CommerceHistoryApiError && error.status === 401
          ? 'Đăng nhập để xem số dư.'
          : error instanceof CommerceHistoryApiError && error.status === 403
            ? 'Cần xác nhận chính sách để xem số dư.'
            : 'Chưa tải được số dư credit. Thử lại sau.',
      );
    }
  }

  private restorePendingAttempt(): void {
    try {
      const serialized = globalThis.sessionStorage.getItem(pendingAttemptStorageKey);
      if (!serialized) return;
      const value: unknown = JSON.parse(serialized);
      if (!isStoredAttempt(value)) {
        this.clearStoredAttempt();
        return;
      }
      this.attempt = { pack: value.pack, idempotencyKey: value.idempotencyKey };
      this.selectedPack.set(value.pack);
      this.uncertainCreate.set(true);
      this.statusMessage.set(
        'Có yêu cầu nạp trước đó chưa nhận được kết quả. Hãy kiểm tra lại bằng cùng mã an toàn trước khi chọn gói khác.',
      );
    } catch {
      this.clearStoredAttempt();
    }
  }

  private storeAttempt(attempt: CheckoutAttempt): boolean {
    try {
      globalThis.sessionStorage.setItem(pendingAttemptStorageKey, JSON.stringify(attempt));
      return true;
    } catch {
      return false;
    }
  }

  private clearStoredAttempt(): void {
    try {
      globalThis.sessionStorage.removeItem(pendingAttemptStorageKey);
    } catch {
      // Session storage is optional after a definitive server response.
    }
  }
}

const pendingAttemptStorageKey = 'novel.credit-shop.pending-create.v1';
const storedIdempotencyKeyPattern =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

function isStoredAttempt(value: unknown): value is CheckoutAttempt {
  if (!isRecord(value) || !isRecord(value['pack'])) return false;
  const pack = value['pack'];
  return (
    typeof value['idempotencyKey'] === 'string' &&
    storedIdempotencyKeyPattern.test(value['idempotencyKey']) &&
    typeof pack['packId'] === 'string' &&
    /^[A-Za-z0-9._-]{1,64}$/.test(pack['packId']) &&
    Number.isSafeInteger(pack['packVersion']) &&
    Number(pack['packVersion']) > 0 &&
    pack['environment'] === 'TEST' &&
    pack['currency'] === 'VND' &&
    Number.isSafeInteger(pack['amountVnd']) &&
    Number(pack['amountVnd']) >= 1_000 &&
    Number.isSafeInteger(pack['paidCredits']) &&
    Number(pack['paidCredits']) > 0 &&
    Number.isSafeInteger(pack['bonusCredits']) &&
    Number(pack['bonusCredits']) >= 0 &&
    isNullableDate(pack['paidExpiresAt']) &&
    isNullableDate(pack['bonusExpiresAt'])
  );
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isNullableDate(value: unknown): value is string | null {
  return value === null || (typeof value === 'string' && Number.isFinite(Date.parse(value)));
}

function isTestCommerceEnabled(status: ApiSystemStatus | null): boolean {
  return (
    status?.features.commerceSandboxEnabled === true &&
    status.integrations.momoTestEnabled === true &&
    status.integrations.momoEnvironment === 'TEST'
  );
}

function samePack(first: CreditPack, second: CreditPack): boolean {
  return first.packId === second.packId && first.packVersion === second.packVersion;
}

function sameOrderSnapshot(first: TopUpPaymentOrder, second: TopUpPaymentOrder): boolean {
  return (
    first.id === second.id &&
    first.packId === second.packId &&
    first.packVersion === second.packVersion &&
    first.amountVnd === second.amountVnd &&
    first.paidCredits === second.paidCredits &&
    first.bonusCredits === second.bonusCredits &&
    first.environment === second.environment &&
    first.currency === second.currency
  );
}

function safeReaderReturnPath(candidate: string | null): string {
  if (
    !candidate ||
    !candidate.startsWith('/app/reader/') ||
    candidate.startsWith('//') ||
    candidate.includes('\\')
  ) {
    return '/app/purchases';
  }
  try {
    const url = new URL(candidate, window.location.origin);
    const readerPath = /^\/app\/reader\/[0-9a-f-]{36}(?:\/[0-9a-f-]{36})?$/i;
    if (url.origin !== window.location.origin || !readerPath.test(url.pathname) || url.hash) {
      return '/app/purchases';
    }
    return `${url.pathname}${url.search}`;
  } catch {
    return '/app/purchases';
  }
}
