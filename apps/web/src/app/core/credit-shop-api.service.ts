import { HttpClient, HttpErrorResponse, HttpHeaders, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom, timeout } from 'rxjs';

export type TopUpOrderState =
  | 'CREATED'
  | 'INITIATING'
  | 'PENDING'
  | 'UNKNOWN'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'CANCELLED'
  | 'EXPIRED';

export type CheckoutOutcome =
  | 'READY'
  | 'WAITING_FOR_PROVIDER'
  | 'PROVIDER_CREATE_IN_PROGRESS'
  | 'PROVIDER_RESULT_UNKNOWN'
  | 'NOT_STARTED'
  | 'TERMINAL';

export interface CreditPack {
  packId: string;
  packVersion: number;
  environment: 'TEST';
  currency: 'VND';
  amountVnd: number;
  paidCredits: number;
  bonusCredits: number;
  paidExpiresAt: string | null;
  bonusExpiresAt: string | null;
}

export interface CreditPackCatalog {
  environment: 'TEST';
  offers: CreditPack[];
}

export interface TopUpPaymentOrder {
  id: string;
  packId: string;
  packVersion: number;
  environment: 'TEST';
  currency: 'VND';
  amountVnd: number;
  paidCredits: number;
  bonusCredits: number;
  paidExpiresAt: string | null;
  bonusExpiresAt: string | null;
  status: TopUpOrderState;
  createdAt: string;
}

export interface CreateTopUpResult {
  order: TopUpPaymentOrder;
  checkoutOutcome: CheckoutOutcome | null;
  checkoutUrl: string | null;
  replayed: boolean;
}

export class CreditShopApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code: string | null,
  ) {
    super(message);
    this.name = 'CreditShopApiError';
  }
}

const catalogUrl = '/api/v1/credit-packs';
const createOrderUrl = '/api/v1/vnpay/payment-orders';
const orderStatusUrl = (orderId: string) => `/api/v1/payment-orders/${orderId}`;
const csrfUrl = '/api/v1/auth/csrf';
const csrfTokenPattern = /^[A-Za-z0-9_-]{43}$/;
const idempotencyKeyPattern = /^[A-Za-z0-9._~-]{16,128}$/;
const packIdPattern = /^[A-Za-z0-9._-]{1,64}$/;
const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const readTimeoutMs = 5_000;
const checkoutTimeoutMs = 50_000;

@Injectable({ providedIn: 'root' })
export class CreditShopApiService {
  private readonly http = inject(HttpClient);

  async getCatalog(): Promise<CreditPackCatalog> {
    try {
      const response: unknown = await firstValueFrom(
        this.http.get<unknown>(catalogUrl).pipe(timeout(readTimeoutMs)),
      );
      return parseCatalog(response);
    } catch (error) {
      throw toCreditShopError(error);
    }
  }

  async createOrder(pack: CreditPack, idempotencyKey: string): Promise<CreateTopUpResult> {
    if (!isCreditPack(pack) || !idempotencyKeyPattern.test(idempotencyKey)) {
      throw new CreditShopApiError(
        'INVALID_CREDIT_PACK_REQUEST',
        400,
        'INVALID_CREDIT_PACK_REQUEST',
      );
    }
    try {
      const response: HttpResponse<unknown> = await firstValueFrom(
        this.http
          .post<unknown>(
            createOrderUrl,
            { packId: pack.packId, packVersion: pack.packVersion },
            {
              headers: (await this.csrfHeaders()).set('Idempotency-Key', idempotencyKey),
              observe: 'response',
            },
          )
          .pipe(timeout(checkoutTimeoutMs)),
      );
      if (response.status !== 200 && response.status !== 201) throw invalidResponse();
      return parseCreateResult(
        response.body,
        pack,
        response.headers.get('Idempotency-Replayed') === 'true',
      );
    } catch (error) {
      throw toCreditShopError(error);
    }
  }

