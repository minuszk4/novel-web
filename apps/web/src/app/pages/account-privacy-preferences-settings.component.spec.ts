import { PLATFORM_ID, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import {
  AccountPrivacyPreferences,
  AccountPrivacyPreferencesApiError,
  AccountPrivacyPreferencesApiService,
} from '../core/account-privacy-preferences-api.service';
import { PolicyOnboardingService } from '../core/policy-onboarding.service';
import { AccountPrivacyPreferencesSettingsComponent } from './account-privacy-preferences-settings.component';

describe('AccountPrivacyPreferencesSettingsComponent', () => {
  it('loads persisted choices and saves them independently for the signed-in account', async () => {
    const accountIdentity = signal({
      userId: 'account-1',
      displayName: 'Reader',
      contactEmail: null,
    });
    const current = versionedPreferences(defaults);
    const saved = versionedPreferences({
      analyticsEnabled: true,
      contentProviderSharingEnabled: false,
      version: 1,
      updatedAt: '2026-11-25T00:00:00Z',
    });
    const api = {
      get: vi.fn().mockResolvedValue(current),
      update: vi.fn().mockResolvedValue(saved),
    };
    const fixture = await createFixture(api, accountIdentity);
    await fixture.whenStable();
    fixture.detectChanges();

    const checkboxes = fixture.nativeElement.querySelectorAll(
      '.account-privacy-settings input[type="checkbox"]',
    ) as NodeListOf<HTMLInputElement>;
    expect(checkboxes).toHaveLength(2);
    expect(checkboxes[0].checked).toBe(false);
    expect(checkboxes[1].checked).toBe(false);
    expect(fixture.nativeElement.textContent).toContain('không bật dịch vụ đó');

    checkboxes[0].checked = true;
    checkboxes[0].dispatchEvent(new Event('change'));
    await fixture.whenStable();
    fixture.detectChanges();

    expect(api.update).toHaveBeenCalledWith(
      { analyticsEnabled: true, contentProviderSharingEnabled: false },
      '"privacy-preferences-v0"',
    );
    expect(checkboxes[0].checked).toBe(true);
    expect(checkboxes[1].checked).toBe(false);
    expect(fixture.nativeElement.textContent).toContain('đã được lưu');
    fixture.destroy();
  });

  it('reloads server state after an optimistic concurrency conflict', async () => {
    const accountIdentity = signal({
      userId: 'account-1',
      displayName: 'Reader',
      contactEmail: null,
    });
    const changedElsewhere = versionedPreferences({
      analyticsEnabled: false,
      contentProviderSharingEnabled: true,
      version: 1,
      updatedAt: '2026-11-25T00:00:00Z',
    });
    const api = {
      get: vi
        .fn()
        .mockResolvedValueOnce(versionedPreferences(defaults))
        .mockResolvedValueOnce(changedElsewhere),
      update: vi
        .fn()
        .mockRejectedValue(new AccountPrivacyPreferencesApiError(412, 'STALE_VERSION')),
    };
    const fixture = await createFixture(api, accountIdentity);
    await fixture.whenStable();
    fixture.detectChanges();

    const firstChoice = fixture.nativeElement.querySelector(
      '.account-privacy-settings input[type="checkbox"]',
    ) as HTMLInputElement;
    firstChoice.checked = true;
    firstChoice.dispatchEvent(new Event('change'));
    await fixture.whenStable();
    fixture.detectChanges();

    const checkboxes = fixture.nativeElement.querySelectorAll(
      '.account-privacy-settings input[type="checkbox"]',
    ) as NodeListOf<HTMLInputElement>;
    expect(checkboxes[0].checked).toBe(false);
    expect(checkboxes[1].checked).toBe(true);
    expect(fixture.nativeElement.textContent).toContain('Đã tải lại lựa chọn mới nhất');
    fixture.destroy();
  });

  it('clears the first account values and loads preferences for a changed account', async () => {
    const accountIdentity = signal({
      userId: 'account-1',
      displayName: 'Reader one',
      contactEmail: null,
    });
    const api = {
      get: vi
        .fn()
        .mockResolvedValueOnce(
          versionedPreferences({
            analyticsEnabled: true,
            contentProviderSharingEnabled: false,
            version: 1,
            updatedAt: '2026-11-25T00:00:00Z',
          }),
        )
        .mockResolvedValueOnce(
          versionedPreferences({
            analyticsEnabled: false,
            contentProviderSharingEnabled: true,
            version: 2,
            updatedAt: '2026-11-25T00:01:00Z',
          }),
        ),
      update: vi.fn(),
    };
    const fixture = await createFixture(api, accountIdentity);
    await fixture.whenStable();
    fixture.detectChanges();

    accountIdentity.set({ userId: 'account-2', displayName: 'Reader two', contactEmail: null });
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    const checkboxes = fixture.nativeElement.querySelectorAll(
      '.account-privacy-settings input[type="checkbox"]',
    ) as NodeListOf<HTMLInputElement>;
    expect(api.get).toHaveBeenCalledTimes(2);
    expect(checkboxes[0].checked).toBe(false);
    expect(checkboxes[1].checked).toBe(true);
    fixture.destroy();
  });

  it('reverts the visual toggle when a save fails without changing stored state', async () => {
    const accountIdentity = signal({
      userId: 'account-1',
      displayName: 'Reader',
      contactEmail: null,
    });
    const api = {
      get: vi.fn().mockResolvedValue(versionedPreferences(defaults)),
      update: vi
        .fn()
        .mockRejectedValue(
          new AccountPrivacyPreferencesApiError(503, 'PRIVACY_PREFERENCES_UNAVAILABLE'),
        ),
    };
    const fixture = await createFixture(api, accountIdentity);
    await fixture.whenStable();
    fixture.detectChanges();

    const firstChoice = fixture.nativeElement.querySelector(
      '.account-privacy-settings input[type="checkbox"]',
    ) as HTMLInputElement;
    firstChoice.checked = true;
    firstChoice.dispatchEvent(new Event('change'));
    await fixture.whenStable();
    fixture.detectChanges();

    expect(firstChoice.checked).toBe(false);
    expect(fixture.nativeElement.textContent).toContain('Chưa tải hoặc lưu được cài đặt');
    fixture.destroy();
  });

  it('keeps the saved choice visible and offers retry when the API is unavailable', async () => {
    const accountIdentity = signal({
      userId: 'account-1',
      displayName: 'Reader',
      contactEmail: null,
    });
    const api = {
      get: vi
        .fn()
        .mockRejectedValueOnce(
          new AccountPrivacyPreferencesApiError(503, 'PRIVACY_PREFERENCES_UNAVAILABLE'),
        )
        .mockResolvedValueOnce(versionedPreferences(defaults)),
      update: vi.fn(),
    };
    const fixture = await createFixture(api, accountIdentity);
    await fixture.whenStable();
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Chưa tải hoặc lưu được cài đặt');
    expect(fixture.nativeElement.querySelectorAll('input[type="checkbox"]')).toHaveLength(0);

    (fixture.nativeElement.querySelector('button') as HTMLButtonElement).click();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelectorAll('input[type="checkbox"]')).toHaveLength(2);
    fixture.destroy();
  });
});

const defaults = {
  analyticsEnabled: false,
  contentProviderSharingEnabled: false,
  version: 0,
  updatedAt: null,
};

function versionedPreferences(preferences: AccountPrivacyPreferences) {
  return {
    preferences,
    etag: `"privacy-preferences-v${preferences.version}"`,
  };
}

async function createFixture(
  api: Partial<AccountPrivacyPreferencesApiService>,
  accountIdentity: ReturnType<typeof signal>,
): Promise<ComponentFixture<AccountPrivacyPreferencesSettingsComponent>> {
  TestBed.configureTestingModule({
    imports: [AccountPrivacyPreferencesSettingsComponent],
    providers: [
      { provide: AccountPrivacyPreferencesApiService, useValue: api },
      { provide: PolicyOnboardingService, useValue: { accountIdentity } },
      { provide: PLATFORM_ID, useValue: 'browser' },
    ],
  });
  const fixture = TestBed.createComponent(AccountPrivacyPreferencesSettingsComponent);
  fixture.detectChanges();
  return fixture;
}
