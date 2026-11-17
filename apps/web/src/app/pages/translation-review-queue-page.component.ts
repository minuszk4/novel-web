import { Component, ElementRef, OnInit, ViewChild, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import {
  TranslationReviewApiError,
  TranslationReviewApiService,
  TranslationReviewQueueItem,
} from '../core/translation-review-api.service';

type QueueAccessState = 'unauthenticated' | 'reviewer-required' | 'policy-required';

@Component({
  selector: 'app-translation-review-queue-page',
  imports: [RouterLink],
  templateUrl: './translation-review-queue-page.component.html',
  styleUrl: './translation-review-queue-page.component.css',
})
export class TranslationReviewQueuePageComponent implements OnInit {
  private readonly api = inject(TranslationReviewApiService);

  protected readonly items = signal<TranslationReviewQueueItem[]>([]);
  protected readonly nextCursor = signal<string | null>(null);
  protected readonly loading = signal(false);
  protected readonly loadingMore = signal(false);
  protected readonly refreshing = signal(false);
  protected readonly errorMessage = signal<string | null>(null);
  protected readonly accessState = signal<QueueAccessState | null>(null);
  protected readonly reloadFirstPage = signal(false);
  protected readonly announcement = signal('');

  @ViewChild('pageHeading')
  private set pageHeading(heading: ElementRef<HTMLHeadingElement> | undefined) {
    heading?.nativeElement.focus({ preventScroll: true });
  }

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
      this.reloadFirstPage.set(false);
      this.loading.set(true);
      this.refreshing.set(this.announcement() !== '');
    } else {
      this.loadingMore.set(true);
      this.errorMessage.set(null);
    }

    try {
      const page = await this.api.getQueue(cursor, 20);
      this.items.update((current) => {
        const known = new Set(current.map(({ submissionId }) => submissionId));
        return [...current, ...page.items.filter(({ submissionId }) => !known.has(submissionId))];
      });
      this.nextCursor.set(page.nextCursor);
      this.announcement.set(
        reset
          ? `${page.items.length} bản dịch đang chờ duyệt.`
          : `Đã tải thêm ${page.items.length} bản dịch.`,
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
      } else if (status === 404) {
        this.items.set([]);
        this.nextCursor.set(null);
        this.accessState.set('reviewer-required');
      } else if (status === 400 && !reset) {
        this.items.set([]);
        this.nextCursor.set(null);
        this.reloadFirstPage.set(true);
        this.errorMessage.set('Trang tiếp theo đã hết hiệu lực. Tải lại hàng đợi để bắt đầu mới.');
      } else {
        this.errorMessage.set(
          status === 503
            ? 'Hàng đợi chưa sẵn sàng. Không có quyết định nào được ghi nhận; hãy thử tải lại.'
            : 'Không thể xác minh hàng đợi. Kiểm tra kết nối rồi thử lại.',
        );
      }
      this.announcement.set(this.errorMessage() ?? 'Không thể tải hàng đợi duyệt bản dịch.');
    } finally {
      this.loading.set(false);
      this.loadingMore.set(false);
      this.refreshing.set(false);
    }
  }
}

function statusOf(error: unknown): number {
  return error instanceof TranslationReviewApiError ? error.status : -1;
}

function codeOf(error: unknown): string | null {
  return error instanceof TranslationReviewApiError ? error.code : null;
}
