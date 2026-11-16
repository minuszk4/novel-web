import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { ReviewerAdminApiError, ReviewerAdminApiService } from './reviewer-admin-api.service';

const userId = '77777777-7777-4777-8777-777777777777';
const assignmentId = '88888888-8888-4888-8888-888888888888';
const csrfToken = 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA';
const assignment = {
  assignmentId,
  userId,
  displayName: 'Reviewer Example',
  maskedContactEmail: 'r***r@example.test',
  userStatus: 'active',
  assignedByUserId: '99999999-9999-4999-8999-999999999999',
  assignedAt: '2026-11-16T10:00:00Z',
  reasonCode: 'REVIEW_CAPACITY',
  revokedAt: null,
  revokedByUserId: null,
  revocationReasonCode: null,
  lastEventId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
  lastEventActionCode: 'GRANTED',
  lastEventActorUserId: '99999999-9999-4999-8999-999999999999',
  lastEventOccurredAt: '2026-11-16T10:00:00Z',
  lastEventReasonCode: 'REVIEW_CAPACITY',
};

async function flushCsrf(http: HttpTestingController): Promise<void> {
  http.expectOne('/api/v1/auth/csrf').flush({ token: csrfToken });
  await Promise.resolve();
  await Promise.resolve();
}

describe('ReviewerAdminApiService', () => {
  let api: ReviewerAdminApiService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    api = TestBed.inject(ReviewerAdminApiService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('loads a bounded status-specific assignment list', async () => {
    const result = api.getAssignments('revoked');
    const request = http.expectOne(
      (candidate) =>
        candidate.url === '/api/v1/admin/security/role-assignments' &&
        candidate.params.get('role') === 'publication_reviewer' &&
        candidate.params.get('status') === 'revoked',
    );
    expect(request.request.method).toBe('GET');
    request.flush({
      items: [
        {
          ...assignment,
          revokedAt: '2026-11-16T11:00:00Z',
          revokedByUserId: assignment.assignedByUserId,
          revocationReasonCode: 'ACCESS_ENDED',
          lastEventId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
          lastEventActionCode: 'REVOKED',
          lastEventOccurredAt: '2026-11-16T11:00:00Z',
          lastEventReasonCode: 'ACCESS_ENDED',
        },
      ],
    });
    await expect(result).resolves.toHaveLength(1);
  });

  it('searches accounts through a CSRF-protected body and accepts only masked results', async () => {
    const result = api.searchEligibleAccounts('reviewer@');
    await flushCsrf(http);
    const request = http.expectOne('/api/v1/admin/security/users/search');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({ query: 'reviewer@' });
    expect(request.request.headers.get('X-CSRF-Token')).toBe(csrfToken);
    request.flush({
      items: [
        {
          userId,
          displayName: 'Reader Example',
          maskedContactEmail: 'r***r@example.test',
          status: 'active',
        },
      ],
    });
    await expect(result).resolves.toMatchObject([
      { userId, maskedContactEmail: 'r***r@example.test' },
    ]);
  });

  it('preserves the server Retry-After duration for a rate-limited search', async () => {
    const result = api.searchEligibleAccounts('reader@example.test');
    await flushCsrf(http);
    http.expectOne('/api/v1/admin/security/users/search').flush(
      { code: 'RATE_LIMITED' },
      {
        status: 429,
        statusText: 'Too Many Requests',
        headers: { 'Retry-After': '90' },
      },
    );
    await expect(result).rejects.toMatchObject({ status: 429, retryAfterSeconds: 90 });
  });

  it('grants with CSRF and an idempotency key', async () => {
    const result = api.grantReviewer(userId, 'REVIEW_CAPACITY', 'reviewer-grant-attempt-0001');
    await flushCsrf(http);
    const request = http.expectOne(
      `/api/v1/admin/security/users/${userId}/roles/publication_reviewer`,
    );
    expect(request.request.method).toBe('PUT');
    expect(request.request.body).toEqual({ reasonCode: 'REVIEW_CAPACITY' });
    expect(request.request.headers.get('X-CSRF-Token')).toBe(csrfToken);
    expect(request.request.headers.get('Idempotency-Key')).toBe('reviewer-grant-attempt-0001');
    request.flush(
      { resultCode: 'GRANTED', assignment, replayed: false },
      { status: 201, statusText: 'Created' },
    );
    await expect(result).resolves.toMatchObject({ userId, assignmentId });
  });

  it('reuses the explicit reason/key on revocation and maps authorization failures safely', async () => {
    const result = api.revokeReviewer(userId, 'ACCESS_ENDED', 'reviewer-revoke-attempt-0001');
    await flushCsrf(http);
    const request = http.expectOne(
      `/api/v1/admin/security/users/${userId}/roles/publication_reviewer`,
    );
    expect(request.request.method).toBe('DELETE');
    expect(request.request.body).toEqual({ reasonCode: 'ACCESS_ENDED' });
    expect(request.request.headers.get('Idempotency-Key')).toBe('reviewer-revoke-attempt-0001');
    request.flush(null, { status: 204, statusText: 'No Content' });
    await expect(result).resolves.toBeUndefined();

    const denied = api.getAssignments('active');
    http
      .expectOne((candidate) => candidate.url === '/api/v1/admin/security/role-assignments')
      .flush({ code: 'ADMIN_REQUIRED' }, { status: 403, statusText: 'Forbidden' });
    await expect(denied).rejects.toBeInstanceOf(ReviewerAdminApiError);
    await expect(denied).rejects.toMatchObject({ status: 403, code: 'ADMIN_REQUIRED' });
  });
});
