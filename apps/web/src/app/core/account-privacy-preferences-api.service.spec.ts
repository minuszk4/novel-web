import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { AccountPrivacyPreferencesApiService } from './account-privacy-preferences-api.service';

describe('AccountPrivacyPreferencesApiService', () => {
  let http: HttpTestingController;
  let api: AccountPrivacyPreferencesApiService;

  const defaults = {
    analyticsEnabled: false,
    contentProviderSharingEnabled: false,
    version: 0,
    updatedAt: null,
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    api = TestBed.inject(AccountPrivacyPreferencesApiService);
  });

  afterEach(() => {
    try {
      http.verify();
    } finally {
      TestBed.resetTestingModule();
    }
  });

  it('loads only strict actor preferences with a version-matched ETag', async () => {
    const pending = api.get();
    http
      .expectOne({ method: 'GET', url: '/api/v1/me/privacy-preferences' })
      .flush(defaults, { headers: { ETag: '"privacy-preferences-v0"' } });

    await expect(pending).resolves.toEqual({
      preferences: defaults,
      etag: '"privacy-preferences-v0"',
    });
  });

  it('gets CSRF before sending the versioned independent choices', async () => {
    const updated = {
      analyticsEnabled: true,
      contentProviderSharingEnabled: false,
      version: 1,
      updatedAt: '2026-11-25T00:00:00Z',
    };
    const pending = api.update(
      { analyticsEnabled: true, contentProviderSharingEnabled: false },
      '"privacy-preferences-v0"',
    );
    http.expectOne('/api/v1/auth/csrf').flush({ token: 'A'.repeat(43) });
    await new Promise((resolve) => setTimeout(resolve, 0));
    const request = http.expectOne({ method: 'PUT', url: '/api/v1/me/privacy-preferences' });
    expect(request.request.headers.get('X-CSRF-Token')).toBe('A'.repeat(43));
    expect(request.request.headers.get('If-Match')).toBe('"privacy-preferences-v0"');
    expect(request.request.body).toEqual({
      analyticsEnabled: true,
      contentProviderSharingEnabled: false,
    });
    request.flush(updated, { headers: { ETag: '"privacy-preferences-v1"' } });

    await expect(pending).resolves.toMatchObject({
      preferences: updated,
      etag: '"privacy-preferences-v1"',
    });
  });

  it('rejects unknown response fields and mismatched validators', async () => {
    const extraField = api.get();
    http
      .expectOne('/api/v1/me/privacy-preferences')
      .flush(
        { ...defaults, userId: 'someone-else' },
        { headers: { ETag: '"privacy-preferences-v0"' } },
      );
    await expect(extraField).rejects.toMatchObject({
      code: 'INVALID_PRIVACY_PREFERENCES_RESPONSE',
    });

    const mismatchedTag = api.get();
    http.expectOne('/api/v1/me/privacy-preferences').flush(
      { ...defaults, version: 1, updatedAt: '2026-11-25T00:00:00Z' },
      {
        headers: { ETag: '"privacy-preferences-v0"' },
      },
    );
    await expect(mismatchedTag).rejects.toMatchObject({
      code: 'INVALID_PRIVACY_PREFERENCES_RESPONSE',
    });
  });

  it('rejects an invalid ETag before obtaining CSRF or sending a write', async () => {
    await expect(
      api.update(
        { analyticsEnabled: true, contentProviderSharingEnabled: false },
        'W/"privacy-preferences-v0"',
      ),
    ).rejects.toMatchObject({ code: 'IF_MATCH_INVALID' });
    http.expectNone('/api/v1/auth/csrf');
  });
});
