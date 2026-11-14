import { Component, DestroyRef, ElementRef, ViewChild, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import {
  TranslationCandidatePreview,
  TranslationJobApiError,
  TranslationJobApiService,
} from '../core/translation-job-api.service';

type PreviewPageState = 'loading' | 'ready' | 'error';

const pageSize = 10;

@Component({
  selector: 'app-translation-candidate-preview-page',
  imports: [RouterLink],
  templateUrl: './translation-candidate-preview-page.component.html',
  styleUrl: './translation-candidate-preview-page.component.css',
})
export class TranslationCandidatePreviewPageComponent {
  protected readonly pageState = signal<PreviewPageState>('loading');
  protected readonly requestedJobId = signal<string | null>(null);
  protected readonly preview = signal<TranslationCandidatePreview | null>(null);
  protected readonly pageError = signal<string | null>(null);
  protected readonly refreshError = signal<string | null>(null);
  protected readonly pageBusy = signal(false);
  protected readonly announcement = signal('');

  private readonly route = inject(ActivatedRoute);
  private readonly api = inject(TranslationJobApiService);
  private readonly destroyRef = inject(DestroyRef);
  private jobId: string | null = null;
  private requestToken = 0;

  @ViewChild('pageHeading')
  private set pageHeading(heading: ElementRef<HTMLHeadingElement> | undefined) {
    heading?.nativeElement.focus({ preventScroll: true });
  }

  constructor() {
    this.route.paramMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((params) => {
      this.jobId = params.get('jobId');
      this.requestedJobId.set(this.jobId);
      this.preview.set(null);
      this.pageError.set(null);
      this.refreshError.set(null);
      this.pageBusy.set(false);
      const token = ++this.requestToken;
      if (!this.jobId) {
        this.pageState.set('error');
        this.pageError.set('Liên kết candidate không hợp lệ.');
        return;
      }
      void this.loadPage(0, token, true);
    });
  }

  protected get canGoBack(): boolean {
    return !!this.preview() && this.preview()!.pageOffset > 0 && !this.pageBusy();
  }

  protected get canGoForward(): boolean {
    const preview = this.preview();
    return (
      !!preview &&
      preview.pageOffset + preview.segments.length < preview.totalSegments &&
      !this.pageBusy()
    );
  }

  protected async previousPage(): Promise<void> {
    const preview = this.preview();
    if (!preview || !this.canGoBack) return;
    await this.loadCurrentPage(Math.max(0, preview.pageOffset - preview.pageLimit));
  }

  protected async nextPage(): Promise<void> {
    const preview = this.preview();
    if (!preview || !this.canGoForward) return;
    await this.loadCurrentPage(preview.pageOffset + preview.pageLimit);
  }

  protected async refresh(): Promise<void> {
    const offset = this.preview()?.pageOffset ?? 0;
    await this.loadPage(offset, ++this.requestToken, this.preview() === null);
  }

  private async loadCurrentPage(offset: number): Promise<void> {
    await this.loadPage(offset, ++this.requestToken, false);
  }

  private async loadPage(offset: number, token: number, initial: boolean): Promise<void> {
    const jobId = this.jobId;
    if (!jobId) return;
    this.pageError.set(null);
    this.refreshError.set(null);
    if (initial) this.pageState.set('loading');
    else this.pageBusy.set(true);

    try {
      const result = await this.api.getCandidatePreview(jobId, offset, pageSize);
      if (token !== this.requestToken) return;
      this.preview.set(result);
      this.pageState.set('ready');
      const first = result.pageOffset + 1;
      const last = result.pageOffset + result.segments.length;
      this.announcement.set(`Đang xem phần ${first} đến ${last} trên ${result.totalSegments}.`);
    } catch (error) {
      if (token !== this.requestToken) return;
      if (initial || !this.preview()) {
        this.pageState.set('error');
        this.pageError.set(previewErrorMessage(error));
      } else {
        this.refreshError.set(previewErrorMessage(error));
      }
    } finally {
      if (token === this.requestToken) this.pageBusy.set(false);
    }
  }
}

function previewErrorMessage(error: unknown): string {
  if (!(error instanceof TranslationJobApiError)) {
    return 'Không thể tải phần đối chiếu. Kiểm tra kết nối rồi thử lại.';
  }
  if (error.status === 401) return 'Phiên đăng nhập đã hết hạn. Hãy đăng nhập lại.';
  if (error.code === 'POLICY_ACCEPTANCE_REQUIRED') {
    return 'Hãy đọc và chấp nhận Điều khoản cùng Thông báo quyền riêng tư để tiếp tục.';
  }
  if (error.code === 'POLICY_NOT_CONFIGURED') {
    return 'Chính sách hiện hành chưa được cấu hình; nội dung candidate đang bị khóa.';
  }
  if (error.code === 'TRANSLATION_SOURCE_STALE') {
    return 'Nguyên tác đã thay đổi. Hãy quay lại chương để đọc phiên bản hiện tại.';
  }
  if (error.code === 'TRANSLATION_CANDIDATE_NOT_READY') {
    return 'Candidate chưa hoàn tất. Hãy tải lại trạng thái job sau.';
  }
  if (error.status === 404) {
    return 'Không tìm thấy candidate hoặc bạn không có quyền xem nội dung này.';
  }
  if (error.status === 403) return 'Tài khoản chưa có quyền xem candidate này.';
  if (error.status === 0 || error.status >= 500) {
    return 'Dịch vụ chưa phản hồi. Candidate không bị thay đổi; hãy thử tải lại.';
  }
  return 'Không thể mở candidate ở trạng thái hiện tại.';
}
