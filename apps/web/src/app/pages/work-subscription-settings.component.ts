import { Component, OnInit, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import {
  FollowedWorkSetting,
  WorkSubscriptionApiError,
  WorkSubscriptionApiService,
} from '../core/work-subscription.service';
import { PolicyOnboardingService } from '../core/policy-onboarding.service';

type ListState = 'loading' | 'guest' | 'empty' | 'ready' | 'error';

@Component({
  selector: 'app-work-subscription-settings',
  imports: [RouterLink],
  templateUrl: './work-subscription-settings.component.html',
})
export class WorkSubscriptionSettingsComponent implements OnInit {
  private readonly api = inject(WorkSubscriptionApiService);
  private readonly policy = inject(PolicyOnboardingService);

  protected readonly state = signal<ListState>('loading');
  protected readonly items = signal<FollowedWorkSetting[]>([]);
  protected readonly nextCursor = signal<string | null>(null);
  protected readonly message = signal('');
  protected readonly error = signal('');
  protected readonly busyWorkId = signal<string | null>(null);
  protected readonly loadingMore = signal(false);

  ngOnInit(): void {
    void this.start();
  }

  protected retry(): void {
    void this.loadFirstPage();
  }

  protected loadMore(): void {
    void this.loadNextPage();
  }

  protected async toggle(item: FollowedWorkSetting): Promise<void> {
    if (!item.available || this.busyWorkId() !== null) return;
    this.busyWorkId.set(item.workId);
    this.message.set('');
    this.error.set('');
    try {
      const receipt = await this.api.setNotifications(
        item.workId,
        !item.notificationsEnabled,
        `"subscription-${item.version}"`,
      );
      const version = parseVersion(receipt.etag);
      this.items.update((rows) =>
        rows.map((row) =>
          row.workId === item.workId
            ? { ...row, notificationsEnabled: receipt.state.notificationsEnabled, version }
            : row,
        ),
      );
      this.message.set(
        receipt.state.notificationsEnabled
          ? 'Đã bật lại lựa chọn thông báo cho tác phẩm.'
          : 'Đã tạm tắt lựa chọn thông báo cho tác phẩm.',
      );
    } catch (cause) {
      await this.reconcile(cause);
    } finally {
      this.busyWorkId.set(null);
    }
  }

  protected async unfollow(item: FollowedWorkSetting): Promise<void> {
    if (this.busyWorkId() !== null) return;
    this.busyWorkId.set(item.workId);
    this.message.set('');
    this.error.set('');
    try {
      await this.api.unfollow(item.workId, `"subscription-${item.version}"`);
      this.items.update((rows) => rows.filter((row) => row.workId !== item.workId));
      this.state.set(this.items().length === 0 ? 'empty' : 'ready');
      this.message.set('Đã bỏ theo dõi tác phẩm.');
    } catch (cause) {
      await this.reconcile(cause);
    } finally {
      this.busyWorkId.set(null);
    }
  }

  protected followedAtLabel(value: string): string {
    const timestamp = Date.parse(value);
    if (!Number.isFinite(timestamp)) return 'Ngày theo dõi không khả dụng';
    return `Theo dõi từ ${new Intl.DateTimeFormat('vi-VN', { dateStyle: 'medium' }).format(timestamp)}`;
  }

  private async start(): Promise<void> {
    await this.policy.load();
    if (!this.policy.accountIdentity()) {
      this.state.set('guest');
      return;
    }
    await this.loadFirstPage();
  }

  private async loadFirstPage(): Promise<void> {
    this.state.set('loading');
    this.items.set([]);
    this.nextCursor.set(null);
    this.error.set('');
    this.message.set('');
    try {
      const page = await this.api.listMine();
      this.items.set(page.items);
      this.nextCursor.set(page.nextCursor);
      this.state.set(page.items.length === 0 ? 'empty' : 'ready');
    } catch (cause) {
      this.state.set('error');
      this.error.set(errorMessage(cause));
    }
  }

  private async loadNextPage(): Promise<void> {
    const cursor = this.nextCursor();
    if (!cursor || this.loadingMore()) return;
    this.loadingMore.set(true);
    this.error.set('');
    try {
      const page = await this.api.listMine(cursor);
      this.items.update((current) => {
        const seen = new Set(current.map((item) => item.id));
        return [...current, ...page.items.filter((item) => !seen.has(item.id))];
      });
      this.nextCursor.set(page.nextCursor);
    } catch (cause) {
      const apiError = cause instanceof WorkSubscriptionApiError ? cause : null;
      if (apiError?.code === 'INVALID_SUBSCRIPTION_CURSOR') {
        await this.loadFirstPage();
        this.message.set('Danh sách đã thay đổi; đã tải lại từ đầu.');
      } else {
        this.error.set(errorMessage(cause));
      }
    } finally {
      this.loadingMore.set(false);
    }
  }

  private async reconcile(cause: unknown): Promise<void> {
    const status = cause instanceof WorkSubscriptionApiError ? cause.status : 0;
    const code = cause instanceof WorkSubscriptionApiError ? cause.code : '';
    if (status === 401) {
      this.error.set('Phiên đăng nhập đã hết hạn. Hãy đăng nhập lại rồi thử tiếp.');
      return;
    }
    if (status === 403) {
      this.error.set('Hãy hoàn tất chấp thuận Điều khoản và Quyền riêng tư trong Cài đặt.');
      return;
    }
    await this.reloadPreservingRows();
    if (status === 429) {
      this.error.set(
        'Bạn vừa thay đổi theo dõi quá nhanh. Trạng thái đã được đọc lại; hãy chờ rồi thử lại.',
      );
    } else if (status === 412 || code === 'SUBSCRIPTION_CHANGED') {
      this.message.set('Lựa chọn theo dõi đã thay đổi ở nơi khác; danh sách đã được cập nhật.');
    } else if (status === 404) {
      this.message.set('Tác phẩm không còn công khai; hãy bỏ theo dõi để dọn mục này.');
    } else {
      this.error.set(
        'Chưa xác nhận được thay đổi. Danh sách đã được đồng bộ lại; bạn có thể thử tiếp.',
      );
    }
  }

  private async reloadPreservingRows(): Promise<void> {
    try {
      const page = await this.api.listMine();
      this.items.set(page.items);
      this.nextCursor.set(page.nextCursor);
      this.state.set(page.items.length === 0 ? 'empty' : 'ready');
    } catch {
      this.error.set('Không thể đồng bộ danh sách lúc này. Hãy thử tải lại.');
    }
  }
}

function parseVersion(etag: string): number {
  const match = /^"subscription-([1-9][0-9]{0,18})"$/.exec(etag);
  if (!match) throw new WorkSubscriptionApiError(0, 'INVALID_SUBSCRIPTION_RESPONSE');
  return Number(match[1]);
}

function errorMessage(cause: unknown): string {
  const apiError = cause instanceof WorkSubscriptionApiError ? cause : null;
  if (apiError?.status === 401) return 'Đăng nhập để xem các tác phẩm đã theo dõi.';
  if (apiError?.status === 403) return 'Hãy hoàn tất chấp thuận Điều khoản và Quyền riêng tư.';
  if (apiError?.status === 503) return 'Danh sách chưa khả dụng. Hãy thử tải lại sau.';
  return 'Không tải được danh sách. Hãy thử lại.';
}
