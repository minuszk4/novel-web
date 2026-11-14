import {
  Component,
  DestroyRef,
  ElementRef,
  HostListener,
  ViewChild,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import {
  newTranslationIdempotencyKey,
  TranslationJobApiError,
  TranslationJobApiService,
  TranslationJobStatus,
} from '../core/translation-job-api.service';

type TranslationJobPageState = 'loading' | 'ready' | 'error';

@Component({
  selector: 'app-translation-job-page',
  imports: [RouterLink],
  templateUrl: './translation-job-page.component.html',
  styleUrl: './translation-job-page.component.css',
})
export class TranslationJobPageComponent {
  protected readonly pageState = signal<TranslationJobPageState>('loading');
  protected readonly job = signal<TranslationJobStatus['job'] | null>(null);
  protected readonly progress = signal(0);
  protected readonly announcement = signal('');
  protected readonly initialError = signal<string | null>(null);
  protected readonly refreshError = signal<string | null>(null);
  protected readonly actionError = signal<string | null>(null);
  protected readonly actionBusy = signal<'retry' | 'cancel' | null>(null);

  private readonly route = inject(ActivatedRoute);
  private readonly api = inject(TranslationJobApiService);
  private readonly destroyRef = inject(DestroyRef);
  private currentJobId: string | null = null;
  private requestToken = 0;
  private pollTimer: number | null = null;
  private lastAnnouncement = '';
  private retryIdempotencyKey: string | null = null;
  private cancelIdempotencyKey: string | null = null;

  @ViewChild('cancelDialog') private cancelDialog?: ElementRef<HTMLDialogElement>;

  constructor() {
    this.route.paramMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((params) => {
      this.stopPolling();
      this.requestToken += 1;
      this.currentJobId = params.get('jobId');
      this.job.set(null);
      this.progress.set(0);
      this.pageState.set('loading');
      this.initialError.set(null);
      this.refreshError.set(null);
      this.actionError.set(null);
      if (!this.currentJobId) {
        this.pageState.set('error');
        this.initialError.set('Liên kết bản xem thử không hợp lệ.');
        return;
      }
      void this.loadStatus(this.requestToken, true);
    });
    this.destroyRef.onDestroy(() => this.stopPolling());
  }

  protected get canRetry(): boolean {
    const job = this.job();
    return !!job && job.canManage && job.status === 'failed' && job.failedUnits > 0;
  }

  protected get canCancel(): boolean {
    const job = this.job();
    return (
      !!job &&
      job.canManage &&
      ['queued', 'preparing', 'translating', 'retry_wait'].includes(job.status)
    );
  }

  protected get isActive(): boolean {
    const status = this.job()?.status;
    return (
      !!status &&
      [
        'queued',
        'preparing',
        'translating',
        'validating',
        'retry_wait',
        'cancel_requested',
      ].includes(status)
    );
  }

  protected statusLabel(): string {
    switch (this.job()?.status) {
      case 'queued':
      case 'preparing':
        return 'Đang chờ xử lý thử';
      case 'translating':
      case 'validating':
        return 'Đang chạy bản thử';
      case 'retry_wait':
        return 'Đang chờ thử lại';
      case 'review_required':
        return 'Bản thử đã tạo xong';
      case 'completed':
        return 'Đã hoàn tất';
      case 'failed':
        return 'Bản thử gặp lỗi';
      case 'cancel_requested':
        return 'Đang dừng bản thử';
      case 'cancelled':
        return 'Đã dừng bản thử';
      default:
        return 'Đang tải trạng thái';
    }
  }

  protected failureMessage(): string | null {
    const code = this.job()?.failureCode;
    if (!code) return null;
    switch (code.toUpperCase()) {
      case 'INPUT_LIMIT_EXCEEDED':
      case 'TRANSLATION_INPUT_LIMIT_EXCEEDED':
        return 'Chương vượt giới hạn của bản xem thử cục bộ.';
      case 'UNSUPPORTED_LANGUAGE':
      case 'UNSUPPORTED_LANGUAGE_PAIR':
        return 'Cặp ngôn ngữ này chưa được hỗ trợ trong bản xem thử.';
      case 'CANCELLED':
        return 'Bản xem thử đã được dừng.';
      default:
        return 'Một unit của bản xem thử không xử lý được. Có thể thử lại nếu thao tác còn khả dụng.';
    }
  }

  protected async refresh(): Promise<void> {
    if (this.actionBusy() || !this.currentJobId) return;
    this.stopPolling();
    await this.loadStatus(this.requestToken, false);
  }

  protected openCancelConfirmation(): void {
    const dialog = this.cancelDialog?.nativeElement;
    if (dialog && !dialog.open) dialog.showModal();
  }

  protected closeCancelConfirmation(): void {
    this.cancelDialog?.nativeElement.close();
  }

  protected async retryFailedUnits(): Promise<void> {
    const jobId = this.currentJobId;
    if (!jobId || !this.canRetry || this.actionBusy()) return;
    const key = (this.retryIdempotencyKey ??= newTranslationIdempotencyKey());
    this.actionBusy.set('retry');
    this.actionError.set(null);
    try {
      await this.api.retry(jobId, key);
      this.retryIdempotencyKey = null;
      await this.loadStatus(this.requestToken, false);
    } catch (error) {
      this.actionError.set(actionErrorMessage(error, 'retry'));
      if (!isAmbiguous(error)) this.retryIdempotencyKey = null;
    } finally {
      this.actionBusy.set(null);
    }
  }

  protected async cancelJob(): Promise<void> {
    const jobId = this.currentJobId;
    if (!jobId || !this.canCancel || this.actionBusy()) return;
    const key = (this.cancelIdempotencyKey ??= newTranslationIdempotencyKey());
    this.actionBusy.set('cancel');
    this.actionError.set(null);
    try {
      await this.api.cancel(jobId, key);
      this.cancelIdempotencyKey = null;
      this.closeCancelConfirmation();
      await this.loadStatus(this.requestToken, false);
    } catch (error) {
      this.actionError.set(actionErrorMessage(error, 'cancel'));
      if (!isAmbiguous(error)) this.cancelIdempotencyKey = null;
    } finally {
      this.actionBusy.set(null);
    }
  }

  @HostListener('document:visibilitychange')
  protected onVisibilityChange(): void {
    if (document.visibilityState === 'hidden') {
      this.stopPolling();
    } else if (this.isActive && this.currentJobId) {
      void this.loadStatus(this.requestToken, false);
    }
  }

  @HostListener('window:pagehide')
  protected onPageHide(): void {
    this.stopPolling();
  }

  private async loadStatus(token: number, initial: boolean): Promise<void> {
    const jobId = this.currentJobId;
    if (!jobId) return;
    if (document.visibilityState === 'hidden') return;
    try {
      const result = await this.api.getStatus(jobId);
      if (token !== this.requestToken) return;
      this.job.set(result.status.job);
      this.progress.set(result.status.progressPercent);
      this.pageState.set('ready');
      this.initialError.set(null);
      this.refreshError.set(null);
      this.actionError.set(null);
      this.announceProgress(result.status);
      this.stopPolling();
      if (this.isActive) this.schedulePoll(result.retryAfterSeconds ?? 2, token);
    } catch (error) {
      if (token !== this.requestToken) return;
      if (initial || !this.job()) {
        this.pageState.set('error');
        this.initialError.set(statusErrorMessage(error));
      } else {
        this.refreshError.set(statusErrorMessage(error));
      }
      this.stopPolling();
    }
  }

  private announceProgress(status: TranslationJobStatus): void {
    const signature = `${status.job.status}:${status.terminalUnits}:${status.job.totalUnits}`;
    if (signature === this.lastAnnouncement) return;
    this.lastAnnouncement = signature;
    this.announcement.set(
      `${this.statusLabel()} · đã xử lý ${status.terminalUnits} trên ${status.job.totalUnits} phần.`,
    );
  }

  private schedulePoll(seconds: number, token: number): void {
    this.stopPolling();
    this.pollTimer = window.setTimeout(
      () => {
        this.pollTimer = null;
        void this.loadStatus(token, false);
      },
      Math.min(Math.max(seconds, 1), 60) * 1000,
    );
  }

  private stopPolling(): void {
    if (this.pollTimer === null) return;
    window.clearTimeout(this.pollTimer);
    this.pollTimer = null;
  }
}

function isAmbiguous(error: unknown): boolean {
  return error instanceof TranslationJobApiError && (error.status === 0 || error.status >= 500);
}

function statusErrorMessage(error: unknown): string {
  if (!(error instanceof TranslationJobApiError)) {
    return 'Không thể tải trạng thái bản xem thử. Kiểm tra kết nối rồi thử lại.';
  }
  if (error.status === 401) return 'Phiên đăng nhập đã hết hạn. Hãy đăng nhập lại để xem job.';
  if (error.code === 'POLICY_ACCEPTANCE_REQUIRED') {
    return 'Hãy đọc và chấp nhận Điều khoản cùng Thông báo quyền riêng tư để tiếp tục.';
  }
  if (error.code === 'POLICY_NOT_CONFIGURED') {
    return 'Chính sách hiện hành chưa được cấu hình; bản xem thử đang bị khóa.';
  }
  if (error.status === 404) return 'Không tìm thấy bản xem thử hoặc bạn không có quyền xem.';
  if (error.status === 403) return 'Tài khoản chưa thể xem trạng thái bản xem thử này.';
  if (error.status === 0 || error.status >= 500) {
    return 'Dịch vụ chưa phản hồi. Job vẫn có thể tiếp tục; hãy tải lại trạng thái.';
  }
  return 'Không thể tải trạng thái bản xem thử. Hãy thử lại.';
}

function actionErrorMessage(error: unknown, action: 'retry' | 'cancel'): string {
  if (!(error instanceof TranslationJobApiError) || error.status === 0 || error.status >= 500) {
    return 'Chưa xác nhận được thao tác. Gửi lại để dùng cùng khóa idempotency, hoặc tải trạng thái trước.';
  }
  if (error.status === 401) return 'Phiên đăng nhập đã hết hạn. Hãy đăng nhập lại.';
  if (error.code === 'POLICY_ACCEPTANCE_REQUIRED')
    return 'Hãy chấp nhận chính sách hiện hành trước khi tiếp tục.';
  if (error.status === 404)
    return 'Job không khả dụng hoặc tài khoản không có quyền thực hiện thao tác này.';
  if (error.status === 409) {
    return action === 'retry'
      ? 'Không thể thử lại: trạng thái job hoặc phiên bản chương đã đổi. Tải lại trạng thái.'
      : 'Job đã chuyển sang trạng thái không thể dừng. Tải lại trạng thái.';
  }
  return 'Thao tác không được chấp nhận. Tải lại trạng thái rồi thử lại nếu còn khả dụng.';
}
