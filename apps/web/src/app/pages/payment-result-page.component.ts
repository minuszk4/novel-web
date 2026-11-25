import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import {
  CommerceHistoryApiError,
  CommerceHistoryApiService,
} from '../core/commerce-history-api.service';
import {
  CreditShopApiError,
  CreditShopApiService,
  TopUpPaymentOrder,
} from '../core/credit-shop-api.service';

type LoadPhase = 'invalid' | 'loading' | 'ready' | 'error';
type BalancePhase = 'idle' | 'loading' | 'ready' | 'error';

const orderIdPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const vnpayOrderReferencePattern = /^[0-9a-f]{32}$/i;
const amountFormatter = new Intl.NumberFormat('vi-VN');

@Component({
  selector: 'app-payment-result-page',
  imports: [RouterLink],
  templateUrl: './payment-result-page.component.html',
  styleUrl: './payment-result-page.component.css',
})
export class PaymentResultPageComponent {
  private readonly route = inject(ActivatedRoute);
  private readonly shopApi = inject(CreditShopApiService);
  private readonly historyApi = inject(CommerceHistoryApiService);
  private revision = 0;

  protected readonly orderId = signal<string | null>(null);
  protected readonly phase = signal<LoadPhase>('invalid');
  protected readonly order = signal<TopUpPaymentOrder | null>(null);
  protected readonly statusError = signal<string | null>(null);
  protected readonly recovery = signal<'sign-in' | 'settings' | null>(null);
  protected readonly checking = signal(false);
  protected readonly balancePhase = signal<BalancePhase>('idle');
  protected readonly totalCredits = signal<number | null>(null);
  protected readonly isUnresolved = computed(() => {
    const status = this.order()?.status;
    return (
      status === 'CREATED' ||
      status === 'INITIATING' ||
      status === 'PENDING' ||
      status === 'UNKNOWN'
    );
  });

  constructor() {
    this.route.queryParamMap.pipe(takeUntilDestroyed()).subscribe((params) => {
      const candidate = params.get('orderId');
      const txnRef = params.get('vnp_TxnRef');
      const id =
        candidate && orderIdPattern.test(candidate)
          ? candidate
          : txnRef && vnpayOrderReferencePattern.test(txnRef)
            ? `${txnRef.slice(0, 8)}-${txnRef.slice(8, 12)}-${txnRef.slice(12, 16)}-${txnRef.slice(16, 20)}-${txnRef.slice(20)}`
            : null;
      this.orderId.set(id);
      this.order.set(null);
      this.statusError.set(null);
      this.recovery.set(null);
      this.checking.set(false);
      this.totalCredits.set(null);
      this.balancePhase.set('idle');
      if (!id) {
        this.phase.set('invalid');
        return;
      }
      void this.loadOrder(id, false);
    });
  }

  protected async checkStatus(): Promise<void> {
    const id = this.orderId();
    if (!id || this.checking()) return;
    await this.loadOrder(id, true);
  }

  protected statusLabel(status: TopUpPaymentOrder['status']): string {
    const labels: Record<TopUpPaymentOrder['status'], string> = {
      CREATED: 'Đang khởi tạo',
      INITIATING: 'Đang khởi tạo',
      PENDING: 'Đang chờ xác nhận',
      UNKNOWN: 'Chưa xác định',
      SUCCEEDED: 'Đã xác nhận thành công',
      FAILED: 'Thanh toán thất bại',
      CANCELLED: 'Đã hủy',
      EXPIRED: 'Đã hết hạn',
    };
    return labels[status];
  }

  protected amount(value: number): string {
    return amountFormatter.format(value);
  }

  protected returnPath(): string {
    const id = this.orderId();
    return id ? `/app/payment/result?orderId=${encodeURIComponent(id)}` : '/app/purchases';
  }

  private async loadOrder(id: string, manualCheck: boolean): Promise<void> {
    const revision = ++this.revision;
    this.statusError.set(null);
    this.recovery.set(null);
    if (manualCheck) this.checking.set(true);
    else this.phase.set('loading');
    try {
      const order = await this.shopApi.getOrderStatus(id);
      if (revision !== this.revision) return;
      if (order.id !== id) throw new CreditShopApiError('INVALID_RESPONSE', 0, 'INVALID_RESPONSE');
      this.order.set(order);
      this.phase.set('ready');
      if (order.status === 'SUCCEEDED') await this.loadConfirmedBalance(revision);
      else {
        this.totalCredits.set(null);
        this.balancePhase.set('idle');
      }
    } catch (error) {
      if (revision !== this.revision) return;
      const apiError = error instanceof CreditShopApiError ? error : null;
      this.recovery.set(
        apiError?.status === 401
          ? 'sign-in'
          : apiError?.status === 403 ||
              (error instanceof CommerceHistoryApiError && error.status === 403)
            ? 'settings'
            : null,
      );
      const message =
        apiError?.status === 401 ||
        (error instanceof CommerceHistoryApiError && error.status === 401)
          ? 'Phiên đăng nhập đã hết hạn. Đăng nhập lại để xem trạng thái lệnh.'
          : apiError?.status === 403 ||
              (error instanceof CommerceHistoryApiError && error.status === 403)
            ? 'Cần xác nhận chính sách tài khoản để xem trạng thái lệnh.'
            : 'Chưa tải được trạng thái do máy chủ lưu. Thử lại sau.';
      if (manualCheck && this.order()) this.statusError.set(message);
      else {
        this.statusError.set(message);
        this.phase.set('error');
      }
    } finally {
      if (revision === this.revision && manualCheck) this.checking.set(false);
    }
  }

  private async loadConfirmedBalance(revision: number): Promise<void> {
    this.balancePhase.set('loading');
    try {
      const balance = await this.historyApi.getCreditBalance();
      if (revision !== this.revision) return;
      this.totalCredits.set(balance.totalCredits);
      this.balancePhase.set('ready');
    } catch {
      if (revision !== this.revision) return;
      this.balancePhase.set('error');
    }
  }
}
