import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { signal } from '@angular/core';
import { BehaviorSubject } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  CommerceHistoryApiError,
  CommerceHistoryApiService,
} from '../core/commerce-history-api.service';
import { ApiSystemStatus } from '../core/models';
import { SystemStatusService } from '../core/system-status.service';
import { PurchasesPageComponent } from './purchases-page.component';

const order = {
  id: '11111111-1111-4111-8111-111111111111',
  packId: 'momo-test-credits-100',
  packVersion: 1,
  provider: 'MOMO' as const,
  environment: 'TEST' as const,
  currency: 'VND' as const,
  amountVnd: 1000,
  paidCreditsPurchased: 100,
  bonusCreditsPurchased: 0,
  paidExpiresAt: null,
  bonusExpiresAt: null,
  paidCreditsGranted: 0,
  bonusCreditsGranted: 0,
  status: 'PENDING' as const,
  createdAt: '2026-11-15T10:00:00Z',
  updatedAt: '2026-11-15T10:00:00Z',
};

const activeStatus: ApiSystemStatus = {
  apiVersion: 'v1',
  environment: 'test',
  integrations: { googleOidcEnabled: false, momoTestEnabled: true, momoEnvironment: 'TEST' },
  features: {
    importProcessingEnabled: false,
    cultivationEnabled: false,
    commerceSandboxEnabled: true,
    translationFakePreviewEnabled: false,
  },
};

describe('PurchasesPageComponent', () => {
  let queryParams: BehaviorSubject<ReturnType<typeof convertToParamMap>>;
  let api: {
    listPaymentOrders: ReturnType<typeof vi.fn>;
    listCreditTransactions: ReturnType<typeof vi.fn>;
    listPurchases: ReturnType<typeof vi.fn>;
    getCreditBalance: ReturnType<typeof vi.fn>;
  };

  beforeEach(() => {
    queryParams = new BehaviorSubject(convertToParamMap({}));
    api = {
      listPaymentOrders: vi.fn().mockResolvedValue({ items: [order], nextCursor: null }),
      listCreditTransactions: vi.fn().mockResolvedValue({ items: [], nextCursor: null }),
      listPurchases: vi.fn().mockResolvedValue({ items: [], nextCursor: null }),
      getCreditBalance: vi.fn().mockResolvedValue({
        paidCredits: 0,
        bonusCredits: 0,
        totalCredits: 0,
        asOf: '2026-11-15T10:00:00Z',
      }),
    };
    TestBed.configureTestingModule({
      imports: [PurchasesPageComponent],
      providers: [
        provideRouter([]),
        { provide: ActivatedRoute, useValue: { queryParamMap: queryParams.asObservable() } },
        { provide: CommerceHistoryApiService, useValue: api },
        { provide: SystemStatusService, useValue: { status: signal(activeStatus) } },
      ],
    });
  });

  it('keeps orders separate from credit history and never counts an unconfirmed order as credit', async () => {
    const fixture = TestBed.createComponent(PurchasesPageComponent);
    fixture.detectChanges();
    await vi.waitFor(() => expect(api.listPaymentOrders).toHaveBeenCalledOnce());
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('Chưa ghi nhận credit');
    });

    expect(fixture.nativeElement.textContent).toContain('MÔI TRƯỜNG TEST');
    expect(fixture.nativeElement.textContent).toContain('Đang chờ xác nhận');
    expect(fixture.nativeElement.textContent).toContain('Chưa ghi nhận credit');
    expect(fixture.nativeElement.textContent).toContain('Đừng thanh toán lại lệnh này.');
    expect(api.listCreditTransactions).not.toHaveBeenCalled();
  });

  it('loads the selected view from the URL and renders a chapter link only for readable purchases', async () => {
    const purchase = {
      entitlementId: '22222222-2222-4222-8222-222222222222',
      chapterId: '33333333-3333-4333-8333-333333333333',
      workId: '44444444-4444-4444-8444-444444444444',
      workTitle: 'Gió qua núi',
      chapterTitle: 'Qua khỏi dốc',
      entitlementStatus: 'GRANTED' as const,
      priceVersion: 1,
      creditsSpent: 10,
      grantedAt: '2026-11-15T10:00:00Z',
      currentReadAccess: 'READABLE' as const,
    };
    api.listPurchases.mockResolvedValue({ items: [purchase], nextCursor: null });
    const fixture = TestBed.createComponent(PurchasesPageComponent);
    fixture.detectChanges();
    queryParams.next(convertToParamMap({ view: 'unlocks' }));
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('Gió qua núi');
    });

    const link = fixture.nativeElement.querySelector('.purchases-read-link') as HTMLAnchorElement;
    expect(fixture.nativeElement.textContent).toContain('Gió qua núi');
    expect(link.getAttribute('href')).toBe(
      '/app/reader/44444444-4444-4444-8444-444444444444/33333333-3333-4333-8333-333333333333',
    );
  });

  it('offers sign-in recovery when the account session expires', async () => {
    api.listPaymentOrders.mockRejectedValue(
      new CommerceHistoryApiError('UNAUTHENTICATED', 401, 'UNAUTHENTICATED'),
    );
    const fixture = TestBed.createComponent(PurchasesPageComponent);
    fixture.detectChanges();
    await vi.waitFor(() => expect(fixture.nativeElement.textContent).toContain('Đăng nhập lại'));
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('a[href^="/sign-in"]')).not.toBeNull();
  });

  it('preserves the current rows if loading a later page fails', async () => {
    api.listPaymentOrders
      .mockResolvedValueOnce({ items: [order], nextCursor: 'cursor-2' })
      .mockRejectedValueOnce(new CommerceHistoryApiError('SERVICE_UNAVAILABLE', 503, null));
    const fixture = TestBed.createComponent(PurchasesPageComponent);
    fixture.detectChanges();
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.querySelector('.purchases-load-more button')).not.toBeNull();
    });
    const loadMore = fixture.nativeElement.querySelector(
      '.purchases-load-more button',
    ) as HTMLButtonElement;
    loadMore.click();
    await vi.waitFor(() =>
      expect(fixture.nativeElement.textContent).toContain('Các mục đã hiện vẫn được giữ nguyên.'),
    );
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Chưa ghi nhận credit');
    expect(api.listPaymentOrders).toHaveBeenLastCalledWith('cursor-2', null, 25);
  });
});
