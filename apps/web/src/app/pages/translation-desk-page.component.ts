import { DatePipe } from '@angular/common';
import { Component, DestroyRef, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import {
  TranslationJobApiError,
  TranslationJobApiService,
  TranslationJobStatus,
  TranslationJobState,
} from '../core/translation-job-api.service';

type DeskPageState = 'loading' | 'ready' | 'error';
type DeskErrorAction = 'retry' | 'reload' | 'sign-in' | 'policy' | 'none';

interface DeskError {
  message: string;
  action: DeskErrorAction;
}

const pageSize = 25;

@Component({
  selector: 'app-translation-desk-page',
  imports: [DatePipe, RouterLink],
  templateUrl: './translation-desk-page.component.html',
  styleUrl: './translation-desk-page.component.css',
})
export class TranslationDeskPageComponent {
  protected readonly pageState = signal<DeskPageState>('loading');
  protected readonly jobs = signal<TranslationJobStatus[]>([]);
  protected readonly nextCursor = signal<string | null>(null);
  protected readonly initialError = signal<DeskError | null>(null);
  protected readonly refreshError = signal<DeskError | null>(null);
  protected readonly loadMoreError = signal<DeskError | null>(null);
  protected readonly refreshing = signal(false);
  protected readonly loadingMore = signal(false);
  protected readonly announcement = signal('');

  private readonly api = inject(TranslationJobApiService);
  private readonly destroyRef = inject(DestroyRef);
  private requestVersion = 0;

  constructor() {
    this.destroyRef.onDestroy(() => this.requestVersion++);
    void this.refresh();
  }

  protected async refresh(): Promise<void> {
    if (this.refreshing() || this.loadingMore()) return;
    const isReload = this.pageState() === 'ready';
    const version = ++this.requestVersion;
    this.refreshing.set(true);
    this.initialError.set(null);
    this.refreshError.set(null);
    this.loadMoreError.set(null);
    if (!this.jobs().length) this.pageState.set('loading');

    try {
      const page = await this.api.getManageableJobs(null, pageSize);
      if (version !== this.requestVersion) return;
      this.jobs.set(page.items);
      this.nextCursor.set(page.nextCursor);
      this.pageState.set('ready');
      this.announcement.set(
        `${isReload ? 'Đã tải lại' : 'Đã tải'} danh sách. Có ${page.items.length} job trên trang này.`,
      );
    } catch (error) {
      if (version !== this.requestVersion) return;
      const view = deskError(error);
      if (!this.jobs().length) {
        this.initialError.set(view);
        this.pageState.set('error');
      } else {
        this.refreshError.set(view);
      }
    } finally {
      if (version === this.requestVersion) this.refreshing.set(false);
    }
  }

  protected async loadMore(): Promise<void> {
    const cursor = this.nextCursor();
    if (!cursor || this.loadingMore() || this.refreshing()) return;
    const version = ++this.requestVersion;
    this.loadingMore.set(true);
    this.loadMoreError.set(null);
    try {
      const page = await this.api.getManageableJobs(cursor, pageSize);
      if (version !== this.requestVersion) return;
      const knownIds = new Set(this.jobs().map(({ job }) => job.id));
      const appended = page.items.filter(({ job }) => !knownIds.has(job.id));
      this.jobs.update((items) => [...items, ...appended]);
      this.nextCursor.set(page.nextCursor);
      this.announcement.set(
        appended.length
          ? `Đã tải thêm ${appended.length} job. Tổng cộng ${this.jobs().length} job đang hiển thị.`
          : 'Không còn job khác.',
      );
    } catch (error) {
      if (version !== this.requestVersion) return;
      const view = deskError(error);
      if (error instanceof TranslationJobApiError && error.code === 'TRANSLATION_CURSOR_INVALID') {
        this.nextCursor.set(null);
        this.loadMoreError.set({
          message: 'Danh sách đã thay đổi. Hãy tải lại từ trang đầu để tiếp tục.',
          action: 'reload',
        });
      } else {
        this.loadMoreError.set(view);
      }
    } finally {
      if (version === this.requestVersion) this.loadingMore.set(false);
    }
  }

  protected retryLoadMore(): void {
    if (this.loadMoreError()?.action === 'retry') void this.loadMore();
  }

  protected statusLabel(status: TranslationJobState): string {
    switch (status) {
      case 'queued':
        return 'Đang chờ xử lý';
      case 'preparing':
        return 'Đang chuẩn bị';
      case 'translating':
        return 'Đang chạy bản thử';
      case 'validating':
        return 'Đang kiểm tra kết quả giả';
      case 'retry_wait':
        return 'Đang chờ thử lại';
      case 'review_required':
        return 'Candidate giả đã sẵn sàng';
      case 'completed':
        return 'Job đã xong · chưa xuất bản';
      case 'failed':
        return 'Job gặp lỗi';
      case 'cancel_requested':
        return 'Đang dừng bản thử';
      case 'cancelled':
        return 'Đã dừng';
    }
  }

  protected statusTone(status: TranslationJobState): 'active' | 'error' | 'quiet' {
    if (status === 'failed') return 'error';
    if (status === 'cancelled' || status === 'completed') return 'quiet';
    return 'active';
  }

  protected canViewCandidate(status: TranslationJobStatus): boolean {
    const job = status.job;
    return (
      job.canManage &&
      job.sourceRevisionCurrent &&
      job.status === 'review_required' &&
      job.candidateStatus === 'ready_for_review' &&
      job.candidateId !== null
    );
  }

  protected processedUnits(status: TranslationJobStatus): number {
    return status.terminalUnits;
  }

  protected formatTimestamp(value: string): string {
    const timestamp = Date.parse(value);
    if (!Number.isFinite(timestamp)) return 'Thời gian không rõ';
    return new Intl.DateTimeFormat('vi-VN', {
      dateStyle: 'medium',
      timeStyle: 'short',
    }).format(timestamp);
  }
}

function deskError(error: unknown): DeskError {
  if (!(error instanceof TranslationJobApiError)) {
    return { message: 'Không tải được danh sách. Kiểm tra kết nối rồi thử lại.', action: 'retry' };
  }
  if (error.status === 401) {
    return { message: 'Phiên đăng nhập đã hết hạn. Đăng nhập lại để tiếp tục.', action: 'sign-in' };
  }
  if (error.code === 'POLICY_ACCEPTANCE_REQUIRED') {
    return {
      message: 'Cần xác nhận Điều khoản và Thông báo quyền riêng tư hiện hành.',
      action: 'policy',
    };
  }
  if (error.code === 'TRANSLATION_CURSOR_INVALID') {
    return { message: 'Danh sách đã thay đổi. Tải lại trang đầu để tiếp tục.', action: 'reload' };
  }
  if (error.status === 0 || error.status >= 500) {
    return {
      message: 'Dịch vụ chưa phản hồi. Dữ liệu không bị thay đổi; hãy thử lại.',
      action: 'retry',
    };
  }
  if (error.status === 404) {
    return {
      message: 'Danh sách hiện không khả dụng hoặc tính năng thử cục bộ chưa bật.',
      action: 'none',
    };
  }
  return { message: 'Không thể tải danh sách với quyền truy cập hiện tại.', action: 'none' };
}
