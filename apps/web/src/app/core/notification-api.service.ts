import { HttpClient, HttpErrorResponse, HttpHeaders, HttpResponse } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom, timeout } from 'rxjs';

const base = '/api/v1/me/notifications';
const csrfUrl = '/api/v1/auth/csrf';
const timeoutMs = 8_000;
const csrfPattern = /^[A-Za-z0-9_-]{43}$/;
const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

export interface InAppNotification {
  notificationId: string;
  eventType: 'CHAPTER_PUBLISHED' | 'UPDATE_UNAVAILABLE';
  createdAt: string;
  readAt: string | null;
  available: boolean;
  workId: string | null;
  workTitle: string | null;
  chapterId: string | null;
  chapterTitle: string | null;
  chapterOrdinal: number | null;
}

export interface NotificationPage {
  items: InAppNotification[];
  nextCursor: string | null;
  unreadCount: number;
}

export interface NotificationReadAllReceipt {
  updatedCount: number;
  hasMore: boolean;
}

export class NotificationApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
  ) {
    super(code);
    this.name = 'NotificationApiError';
  }
}

@Injectable({ providedIn: 'root' })
export class NotificationApiService {
  private readonly http = inject(HttpClient);
  readonly unreadCount = signal(0);

  async listMine(cursor: string | null = null, limit = 20): Promise<NotificationPage> {
    if (
      (cursor !== null && (cursor.length < 1 || cursor.length > 2048)) ||
      !Number.isSafeInteger(limit) ||
      limit < 1 ||
      limit > 50
    ) {
      throw new NotificationApiError(400, 'INVALID_NOTIFICATION_PAGE');
    }
    const params = new URLSearchParams({ limit: String(limit) });
    if (cursor !== null) params.set('cursor', cursor);
    try {
      const response = await firstValueFrom(
        this.http
          .get<unknown>(`${base}?${params.toString()}`, { observe: 'response' })
          .pipe(timeout(timeoutMs)),
      );
      if (response.status !== 200) throw invalidResponse();
      requireNoStore(response);
      const page = parsePage(response.body);
      this.unreadCount.set(page.unreadCount);
      return page;
    } catch (error) {
      throw toApiError(error);
    }
  }

  async markRead(notificationId: string): Promise<string> {
    this.requireNotificationId(notificationId);
    try {
      const response = await firstValueFrom(
        this.http
          .post<unknown>(`${base}/${encodeURIComponent(notificationId)}/read`, null, {
            headers: await this.csrfHeaders(),
            observe: 'response',
          })
          .pipe(timeout(timeoutMs)),
      );
      requireNoStore(response);
      if (
        response.status !== 200 ||
        !isRecord(response.body) ||
        !hasExactKeys(response.body, 'notificationId,readAt') ||
        response.body['notificationId'] !== notificationId ||
        !isDateTime(response.body['readAt'])
      ) {
        throw invalidResponse();
      }
      this.unreadCount.update((count) => Math.max(0, count - 1));
      return response.body['readAt'];
    } catch (error) {
      throw toApiError(error);
    }
  }

  async markAllRead(): Promise<NotificationReadAllReceipt> {
    try {
      const response = await firstValueFrom(
        this.http
          .post<unknown>(`${base}/read-all`, null, {
            headers: await this.csrfHeaders(),
            observe: 'response',
          })
          .pipe(timeout(timeoutMs)),
      );
      requireNoStore(response);
      const value = response.body;
      if (
        response.status !== 200 ||
        !isRecord(value) ||
        !hasExactKeys(value, 'hasMore,updatedCount') ||
        typeof value['hasMore'] !== 'boolean' ||
        !Number.isSafeInteger(value['updatedCount']) ||
        (value['updatedCount'] as number) < 0 ||
        (value['updatedCount'] as number) > 500
      ) {
        throw invalidResponse();
      }
      this.unreadCount.update((count) => Math.max(0, count - (value['updatedCount'] as number)));
      return { updatedCount: value['updatedCount'] as number, hasMore: value['hasMore'] };
    } catch (error) {
      throw toApiError(error);
    }
  }

  clearUnreadCount(): void {
    this.unreadCount.set(0);
  }

