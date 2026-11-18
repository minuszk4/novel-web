import { PLATFORM_ID, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import {
  AccountDataExportApiError,
  AccountDataExportApiService,
} from '../core/account-data-export-api.service';
import { PolicyOnboardingService } from '../core/policy-onboarding.service';
import { AccountDataExportSettingsComponent } from './account-data-export-settings.component';

describe('AccountDataExportSettingsComponent', () => {
  it('explains the full owned-content scope and requests only after confirmation', async () => {
    const accountIdentity = signal({
      userId: 'account-1',
      displayName: 'Reader',
      contactEmail: null,
    });
    const api = {
      latest: vi.fn().mockRejectedValue(new AccountDataExportApiError(404, 'EXPORT_NOT_FOUND')),
      request: vi.fn().mockResolvedValue({ job: readyJob, replayed: false }),
    };
    const fixture = await createFixture(api, accountIdentity);
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('nội dung đầy đủ mà tài khoản này sở hữu');
    expect(fixture.nativeElement.textContent).toContain('Nội dung thuộc người khác');
    const requestButton = fixture.nativeElement.querySelector(
      'button.button-primary',
    ) as HTMLButtonElement;
    expect(requestButton.disabled).toBe(true);

    const checkbox = fixture.nativeElement.querySelector(
      '.export-confirmation input',
    ) as HTMLInputElement;
    checkbox.checked = true;
    checkbox.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    expect(requestButton.disabled).toBe(false);

    requestButton.click();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(api.request).toHaveBeenCalledOnce();
    expect(fixture.nativeElement.querySelector('a[download]')?.textContent).toContain(
      'Tải tệp ZIP',
    );
    fixture.destroy();
  });

  it('shows a sign-in explanation without exposing the request control', async () => {
    const accountIdentity = signal(null);
    const api = { latest: vi.fn(), request: vi.fn() };
    const fixture = await createFixture(api, accountIdentity);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Đăng nhập bằng tài khoản server');
    expect(fixture.nativeElement.querySelector('button.button-primary')).toBeNull();
    expect(api.latest).not.toHaveBeenCalled();
    fixture.destroy();
  });
});

const readyJob = {
  id: '88f4e518-8587-4b96-b424-d657ef71a660',
  status: 'ready' as const,
  requestedAt: '2026-11-18T10:00:00Z',
  startedAt: '2026-11-18T10:00:01Z',
  readyAt: '2026-11-18T10:00:02Z',
  expiresAt: '2026-11-19T10:00:02Z',
  artifactBytes: 1024,
  failureCode: null,
  downloadUrl: '/api/v1/me/data-exports/88f4e518-8587-4b96-b424-d657ef71a660/download',
};

async function createFixture(
  api: Partial<AccountDataExportApiService>,
  accountIdentity: ReturnType<typeof signal>,
): Promise<ComponentFixture<AccountDataExportSettingsComponent>> {
  TestBed.configureTestingModule({
    imports: [AccountDataExportSettingsComponent],
    providers: [
      { provide: AccountDataExportApiService, useValue: api },
      { provide: PolicyOnboardingService, useValue: { accountIdentity } },
      { provide: PLATFORM_ID, useValue: 'browser' },
    ],
  });
  const fixture = TestBed.createComponent(AccountDataExportSettingsComponent);
  fixture.detectChanges();
  return fixture;
}
