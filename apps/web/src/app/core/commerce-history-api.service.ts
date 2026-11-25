import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom, timeout } from 'rxjs';

export const paymentOrderStates = [
  'CREATED',
  'INITIATING',
  'PENDING',
  'UNKNOWN',
  'SUCCEEDED',
  'FAILED',
  'CANCELLED',
  'EXPIRED',
] as const;

export type PaymentOrderState = (typeof paymentOrderStates)[number];
export type CreditHistoryEntryType =
  'TOP_UP' | 'BONUS_GRANT' | 'UNLOCK_DEBIT' | 'ADJUSTMENT' | 'REVERSAL';
export type CreditBucket = 'PAID' | 'BONUS';
export type CreditSourceType = 'PAYMENT_ORDER' | 'CHAPTER_UNLOCK' | 'OPERATOR' | 'REVERSAL';

export interface CommerceHistoryPage<T> {
  items: T[];
  nextCursor: string | null;
}

export interface CommercePaymentOrder {
  id: string;
  packId: string;
  packVersion: number;
  provider: 'MOMO' | 'VNPAY';
  environment: 'TEST';
  currency: 'VND';
  amountVnd: number;
  paidCreditsPurchased: number;
  bonusCreditsPurchased: number;
  paidExpiresAt: string | null;
  bonusExpiresAt: string | null;
  paidCreditsGranted: number;
  bonusCreditsGranted: number;
  status: PaymentOrderState;
  createdAt: string;
  updatedAt: string;
}

export interface CommerceCreditTransaction {
  id: string;
  entryType: CreditHistoryEntryType;
  bucket: CreditBucket;
  amountDelta: number;
  expiresAt: string | null;
  sourceType: CreditSourceType;
  occurredAt: string;
}

export interface CommerceChapterPurchase {
  entitlementId: string;
  chapterId: string;
  workId: string;
  workTitle: string;
  chapterTitle: string;
  entitlementStatus: 'GRANTED';
  priceVersion: number;
  creditsSpent: number;
  grantedAt: string;
  currentReadAccess: 'READABLE' | 'UNAVAILABLE';
}

export interface CommerceCreditBalance {
  paidCredits: number;
  bonusCredits: number;
  totalCredits: number;
  asOf: string;
}

export class CommerceHistoryApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code: string | null,
  ) {
    super(message);
    this.name = 'CommerceHistoryApiError';
  }
}

const paymentOrdersUrl = '/api/v1/me/payment-orders';
const purchasesUrl = '/api/v1/me/purchases';
const creditTransactionsUrl = '/api/v1/me/credits/transactions';
const creditBalanceUrl = '/api/v1/me/credits/balance';
const requestTimeoutMs = 5_000;
const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

@Injectable({ providedIn: 'root' })
export class CommerceHistoryApiService {
  private readonly http = inject(HttpClient);

  async listPaymentOrders(
    cursor: string | null,
    state: PaymentOrderState | null,
    limit = 25,
  ): Promise<CommerceHistoryPage<CommercePaymentOrder>> {
    validatePageRequest(cursor, limit);
    if (state !== null && !paymentOrderStates.includes(state)) {
      throw new CommerceHistoryApiError(
        'INVALID_PAYMENT_ORDER_STATE',
        400,
        'INVALID_PAYMENT_ORDER_STATE',
      );
    }
    let params = new HttpParams().set('limit', limit);
    if (cursor) params = params.set('cursor', cursor);
    if (state) params = params.set('state', state);
    try {
      const response: unknown = await firstValueFrom(
        this.http.get<unknown>(paymentOrdersUrl, { params }).pipe(timeout(requestTimeoutMs)),
      );
      return parsePage(response, limit, parsePaymentOrder);
    } catch (error) {
      throw toCommerceHistoryError(error);
    }
  }

  async listCreditTransactions(
    cursor: string | null,
    limit = 25,
  ): Promise<CommerceHistoryPage<CommerceCreditTransaction>> {
    validatePageRequest(cursor, limit);
    let params = new HttpParams().set('limit', limit);
    if (cursor) params = params.set('cursor', cursor);
    try {
      const response: unknown = await firstValueFrom(
        this.http.get<unknown>(creditTransactionsUrl, { params }).pipe(timeout(requestTimeoutMs)),
      );
      return parsePage(response, limit, parseCreditTransaction);
    } catch (error) {
      throw toCommerceHistoryError(error);
    }
  }

