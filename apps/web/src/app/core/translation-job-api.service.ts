import { HttpClient, HttpErrorResponse, HttpHeaders, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom, timeout } from 'rxjs';

export type TranslationJobState =
  | 'queued'
  | 'preparing'
  | 'translating'
  | 'validating'
  | 'retry_wait'
  | 'review_required'
  | 'completed'
  | 'failed'
  | 'cancel_requested'
  | 'cancelled';

export interface TranslationJobStatus {
  job: {
    id: string;
    workId: string;
    chapterId: string;
    sourceRevisionId: string;
    workTitle: string;
    chapterTitle: string;
    chapterOrdinal: number;
    canManage: boolean;
    sourceLanguage: string;
    targetLanguage: string;
    providerId: 'fake-local';
    status: TranslationJobState;
    totalUnits: number;
    completedUnits: number;
    failedUnits: number;
    queuedUnits: number;
    runningUnits: number;
    retryWaitUnits: number;
    cancelledUnits: number;
    failureCode: string | null;
    candidateId: string | null;
    candidateStatus: string | null;
    createdAt: string;
    updatedAt: string;
    startedAt: string | null;
    completedAt: string | null;
    cancelRequestedAt: string | null;
  };
  terminalUnits: number;
  progressPercent: number;
}

export interface TranslationEnqueueResult {
  jobId: string;
  replayed: boolean;
  location: string | null;
}

export interface TranslationJobStatusResult {
  status: TranslationJobStatus;
  retryAfterSeconds: number | null;
}

export interface TranslationActionReceipt {
  jobId: string;
  jobStatus: string;
  retriedUnits?: number;
  cancelledUnits?: number;
  cancelledAttempts?: number;
  replayed: boolean;
  outboxEventId: string;
}

const jobsUrl = '/api/v1/translation/jobs';
const csrfUrl = '/api/v1/auth/csrf';
const requestTimeoutMs = 10_000;
const csrfTokenPattern = /^[A-Za-z0-9_-]{43}$/;
const idempotencyKeyPattern = /^[A-Za-z0-9._~-]{16,128}$/;
const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const jobStates = new Set<TranslationJobState>([
  'queued',
  'preparing',
  'translating',
  'validating',
  'retry_wait',
  'review_required',
  'completed',
  'failed',
  'cancel_requested',
  'cancelled',
]);

@Injectable({ providedIn: 'root' })
export class TranslationJobApiService {
  private readonly http = inject(HttpClient);

  async enqueue(
    chapterId: string,
    sourceRevisionId: string,
    targetLanguage: 'en' | 'vi',
    idempotencyKey: string,
  ): Promise<TranslationEnqueueResult> {
    if (
      !isUuid(chapterId) ||
      !isUuid(sourceRevisionId) ||
      !['en', 'vi'].includes(targetLanguage) ||
      !isValidIdempotencyKey(idempotencyKey)
    ) {
      throw new TranslationJobApiError(400, 'INVALID_TRANSLATION_REQUEST');
    }

    try {
      const response = await firstValueFrom(
        this.http
          .post<unknown>(
            jobsUrl,
            { chapterId, sourceRevisionId, targetLanguage },
            {
              headers: (await this.csrfHeaders()).set('Idempotency-Key', idempotencyKey),
              observe: 'response',
            },
          )
          .pipe(timeout(requestTimeoutMs)),
      );
      const body = parseEnqueue(response.body);
      return {
        jobId: body.jobId,
        replayed: body.replayed,
        location: response.headers.get('Location'),
      };
    } catch (error) {
      throw toApiError(error);
    }
  }

  async getStatus(jobId: string): Promise<TranslationJobStatusResult> {
    if (!isUuid(jobId)) throw new TranslationJobApiError(400, 'INVALID_TRANSLATION_REQUEST');
    try {
      const response = await firstValueFrom(
        this.http
          .get<unknown>(`${jobsUrl}/${jobId}`, { observe: 'response' })
          .pipe(timeout(requestTimeoutMs)),
      );
      return {
        status: parseStatus(response.body),
        retryAfterSeconds: parseRetryAfter(response),
      };
    } catch (error) {
      throw toApiError(error);
    }
  }

  async retry(jobId: string, idempotencyKey: string): Promise<TranslationActionReceipt> {
    return this.postAction(`${jobsUrl}/${validatedJobId(jobId)}/retry`, idempotencyKey, 'retry');
  }

  async cancel(jobId: string, idempotencyKey: string): Promise<TranslationActionReceipt> {
    return this.postAction(`${jobsUrl}/${validatedJobId(jobId)}/cancel`, idempotencyKey, 'cancel');
  }

  private async postAction(
    url: string,
    idempotencyKey: string,
    action: 'retry' | 'cancel',
  ): Promise<TranslationActionReceipt> {
    if (!isValidIdempotencyKey(idempotencyKey)) {
      throw new TranslationJobApiError(400, 'INVALID_TRANSLATION_REQUEST');
    }
    try {
      const response = await firstValueFrom(
        this.http
          .post<unknown>(url, null, {
            headers: (await this.csrfHeaders()).set('Idempotency-Key', idempotencyKey),
            observe: 'response',
          })
          .pipe(timeout(requestTimeoutMs)),
      );
      return parseReceipt(response.body, action);
    } catch (error) {
      throw toApiError(error);
    }
  }

