import { HttpHeaders, provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
  TestRequest,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { NotificationApiError, NotificationApiService } from './notification-api.service';

const notificationId = '11111111-1111-4111-8111-111111111111';
const workId = '22222222-2222-4222-8222-222222222222';
const chapterId = '33333333-3333-4333-8333-333333333333';
const csrf = 'A'.repeat(43);
const noStore = new HttpHeaders({ 'Cache-Control': 'no-store', Pragma: 'no-cache' });
const publicItem = {
  notificationId,
  eventType: 'CHAPTER_PUBLISHED',
  createdAt: '2026-11-18T10:00:00Z',
  readAt: null,
  available: true,
  workId,
  workTitle: 'Tác phẩm công khai',
  chapterId,
  chapterTitle: 'Chương mới',
  chapterOrdinal: 5,
};

describe('NotificationApiService', () => {
  let api: NotificationApiService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    api = TestBed.inject(NotificationApiService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('loads a strict no-store inbox page and updates the unread badge', async () => {
    const result = api.listMine();
    http
      .expectOne('/api/v1/me/notifications?limit=20')
      .flush({ items: [publicItem], nextCursor: null, unreadCount: 1 }, { headers: noStore });
    await expect(result).resolves.toMatchObject({
      items: [{ chapterTitle: 'Chương mới', available: true }],
      unreadCount: 1,
    });
    expect(api.unreadCount()).toBe(1);
  });

  it('rejects identifying metadata when an update is no longer public', async () => {
    const result = api.listMine();
    http.expectOne('/api/v1/me/notifications?limit=20').flush(
      {
        items: [
          {
            ...publicItem,
            eventType: 'UPDATE_UNAVAILABLE',
            available: false,
            workId: null,
            workTitle: null,
            chapterId: null,
            chapterTitle: null,
            chapterOrdinal: null,
          },
        ],
        nextCursor: null,
        unreadCount: 1,
      },
      { headers: noStore },
    );
    await expect(result).resolves.toMatchObject({
      items: [{ available: false, workId: null, chapterTitle: null }],
    });
  });

  it('rejects a response that can be cached or contains unexpected fields', async () => {
    const cached = api.listMine();
    http
      .expectOne('/api/v1/me/notifications?limit=20')
      .flush(
        { items: [], nextCursor: null, unreadCount: 0 },
        { headers: new HttpHeaders({ 'Cache-Control': 'private' }) },
      );
    await expect(cached).rejects.toEqual(
      new NotificationApiError(0, 'INVALID_NOTIFICATIONS_RESPONSE'),
    );

    const extra = api.listMine();
    http
      .expectOne('/api/v1/me/notifications?limit=20')
      .flush(
        { items: [], nextCursor: null, unreadCount: 0, privateNote: 'secret' },
        { headers: noStore },
      );
    await expect(extra).rejects.toMatchObject({ code: 'INVALID_NOTIFICATIONS_RESPONSE' });
  });

  it('uses a fresh CSRF token and updates one notification as read', async () => {
    const result = api.markRead(notificationId);
    http.expectOne('/api/v1/auth/csrf').flush({ token: csrf });
    const request = await expectNextRequest(
      http,
      `/api/v1/me/notifications/${notificationId}/read`,
    );
    expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('X-CSRF-Token')).toBe(csrf);
    request.flush({ notificationId, readAt: '2026-11-18T10:01:00Z' }, { headers: noStore });
    await expect(result).resolves.toBe('2026-11-18T10:01:00Z');
  });

  it('uses CSRF for a bounded read-all batch and updates the badge count', async () => {
    const firstPage = api.listMine();
    http
      .expectOne('/api/v1/me/notifications?limit=20')
      .flush({ items: [publicItem], nextCursor: null, unreadCount: 501 }, { headers: noStore });
    await firstPage;

    const result = api.markAllRead();
    http.expectOne('/api/v1/auth/csrf').flush({ token: csrf });
    const request = await expectNextRequest(http, '/api/v1/me/notifications/read-all');
    expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('X-CSRF-Token')).toBe(csrf);
    request.flush({ updatedCount: 500, hasMore: true }, { headers: noStore });
    await expect(result).resolves.toEqual({ updatedCount: 500, hasMore: true });
    expect(api.unreadCount()).toBe(1);
  });

  it('rejects malformed notification identifiers before making a request', async () => {
    await expect(api.markRead('not-a-uuid')).rejects.toEqual(
      new NotificationApiError(400, 'INVALID_NOTIFICATION_ID'),
    );
    http.expectNone('/api/v1/auth/csrf');
  });
});

async function expectNextRequest(http: HttpTestingController, url: string): Promise<TestRequest> {
  let requests: TestRequest[] = [];
  await vi.waitFor(() => {
    requests = http.match(url);
    expect(requests).toHaveLength(1);
  });
  return requests[0];
}
