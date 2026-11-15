import { HttpHeaders, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { CreditShopApiError, CreditShopApiService } from './credit-shop-api.service';

const pack = {
  packId: 'momo-test-credits-100',
  packVersion: 1,
  environment: 'TEST',
  currency: 'VND',
  amountVnd: 1000,
  paidCredits: 100,
  bonusCredits: 0,
  paidExpiresAt: null,
  bonusExpiresAt: null,
} as const;
const order = {
  id: '11111111-1111-4111-8111-111111111111',
  packId: pack.packId,
  packVersion: 1,
  environment: 'TEST',
  currency: 'VND',
  amountVnd: 1000,
  paidCredits: 100,
  bonusCredits: 0,
  paidExpiresAt: null,
  bonusExpiresAt: null,
  status: 'PENDING',
  createdAt: '2026-11-15T10:00:00Z',
};
const csrfToken = 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA';
const idempotencyKey = 'momo-test-checkout-key-0001';

describe('CreditShopApiService', () => {
  let api: CreditShopApiService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    api = TestBed.inject(CreditShopApiService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('loads only a unique TEST/VND catalog and rejects a production-shaped offer', async () => {
    const result = api.getCatalog();
    http.expectOne('/api/v1/credit-packs').flush({ environment: 'TEST', offers: [pack] });
    await expect(result).resolves.toMatchObject({
      offers: [{ packId: pack.packId, amountVnd: 1000 }],
    });

    const invalid = api.getCatalog();
    http.expectOne('/api/v1/credit-packs').flush({
      environment: 'TEST',
      offers: [{ ...pack, environment: 'PRODUCTION' }],
    });
    await expect(invalid).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
  });

  it('creates from the exact selected server snapshot with CSRF and a stable idempotency key', async () => {
    const result = api.createOrder(pack, idempotencyKey);
    http.expectOne('/api/v1/auth/csrf').flush({ token: csrfToken });
    await nextTask();
    const request = http.expectOne('/api/v1/momo/payment-orders');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({ packId: pack.packId, packVersion: pack.packVersion });
    expect(request.request.headers.get('X-CSRF-Token')).toBe(csrfToken);
    expect(request.request.headers.get('Idempotency-Key')).toBe(idempotencyKey);
    request.flush(
      {
        order,
        checkoutOutcome: 'READY',
        checkoutUrl: 'https://test-payment.momo.vn/v2/gateway/pay?token=signed',
      },
      {
        status: 201,
        statusText: 'Created',
        headers: new HttpHeaders({ Location: `/api/v1/payment-orders/${order.id}` }),
      },
    );
    await expect(result).resolves.toMatchObject({
      order: { id: order.id, amountVnd: 1000, status: 'PENDING' },
      checkoutOutcome: 'READY',
      replayed: false,
    });
  });

  it('rejects a checkout URL outside the exact HTTPS MoMo TEST host and path', async () => {
    const result = api.createOrder(pack, idempotencyKey);
    http.expectOne('/api/v1/auth/csrf').flush({ token: csrfToken });
    await nextTask();
    http.expectOne('/api/v1/momo/payment-orders').flush(
      {
        order,
        checkoutOutcome: 'READY',
        checkoutUrl: 'https://evil.example/v2/gateway/pay?token=synthetic',
      },
      { status: 201, statusText: 'Created' },
    );
    await expect(result).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
  });

  it('reads actor-owned order status without expecting a checkout URL', async () => {
    const result = api.getOrderStatus(order.id);
    const request = http.expectOne(`/api/v1/payment-orders/${order.id}`);
    expect(request.request.method).toBe('GET');
    request.flush({ ...order, status: 'SUCCEEDED' });
    await expect(result).resolves.toMatchObject({ id: order.id, status: 'SUCCEEDED' });
  });

  it('surfaces stable problem codes for stale catalog conflicts without exposing server detail', async () => {
    const result = api.createOrder(pack, idempotencyKey);
    http.expectOne('/api/v1/auth/csrf').flush({ token: csrfToken });
    await nextTask();
    http
      .expectOne('/api/v1/momo/payment-orders')
      .flush(
        { code: 'CREDIT_PACK_VERSION_STALE', detail: 'sensitive provider message' },
        { status: 409, statusText: 'Conflict' },
      );
    await expect(result).rejects.toBeInstanceOf(CreditShopApiError);
    await expect(result).rejects.toMatchObject({ status: 409, code: 'CREDIT_PACK_VERSION_STALE' });
  });
});

function nextTask(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}