  private async csrfHeaders(): Promise<HttpHeaders> {
    try {
      const response: unknown = await firstValueFrom(
        this.http.get<unknown>(csrfUrl).pipe(timeout(requestTimeoutMs)),
      );
      if (!isRecord(response) || typeof response['token'] !== 'string') {
        throw new TranslationJobApiError(503, 'CSRF_UNAVAILABLE');
      }
      const token = response['token'];
      if (!csrfTokenPattern.test(token)) throw new TranslationJobApiError(503, 'CSRF_UNAVAILABLE');
      return new HttpHeaders({ 'X-CSRF-Token': token });
    } catch (error) {
      throw toApiError(error);
    }
  }
}

export class TranslationJobApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string | null,
  ) {
    super(code ?? 'TRANSLATION_UNAVAILABLE');
    this.name = 'TranslationJobApiError';
  }
}

export function newTranslationIdempotencyKey(): string {
  return globalThis.crypto.randomUUID();
}

function parseEnqueue(value: unknown): { jobId: string; replayed: boolean } {
  if (
    !isRecord(value) ||
    !isUuid(value['jobId']) ||
    typeof value['replayed'] !== 'boolean' ||
    value['providerId'] !== 'fake-local'
  ) {
    throw new TranslationJobApiError(502, 'INVALID_TRANSLATION_RESPONSE');
  }
  return { jobId: value['jobId'], replayed: value['replayed'] };
}

function parseStatus(value: unknown): TranslationJobStatus {
  if (!isRecord(value) || !isRecord(value['job'])) {
    throw new TranslationJobApiError(502, 'INVALID_TRANSLATION_RESPONSE');
  }
  const job = value['job'];
  const valid =
    isUuid(job['id']) &&
    isUuid(job['workId']) &&
    isUuid(job['chapterId']) &&
    isUuid(job['sourceRevisionId']) &&
    isString(job['workTitle']) &&
    isString(job['chapterTitle']) &&
    isPositiveInteger(job['chapterOrdinal']) &&
    typeof job['canManage'] === 'boolean' &&
    isString(job['sourceLanguage']) &&
    isString(job['targetLanguage']) &&
    job['providerId'] === 'fake-local' &&
    typeof job['status'] === 'string' &&
    jobStates.has(job['status'] as TranslationJobState) &&
    isNonNegativeInteger(job['totalUnits']) &&
    isNonNegativeInteger(job['completedUnits']) &&
    isNonNegativeInteger(job['failedUnits']) &&
    isNonNegativeInteger(job['queuedUnits']) &&
    isNonNegativeInteger(job['runningUnits']) &&
    isNonNegativeInteger(job['retryWaitUnits']) &&
    isNonNegativeInteger(job['cancelledUnits']) &&
    isNullableString(job['failureCode']) &&
    isNullableUuid(job['candidateId']) &&
    isNullableString(job['candidateStatus']) &&
    isInstant(job['createdAt']) &&
    isInstant(job['updatedAt']) &&
    isNullableInstant(job['startedAt']) &&
    isNullableInstant(job['completedAt']) &&
    isNullableInstant(job['cancelRequestedAt']) &&
    isNonNegativeInteger(value['terminalUnits']) &&
    isNonNegativeInteger(value['progressPercent']) &&
    Number(value['progressPercent']) <= 100;
  if (!valid) throw new TranslationJobApiError(502, 'INVALID_TRANSLATION_RESPONSE');
  return value as unknown as TranslationJobStatus;
}

function parseReceipt(value: unknown, action: 'retry' | 'cancel'): TranslationActionReceipt {
  if (
    !isRecord(value) ||
    !isUuid(value['jobId']) ||
    !isString(value['jobStatus']) ||
    typeof value['replayed'] !== 'boolean' ||
    !isUuid(value['outboxEventId']) ||
    (action === 'retry' && !isNonNegativeInteger(value['retriedUnits'])) ||
    (action === 'cancel' &&
      (!isNonNegativeInteger(value['cancelledUnits']) ||
        !isNonNegativeInteger(value['cancelledAttempts'])))
  ) {
    throw new TranslationJobApiError(502, 'INVALID_TRANSLATION_RESPONSE');
  }
  return value as unknown as TranslationActionReceipt;
}

function parseRetryAfter(response: HttpResponse<unknown>): number | null {
  const value = response.headers.get('Retry-After');
  if (!value || !/^\d+$/.test(value)) return null;
  const seconds = Number(value);
  return seconds >= 1 && seconds <= 60 ? seconds : null;
}

function validatedJobId(jobId: string): string {
  if (!isUuid(jobId)) throw new TranslationJobApiError(400, 'INVALID_TRANSLATION_REQUEST');
  return jobId;
}

function toApiError(error: unknown): TranslationJobApiError {
  if (error instanceof TranslationJobApiError) return error;
  if (!(error instanceof HttpErrorResponse)) {
    return new TranslationJobApiError(0, 'TRANSLATION_UNAVAILABLE');
  }
  const code = isRecord(error.error) && isString(error.error['code']) ? error.error['code'] : null;
  return new TranslationJobApiError(error.status, code);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isString(value: unknown): value is string {
  return typeof value === 'string';
}

function isNullableString(value: unknown): value is string | null {
  return value === null || isString(value);
}

function isUuid(value: unknown): value is string {
  return typeof value === 'string' && uuidPattern.test(value);
}

function isNullableUuid(value: unknown): value is string | null {
  return value === null || isUuid(value);
}

function isPositiveInteger(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value > 0;
}

function isNonNegativeInteger(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0;
}

function isInstant(value: unknown): value is string {
  return typeof value === 'string' && Number.isFinite(Date.parse(value));
}

function isNullableInstant(value: unknown): value is string | null {
  return value === null || isInstant(value);
}

function isValidIdempotencyKey(value: string): boolean {
  return typeof value === 'string' && idempotencyKeyPattern.test(value);
}
