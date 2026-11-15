import { HttpClient, HttpErrorResponse, HttpHeaders, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom, timeout } from 'rxjs';

export interface ChapterUnlockQuote {
  chapterId: string;
  workId: string;
  chapterTitle: string;
  workTitle: string;
  priceVersion: number;
  priceCredits: number;
  accessAlreadyGranted: boolean;
}

export type ChapterUnlockOutcome = 'CREATED' | 'REPLAYED' | 'ALREADY_OWNED' | 'ALREADY_ACCESSIBLE';

export interface ChapterUnlockResult {
  outcome: ChapterUnlockOutcome;
  entitlementId: string | null;
  chapterId: string;
  priceVersion: number;
  creditsSpent: number;
  grantedAt: string | null;
}

export class ChapterUnlockApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code: string | null,
  ) {
    super(message);
    this.name = 'ChapterUnlockApiError';
  }
}

const quoteUrl = (chapterId: string) => `/api/v1/chapters/${chapterId}/unlock-quote`;
const unlockUrl = (chapterId: string) => `/api/v1/chapters/${chapterId}/unlock`;
const csrfUrl = '/api/v1/auth/csrf';
const attemptStoragePrefix = 'novel.chapter-unlock-attempt.v1:';
const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const idempotencyKeyPattern = /^[A-Za-z0-9._~-]{16,128}$/;
const csrfTokenPattern = /^[A-Za-z0-9_-]{43}$/;
const readTimeoutMs = 5_000;
const writeTimeoutMs = 12_000;

@Injectable({ providedIn: 'root' })
export class ChapterUnlockApiService {
  private readonly http = inject(HttpClient);

  async getQuote(chapterId: string): Promise<ChapterUnlockQuote> {
    if (!uuidPattern.test(chapterId)) throw invalidResponse('INVALID_CHAPTER_ID');
    try {
      const response: unknown = await firstValueFrom(
        this.http.get<unknown>(quoteUrl(chapterId)).pipe(timeout(readTimeoutMs)),
      );
      return parseQuote(response, chapterId);
    } catch (error) {
      throw toUnlockApiError(error);
    }
  }

  async unlock(
    chapterId: string,
    priceVersion: number,
    idempotencyKey: string,
  ): Promise<ChapterUnlockResult> {
    if (
      !uuidPattern.test(chapterId) ||
      !Number.isSafeInteger(priceVersion) ||
      priceVersion < 1 ||
      !idempotencyKeyPattern.test(idempotencyKey)
    ) {
      throw invalidResponse('INVALID_CHAPTER_UNLOCK_REQUEST');
    }
    try {
      const response: HttpResponse<unknown> = await firstValueFrom(
        this.http
          .post<unknown>(
            unlockUrl(chapterId),
            { priceVersion },
            {
              headers: (await this.csrfHeaders()).set('Idempotency-Key', idempotencyKey),
              observe: 'response',
            },
          )
          .pipe(timeout(writeTimeoutMs)),
      );
      if (response.status !== 200 && response.status !== 201) throw invalidResponse();
      return parseResult(response.body, chapterId, priceVersion);
    } catch (error) {
      throw toUnlockApiError(error);
    }
  }

  getOrCreateIdempotencyKey(chapterId: string): string {
    if (!uuidPattern.test(chapterId)) throw invalidResponse('INVALID_CHAPTER_ID');
    const storageKey = `${attemptStoragePrefix}${chapterId}`;
    try {
      const existing = sessionStorage.getItem(storageKey);
      if (existing && idempotencyKeyPattern.test(existing)) return existing;
      const key = crypto.randomUUID();
      sessionStorage.setItem(storageKey, key);
      return key;
    } catch {
      return crypto.randomUUID();
    }
  }

  clearIdempotencyKey(chapterId: string): void {
    if (!uuidPattern.test(chapterId)) return;
    try {
      sessionStorage.removeItem(`${attemptStoragePrefix}${chapterId}`);
    } catch {
      // The server remains authoritative if browser storage is unavailable.
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
      throw invalidResponse('INVALID_CSRF_RESPONSE');
    }
    return new HttpHeaders({ 'X-CSRF-Token': response['token'] });
  }
}

