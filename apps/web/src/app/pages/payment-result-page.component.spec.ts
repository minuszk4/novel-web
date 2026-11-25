import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { BehaviorSubject } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { CommerceHistoryApiService } from '../core/commerce-history-api.service';
import { CreditShopApiService } from '../core/credit-shop-api.service';
import { PaymentResultPageComponent } from './payment-result-page.component';

const orderId = '11111111-1111-4111-8111-111111111111';
const pendingOrder = {
  id: orderId,
  packId: 'vnpay-test-credits-100',
  packVersion: 1,
  environment: 'TEST' as const,
  currency: 'VND' as const,
  amountVnd: 1000,
  paidCredits: 100,
  bonusCredits: 0,
  paidExpiresAt: null,
  bonusExpiresAt: null,
  status: 'PENDING' as const,
  createdAt: '2026-11-15T10:00:00Z',
};

describe('PaymentResultPageComponent', () => {
  let queryParams: BehaviorSubject<ReturnType<typeof convertToParamMap>>;
  let shopApi: { getOrderStatus: ReturnType<typeof vi.fn> };
  let historyApi: { getCreditBalance: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    queryParams = new BehaviorSubject(convertToParamMap({ orderId, resultCode: '0' }));
    shopApi = { getOrderStatus: vi.fn().mockResolvedValue(pendingOrder) };
    historyApi = {
      getCreditBalance: vi.fn().mockResolvedValue({
        paidCredits: 0,
        bonusCredits: 0,
        totalCredits: 0,
        asOf: '2026-11-15T10:00:00Z',
      }),
    };
    TestBed.configureTestingModule({
      imports: [PaymentResultPageComponent],
      providers: [
        provideRouter([]),
        { provide: ActivatedRoute, useValue: { queryParamMap: queryParams.asObservable() } },
        { provide: CreditShopApiService, useValue: shopApi },
        { provide: CommerceHistoryApiService, useValue: historyApi },
      ],
    });
  });

  it('uses the saved server order and treats a success-looking return code as untrusted', async () => {
    const fixture = TestBed.createComponent(PaymentResultPageComponent);
    fixture.detectChanges();
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('Chưa xác nhận thanh toán');
    });

    expect(shopApi.getOrderStatus).toHaveBeenCalledWith(orderId);
    expect(fixture.nativeElement.textContent).toContain('100 credit đã mua chưa được cộng');
    expect(fixture.nativeElement.textContent).toContain('Đừng thanh toán lại');
    expect(fixture.nativeElement.textContent).not.toContain('đã được xác nhận');
    expect(historyApi.getCreditBalance).not.toHaveBeenCalled();
  });

  it('maps the VNPAY reference to the saved order and ignores its browser result code', async () => {
    queryParams.next(
      convertToParamMap({
        vnp_TxnRef: orderId.replaceAll('-', ''),
        vnp_ResponseCode: '00',
        vnp_TransactionStatus: '00',
      }),
    );
    const fixture = TestBed.createComponent(PaymentResultPageComponent);
    fixture.detectChanges();
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('Chưa xác nhận thanh toán');
    });

    expect(shopApi.getOrderStatus).toHaveBeenCalledWith(orderId);
    expect(historyApi.getCreditBalance).not.toHaveBeenCalled();
  });

  it('loads credit balance only after the server reports a successful order', async () => {
    shopApi.getOrderStatus.mockResolvedValue({ ...pendingOrder, status: 'SUCCEEDED' });
    historyApi.getCreditBalance.mockResolvedValue({
      paidCredits: 100,
      bonusCredits: 0,
      totalCredits: 100,
      asOf: '2026-11-15T10:00:00Z',
    });
    const fixture = TestBed.createComponent(PaymentResultPageComponent);
    fixture.detectChanges();
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('Số dư hiện tại: 100 credit');
    });

    expect(historyApi.getCreditBalance).toHaveBeenCalledOnce();
  });

  it('does not query the API when the return URL has no valid order id', async () => {
    queryParams.next(convertToParamMap({ resultCode: '0' }));
    const fixture = TestBed.createComponent(PaymentResultPageComponent);
    fixture.detectChanges();
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('Thiếu mã lệnh hợp lệ');
    });

    expect(shopApi.getOrderStatus).not.toHaveBeenCalled();
    expect(historyApi.getCreditBalance).not.toHaveBeenCalled();
  });
});
