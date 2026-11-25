import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { CommerceHistoryApiError, CommerceHistoryApiService } from './commerce-history-api.service';

const order = {
  id: '11111111-1111-4111-8111-111111111111',
  packId: 'vnpay-test-credits-100',
  packVersion: 1,
  provider: 'VNPAY',
  environment: 'TEST',
  currency: 'VND',
  amountVnd: 1000,
  paidCreditsPurchased: 100,
  bonusCreditsPurchased: 0,
  paidExpiresAt: null,
  bonusExpiresAt: null,
  paidCreditsGranted: 0,
  bonusCreditsGranted: 0,
  status: 'PENDING',
  createdAt: '2026-11-15T10:00:00Z',
  updatedAt: '2026-11-15T10:00:00Z',
};

describe('CommerceHistoryApiService', () => {
  let api: CommerceHistoryApiService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    api = TestBed.inject(CommerceHistoryApiService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('parses owner order history and sends the signed cursor and status filter', async () => {
    const result = api.listPaymentOrders('signed-cursor', 'PENDING', 10);
    const request = http.expectOne(
      '/api/v1/me/payment-orders?limit=10&cursor=signed-cursor&state=PENDING',
    );
    expect(request.request.method).toBe('GET');
    request.flush({ items: [order], nextCursor: null });
    await expect(result).resolves.toMatchObject({
      items: [{ id: order.id, paidCreditsGranted: 0 }],
    });
  });

  it('rejects a pending order that claims a grant amount without server ledger evidence shape', async () => {
    const result = api.listPaymentOrders(null, null);
    const request = http.expectOne((candidate) => candidate.url === '/api/v1/me/payment-orders');
    request.flush({ items: [{ ...order, paidCreditsGranted: -1 }], nextCursor: null });
    await expect(result).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
  });

  it('requires the balance total to equal the paid and bonus buckets', async () => {
    const result = api.getCreditBalance();
    http.expectOne('/api/v1/me/credits/balance').flush({
      paidCredits: 100,
      bonusCredits: 2,
      totalCredits: 100,
      asOf: '2026-11-15T10:00:00Z',
    });
    await expect(result).rejects.toBeInstanceOf(CommerceHistoryApiError);
  });

  it('maps authentication errors without exposing response text as UI content', async () => {
    const result = api.listPurchases(null);
    http
      .expectOne('/api/v1/me/purchases?limit=25')
      .flush(
        { code: 'UNAUTHENTICATED', detail: 'private detail' },
        { status: 401, statusText: 'Unauthorized' },
      );
    await expect(result).rejects.toMatchObject({ status: 401, code: 'UNAUTHENTICATED' });
  });
});