  async listPurchases(
    cursor: string | null,
    limit = 25,
  ): Promise<CommerceHistoryPage<CommerceChapterPurchase>> {
    validatePageRequest(cursor, limit);
    let params = new HttpParams().set('limit', limit);
    if (cursor) params = params.set('cursor', cursor);
    try {
      const response: unknown = await firstValueFrom(
        this.http.get<unknown>(purchasesUrl, { params }).pipe(timeout(requestTimeoutMs)),
      );
      return parsePage(response, limit, parseChapterPurchase);
    } catch (error) {
      throw toCommerceHistoryError(error);
    }
  }

  async getCreditBalance(): Promise<CommerceCreditBalance> {
    try {
      const response: unknown = await firstValueFrom(
        this.http.get<unknown>(creditBalanceUrl).pipe(timeout(requestTimeoutMs)),
      );
      return parseCreditBalance(response);
    } catch (error) {
      throw toCommerceHistoryError(error);
    }
  }
}

function parsePage<T>(
  value: unknown,
  limit: number,
  parseItem: (item: unknown) => T,
): CommerceHistoryPage<T> {
  if (!isRecord(value) || !Array.isArray(value['items']) || value['items'].length > limit) {
    throw invalidResponse();
  }
  const items = value['items'].map(parseItem);
  if (new Set(items.map((item) => itemIdentity(item))).size !== items.length)
    throw invalidResponse();
  const nextCursor = value['nextCursor'];
  if (
    nextCursor !== null &&
    (typeof nextCursor !== 'string' || nextCursor.length < 1 || nextCursor.length > 256)
  ) {
    throw invalidResponse();
  }
  return { items, nextCursor };
}

function itemIdentity(value: unknown): string {
  if (!isRecord(value)) throw invalidResponse();
  const id = value['id'] ?? value['entitlementId'];
  return parseUuid(id);
}

function parsePaymentOrder(value: unknown): CommercePaymentOrder {
  if (!isRecord(value)) throw invalidResponse();
  const state = value['status'];
  if (
    (value['provider'] !== 'MOMO' && value['provider'] !== 'VNPAY') ||
    value['environment'] !== 'TEST' ||
    value['currency'] !== 'VND' ||
    typeof state !== 'string' ||
    !paymentOrderStates.includes(state as PaymentOrderState)
  ) {
    throw invalidResponse();
  }
  return {
    id: parseUuid(value['id']),
    packId: parseText(value['packId'], 80),
    packVersion: parsePositiveInteger(value['packVersion']),
    provider: value['provider'],
    environment: 'TEST',
    currency: 'VND',
    amountVnd: parsePositiveInteger(value['amountVnd']),
    paidCreditsPurchased: parsePositiveInteger(value['paidCreditsPurchased']),
    bonusCreditsPurchased: parseNonNegativeInteger(value['bonusCreditsPurchased']),
    paidExpiresAt: parseNullableDateTime(value['paidExpiresAt']),
    bonusExpiresAt: parseNullableDateTime(value['bonusExpiresAt']),
    paidCreditsGranted: parseNonNegativeInteger(value['paidCreditsGranted']),
    bonusCreditsGranted: parseNonNegativeInteger(value['bonusCreditsGranted']),
    status: state as PaymentOrderState,
    createdAt: parseDateTime(value['createdAt']),
    updatedAt: parseDateTime(value['updatedAt']),
  };
}

function parseCreditTransaction(value: unknown): CommerceCreditTransaction {
  if (!isRecord(value)) throw invalidResponse();
  const entryType = value['entryType'];
  const bucket = value['bucket'];
  const sourceType = value['sourceType'];
  const allowedEntryTypes: CreditHistoryEntryType[] = [
    'TOP_UP',
    'BONUS_GRANT',
    'UNLOCK_DEBIT',
    'ADJUSTMENT',
    'REVERSAL',
  ];
  const allowedSourceTypes: CreditSourceType[] = [
    'PAYMENT_ORDER',
    'CHAPTER_UNLOCK',
    'OPERATOR',
    'REVERSAL',
  ];
  if (
    (bucket !== 'PAID' && bucket !== 'BONUS') ||
    typeof entryType !== 'string' ||
    !allowedEntryTypes.includes(entryType as CreditHistoryEntryType) ||
    typeof sourceType !== 'string' ||
    !allowedSourceTypes.includes(sourceType as CreditSourceType)
  ) {
    throw invalidResponse();
  }
  return {
    id: parseUuid(value['id']),
    entryType: entryType as CreditHistoryEntryType,
    bucket,
    amountDelta: parseInteger(value['amountDelta']),
    expiresAt: parseNullableDateTime(value['expiresAt']),
    sourceType: sourceType as CreditSourceType,
    occurredAt: parseDateTime(value['occurredAt']),
  };
}

