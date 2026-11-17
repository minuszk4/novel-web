import { DatePipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import {
  InAppNotification,
  NotificationApiError,
  NotificationApiService,
  NotificationPage,
} from '../core/notification-api.service';

@Component({
  selector: 'app-notifications-page',
  imports: [DatePipe, RouterLink],
  templateUrl: './notifications-page.component.html',
  styleUrl: './notifications-page.component.css',
})
export class NotificationsPageComponent {
  private readonly api = inject(NotificationApiService);
  protected readonly page = signal<NotificationPage | null>(null);
  protected readonly loading = signal(true);
  protected readonly loadingMore = signal(false);
  protected readonly markingAllRead = signal(false);
  protected readonly markingReadId = signal<string | null>(null);
  protected readonly error = signal<NotificationApiError | null>(null);
  protected readonly actionError = signal<string | null>(null);
  protected readonly message = signal<string | null>(null);
  protected readonly hasItems = computed(() => (this.page()?.items.length ?? 0) > 0);

  constructor() {
    void this.loadFirstPage();
  }

  protected async loadMore(): Promise<void> {
    const cursor = this.page()?.nextCursor;
    if (!cursor || this.loadingMore()) return;
    this.loadingMore.set(true);
    this.actionError.set(null);
    try {
      const next = await this.api.listMine(cursor);
      this.page.update((current) => {
        if (!current) return next;
        const seen = new Set(current.items.map((item) => item.notificationId));
        return {
          items: [...current.items, ...next.items.filter((item) => !seen.has(item.notificationId))],
          nextCursor: next.nextCursor,
          unreadCount: next.unreadCount,
        };
      });
    } catch {
      this.actionError.set('Chưa tải thêm được. Hãy thử lại.');
    } finally {
      this.loadingMore.set(false);
    }
  }

  protected async markRead(item: InAppNotification): Promise<void> {
    if (item.readAt || this.markingReadId()) return;
    this.markingReadId.set(item.notificationId);
    this.actionError.set(null);
    try {
      const readAt = await this.api.markRead(item.notificationId);
      this.page.update((current) => {
        if (!current) return current;
        return {
          ...current,
          items: current.items.map((candidate) =>
            candidate.notificationId === item.notificationId ? { ...candidate, readAt } : candidate,
          ),
          unreadCount: Math.max(0, current.unreadCount - 1),
        };
      });
      this.message.set('Đã đánh dấu thông báo là đã đọc.');
    } catch {
      this.actionError.set('Không thể cập nhật thông báo. Hãy tải lại danh sách rồi thử lại.');
    } finally {
      this.markingReadId.set(null);
    }
  }

  protected async markAllRead(): Promise<void> {
    if (this.markingAllRead() || !this.page()?.unreadCount) return;
    this.markingAllRead.set(true);
    this.actionError.set(null);
    let totalUpdated = 0;
    let hasMore = true;
    let batches = 0;
    try {
      while (hasMore && batches < 20) {
        const receipt = await this.api.markAllRead();
        totalUpdated += receipt.updatedCount;
        hasMore = receipt.hasMore;
        batches += 1;
        if (receipt.updatedCount === 0 && receipt.hasMore) break;
      }
      const readAt = new Date().toISOString();
      this.page.update((current) =>
        current
          ? {
              ...current,
              items: current.items.map((item) => ({ ...item, readAt: item.readAt ?? readAt })),
              unreadCount: this.api.unreadCount(),
            }
          : current,
      );
      this.message.set(
        hasMore
          ? `Đã đánh dấu ${totalUpdated} thông báo. Bấm lại để tiếp tục phần còn lại.`
          : totalUpdated > 0
            ? `Đã đánh dấu ${totalUpdated} thông báo là đã đọc.`
            : 'Không còn thông báo chưa đọc.',
      );
      void this.api.refreshUnreadCount().catch(() => undefined);
    } catch {
      this.actionError.set('Chưa đánh dấu hết được. Hãy thử lại; trạng thái đã lưu vẫn được giữ.');
    } finally {
      this.markingAllRead.set(false);
    }
  }

  protected retry(): void {
    void this.loadFirstPage();
  }

  private async loadFirstPage(): Promise<void> {
    this.loading.set(true);
    this.error.set(null);
    this.actionError.set(null);
    try {
      this.page.set(await this.api.listMine());
    } catch (error) {
      this.error.set(
        error instanceof NotificationApiError
          ? error
          : new NotificationApiError(0, 'NOTIFICATIONS_REQUEST_FAILED'),
      );
    } finally {
      this.loading.set(false);
    }
  }
}
