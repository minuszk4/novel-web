import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import {
  CommerceHistoryApiError,
  CommerceHistoryApiService,
  CommerceChapterPurchase,
  CommerceCreditBalance,
  CommerceCreditTransaction,
  CommerceHistoryPage,
  CommercePaymentOrder,
  PaymentOrderState,
  paymentOrderStates,
} from '../core/commerce-history-api.service';
import type { ApiSystemStatus } from '../core/models';
import { SystemStatusService } from '../core/system-status.service';

type PurchasesView = 'orders' | 'credits' | 'unlocks';
type PurchaseHistoryItem =
  CommercePaymentOrder | CommerceCreditTransaction | CommerceChapterPurchase;
type LoadPhase = 'loading' | 'ready' | 'error';

const pageSize = 25;

@Component({
  selector: 'app-purchases-page',
  imports: [RouterLink],
  templateUrl: './purchases-page.component.html',
})
export class PurchasesPageComponent {
  private readonly api = inject(CommerceHistoryApiService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  protected readonly systemStatus = inject(SystemStatusService);

  protected readonly view = signal<PurchasesView>('orders');
  protected readonly orderState = signal<PaymentOrderState | null>(null);
  protected readonly phase = signal<LoadPhase>('loading');
  protected readonly items = signal<PurchaseHistoryItem[]>([]);
  protected readonly nextCursor = signal<string | null>(null);
  protected readonly loadingMore = signal(false);
  protected readonly errorMessage = signal<string | null>(null);
  protected readonly recovery = signal<'sign-in' | 'settings' | null>(null);
  protected readonly balancePhase = signal<LoadPhase>('loading');
  protected readonly balance = signal<CommerceCreditBalance | null>(null);
  protected readonly balanceError = signal<string | null>(null);
  protected readonly availableStates = paymentOrderStates;
  protected readonly sandboxEnabled = computed(() =>
    isTestCommerceEnabled(this.systemStatus.status()),
  );

  private requestRevision = 0;

  constructor() {
    this.route.queryParamMap.pipe(takeUntilDestroyed()).subscribe((params) => {
      const nextView = parseView(params.get('view'));
      const nextState = parseOrderState(params.get('state'));
      const changed = nextView !== this.view() || nextState !== this.orderState();
      this.view.set(nextView);
      this.orderState.set(nextState);
      if (changed || this.phase() === 'loading') {
        this.items.set([]);
        this.nextCursor.set(null);
        this.errorMessage.set(null);
        this.recovery.set(null);
        void this.loadFirstPage();
      }
    });
    void this.loadBalance();
  }

  protected async loadFirstPage(): Promise<void> {
    const revision = ++this.requestRevision;
    const currentView = this.view();
    const currentState = this.orderState();
    this.phase.set('loading');
    this.errorMessage.set(null);
    this.recovery.set(null);
    this.loadingMore.set(false);
    try {
      const page = await this.fetchPage(currentView, null, currentState);
      if (revision !== this.requestRevision) return;
      this.items.set(page.items);
      this.nextCursor.set(page.nextCursor);
      this.phase.set('ready');
    } catch (error) {
      if (revision !== this.requestRevision) return;
      this.showLoadError(error);
      this.phase.set('error');
    }
  }

  protected async loadMore(): Promise<void> {
    const cursor = this.nextCursor();
    if (!cursor || this.loadingMore() || this.phase() !== 'ready') return;
    const revision = this.requestRevision;
    const currentView = this.view();
    const currentState = this.orderState();
    this.loadingMore.set(true);
    this.errorMessage.set(null);
    try {
      const page = await this.fetchPage(currentView, cursor, currentState);
      if (revision !== this.requestRevision) return;
      const seen = new Set(this.items().map(historyItemKey));
      this.items.update((items) => [
        ...items,
        ...page.items.filter((item) => !seen.has(historyItemKey(item))),
      ]);
      this.nextCursor.set(page.nextCursor);
    } catch (error) {
      if (revision !== this.requestRevision) return;
      if (isAccountAccessError(error)) {
        this.items.set([]);
        this.nextCursor.set(null);
        this.showLoadError(error);
        this.phase.set('error');
      } else {
        this.errorMessage.set(
          'Chưa tải được trang tiếp theo. Các mục đã hiện vẫn được giữ nguyên.',
        );
      }
    } finally {
      if (revision === this.requestRevision) this.loadingMore.set(false);
    }
  }

  protected async retryList(): Promise<void> {
    if (this.phase() === 'error' || !this.items().length) {
      await this.loadFirstPage();
    } else {
      await this.loadMore();
    }
  }

  protected async retryBalance(): Promise<void> {
    await this.loadBalance();
  }

  protected changeOrderState(event: Event): void {
    const target = event.target;
    if (!(target instanceof HTMLSelectElement)) return;
    const state = parseOrderState(target.value);
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { view: 'orders', state },
    });
  }

  protected orderStatusLabel(state: PaymentOrderState): string {
    const labels: Record<PaymentOrderState, string> = {
      CREATED: 'Đã tạo lệnh',
      INITIATING: 'Đang kết nối MoMo',
      PENDING: 'Đang chờ xác nhận',
      UNKNOWN: 'Đang đối soát',
      SUCCEEDED: 'Thành công',
      FAILED: 'Thất bại',
      CANCELLED: 'Đã hủy',
      EXPIRED: 'Hết hạn',
    };
    return labels[state];
  }

  protected isPendingOrder(state: PaymentOrderState): boolean {
    return (
      state === 'CREATED' || state === 'INITIATING' || state === 'PENDING' || state === 'UNKNOWN'
    );
  }

  protected grantedCreditTotal(order: CommercePaymentOrder): number {
    return order.paidCreditsGranted + order.bonusCreditsGranted;
  }

  protected orderQuantityLabel(order: CommercePaymentOrder): string {
    const paid = `${this.amount(order.paidCreditsPurchased)} paid credit`;
    return order.bonusCreditsPurchased > 0
      ? `${paid} + ${this.amount(order.bonusCreditsPurchased)} bonus`
      : paid;
  }

  protected packLabel(order: CommercePaymentOrder): string {
    return order.packId === 'momo-test-credits-100'
      ? `Gói TEST 100 paid credit · phiên bản ${order.packVersion}`
      : `Gói credit · phiên bản ${order.packVersion}`;
  }

  protected orderReference(id: string): string {
    return id.slice(0, 8).toLocaleUpperCase('en-US');
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

  protected creditDelta(value: number): string {
    return `${value > 0 ? '+' : ''}${this.amount(value)}`;
  }

  protected ledgerEntryLabel(entry: CommerceCreditTransaction): string {
    const labels: Record<CommerceCreditTransaction['entryType'], string> = {
      TOP_UP: 'Nạp credit đã xác nhận',
      BONUS_GRANT: 'Credit thưởng',
      UNLOCK_DEBIT: 'Mở khóa chương',
      ADJUSTMENT: 'Điều chỉnh số dư',
      REVERSAL: 'Điều chỉnh giao dịch',
    };
    return labels[entry.entryType];
  }

  protected bucketLabel(bucket: CommerceCreditTransaction['bucket']): string {
    return bucket === 'PAID' ? 'Credit đã mua' : 'Credit thưởng';
  }

  protected sourceLabel(source: CommerceCreditTransaction['sourceType']): string {
    const labels: Record<CommerceCreditTransaction['sourceType'], string> = {
      PAYMENT_ORDER: 'Lệnh nạp',
      CHAPTER_UNLOCK: 'Mở chương',
      OPERATOR: 'Hỗ trợ tài khoản',
      REVERSAL: 'Điều chỉnh giao dịch',
    };
    return labels[source];
  }

  protected purchaseRoute(item: CommerceChapterPurchase): string[] {
    return ['/app/reader', item.workId, item.chapterId];
  }

  protected itemKey(item: PurchaseHistoryItem): string {
    return historyItemKey(item);
  }

  protected recoveryReturnUrl(): string {
    return `/app/purchases?view=${this.view()}`;
  }

  private async fetchPage(
    view: PurchasesView,
    cursor: string | null,
    state: PaymentOrderState | null,
  ): Promise<CommerceHistoryPage<PurchaseHistoryItem>> {
    switch (view) {
      case 'orders':
        return this.api.listPaymentOrders(cursor, state, pageSize);
      case 'credits':
        return this.api.listCreditTransactions(cursor, pageSize);
      case 'unlocks':
        return this.api.listPurchases(cursor, pageSize);
    }
  }

  private async loadBalance(): Promise<void> {
    this.balancePhase.set('loading');
    this.balanceError.set(null);
    try {
      this.balance.set(await this.api.getCreditBalance());
      this.balancePhase.set('ready');
    } catch (error) {
      this.balance.set(null);
      this.balanceError.set(
        error instanceof CommerceHistoryApiError && error.status === 401
          ? 'Đăng nhập để xem số dư credit.'
          : error instanceof CommerceHistoryApiError && error.status === 403
            ? 'Cần xác nhận chính sách tài khoản để xem số dư.'
            : 'Chưa tải được số dư. Thử lại sau.',
      );
      this.balancePhase.set('error');
    }
  }

  private showLoadError(error: unknown): void {
    const apiError = error instanceof CommerceHistoryApiError ? error : null;
    this.recovery.set(
      apiError?.status === 401 ? 'sign-in' : apiError?.status === 403 ? 'settings' : null,
    );
    this.errorMessage.set(
      apiError?.status === 401
        ? 'Phiên đăng nhập đã hết hạn. Đăng nhập lại để xem lịch sử mua hàng.'
        : apiError?.status === 403
          ? 'Tài khoản cần hoàn tất xác nhận chính sách để xem lịch sử mua hàng.'
          : 'Không tải được lịch sử lúc này. Kiểm tra kết nối rồi thử lại.',
    );
  }
}

function parseView(value: string | null): PurchasesView {
  return value === 'credits' || value === 'unlocks' ? value : 'orders';
}

function parseOrderState(value: string | null): PaymentOrderState | null {
  if (!value) return null;
  const normalized = value.toUpperCase();
  return paymentOrderStates.find((state) => state === normalized) ?? null;
}

function isTestCommerceEnabled(status: ApiSystemStatus | null): boolean {
  return (
    status?.features.commerceSandboxEnabled === true &&
    status.integrations.momoTestEnabled === true &&
    status.integrations.momoEnvironment === 'TEST'
  );
}

function isAccountAccessError(error: unknown): boolean {
  return error instanceof CommerceHistoryApiError && (error.status === 401 || error.status === 403);
}

function historyItemKey(item: PurchaseHistoryItem): string {
  if ('entitlementId' in item) return item.entitlementId;
  return item.id;
}