  async getOrderStatus(orderId: string): Promise<TopUpPaymentOrder> {
    if (!uuidPattern.test(orderId)) {
      throw new CreditShopApiError('INVALID_PAYMENT_ORDER_ID', 400, 'INVALID_PAYMENT_ORDER_ID');
    }
    try {
      const response: unknown = await firstValueFrom(
        this.http.get<unknown>(orderStatusUrl(orderId)).pipe(timeout(readTimeoutMs)),
      );
      const order = parsePaymentOrder(response);
      if (order.id !== orderId) throw invalidResponse();
      return order;
    } catch (error) {
      throw toCreditShopError(error);
    }
  }

  private async csrfHeaders(): Promise<HttpHeaders> {
    const response: unknown = await firstValueFrom(
      this.http.get<unknown>(csrfUrl).pipe(timeout(readTimeoutMs)),
    );
    if (
      !isRecord(response) ||
      typeof response['token'] !== 'string' ||
      !csrfTokenPattern.test(response['token'])
    ) {
      throw invalidResponse();
    }
    return new HttpHeaders({ 'X-CSRF-Token': response['token'] });
  }
}

function parseCatalog(value: unknown): CreditPackCatalog {
  if (
    !isRecord(value) ||
    value['environment'] !== 'TEST' ||
    !Array.isArray(value['offers']) ||
    value['offers'].length > 20
  ) {
    throw invalidResponse();
  }
  const offers = value['offers'].map(parseCreditPack);
  const identities = offers.map(({ packId, packVersion }) => `${packId}:${packVersion}`);
  if (new Set(identities).size !== identities.length) throw invalidResponse();
  return { environment: 'TEST', offers };
}

function parseCreditPack(value: unknown): CreditPack {
  if (!isRecord(value) || !isCreditPack(value)) throw invalidResponse();
  return {
    packId: value['packId'],
    packVersion: value['packVersion'],
    environment: 'TEST',
    currency: 'VND',
    amountVnd: value['amountVnd'],
    paidCredits: value['paidCredits'],
    bonusCredits: value['bonusCredits'],
    paidExpiresAt: value['paidExpiresAt'],
    bonusExpiresAt: value['bonusExpiresAt'],
  };
}

function isCreditPack(value: unknown): value is CreditPack {
  if (!isRecord(value)) return false;
  return (
    typeof value['packId'] === 'string' &&
    packIdPattern.test(value['packId']) &&
    isPositiveInteger(value['packVersion']) &&
    value['environment'] === 'TEST' &&
    value['currency'] === 'VND' &&
    isPositiveInteger(value['amountVnd']) &&
    value['amountVnd'] >= 1_000 &&
    isPositiveInteger(value['paidCredits']) &&
    isNonNegativeInteger(value['bonusCredits']) &&
    isNullableDateTime(value['paidExpiresAt']) &&
    isNullableDateTime(value['bonusExpiresAt']) &&
    (value['bonusCredits'] > 0 || value['bonusExpiresAt'] === null)
  );
}

function parseCreateResult(value: unknown, pack: CreditPack, replayed: boolean): CreateTopUpResult {
  if (!isRecord(value) || !isRecord(value['order'])) throw invalidResponse();
  const order = parsePaymentOrder(value['order']);
  if (
    order.packId !== pack.packId ||
    order.packVersion !== pack.packVersion ||
    order.environment !== pack.environment ||
    order.currency !== pack.currency ||
    order.amountVnd !== pack.amountVnd ||
    order.paidCredits !== pack.paidCredits ||
    order.bonusCredits !== pack.bonusCredits ||
    order.paidExpiresAt !== pack.paidExpiresAt ||
    order.bonusExpiresAt !== pack.bonusExpiresAt
  ) {
    throw invalidResponse();
  }
  const outcome = value['checkoutOutcome'];
  const allowedOutcomes: CheckoutOutcome[] = [
    'READY',
    'WAITING_FOR_PROVIDER',
    'PROVIDER_CREATE_IN_PROGRESS',
    'PROVIDER_RESULT_UNKNOWN',
    'NOT_STARTED',
    'TERMINAL',
  ];
  if (
    outcome !== null &&
    (typeof outcome !== 'string' || !allowedOutcomes.includes(outcome as CheckoutOutcome))
  ) {
    throw invalidResponse();
  }
  const checkoutUrl = parseCheckoutUrl(value['checkoutUrl']);
  if (
    (checkoutUrl !== null && outcome !== 'READY') ||
    (outcome === 'READY' && checkoutUrl === null)
  ) {
    throw invalidResponse();
  }
  return {
    order,
    checkoutOutcome: outcome as CheckoutOutcome | null,
    checkoutUrl,
    replayed,
  };
}

