import { HttpHeaders, provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
  TestRequest,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { WorkSubscriptionApiError, WorkSubscriptionApiService } from './work-subscription.service';

const workId = '11111111-1111-4111-8111-111111111111';
const csrf = 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA';
const noStore = new HttpHeaders({ 'Cache-Control': 'no-store', Pragma: 'no-cache' });

describe('WorkSubscriptionApiService', () => {
  let api: WorkSubscriptionApiService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    api = TestBed.inject(WorkSubscriptionApiService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('loads the allow-listed actor subscription state and strong ETag', async () => {
    const result = api.get(workId);
    http
      .expectOne(`/api/v1/works/${workId}/subscription`)
      .flush(
        { following: false, notificationsEnabled: false },
        { headers: noStore.set('ETag', '"subscription-absent"') },
      );

    await expect(result).resolves.toEqual({
      state: { following: false, notificationsEnabled: false },
      etag: '"subscription-absent"',
    });
  });

  it('fetches a fresh CSRF token and sends If-Match for a state change', async () => {
    const result = api.setNotifications(workId, true, '"subscription-absent"');
    http.expectOne('/api/v1/auth/csrf').flush({ token: csrf });
    const request = await expectNextRequest(http, `/api/v1/works/${workId}/subscription`);
    expect(request.request.method).toBe('PUT');
    expect(request.request.body).toEqual({ notificationsEnabled: true });
    expect(request.request.headers.get('X-CSRF-Token')).toBe(csrf);
    expect(request.request.headers.get('If-Match')).toBe('"subscription-absent"');
    request.flush(
      { following: true, notificationsEnabled: true },
      { headers: noStore.set('ETag', '"subscription-1"') },
    );

    await expect(result).resolves.toEqual({
      state: { following: true, notificationsEnabled: true },
      etag: '"subscription-1"',
    });
  });

  it('uses CSRF and the current ETag when unfollowing', async () => {
    const result = api.unfollow(workId, '"subscription-3"');
    http.expectOne('/api/v1/auth/csrf').flush({ token: csrf });
    const request = await expectNextRequest(http, `/api/v1/works/${workId}/subscription`);
    expect(request.request.method).toBe('DELETE');
    expect(request.request.headers.get('If-Match')).toBe('"subscription-3"');
    expect(request.request.headers.get('X-CSRF-Token')).toBe(csrf);
    request.flush(null, {
      status: 204,
      statusText: 'No Content',
      headers: noStore.set('ETag', '"subscription-4"'),
    });
    await expect(result).resolves.toBe('"subscription-4"');
  });

  it('rejects private or unexpected response fields before returning state', async () => {
    const result = api.get(workId);
    http
      .expectOne(`/api/v1/works/${workId}/subscription`)
      .flush(
        { following: true, notificationsEnabled: true, userId: 'private' },
        { headers: noStore.set('ETag', '"subscription-1"') },
      );
    await expect(result).rejects.toEqual(
      new WorkSubscriptionApiError(0, 'INVALID_SUBSCRIPTION_RESPONSE'),
    );
  });

  it('maps an expired session without claiming that following succeeded', async () => {
    const result = api.get(workId);
    http
      .expectOne(`/api/v1/works/${workId}/subscription`)
      .flush(
        { code: 'UNAUTHENTICATED' },
        { status: 401, statusText: 'Unauthorized', headers: noStore },
      );
    await expect(result).rejects.toMatchObject({ status: 401, code: 'UNAUTHENTICATED' });
  });

  it('preserves a bounded Retry-After from a subscription mutation limit', async () => {
    const result = api.setNotifications(workId, true, '"subscription-absent"');
    const request = await expectNextRequest(http, '/api/v1/auth/csrf');
    request.flush({ token: 'A'.repeat(43) });
    const mutation = await expectNextRequest(http, `/api/v1/works/${workId}/subscription`);
    mutation.flush(
      { code: 'SUBSCRIPTION_RATE_LIMITED' },
      {
        status: 429,
        statusText: 'Too Many Requests',
        headers: noStore.set('Retry-After', '23'),
      },
    );
    await expect(result).rejects.toMatchObject({
      status: 429,
      code: 'SUBSCRIPTION_RATE_LIMITED',
      retryAfterSeconds: 23,
    });
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
