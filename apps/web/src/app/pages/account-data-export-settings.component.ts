import { isPlatformBrowser } from '@angular/common';
import { Component, effect, inject, signal } from '@angular/core';
import { PLATFORM_ID } from '@angular/core';
import {
  AccountDataExportApiError,
  AccountDataExportApiService,
  AccountDataExportJob,
} from '../core/account-data-export-api.service';
import { PolicyOnboardingService } from '../core/policy-onboarding.service';

@Component({
  selector: 'app-account-data-export-settings',
  templateUrl: './account-data-export-settings.component.html',
  styleUrl: './account-data-export-settings.component.css',
})
export class AccountDataExportSettingsComponent {
  protected readonly policy = inject(PolicyOnboardingService);
  protected readonly job = signal<AccountDataExportJob | null>(null);
  protected readonly busy = signal(false);
  protected readonly confirmed = signal(false);
  protected readonly message = signal('');
  protected readonly error = signal('');

  private readonly api = inject(AccountDataExportApiService);
  private readonly browser = isPlatformBrowser(inject(PLATFORM_ID));
  private restoredAccountId: string | null = null;

  constructor() {
    effect(() => {
      const accountId = this.policy.accountIdentity()?.userId ?? null;
      if (accountId === this.restoredAccountId) return;
      this.restoredAccountId = accountId;
      this.job.set(null);
      this.error.set('');
      if (accountId) void this.restoreLatest(accountId);
    });
  }

  protected isActive(): boolean {
    const status = this.job()?.status;
    return status === 'queued' || status === 'processing';
  }

  protected async request(): Promise<void> {
    const accountId = this.policy.accountIdentity()?.userId;
    if (!accountId || !this.confirmed() || this.busy() || this.isActive()) return;

    this.busy.set(true);
    this.error.set('');
    this.message.set('Đang gửi yêu cầu tạo bản sao dữ liệu…');
    const pendingKey = this.storageGet(this.storageKey(accountId, 'pending-key'));
    const idempotencyKey = pendingKey ?? createIdempotencyKey();
    this.storageSet(this.storageKey(accountId, 'pending-key'), idempotencyKey);
    try {
      const result = await this.api.request(idempotencyKey);
      this.job.set(result.job);
      this.storageRemove(this.storageKey(accountId, 'pending-key'));
      this.confirmed.set(false);
      this.message.set(
        result.replayed
          ? 'Đã khôi phục yêu cầu export trước đó. Trạng thái bên dưới đã được cập nhật.'
          : 'Yêu cầu đã được tiếp nhận. Bạn có thể quay lại trang này để kiểm tra trạng thái.',
      );
      if (result.job.status === 'failed') this.message.set('Máy chủ chưa tạo được bản export.');
    } catch (caught) {
      const apiError = asApiError(caught);
      if (apiError.code === 'EXPORT_ALREADY_PENDING') {
        this.storageRemove(this.storageKey(accountId, 'pending-key'));
        await this.restoreLatest(accountId);
        this.message.set('Đã tìm thấy một yêu cầu đang được xử lý.');
      } else {
        this.error.set(messageFor(apiError));
        this.message.set('');
      }
    } finally {
      this.busy.set(false);
    }
  }

  protected async refresh(): Promise<void> {
    const accountId = this.policy.accountIdentity()?.userId;
    if (!accountId || this.busy()) return;
    this.busy.set(true);
    this.error.set('');
    try {
      const latest = await this.api.latest();
      this.job.set(latest);
      this.message.set('Trạng thái đã được cập nhật.');
    } catch (caught) {
      this.error.set(messageFor(asApiError(caught)));
      this.message.set('');
    } finally {
      this.busy.set(false);
    }
  }

  protected statusLabel(status: AccountDataExportJob['status']): string {
    return {
      queued: 'Đang chờ xử lý',
      processing: 'Đang tạo bản export',
      ready: 'Đã sẵn sàng tải xuống',
      failed: 'Tạo bản export không thành công',
      expired: 'Bản export đã hết hạn',
    }[status];
  }

  protected formatSize(bytes: number | null): string {
    if (bytes === null) return '';
    if (bytes < 1024) return `${bytes} byte`;
    if (bytes < 1_048_576) return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / 1_048_576).toFixed(1)} MB`;
  }

  private async restoreLatest(accountId: string): Promise<void> {
    this.busy.set(true);
    try {
      const latest = await this.api.latest();
      if (this.policy.accountIdentity()?.userId !== accountId) return;
      this.job.set(latest);
    } catch (caught) {
      const apiError = asApiError(caught);
      if (apiError.status !== 404) this.error.set(messageFor(apiError));
    } finally {
      this.busy.set(false);
    }
  }

  private storageKey(accountId: string, suffix: string): string {
    return `novel-web.account-data-export.${accountId}.${suffix}`;
  }

  private storageGet(key: string): string | null {
    if (!this.browser) return null;
    try {
      return sessionStorage.getItem(key);
    } catch {
      return null;
    }
  }

  private storageSet(key: string, value: string): void {
    if (!this.browser) return;
    try {
      sessionStorage.setItem(key, value);
    } catch {
      // The account export remains available when browser storage is blocked.
    }
  }

  private storageRemove(key: string): void {
    if (!this.browser) return;
    try {
      sessionStorage.removeItem(key);
    } catch {
      // The account export remains available when browser storage is blocked.
    }
  }
}

function createIdempotencyKey(): string {
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID();
  return `export-${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

function asApiError(error: unknown): AccountDataExportApiError {
  return error instanceof AccountDataExportApiError
    ? error
    : new AccountDataExportApiError(0, 'NETWORK_UNAVAILABLE');
}

function messageFor(error: AccountDataExportApiError): string {
  switch (error.code) {
    case 'ACCOUNT_EXPORT_DISABLED':
      return 'Tính năng export chưa được bật trên máy chủ này.';
    case 'RECENT_AUTHENTICATION_REQUIRED':
      return 'Vì lý do bảo mật, hãy đăng xuất rồi đăng nhập lại trước khi yêu cầu export.';
    case 'POLICY_ACCEPTANCE_REQUIRED':
      return 'Hãy xem và xác nhận Điều khoản cùng Thông báo quyền riêng tư hiện hành trước.';
    case 'UNAUTHENTICATED':
      return 'Phiên đăng nhập đã hết hạn. Hãy đăng nhập lại để tiếp tục.';
    case 'ACCOUNT_EXPORT_UNAVAILABLE':
    case 'EXPORT_ARTIFACT_UNAVAILABLE':
      return 'Dịch vụ export tạm thời chưa sẵn sàng. Hãy thử tải lại trạng thái sau.';
    case 'NETWORK_UNAVAILABLE':
      return 'Không kết nối được máy chủ. Kiểm tra mạng rồi thử lại.';
    default:
      return error.status === 0
        ? 'Không kết nối được máy chủ. Kiểm tra mạng rồi thử lại.'
        : 'Máy chủ chưa xử lý được yêu cầu. Hãy thử lại sau.';
  }
}
