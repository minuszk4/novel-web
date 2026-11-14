import {
  Component,
  DestroyRef,
  ElementRef,
  HostListener,
  QueryList,
  ViewChild,
  ViewChildren,
  inject,
  signal,
} from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import {
  newTranslationIdempotencyKey,
  TranslationCandidateEditReceipt,
  TranslationCandidatePreview,
  TranslationCandidatePreviewSegment,
  TranslationJobApiError,
  TranslationJobApiService,
} from '../core/translation-job-api.service';

type PreviewPageState = 'loading' | 'ready' | 'error';

const pageSize = 10;
const maxEditBytes = 1_048_576;

type SegmentEditState =
  'editing' | 'saving' | 'uncertain' | 'conflict' | 'refresh' | 'source-stale' | 'error';

interface EditAttempt {
  text: string;
  ifMatch: string;
  idempotencyKey: string;
}

interface SegmentDraft {
  text: string;
  baseText: string;
  baseEtag: string;
  dirty: boolean;
  state: SegmentEditState;
  message: string | null;
  currentEtag: string | null;
  attempt: EditAttempt | null;
}

@Component({
  selector: 'app-translation-candidate-preview-page',
  imports: [DecimalPipe, RouterLink],
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
  protected readonly requiresSignIn = signal(false);
  protected readonly requiresPolicyAcceptance = signal(false);
  protected readonly activeSegmentId = signal<string | null>(null);
  protected readonly drafts = signal<Record<string, SegmentDraft>>({});

  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly api = inject(TranslationJobApiService);
  private readonly destroyRef = inject(DestroyRef);
  private jobId: string | null = null;
  private requestToken = 0;

  @ViewChildren('segmentEditor')
  private readonly segmentEditors!: QueryList<ElementRef<HTMLTextAreaElement>>;

  @ViewChildren('editButton')
  private readonly editButtons!: QueryList<ElementRef<HTMLButtonElement>>;

  @ViewChild('pageHeading')
  private set pageHeading(heading: ElementRef<HTMLHeadingElement> | undefined) {
    heading?.nativeElement.focus({ preventScroll: true });
  }

  constructor() {
    this.route.paramMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((params) => {
      const nextJobId = params.get('jobId');
      if (this.jobId && nextJobId !== this.jobId && !this.confirmDiscardDraft()) {
        void this.router.navigateByUrl(
          `/app/translation/jobs/${encodeURIComponent(this.jobId)}/candidate`,
          { replaceUrl: true },
        );
        return;
      }
      const jobChanged = nextJobId !== this.jobId;
      this.jobId = nextJobId;
      if (jobChanged) {
        this.drafts.set({});
        this.activeSegmentId.set(null);
      }
      this.requestedJobId.set(this.jobId);
      this.preview.set(null);
      this.pageError.set(null);
      this.refreshError.set(null);
      this.pageBusy.set(false);
      this.requiresSignIn.set(false);
      this.requiresPolicyAcceptance.set(false);
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
    if (!this.confirmDiscardDraft()) return;
    await this.loadCurrentPage(Math.max(0, preview.pageOffset - preview.pageLimit));
  }

  protected async nextPage(): Promise<void> {
    const preview = this.preview();
    if (!preview || !this.canGoForward) return;
    if (!this.confirmDiscardDraft()) return;
    await this.loadCurrentPage(preview.pageOffset + preview.pageLimit);
  }

  protected draftFor(segmentId: string): SegmentDraft | null {
    return this.drafts()[segmentId] ?? null;
  }

  protected isEditing(segmentId: string): boolean {
    return this.activeSegmentId() === segmentId;
  }

  protected byteCount(value: string): number {
    return new TextEncoder().encode(value).byteLength;
  }

  protected editorDescribedBy(segmentId: string): string {
    const draft = this.draftFor(segmentId);
    const ids = [`edit-help-${segmentId}`];
    if (draft?.message) ids.push(`edit-message-${segmentId}`);
    if (draft && (draft.text.trim().length === 0 || this.byteCount(draft.text) > maxEditBytes)) {
      ids.push(`edit-validation-${segmentId}`);
    }
    return ids.join(' ');
  }

  protected canSave(segmentId: string): boolean {
    const draft = this.draftFor(segmentId);
    return (
      !!draft &&
      (draft.state === 'editing' || draft.state === 'error' || draft.state === 'uncertain') &&
      draft.dirty &&
      draft.text.trim().length > 0 &&
      this.byteCount(draft.text) <= maxEditBytes
    );
  }

  protected openEditor(segment: TranslationCandidatePreviewSegment): void {
    if (this.activeSegmentId() && this.activeSegmentId() !== segment.sourceSegmentId) return;
    const baseText = segment.editedText ?? segment.fakeResultText;
    this.drafts.update((current) => ({
      ...current,
      [segment.sourceSegmentId]: {
        text: baseText,
        baseText,
        baseEtag: segment.editEtag,
        dirty: segment.editedText === null,
        state: 'editing',
        message: null,
        currentEtag: null,
        attempt: null,
      },
    }));
    this.activeSegmentId.set(segment.sourceSegmentId);
    setTimeout(() => {
      this.segmentEditors
        ?.find((editor) => editor.nativeElement.dataset['segmentId'] === segment.sourceSegmentId)
        ?.nativeElement.focus();
    });
  }

  protected updateDraft(segmentId: string, event: Event): void {
    const target = event.target;
    if (!(target instanceof HTMLTextAreaElement)) return;
    this.drafts.update((current) => {
      const draft = current[segmentId];
      if (!draft || draft.state === 'saving') return current;
      return {
        ...current,
        [segmentId]: {
          ...draft,
          text: target.value,
          dirty: target.value !== draft.baseText || draft.baseEtag === '"edit-0"',
          state: ['source-stale', 'conflict', 'refresh'].includes(draft.state)
            ? draft.state
            : 'editing',
          message: ['source-stale', 'conflict', 'refresh'].includes(draft.state)
            ? draft.message
            : null,
        },
      };
    });
  }

  protected async cancelEdit(segmentId: string): Promise<void> {
    const draft = this.draftFor(segmentId);
    if (!draft) return;
    const message =
      draft.state === 'uncertain'
        ? 'Lần lưu có thể đã thành công. Bỏ bản nháp khỏi trang rồi tải lại phiên bản đã lưu?'
        : 'Bỏ bản nháp chỉnh sửa chưa lưu của đoạn này?';
    if (draft.dirty && !window.confirm(message)) return;
    this.removeDraft(segmentId);
    if (draft.state === 'uncertain') {
      await this.loadPage(this.preview()?.pageOffset ?? 0, ++this.requestToken, false);
    }
    this.focusEditButton(segmentId);
  }

  protected async saveEdit(segmentId: string): Promise<void> {
    const draft = this.draftFor(segmentId);
    const jobId = this.jobId;
    const segment = this.preview()?.segments.find((item) => item.sourceSegmentId === segmentId);
    if (!draft || !jobId || !segment || !this.canSave(segmentId)) return;
    const attempt = draft.attempt ?? {
      text: draft.text,
      ifMatch: draft.baseEtag,
      idempotencyKey: newTranslationIdempotencyKey(),
    };
    this.patchDraft(segmentId, {
      state: 'saving',
      message: null,
      attempt,
    });
    try {
      const receipt = await this.api.saveCandidateSegmentEdit(
        jobId,
        segmentId,
        attempt.text,
        attempt.ifMatch,
        attempt.idempotencyKey,
      );
      this.applySavedEdit(segmentId, receipt);
      this.removeDraft(segmentId);
      this.announcement.set(
        `Đã lưu chỉnh sửa riêng tư cho phần ${segment.ordinal}, bản ${receipt.editRevision}.`,
      );
      this.focusEditButton(segmentId);
      void this.loadPage(this.preview()?.pageOffset ?? 0, ++this.requestToken, false, false);
    } catch (error) {
      const apiError = error instanceof TranslationJobApiError ? error : null;
      if (apiError?.code === 'TRANSLATION_EDIT_STALE') {
        this.patchDraft(segmentId, {
          state: 'conflict',
          message: 'Đoạn này đã được sửa ở nơi khác. Tải bản mới nhất rồi chọn cách xử lý.',
          currentEtag: apiError.currentEtag,
          attempt: null,
        });
        await this.loadPage(this.preview()?.pageOffset ?? 0, ++this.requestToken, false);
        return;
      }
      if (apiError?.code === 'TRANSLATION_SOURCE_STALE') {
        this.patchDraft(segmentId, {
          state: 'source-stale',
          message:
            'Nguyên tác đã thay đổi. Bản nháp được giữ trong trang nhưng không thể lưu vào candidate cũ.',
          attempt: null,
        });
        this.announcement.set('Nguyên tác đã thay đổi; chỉnh sửa chưa được lưu.');
        return;
      }
      if ([401, 403, 404].includes(apiError?.status ?? -1)) {
        this.drafts.set({});
        this.activeSegmentId.set(null);
        this.preview.set(null);
        this.pageState.set('error');
        this.requiresSignIn.set(apiError?.status === 401);
        this.requiresPolicyAcceptance.set(apiError?.code === 'POLICY_ACCEPTANCE_REQUIRED');
        this.pageError.set(editErrorMessage(error));
        return;
      }
      if (
        apiError?.code === 'TRANSLATION_EDIT_CONFLICT' ||
        apiError?.code === 'IDEMPOTENCY_KEY_REUSED'
      ) {
        this.patchDraft(segmentId, {
          state: 'refresh',
          message:
            'Máy chủ yêu cầu tải lại phiên bản hiện tại trước khi tạo lần lưu mới. Bản nháp vẫn được giữ.',
          attempt: null,
        });
        return;
      }
      if (apiError?.status === 0 || (apiError?.status ?? 0) >= 500) {
        this.patchDraft(segmentId, {
          state: 'uncertain',
          message:
            'Chưa rõ máy chủ đã nhận chỉnh sửa chưa. Gửi lại đúng nội dung và mã lần lưu để kiểm tra an toàn.',
        });
        return;
      }
      this.patchDraft(segmentId, {
        state: 'error',
        message: editErrorMessage(error),
        attempt: null,
      });
    }
  }

  protected async refreshEditVersion(segmentId: string): Promise<void> {
    const offset = this.preview()?.pageOffset ?? 0;
    await this.loadPage(offset, ++this.requestToken, false);
    const latest = this.preview()?.segments.find((item) => item.sourceSegmentId === segmentId);
    const draft = this.draftFor(segmentId);
    if (!latest || !draft) return;
    this.patchDraft(segmentId, {
      state: 'conflict',
      currentEtag: latest.editEtag,
      message: 'Đã tải phiên bản mới nhất. Chọn dùng bản máy chủ hoặc giữ bản nháp của bạn.',
    });
  }

  protected useLatestEdit(segmentId: string): void {
    const segment = this.preview()?.segments.find((item) => item.sourceSegmentId === segmentId);
    const draft = this.draftFor(segmentId);
    if (!segment || !draft || (draft.currentEtag && segment.editEtag !== draft.currentEtag)) return;
    if (!window.confirm('Bỏ bản nháp hiện tại và dùng chỉnh sửa mới nhất đã lưu?')) return;
    this.removeDraft(segmentId);
    this.focusEditButton(segmentId);
  }

  protected keepDraftOnLatest(segmentId: string): void {
    const segment = this.preview()?.segments.find((item) => item.sourceSegmentId === segmentId);
    const draft = this.draftFor(segmentId);
    if (!segment || !draft || (draft.currentEtag && segment.editEtag !== draft.currentEtag)) return;
    const baseText = segment.editedText ?? segment.fakeResultText;
    this.patchDraft(segmentId, {
      baseText,
      baseEtag: segment.editEtag,
      dirty: draft.text !== baseText || segment.editedText === null,
      state: 'editing',
      currentEtag: null,
      message: null,
      attempt: null,
    });
  }

  protected async refresh(): Promise<void> {
    const offset = this.preview()?.pageOffset ?? 0;
    await this.loadPage(offset, ++this.requestToken, this.preview() === null);
  }

  canDeactivate(): boolean {
    return this.confirmDiscardDraft();
  }

  @HostListener('window:beforeunload', ['$event'])
  protectUnsavedDraft(event: BeforeUnloadEvent): void {
    if (!this.hasUnsavedDraft()) return;
    event.preventDefault();
    event.returnValue = '';
  }

  private async loadCurrentPage(offset: number): Promise<void> {
    await this.loadPage(offset, ++this.requestToken, false);
  }

  private async loadPage(
    offset: number,
    token: number,
    initial: boolean,
    announceRange = true,
  ): Promise<void> {
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
      this.requiresSignIn.set(false);
      this.requiresPolicyAcceptance.set(false);
      const first = result.pageOffset + 1;
      const last = result.pageOffset + result.segments.length;
      if (announceRange) {
        this.announcement.set(`Đang xem phần ${first} đến ${last} trên ${result.totalSegments}.`);
      }
    } catch (error) {
      if (token !== this.requestToken) return;
      if (error instanceof TranslationJobApiError && [401, 403, 404].includes(error.status)) {
        this.preview.set(null);
        this.drafts.set({});
        this.activeSegmentId.set(null);
        this.pageState.set('error');
        this.requiresSignIn.set(error.status === 401);
        this.requiresPolicyAcceptance.set(error.code === 'POLICY_ACCEPTANCE_REQUIRED');
        this.pageError.set(previewErrorMessage(error));
        return;
      }
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

  private patchDraft(segmentId: string, patch: Partial<SegmentDraft>): void {
    this.drafts.update((current) => {
      const draft = current[segmentId];
      return draft ? { ...current, [segmentId]: { ...draft, ...patch } } : current;
    });
  }

  private removeDraft(segmentId: string): void {
    this.drafts.update((current) => {
      const next = { ...current };
      delete next[segmentId];
      return next;
    });
    if (this.activeSegmentId() === segmentId) this.activeSegmentId.set(null);
  }

  private applySavedEdit(segmentId: string, receipt: TranslationCandidateEditReceipt): void {
    this.preview.update((current) => {
      if (!current) return current;
      return {
        ...current,
        segments: current.segments.map((segment) =>
          segment.sourceSegmentId === segmentId
            ? {
                ...segment,
                editedText: receipt.translatedText,
                editRevision: receipt.editRevision,
                editEtag: receipt.editEtag,
              }
            : segment,
        ),
      };
    });
  }

  private focusEditButton(segmentId: string): void {
    setTimeout(() => {
      this.editButtons
        ?.find((button) => button.nativeElement.dataset['segmentId'] === segmentId)
        ?.nativeElement.focus();
    });
  }

  private hasUnsavedDraft(): boolean {
    return Object.values(this.drafts()).some(
      (draft) => draft.dirty || draft.state === 'saving' || draft.state === 'uncertain',
    );
  }

  private confirmDiscardDraft(): boolean {
    if (Object.values(this.drafts()).some((draft) => draft.state === 'saving')) {
      this.announcement.set('Đang lưu chỉnh sửa. Hãy chờ máy chủ phản hồi trước khi rời trang.');
      return false;
    }
    if (!this.hasUnsavedDraft()) {
      this.drafts.set({});
      this.activeSegmentId.set(null);
      return true;
    }
    const confirmed = window.confirm(
      'Rời trang sẽ bỏ bản nháp chỉnh sửa chưa lưu. Bạn có muốn tiếp tục?',
    );
    if (confirmed) {
      this.drafts.set({});
      this.activeSegmentId.set(null);
    }
    return confirmed;
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

function editErrorMessage(error: unknown): string {
  if (!(error instanceof TranslationJobApiError)) {
    return 'Không thể lưu chỉnh sửa. Bản nháp vẫn còn trong trang; hãy kiểm tra kết nối.';
  }
  if (error.code === 'TRANSLATION_EDIT_TOO_LARGE')
    return 'Bản chỉnh sửa vượt quá giới hạn 1 MiB UTF-8.';
  if (error.code === 'INVALID_TRANSLATION_EDIT')
    return 'Nội dung không hợp lệ hoặc chỉ có khoảng trắng.';
  if (error.code === 'POLICY_ACCEPTANCE_REQUIRED')
    return 'Hãy chấp nhận chính sách hiện hành trước khi lưu.';
  if (error.code === 'POLICY_NOT_CONFIGURED')
    return 'Chính sách hiện hành chưa được cấu hình; thao tác lưu đang bị khóa.';
  if (error.status === 401) return 'Phiên đăng nhập đã hết hạn.';
  if (error.status === 403) return 'Tài khoản hiện không có quyền chỉnh sửa candidate này.';
  if (error.status === 404) return 'Candidate không còn khả dụng hoặc quyền truy cập đã thay đổi.';
  return 'Không thể lưu chỉnh sửa ở trạng thái hiện tại. Bản nháp vẫn được giữ trong trang.';
}
