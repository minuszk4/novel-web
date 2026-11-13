import { provideHttpClient } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  CultivationApiError,
  CultivationApiService,
  CultivationProfile,
} from '../core/cultivation-api.service';
import { DemoStateService } from '../core/demo-state.service';
import { PolicyOnboardingService } from '../core/policy-onboarding.service';
import { CultivationPageComponent } from './cultivation-page.component';

describe('CultivationPageComponent', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient()] });
  });

  it('shows the example rank, next threshold, and accessible progress', () => {
    const fixture = TestBed.createComponent(CultivationPageComponent);
    fixture.detectChanges();

    const element: HTMLElement = fixture.nativeElement;
    const progress = element.querySelector('[role="progressbar"]');

    expect(element.querySelector('h3')?.textContent?.trim()).toBe('Luyện Khí tầng 6');
    expect(element.querySelector('.rank-next-box strong')?.textContent?.trim()).toBe(
      'Luyện Khí tầng 7',
    );
    expect(element.querySelector('.rank-next-box span')?.textContent?.trim()).toBe('700 XP');
    expect(progress?.getAttribute('aria-valuenow')).toBe('20');
    expect(progress?.getAttribute('aria-label')).toBe('Ví dụ tiến độ tới Luyện Khí tầng 7');
  });

  it('keeps the complete cultivation ladder in ascending threshold order', () => {
    const fixture = TestBed.createComponent(CultivationPageComponent);
    fixture.detectChanges();

    const element: HTMLElement = fixture.nativeElement;
    const ranks = Array.from(element.querySelectorAll('.rank-ladder-copy strong')).map((rank) =>
      rank.textContent?.trim(),
    );

    expect(ranks).toEqual([
      'Phàm nhân',
      'Luyện Khí tầng 1',
      'Luyện Khí tầng 2',
      'Luyện Khí tầng 3',
      'Luyện Khí tầng 4',
      'Luyện Khí tầng 5',
      'Luyện Khí tầng 6',
      'Luyện Khí tầng 7',
      'Luyện Khí tầng 8',
      'Luyện Khí tầng 9',
      'Trúc Cơ',
      'Kim Đan',
      'Nguyên Anh',
      'Hóa Thần',
      'Luyện Hư',
      'Hợp Thể',
      'Đại Thừa',
      'Độ Kiếp',
    ]);
    expect(element.querySelector('.private-tag')?.textContent).toContain('KHÔNG BXH');
  });

  it('requires explicit opt-in and allows pause and resume', () => {
    const fixture = TestBed.createComponent(CultivationPageComponent);
    const demo = TestBed.inject(DemoStateService);
    fixture.detectChanges();

    expect(demo.cultivationEnabled()).toBe(false);
    const enableButton = fixture.nativeElement.querySelector('button') as HTMLButtonElement;
    expect(enableButton.textContent?.trim()).toBe('Bật trong bản demo');
    enableButton.click();
    fixture.detectChanges();

    expect(demo.cultivationEnabled()).toBe(true);
    expect(demo.cultivationPaused()).toBe(false);
    const pauseButton = fixture.nativeElement.querySelector('button') as HTMLButtonElement;
    expect(pauseButton.textContent?.trim()).toBe('Tạm dừng tích lũy');
    pauseButton.click();
    fixture.detectChanges();

    expect(demo.cultivationPaused()).toBe(true);
    expect(fixture.nativeElement.textContent).toContain('Đang tạm dừng.');
    const resumeButton = fixture.nativeElement.querySelector('button') as HTMLButtonElement;
    expect(resumeButton.textContent?.trim()).toBe('Tiếp tục');
    resumeButton.click();
    fixture.detectChanges();

    expect(demo.cultivationPaused()).toBe(false);
  });

  it('uses real account rank and daily activity without showing demo XP', async () => {
    const currentProfile = accountProfile();
    const api = {
      getProfile: vi.fn().mockResolvedValue(currentProfile),
      getActivity: vi.fn().mockResolvedValue({
        items: [{ activityDate: '2026-11-12', xpEarned: 45 }],
        nextCursor: null,
      }),
      setEnabled: vi.fn().mockImplementation(async (enabled: boolean) => ({
        ...currentProfile,
        enabled,
        profileVersion: 2,
      })),
    };
    TestBed.overrideProvider(CultivationApiService, { useValue: api });
    const policy = TestBed.inject(PolicyOnboardingService);
    policy.status.set('accepted');
    policy.accountIdentity.set({ displayName: 'Reader', contactEmail: null });

    const fixture = TestBed.createComponent(CultivationPageComponent);
    await fixture.whenStable();
    fixture.detectChanges();

    const element: HTMLElement = fixture.nativeElement;
    expect(api.getProfile).toHaveBeenCalledOnce();
    expect(api.getActivity).toHaveBeenCalledOnce();
    expect(element.querySelector('.cultivation-demo-notice')).toBeNull();
    expect(element.textContent).toContain('125 XP');
    expect(element.textContent).toContain('Luyện Khí tầng 1');
    expect(element.textContent).toContain('45 XP');
    expect(element.textContent).not.toContain('620 XP');
    expect(element.textContent).not.toContain('private title');
    expect(element.textContent).not.toContain('workId');

    const pauseButton = element.querySelector(
      '.cultivation-control-card button',
    ) as HTMLButtonElement;
    expect(pauseButton.textContent?.trim()).toBe('Tạm dừng tích lũy');
    pauseButton.click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(api.setEnabled).toHaveBeenCalledWith(false);
    expect(element.textContent).toContain('Hành trình đang tạm dừng');
    expect(element.textContent).toContain('45 XP');
    expect(
      (element.querySelector('.cultivation-control-card button') as HTMLButtonElement).textContent,
    ).toContain('Bật hành trình');
  });

  it('fails closed when the global feature flag keeps account cultivation unavailable', async () => {
    const api = {
      getProfile: vi
        .fn()
        .mockRejectedValue(new CultivationApiError('Unavailable', 503, 'CULTIVATION_NOT_ENABLED')),
      getActivity: vi.fn(),
      setEnabled: vi.fn(),
    };
    TestBed.overrideProvider(CultivationApiService, { useValue: api });
    const policy = TestBed.inject(PolicyOnboardingService);
    policy.status.set('accepted');
    policy.accountIdentity.set({ displayName: 'Reader', contactEmail: null });

    const fixture = TestBed.createComponent(CultivationPageComponent);
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Tính năng tu luyện chưa được bật');
    expect(fixture.nativeElement.textContent).not.toContain('620 XP');
    expect(api.getActivity).not.toHaveBeenCalled();
    expect(fixture.nativeElement.querySelector('.rank-progress-card')).toBeNull();
  });
});

function accountProfile(): CultivationProfile {
  const mortal = {
    key: 'mortal',
    group: 'Nhập môn',
    name: 'Phàm nhân',
    thresholdXp: 0,
    sortOrder: 0,
  };
  const qi1 = {
    key: 'qi-1',
    group: 'Luyện Khí',
    name: 'Luyện Khí tầng 1',
    thresholdXp: 100,
    sortOrder: 1,
  };
  const qi2 = {
    key: 'qi-2',
    group: 'Luyện Khí',
    name: 'Luyện Khí tầng 2',
    thresholdXp: 200,
    sortOrder: 2,
  };
  return {
    enabled: true,
    profileVersion: 1,
    totalXp: 125,
    todayXpEarned: 45,
    currentRank: qi1,
    nextRank: qi2,
    xpToNextRank: 75,
    progressRatio: 0.25,
    rankCatalogVersion: 'cultivation-v1',
    policyVersion: 'xp-v1',
    locale: 'vi-VN',
    xpPerActiveMinute: 1,
    dailyXpCap: 120,
    nextDailyResetAt: '2026-11-14T00:00:00Z',
    rankCatalog: [mortal, qi1, qi2],
  };
}
