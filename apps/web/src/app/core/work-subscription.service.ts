import { HttpClient, HttpErrorResponse, HttpHeaders, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom, timeout } from 'rxjs';

const base = '/api/v1/works';
const mineBase = '/api/v1/me/subscriptions';
const csrfUrl = '/api/v1/auth/csrf';
const timeoutMs = 8_000;
const csrfPattern = /^[A-Za-z0-9_-]{43}$/;
const etagPattern = /^"subscription-(?:absent|[1-9][0-9]{0,18})"$/;
const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

export interface WorkSubscription {
  following: boolean;
  notificationsEnabled: boolean;
}

export interface WorkSubscriptionReceipt {
  state: WorkSubscription;
  etag: string;
}

export interface FollowedWorkSetting {
  id: string;
  workId: string;
  available: boolean;
  notificationsEnabled: boolean;
  version: number;
  followedAt: string;
  title: string | null;
  author: string | null;
}

export interface FollowedWorkPage {
  items: FollowedWorkSetting[];
  nextCursor: string | null;
}

export class WorkSubscriptionApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    readonly retryAfterSeconds: number | null = null,
  ) {
    super(code);
    this.name = 'WorkSubscriptionApiError';
  }
}

@Injectable({ providedIn: 'root' })
export class WorkSubscriptionApiService {
  private readonly http = inject(HttpClient);

  async listMine(cursor: string | null = null): Promise<FollowedWorkPage> {
    if (cursor !== null && (cursor.length < 1 || cursor.length > 2048)) {
      throw new WorkSubscriptionApiError(400, 'INVALID_SUBSCRIPTION_CURSOR');
    }
    const params = new URLSearchParams({ limit: '20' });
    if (cursor !== null) params.set('cursor', cursor);
    try {
      const response = await firstValueFrom(
        this.http
          .get<unknown>(`${mineBase}?${params.toString()}`, { observe: 'response' })
          .pipe(timeout(timeoutMs)),
      );
      requireNoStore(response);
      const value = response.body;
      if (
        response.status !== 200 ||
        !isRecord(value) ||
        !hasExactKeys(value, 'items,nextCursor') ||
        !Array.isArray(value['items']) ||
        !(value['nextCursor'] === null || isBoundedString(value['nextCursor'], 1, 2048))
      ) {
        throw invalidResponse();
      }
      const items = value['items'].map(parseFollowedWorkSetting);
      if (new Set(items.map((item) => item.id)).size !== items.length) throw invalidResponse();
      return { items, nextCursor: value['nextCursor'] as string | null };
    } catch (error) {
      throw toApiError(error);
    }
  }

  async get(workId: string): Promise<WorkSubscriptionReceipt> {
    this.requireWorkId(workId);
    try {
      const response = await firstValueFrom(
        this.http
          .get<unknown>(`${base}/${encodeURIComponent(workId)}/subscription`, {
            observe: 'response',
          })
          .pipe(timeout(timeoutMs)),
      );
      return parseReceipt(response, 200);
    } catch (error) {
      throw toApiError(error);
    }
  }

  async setNotifications(
    workId: string,
    notificationsEnabled: boolean,
    etag: string,
  ): Promise<WorkSubscriptionReceipt> {
    this.requireMutation(workId, etag);
    try {
      const response = await firstValueFrom(
        this.http
          .put<unknown>(
            `${base}/${encodeURIComponent(workId)}/subscription`,
            { notificationsEnabled },
            {
              headers: (await this.csrfHeaders()).set('If-Match', etag),
              observe: 'response',
            },
          )
          .pipe(timeout(timeoutMs)),
      );
      return parseReceipt(response, 200);
    } catch (error) {
      throw toApiError(error);
    }
  }

  async unfollow(workId: string, etag: string): Promise<string> {
    this.requireMutation(workId, etag);
    try {
      const response = await firstValueFrom(
        this.http
          .delete<unknown>(`${base}/${encodeURIComponent(workId)}/subscription`, {
            headers: (await this.csrfHeaders()).set('If-Match', etag),
            observe: 'response',
          })
          .pipe(timeout(timeoutMs)),
      );
      requireNoStore(response);
      const nextEtag = response.headers.get('ETag');
      if (response.status !== 204 || response.body !== null || !isEtag(nextEtag)) {
        throw invalidResponse();
      }
      return nextEtag;
    } catch (error) {
      throw toApiError(error);
    }
  }

  private async csrfHeaders(): Promise<HttpHeaders> {
    const response: unknown = await firstValueFrom(
      this.http.get<unknown>(csrfUrl).pipe(timeout(timeoutMs)),
    );
    if (
      !isRecord(response) ||
      typeof response['token'] !== 'string' ||
      !csrfPattern.test(response['token'])
    ) {
      throw new WorkSubscriptionApiError(0, 'INVALID_CSRF_RESPONSE');
    }
    return new HttpHeaders({ 'X-CSRF-Token': response['token'] });
  }

