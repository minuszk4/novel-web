import { HttpClient, HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom, timeout } from 'rxjs';

const exportsUrl = '/api/v1/me/data-exports';
const csrfUrl = '/api/v1/auth/csrf';
const csrfPattern = /^[A-Za-z0-9_-]{43}$/;
const idPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const idempotencyKeyPattern = /^[A-Za-z0-9._~-]{16,128}$/;
const requestTimeoutMs = 10_000;

export type AccountDataExportStatus = 'queued' | 'processing' | 'ready' | 'failed' | 'expired';

export interface AccountDataExportJob {
  id: string;
  status: AccountDataExportStatus;
  requestedAt: string;
  startedAt: string | null;
  readyAt: string | null;
  expiresAt: string | null;
  artifactBytes: number | null;
  failureCode: string | null;
  downloadUrl: string | null;
}

export class AccountDataExportApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
  ) {
    super(code);
  }
}

@Injectable({ providedIn: 'root' })
export class AccountDataExportApiService {
  private readonly http = inject(HttpClient);

  async request(idempotencyKey: string): Promise<{ job: AccountDataExportJob; replayed: boolean }> {
    if (!idempotencyKeyPattern.test(idempotencyKey)) {
      throw new AccountDataExportApiError(400, 'INVALID_IDEMPOTENCY_KEY');
    }
    try {
      const response = await firstValueFrom(
        this.http
          .post<unknown>(exportsUrl, null, {
            headers: (await this.csrfHeaders()).set('Idempotency-Key', idempotencyKey),
          })
          .pipe(timeout(requestTimeoutMs)),
      );
      if (!isObject(response) || typeof response['replayed'] !== 'boolean') {
        throw new AccountDataExportApiError(503, 'INVALID_EXPORT_RESPONSE');
      }
      return { job: parseJob(response['export']), replayed: response['replayed'] };
    } catch (error) {
      throw mapError(error);
    }
  }

  async status(exportId: string): Promise<AccountDataExportJob> {
    if (!idPattern.test(exportId)) throw new AccountDataExportApiError(400, 'INVALID_EXPORT_ID');
    try {
      const response = await firstValueFrom(
        this.http
          .get<unknown>(`${exportsUrl}/${encodeURIComponent(exportId)}`)
          .pipe(timeout(requestTimeoutMs)),
      );
      return parseJob(response);
    } catch (error) {
      throw mapError(error);
    }
  }

  async latest(): Promise<AccountDataExportJob> {
    try {
      const response = await firstValueFrom(
        this.http.get<unknown>(`${exportsUrl}/latest`).pipe(timeout(requestTimeoutMs)),
      );
      return parseJob(response);
    } catch (error) {
      throw mapError(error);
    }
  }

  private async csrfHeaders(): Promise<HttpHeaders> {
    try {
      const response: unknown = await firstValueFrom(
        this.http.get<unknown>(csrfUrl).pipe(timeout(requestTimeoutMs)),
      );
      if (!isObject(response) || typeof response['token'] !== 'string') {
        throw new AccountDataExportApiError(503, 'CSRF_UNAVAILABLE');
      }
      if (!csrfPattern.test(response['token'])) {
        throw new AccountDataExportApiError(503, 'CSRF_UNAVAILABLE');
      }
      return new HttpHeaders({ 'X-CSRF-Token': response['token'] });
    } catch (error) {
      throw mapError(error);
    }
  }
}

function parseJob(value: unknown): AccountDataExportJob {
  if (!isObject(value)) throw new AccountDataExportApiError(503, 'INVALID_EXPORT_RESPONSE');
  const status = value['status'];
  const requestedAt = value['requestedAt'];
  const startedAt = value['startedAt'];
  const readyAt = value['readyAt'];
  const expiresAt = value['expiresAt'];
  const artifactBytes = value['artifactBytes'];
  const failureCode = value['failureCode'];
  const downloadUrl = value['downloadUrl'];
  if (
    typeof value['id'] !== 'string' ||
    !idPattern.test(value['id']) ||
    !['queued', 'processing', 'ready', 'failed', 'expired'].includes(String(status)) ||
    !isTimestamp(requestedAt) ||
    !isNullableTimestamp(startedAt) ||
    !isNullableTimestamp(readyAt) ||
    !isNullableTimestamp(expiresAt) ||
    !(
      artifactBytes === null ||
      (Number.isSafeInteger(artifactBytes) && Number(artifactBytes) > 0)
    ) ||
    !(failureCode === null || typeof failureCode === 'string') ||
    !(downloadUrl === null || downloadUrl === `${exportsUrl}/${value['id']}/download`)
  ) {
    throw new AccountDataExportApiError(503, 'INVALID_EXPORT_RESPONSE');
  }
  return {
    id: value['id'],
    status: status as AccountDataExportStatus,
    requestedAt,
    startedAt,
    readyAt,
    expiresAt,
    artifactBytes: artifactBytes as number | null,
    failureCode: failureCode as string | null,
    downloadUrl: downloadUrl as string | null,
  };
}

function isTimestamp(value: unknown): value is string {
  return typeof value === 'string' && Number.isFinite(Date.parse(value));
}

function isNullableTimestamp(value: unknown): value is string | null {
  return value === null || isTimestamp(value);
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function mapError(error: unknown): AccountDataExportApiError {
  if (error instanceof AccountDataExportApiError) return error;
  if (error instanceof HttpErrorResponse) {
    const problem = isObject(error.error) ? error.error : {};
    const code = typeof problem['code'] === 'string' ? problem['code'] : 'REQUEST_FAILED';
    return new AccountDataExportApiError(error.status, code);
  }
  return new AccountDataExportApiError(0, 'NETWORK_UNAVAILABLE');
}