function parsePaymentOrder(value: unknown): TopUpPaymentOrder {
  if (!isRecord(value)) throw invalidResponse();
  const state = value['status'];
  const allowedStates: TopUpOrderState[] = [
    'CREATED',
    'INITIATING',
    'PENDING',
    'UNKNOWN',
    'SUCCEEDED',
    'FAILED',
    'CANCELLED',
    'EXPIRED',
  ];
  if (
    typeof state !== 'string' ||
    !allowedStates.includes(state as TopUpOrderState) ||
    value['environment'] !== 'TEST' ||
    value['currency'] !== 'VND'
  ) {
    throw invalidResponse();
  }
  return {
    id: parseUuid(value['id']),
    packId: parsePackId(value['packId']),
    packVersion: parsePositiveInteger(value['packVersion']),
    environment: 'TEST',
    currency: 'VND',
    amountVnd: parsePositiveInteger(value['amountVnd']),
    paidCredits: parsePositiveInteger(value['paidCredits']),
    bonusCredits: parseNonNegativeInteger(value['bonusCredits']),
    paidExpiresAt: parseNullableDateTime(value['paidExpiresAt']),
    bonusExpiresAt: parseNullableDateTime(value['bonusExpiresAt']),
    status: state as TopUpOrderState,
    createdAt: parseDateTime(value['createdAt']),
  };
}

function parseCheckoutUrl(value: unknown): string | null {
  if (value === null) return null;
  if (typeof value !== 'string' || value.length > 4_096) throw invalidResponse();
  try {
    const url = new URL(value);
    if (
      url.protocol !== 'https:' ||
      url.hostname.toLowerCase() !== 'sandbox.vnpayment.vn' ||
      (url.port !== '' && url.port !== '443') ||
      url.pathname !== '/paymentv2/vpcpay.html' ||
      !/^[0-9a-f]{128}$/i.test(url.searchParams.get('vnp_SecureHash') ?? '') ||
      url.username ||
      url.password ||
      url.hash
    ) {
      throw invalidResponse();
    }
    return value;
  } catch (error) {
    if (error instanceof CreditShopApiError) throw error;
    throw invalidResponse();
  }
}

function toCreditShopError(error: unknown): CreditShopApiError {
  if (error instanceof CreditShopApiError) return error;
  if (!(error instanceof HttpErrorResponse)) {
    return new CreditShopApiError('NETWORK_ERROR', 0, null);
  }
  const body = isRecord(error.error) ? error.error : null;
  const code = body && typeof body['code'] === 'string' ? body['code'] : null;
  return new CreditShopApiError(code ?? 'REQUEST_FAILED', error.status, code);
}

function invalidResponse(): CreditShopApiError {
  return new CreditShopApiError('INVALID_RESPONSE', 0, 'INVALID_RESPONSE');
}

function parsePackId(value: unknown): string {
  if (typeof value !== 'string' || !packIdPattern.test(value)) throw invalidResponse();
  return value;
}

function parseUuid(value: unknown): string {
  if (typeof value !== 'string' || !uuidPattern.test(value)) throw invalidResponse();
  return value;
}

function parsePositiveInteger(value: unknown): number {
  if (!isPositiveInteger(value)) throw invalidResponse();
  return value;
}

function parseNonNegativeInteger(value: unknown): number {
  if (!isNonNegativeInteger(value)) throw invalidResponse();
  return value;
}

function parseDateTime(value: unknown): string {
  if (typeof value !== 'string' || !Number.isFinite(Date.parse(value))) throw invalidResponse();
  return value;
}

function parseNullableDateTime(value: unknown): string | null {
  if (!isNullableDateTime(value)) throw invalidResponse();
  return value;
}

function isNullableDateTime(value: unknown): value is string | null {
  return value === null || (typeof value === 'string' && Number.isFinite(Date.parse(value)));
}

function isPositiveInteger(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value > 0;
}

function isNonNegativeInteger(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