  private requireWorkId(workId: string): void {
    if (!uuidPattern.test(workId)) throw new WorkSubscriptionApiError(400, 'INVALID_WORK_ID');
  }

  private requireMutation(workId: string, etag: string): void {
    this.requireWorkId(workId);
    if (!isEtag(etag)) throw new WorkSubscriptionApiError(400, 'INVALID_SUBSCRIPTION_ETAG');
  }
}

function parseReceipt(
  response: HttpResponse<unknown>,
  expectedStatus: number,
): WorkSubscriptionReceipt {
  requireNoStore(response);
  const etag = response.headers.get('ETag');
  const value = response.body;
  if (
    response.status !== expectedStatus ||
    !isEtag(etag) ||
    !isRecord(value) ||
    !hasExactKeys(value, 'following,notificationsEnabled') ||
    typeof value['following'] !== 'boolean' ||
    typeof value['notificationsEnabled'] !== 'boolean' ||
    (value['notificationsEnabled'] && !value['following'])
  ) {
    throw invalidResponse();
  }
  return {
    state: {
      following: value['following'],
      notificationsEnabled: value['notificationsEnabled'],
    },
    etag,
  };
}

function requireNoStore(response: HttpResponse<unknown>): void {
  if (
    response.headers
      .get('Cache-Control')
      ?.toLowerCase()
      .split(',')
      .map((part) => part.trim())
      .includes('no-store') !== true ||
    response.headers.get('Pragma')?.toLowerCase() !== 'no-cache'
  ) {
    throw invalidResponse();
  }
}

function parseFollowedWorkSetting(value: unknown): FollowedWorkSetting {
  if (
    !isRecord(value) ||
    !hasExactKeys(
      value,
      'author,available,followedAt,id,notificationsEnabled,title,version,workId',
    ) ||
    typeof value['id'] !== 'string' ||
    !uuidPattern.test(value['id']) ||
    typeof value['workId'] !== 'string' ||
    !uuidPattern.test(value['workId']) ||
    typeof value['available'] !== 'boolean' ||
    typeof value['notificationsEnabled'] !== 'boolean' ||
    (value['notificationsEnabled'] && !value['available']) ||
    !Number.isSafeInteger(value['version']) ||
    (value['version'] as number) < 1 ||
    !isBoundedString(value['followedAt'], 1, 64) ||
    !Number.isFinite(Date.parse(value['followedAt'])) ||
    !(value['title'] === null || isBoundedString(value['title'], 1, 300)) ||
    !(value['author'] === null || isBoundedString(value['author'], 0, 300)) ||
    (value['available'] &&
      (typeof value['title'] !== 'string' || typeof value['author'] !== 'string')) ||
    (!value['available'] && (value['title'] !== null || value['author'] !== null))
  ) {
    throw invalidResponse();
  }
  return {
    id: value['id'],
    workId: value['workId'],
    available: value['available'],
    notificationsEnabled: value['notificationsEnabled'],
    version: value['version'] as number,
    followedAt: value['followedAt'],
    title: value['title'],
    author: value['author'],
  };
}

function isBoundedString(value: unknown, min: number, max: number): value is string {
  return typeof value === 'string' && value.length >= min && value.length <= max;
}

function toApiError(error: unknown): WorkSubscriptionApiError {
  if (error instanceof WorkSubscriptionApiError) return error;
  if (error instanceof HttpErrorResponse) {
    let code = 'SUBSCRIPTION_REQUEST_FAILED';
    if (isRecord(error.error) && typeof error.error['code'] === 'string') {
      code = error.error['code'].slice(0, 80);
    }
    return new WorkSubscriptionApiError(
      error.status,
      code,
      parseRetryAfter(error.headers.get('Retry-After')),
    );
  }
  return new WorkSubscriptionApiError(0, 'SUBSCRIPTION_NETWORK_ERROR');
}

function parseRetryAfter(value: string | null): number | null {
  if (!value || !/^[0-9]{1,5}$/.test(value)) return null;
  const seconds = Number(value);
  return Number.isSafeInteger(seconds) && seconds >= 1 && seconds <= 86_400 ? seconds : null;
}

function invalidResponse(): WorkSubscriptionApiError {
  return new WorkSubscriptionApiError(0, 'INVALID_SUBSCRIPTION_RESPONSE');
}

function isEtag(value: string | null): value is string {
  return value !== null && etagPattern.test(value);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function hasExactKeys(value: Record<string, unknown>, expected: string): boolean {
  return Object.keys(value).sort().join(',') === expected;
}
