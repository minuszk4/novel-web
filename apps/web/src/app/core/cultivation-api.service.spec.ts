import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { CultivationApiService, CultivationProfile } from './cultivation-api.service';

describe('CultivationApiService', () => {
  let http: HttpTestingController;
  let api: CultivationApiService;

  const profile: CultivationProfile = {
    enabled: true,
    profileVersion: 1,
    totalXp: 100,
    todayXpEarned: 25,
    currentRank: {
      key: 'qi-1',
      group: 'Luyện Khí',
      name: 'Luyện Khí tầng 1',
      thresholdXp: 100,
      sortOrder: 1,
    },
    nextRank: {
      key: 'qi-2',
      group: 'Luyện Khí',
      name: 'Luyện Khí tầng 2',
      thresholdXp: 200,
      sortOrder: 2,
    },
    xpToNextRank: 100,
    progressRatio: 0,
    rankCatalogVersion: 'cultivation-v1',
    policyVersion: 'xp-v1',
    locale: 'vi-VN',
    xpPerActiveMinute: 1,
    dailyXpCap: 120,
    nextDailyResetAt: '2026-11-14T00:00:00Z',
    rankCatalog: [
      { key: 'mortal', group: 'Nhập môn', name: 'Phàm nhân', thresholdXp: 0, sortOrder: 0 },
      { key: 'qi-1', group: 'Luyện Khí', name: 'Luyện Khí tầng 1', thresholdXp: 100, sortOrder: 1 },
      { key: 'qi-2', group: 'Luyện Khí', name: 'Luyện Khí tầng 2', thresholdXp: 200, sortOrder: 2 },
    ],
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    api = TestBed.inject(CultivationApiService);
  });

  afterEach(() => {
    try {
      http.verify();
    } finally {
      TestBed.resetTestingModule();
    }
  });

  it('loads and validates the server profile and rank catalog', async () => {
    const pending = api.getProfile();
    http.expectOne({ method: 'GET', url: '/api/v1/me/cultivation' }).flush(profile);

    await expect(pending).resolves.toEqual(profile);
  });

  it('requests signed-cursor activity and accepts only daily date and XP fields', async () => {
    const pending = api.getActivity('signed-cursor', 20);
    const request = http.expectOne(
      (candidate) =>
        candidate.method === 'GET' &&
        candidate.url === '/api/v1/me/cultivation/activity' &&
        candidate.params.get('limit') === '20' &&
        candidate.params.get('cursor') === 'signed-cursor',
    );
    request.flush({
      items: [{ activityDate: '2026-11-12', xpEarned: 45 }],
      nextCursor: null,
    });

    await expect(pending).resolves.toEqual({
      items: [{ activityDate: '2026-11-12', xpEarned: 45 }],
      nextCursor: null,
    });
  });

  it('rejects story metadata if it is ever added to an activity row', async () => {
    const pending = api.getActivity();
    http
      .expectOne((request) => request.url === '/api/v1/me/cultivation/activity')
      .flush({
        items: [{ activityDate: '2026-11-12', xpEarned: 45, chapterTitle: 'private title' }],
        nextCursor: null,
      });

    await expect(pending).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
  });

  it('gets session CSRF and only then saves the explicit enabled setting', async () => {
    const pending = api.setEnabled(false);
    http.expectOne({ method: 'GET', url: '/api/v1/auth/csrf' }).flush({
      token: 'A'.repeat(43),
    });
    await Promise.resolve();
    const request = http.expectOne({ method: 'PUT', url: '/api/v1/me/cultivation/settings' });
    expect(request.request.body).toEqual({ enabled: false });
    expect(request.request.headers.get('X-CSRF-Token')).toBe('A'.repeat(43));
    request.flush({ ...profile, enabled: false, profileVersion: 2 });

    await expect(pending).resolves.toMatchObject({ enabled: false, profileVersion: 2 });
  });

  it('preserves server feature-gate errors for the account screen', async () => {
    const pending = api.getProfile();
    http
      .expectOne('/api/v1/me/cultivation')
      .flush(
        { code: 'CULTIVATION_NOT_ENABLED' },
        { status: 503, statusText: 'Service Unavailable' },
      );

    await expect(pending).rejects.toMatchObject({ code: 'CULTIVATION_NOT_ENABLED', status: 503 });
  });
});