  async refreshUnreadCount(): Promise<void> {
    await this.listMine(null, 1);
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
      throw new NotificationApiError(0, 'INVALID_CSRF_RESPONSE');
    }
    return new HttpHeaders({ 'X-CSRF-Token': response['token'] });
  }

  private requireNotificationId(notificationId: string): void {
    if (!uuidPattern.test(notificationId)) {
      throw new NotificationApiError(400, 'INVALID_NOTIFICATION_ID');
    }
  }
}

function parsePage(value: unknown): NotificationPage {
  if (
    !isRecord(value) ||
    !hasExactKeys(value, 'items,nextCursor,unreadCount') ||
    !Array.isArray(value['items']) ||
    value['items'].length > 50 ||
    !(value['nextCursor'] === null || isBoundedString(value['nextCursor'], 1, 2048)) ||
    !Number.isSafeInteger(value['unreadCount']) ||
    (value['unreadCount'] as number) < 0
  ) {
    throw invalidResponse();
  }
  const items = value['items'].map(parseNotification);
  if (new Set(items.map((item) => item.notificationId)).size !== items.length) {
    throw invalidResponse();
  }
  return {
    items,
    nextCursor: value['nextCursor'] as string | null,
    unreadCount: value['unreadCount'] as number,
  };
}

function parseNotification(value: unknown): InAppNotification {
  if (
    !isRecord(value) ||
    !hasExactKeys(
      value,
      'available,chapterId,chapterOrdinal,chapterTitle,createdAt,eventType,notificationId,readAt,workId,workTitle',
    ) ||
    typeof value['notificationId'] !== 'string' ||
    !uuidPattern.test(value['notificationId']) ||
    !isDateTime(value['createdAt']) ||
    !(value['readAt'] === null || isDateTime(value['readAt'])) ||
    typeof value['available'] !== 'boolean'
  ) {
    throw invalidResponse();
  }

  if (value['available']) {
    if (
      value['eventType'] !== 'CHAPTER_PUBLISHED' ||
      typeof value['workId'] !== 'string' ||
      !uuidPattern.test(value['workId']) ||
      typeof value['chapterId'] !== 'string' ||
      !uuidPattern.test(value['chapterId']) ||
      !isBoundedString(value['workTitle'], 1, 300) ||
      !isBoundedString(value['chapterTitle'], 1, 300) ||
      !Number.isSafeInteger(value['chapterOrdinal']) ||
      (value['chapterOrdinal'] as number) < 1
    ) {
      throw invalidResponse();
    }
  } else if (
    value['eventType'] !== 'UPDATE_UNAVAILABLE' ||
    value['workId'] !== null ||
    value['chapterId'] !== null ||
    value['workTitle'] !== null ||
    value['chapterTitle'] !== null ||
    value['chapterOrdinal'] !== null
  ) {
    throw invalidResponse();
  }

  return {
    notificationId: value['notificationId'],
    eventType: value['eventType'],
    createdAt: value['createdAt'],
    readAt: value['readAt'],
    available: value['available'],
    workId: value['workId'],
    workTitle: value['workTitle'],
    chapterId: value['chapterId'],
    chapterTitle: value['chapterTitle'],
    chapterOrdinal: value['chapterOrdinal'],
  } as InAppNotification;
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

function isDateTime(value: unknown): value is string {
  return isBoundedString(value, 1, 64) && Number.isFinite(Date.parse(value));
}

function isBoundedString(value: unknown, min: number, max: number): value is string {
  return typeof value === 'string' && value.length >= min && value.length <= max;
}

function toApiError(error: unknown): NotificationApiError {
  if (error instanceof NotificationApiError) return error;
  if (error instanceof HttpErrorResponse) {
    let code = 'NOTIFICATIONS_REQUEST_FAILED';
    if (isRecord(error.error) && typeof error.error['code'] === 'string') {
      code = error.error['code'].slice(0, 80);
    }
    return new NotificationApiError(error.status, code);
  }
  return new NotificationApiError(0, 'NOTIFICATIONS_NETWORK_ERROR');
}

function invalidResponse(): NotificationApiError {
  return new NotificationApiError(0, 'INVALID_NOTIFICATIONS_RESPONSE');
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function hasExactKeys(value: Record<string, unknown>, expected: string): boolean {
  return Object.keys(value).sort().join(',') === expected;
}
