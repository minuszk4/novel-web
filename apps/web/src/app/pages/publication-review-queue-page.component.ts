import { Component, OnInit, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { SystemStatusService } from '../core/system-status.service';
import {
  PublicationReviewApiError,
  PublicationReviewApiService,
  PublicationReviewQueueItem,
  PublicationReviewScope,
} from '../core/publication-review-api.service';

type QueueAccessState = 'unauthenticated' | 'reviewer-required' | 'policy-required';

@Component({
  selector: 'app-publication-review-queue-page',
  imports: [RouterLink],
  templateUrl: './publication-review-queue-page.component.html',
  styleUrl: './publication-review-queue-page.component.css',
})
export class PublicationReviewQueuePageComponent implements OnInit {
  private readonly api = inject(PublicationReviewApiService);
  protected readonly systemStatus = inject(SystemStatusService);

  protected readonly items = signal<PublicationReviewQueueItem[]>([]);
  protected readonly nextCursor = signal<string | null>(null);
  protected readonly loading = signal(false);
  protected readonly loadingMore = signal(false);
  protected readonly refreshing = signal(false);
  protected readonly errorMessage = signal<string | null>(null);
  protected readonly accessState = signal<QueueAccessState | null>(null);
  protected readonly firstPageRecovery = signal(false);
  protected readonly announcement = signal('');

  ngOnInit(): void {
    void this.loadQueue(true);
  }

  protected async refresh(): Promise<void> {
    await this.loadQueue(true);
  }

  protected async loadMore(): Promise<void> {
    if (!this.nextCursor() || this.loadingMore() || this.loading()) return;
    await this.loadQueue(false);
  }

  protected scopeLabel(scope: PublicationReviewScope): string {
    switch (scope) {
      case 'PUBLIC_METADATA':
        return 'Thông tin công khai';
      case 'CHAPTER_TEXT':
        return 'Văn bản chương';
      case 'COMMERCIAL_SALE':
        return 'Quyền thương mại';
      case 'TRANSLATION_ADAPTATION':
        return 'Quyền chuyển ngữ';
    }
  }

  protected formatDate(value: string): string {
    return new Intl.DateTimeFormat('vi-VN', { dateStyle: 'medium', timeStyle: 'short' }).format(
      new Date(value),
    );
  }

  private async loadQueue(reset: boolean): Promise<void> {
    if (this.loading() || this.loadingMore()) return;
    const cursor = reset ? null : this.nextCursor();
    if (!reset && !cursor) return;

    if (reset) {
      this.items.set([]);
      this.nextCursor.set(null);
      this.accessState.set(null);
      this.errorMessage.set(null);
      this.firstPageRecovery.set(false);
      this.loading.set(true);
      this.refreshing.set(this.announcement() !== '');
    } else {
      this.loadingMore.set(true);
      this.errorMessage.set(null);
    }

    try {
      const page = await this.api.getQueue(cursor, 20);
      this.items.update((current) => {
        const known = new Set(current.map(({ requestId }) => requestId));
        return [...current, ...page.items.filter(({ requestId }) => !known.has(requestId))];
      });
      this.nextCursor.set(page.nextCursor);
      this.announcement.set(
        reset
          ? `${page.items.length} yêu cầu đang chờ duyệt.`
          : `Đã tải thêm ${page.items.length} yêu cầu.`,
      );
    } catch (error) {
      const status = statusOf(error);
      const code = codeOf(error);
      if (status === 401) {
        this.items.set([]);
        this.accessState.set('unauthenticated');
      } else if (status === 403) {
        this.items.set([]);
        this.nextCursor.set(null);
        this.accessState.set(
          code === 'POLICY_ACCEPTANCE_REQUIRED' ? 'policy-required' : 'reviewer-required',
        );
      } else if (status === 400 && !reset) {
        this.items.set([]);
        this.nextCursor.set(null);
        this.firstPageRecovery.set(true);
        this.errorMessage.set('Trang tiếp theo đã hết hiệu lực. Tải lại hàng đợi để bắt đầu mới.');
      } else {
        this.errorMessage.set(queueErrorMessage(error));
      }
      this.announcement.set(this.errorMessage() ?? 'Không thể tải hàng đợi duyệt.');
    } finally {
      this.loading.set(false);
      this.loadingMore.set(false);
      this.refreshing.set(false);
    }
  }
}

function statusOf(error: unknown): number {
  return error instanceof PublicationReviewApiError ? error.status : -1;
}

function codeOf(error: unknown): string | null {
  return error instanceof PublicationReviewApiError ? error.code : null;
}

function queueErrorMessage(error: unknown): string {
  if (statusOf(error) === 503) {
    return 'Hàng đợi hiện chưa sẵn sàng. Không có thay đổi nào được thực hiện; hãy thử tải lại.';
  }
  if (statusOf(error) === 0) {
    return 'Không thể xác minh dữ liệu hàng đợi. Kiểm tra kết nối rồi thử tải lại.';
  }
  return 'Không thể tải hàng đợi duyệt. Hãy thử lại sau.';
}
