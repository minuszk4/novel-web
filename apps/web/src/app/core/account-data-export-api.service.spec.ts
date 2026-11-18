import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { AccountDataExportApiService } from './account-data-export-api.service';

describe('AccountDataExportApiService', () => {
  let http: HttpTestingController;
  let api: AccountDataExportApiService;

  const job = {
    id: '88f4e518-8587-4b96-b424-d657ef71a660',
    status: 'ready',
    requestedAt: '2026-11-18T10:00:00Z',
    startedAt: '2026-11-18T10:00:01Z',
    readyAt: '2026-11-18T10:00:02Z',
    expiresAt: '2026-11-19T10:00:02Z',
    artifactBytes: 1024,
    failureCode: null,
    downloadUrl: '/api/v1/me/data-exports/88f4e518-8587-4b96-b424-d657ef71a660/download',
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    api = TestBed.inject(AccountDataExportApiService);
  });

  afterEach(() => {
    try {
      http.verify();
    } finally {
      TestBed.resetTestingModule();
    }
  });

  it('requests CSRF before creating an idempotent export', async () => {
    const pending = api.request('account-export-key-0001');
    http.expectOne('/api/v1/auth/csrf').flush({ token: 'A'.repeat(43) });
    await new Promise((resolve) => setTimeout(resolve, 0));
    const request = http.expectOne({ method: 'POST', url: '/api/v1/me/data-exports' });
    expect(request.request.headers.get('X-CSRF-Token')).toBe('A'.repeat(43));
    expect(request.request.headers.get('Idempotency-Key')).toBe('account-export-key-0001');
    expect(request.request.body).toBeNull();
    request.flush({ export: job, replayed: false });

    await expect(pending).resolves.toMatchObject({ job, replayed: false });
  });

  it('restores the latest actor export after navigation or reload', async () => {
    const pending = api.latest();
    http.expectOne({ method: 'GET', url: '/api/v1/me/data-exports/latest' }).flush(job);

    await expect(pending).resolves.toMatchObject({ id: job.id, status: 'ready' });
  });

  it('rejects a download URL outside the fixed same-origin account route', async () => {
    const pending = api.latest();
    http
      .expectOne('/api/v1/me/data-exports/latest')
      .flush({ ...job, downloadUrl: 'https://example.test/steal.zip' });

    await expect(pending).rejects.toMatchObject({ code: 'INVALID_EXPORT_RESPONSE' });
  });

  it('rejects invalid idempotency keys before making a request', async () => {
    await expect(api.request('short')).rejects.toMatchObject({ code: 'INVALID_IDEMPOTENCY_KEY' });
    http.expectNone('/api/v1/auth/csrf');
  });
});