function parseQuote(value: unknown, chapterId: string): ChapterUnlockQuote {
  if (
    !isRecord(value) ||
    !uuidPattern.test(String(value['chapterId'])) ||
    value['chapterId'] !== chapterId ||
    !uuidPattern.test(String(value['workId'])) ||
    !parseText(value['chapterTitle'], 300) ||
    !parseText(value['workTitle'], 300) ||
    !isPositiveInteger(value['priceVersion']) ||
    !isPositiveInteger(value['priceCredits']) ||
    typeof value['accessAlreadyGranted'] !== 'boolean'
  ) {
    throw invalidResponse();
  }
  return {
    chapterId,
    workId: value['workId'] as string,
    chapterTitle: value['chapterTitle'] as string,
    workTitle: value['workTitle'] as string,
    priceVersion: value['priceVersion'] as number,
    priceCredits: value['priceCredits'] as number,
    accessAlreadyGranted: value['accessAlreadyGranted'],
  };
}

function parseResult(
  value: unknown,
  chapterId: string,
  expectedPriceVersion: number,
): ChapterUnlockResult {
  if (
    !isRecord(value) ||
    !['CREATED', 'REPLAYED', 'ALREADY_OWNED', 'ALREADY_ACCESSIBLE'].includes(
      String(value['outcome']),
    ) ||
    value['chapterId'] !== chapterId ||
    !isPositiveInteger(value['priceVersion']) ||
    value['priceVersion'] !== expectedPriceVersion ||
    !isNonNegativeInteger(value['creditsSpent']) ||
    !(value['entitlementId'] === null || isUuid(value['entitlementId'])) ||
    !(value['grantedAt'] === null || isTimestamp(value['grantedAt']))
  ) {
    throw invalidResponse();
  }
  const outcome = value['outcome'] as ChapterUnlockOutcome;
  if ((outcome === 'ALREADY_ACCESSIBLE') !== (value['entitlementId'] === null)) {
    throw invalidResponse();
  }
  return {
    outcome,
    entitlementId: value['entitlementId'] as string | null,
    chapterId,
    priceVersion: expectedPriceVersion,
    creditsSpent: value['creditsSpent'] as number,
    grantedAt: value['grantedAt'] as string | null,
  };
}

function toUnlockApiError(error: unknown): ChapterUnlockApiError {
  if (error instanceof ChapterUnlockApiError) return error;
  if (error instanceof HttpErrorResponse) {
    if (error.status === 0) return new ChapterUnlockApiError('NETWORK_ERROR', 0, null);
    const body = isRecord(error.error) ? error.error : null;
    const code = body && typeof body['code'] === 'string' ? body['code'] : null;
    return new ChapterUnlockApiError(code ?? 'REQUEST_FAILED', error.status, code);
  }
  return new ChapterUnlockApiError('REQUEST_FAILED', 0, null);
}

function invalidResponse(code = 'INVALID_RESPONSE'): ChapterUnlockApiError {
  return new ChapterUnlockApiError(code, 502, code);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isUuid(value: unknown): value is string {
  return typeof value === 'string' && uuidPattern.test(value);
}

function parseText(value: unknown, maxLength: number): boolean {
  return typeof value === 'string' && !!value.trim() && [...value].length <= maxLength;
}

function isPositiveInteger(value: unknown): value is number {
  return Number.isSafeInteger(value) && (value as number) > 0;
}

function isNonNegativeInteger(value: unknown): value is number {
  return Number.isSafeInteger(value) && (value as number) >= 0;
}

function isTimestamp(value: unknown): value is string {
  return (
    typeof value === 'string' &&
    /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?(?:Z|[+-]\d{2}:\d{2})$/.test(value) &&
    Number.isFinite(Date.parse(value))
  );
}
