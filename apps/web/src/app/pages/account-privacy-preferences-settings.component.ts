import { Component, effect, inject, signal } from '@angular/core';
import {
  AccountPrivacyPreferencesApiError,
  AccountPrivacyPreferencesApiService,
  VersionedAccountPrivacyPreferences,
} from '../core/account-privacy-preferences-api.service';
import { PolicyOnboardingService } from '../core/policy-onboarding.service';

@Component({
  selector: 'app-account-privacy-preferences-settings',
  templateUrl: './account-privacy-preferences-settings.component.html',
  styleUrl: './account-privacy-preferences-settings.component.css',
})
export class AccountPrivacyPreferencesSettingsComponent {
  protected readonly policy = inject(PolicyOnboardingService);
  protected readonly preferences = signal<VersionedAccountPrivacyPreferences | null>(null);
  protected readonly busy = signal(false);
  protected readonly loading = signal(false);
  protected readonly error = signal('');
  protected readonly message = signal('');

  private readonly api = inject(AccountPrivacyPreferencesApiService);
  private loadedAccountId: string | null = null;
  private loadSequence = 0;

  constructor() {
    effect(() => {
      const accountId = this.policy.accountIdentity()?.userId ?? null;
      if (accountId === this.loadedAccountId) return;
      this.loadedAccountId = accountId;
      this.loadSequence++;
      this.preferences.set(null);
      this.busy.set(false);
      this.loading.set(false);
      this.error.set('');
      this.message.set('');
      if (accountId) void this.load(accountId);
    });
  }

  protected async retry(): Promise<void> {
    const accountId = this.policy.accountIdentity()?.userId;
    if (!accountId || this.busy()) return;
    await this.load(accountId);
  }

  protected async setChoice(
    field: 'analyticsEnabled' | 'contentProviderSharingEnabled',
    event: Event,
  ): Promise<void> {
    const current = this.preferences();
    const accountId = this.policy.accountIdentity()?.userId;
    if (!current || !accountId || this.busy()) return;
    const input = event.target;
    if (!(input instanceof HTMLInputElement)) return;

    const next = {
      analyticsEnabled: current.preferences.analyticsEnabled,
      contentProviderSharingEnabled: current.preferences.contentProviderSharingEnabled,
      [field]: input.checked,
    };
    this.busy.set(true);
    this.error.set('');
    this.message.set('');
    try {
      const updated = await this.api.update(next, current.etag);
      if (this.policy.accountIdentity()?.userId !== accountId) return;
      this.preferences.set(updated);
      this.message.set('Lựa chọn quyền riêng tư đã được lưu.');
    } catch (caught) {
      const apiError = asApiError(caught);
      if (apiError.status === 412) {
        const reloaded = await this.load(accountId);
        if (this.policy.accountIdentity()?.userId === accountId) {
          input.checked = this.preferences()?.preferences[field] ?? current.preferences[field];
          if (reloaded) {
            this.message.set('Cài đặt đã được đổi ở nơi khác. Đã tải lại lựa chọn mới nhất.');
          }
        }
      } else {
        input.checked = current.preferences[field];
        this.error.set(messageFor(apiError));
      }
    } finally {
      this.busy.set(false);
    }
  }

  private async load(accountId: string): Promise<boolean> {
    const sequence = ++this.loadSequence;
    this.loading.set(true);
    this.error.set('');
    try {
      const current = await this.api.get();
      if (sequence !== this.loadSequence || this.policy.accountIdentity()?.userId !== accountId) {
        return false;
      }
      this.preferences.set(current);
      return true;
    } catch (caught) {
      const apiError = asApiError(caught);
      if (sequence === this.loadSequence && this.policy.accountIdentity()?.userId === accountId) {
        this.error.set(messageFor(apiError));
      }
      return false;
    } finally {
      if (sequence === this.loadSequence) this.loading.set(false);
    }
  }
}

function asApiError(error: unknown): AccountPrivacyPreferencesApiError {
  return error instanceof AccountPrivacyPreferencesApiError
    ? error
    : new AccountPrivacyPreferencesApiError(0, 'NETWORK_UNAVAILABLE');
}

function messageFor(error: AccountPrivacyPreferencesApiError): string {
  switch (error.code) {
    case 'UNAUTHENTICATED':
      return 'Phiên đăng nhập đã hết hạn. Hãy đăng nhập lại để xem lựa chọn quyền riêng tư.';
    case 'PRIVACY_PREFERENCES_UNAVAILABLE':
      return 'Chưa tải hoặc lưu được cài đặt. Hãy kiểm tra kết nối rồi thử lại.';
    case 'CSRF_UNAVAILABLE':
      return 'Không xác minh được yêu cầu bảo mật. Hãy tải lại trang rồi thử lại.';
    case 'NETWORK_UNAVAILABLE':
      return 'Không kết nối được máy chủ. Lựa chọn trước đó vẫn được giữ nguyên.';
    default:
      return error.status === 0
        ? 'Không kết nối được máy chủ. Lựa chọn trước đó vẫn được giữ nguyên.'
        : 'Máy chủ chưa lưu được lựa chọn. Hãy thử lại sau.';
  }
}