function parseChapterPurchase(value: unknown): CommerceChapterPurchase {
  if (!isRecord(value) || value['entitlementStatus'] !== 'GRANTED') throw invalidResponse();
  const currentReadAccess = value['currentReadAccess'];
  if (currentReadAccess !== 'READABLE' && currentReadAccess !== 'UNAVAILABLE') {
    throw invalidResponse();
  }
  return {
    entitlementId: parseUuid(value['entitlementId']),
    chapterId: parseUuid(value['chapterId']),
    workId: parseUuid(value['workId']),
    workTitle: parseText(value['workTitle'], 300),
    chapterTitle: parseText(value['chapterTitle'], 300),
    entitlementStatus: 'GRANTED',
    priceVersion: parsePositiveInteger(value['priceVersion']),
    creditsSpent: parsePositiveInteger(value['creditsSpent']),
    grantedAt: parseDateTime(value['grantedAt']),
    currentReadAccess,
  };
}

function parseCreditBalance(value: unknown): CommerceCreditBalance {
  if (!isRecord(value)) throw invalidResponse();
  const paidCredits = parseNonNegativeInteger(value['paidCredits']);
  const bonusCredits = parseNonNegativeInteger(value['bonusCredits']);
  const totalCredits = parseNonNegativeInteger(value['totalCredits']);
  if (totalCredits !== paidCredits + bonusCredits) throw invalidResponse();
  return { paidCredits, bonusCredits, totalCredits, asOf: parseDateTime(value['asOf']) };
}

function validatePageRequest(cursor: string | null, limit: number): void {
  if (!Number.isSafeInteger(limit) || limit < 1 || limit > 50) {
    throw new CommerceHistoryApiError('INVALID_PAGE_LIMIT', 400, 'INVALID_PAGE_LIMIT');
  }
  if (cursor !== null && (cursor.length < 1 || cursor.length > 256)) {
    throw new CommerceHistoryApiError('INVALID_CURSOR', 400, 'INVALID_CURSOR');
  }
}

function parseUuid(value: unknown): string {
  if (typeof value !== 'string' || !uuidPattern.test(value)) throw invalidResponse();
  return value;
}

function parseText(value: unknown, maxLength: number): string {
  if (typeof value !== 'string' || !value.trim() || [...value].length > maxLength) {
    throw invalidResponse();
  }
  return value;
}

function parsePositiveInteger(value: unknown): number {
  const parsed = parseInteger(value);
  if (parsed < 1) throw invalidResponse();
  return parsed;
}

function parseNonNegativeInteger(value: unknown): number {
  const parsed = parseInteger(value);
  if (parsed < 0) throw invalidResponse();
  return parsed;
}

function parseInteger(value: unknown): number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value)) throw invalidResponse();
  return value;
}

function parseNullableDateTime(value: unknown): string | null {
  return value === null ? null : parseDateTime(value);
}

function parseDateTime(value: unknown): string {
  if (typeof value !== 'string' || !Number.isFinite(Date.parse(value))) throw invalidResponse();
  return value;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function invalidResponse(): CommerceHistoryApiError {
  return new CommerceHistoryApiError('INVALID_RESPONSE', 0, 'INVALID_RESPONSE');
}

function toCommerceHistoryError(error: unknown): CommerceHistoryApiError {
  if (error instanceof CommerceHistoryApiError) return error;
  if (!(error instanceof HttpErrorResponse)) {
    return new CommerceHistoryApiError('NETWORK_ERROR', 0, null);
  }
  const body = isRecord(error.error) ? error.error : null;
  const code = body && typeof body['code'] === 'string' ? body['code'] : null;
  return new CommerceHistoryApiError(code ?? 'REQUEST_FAILED', error.status, code);
}
