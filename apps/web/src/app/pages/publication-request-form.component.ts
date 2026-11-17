import {
  afterNextRender,
  Component,
  ElementRef,
  EventEmitter,
  Injector,
  Input,
  OnChanges,
  OnDestroy,
  Output,
  SimpleChanges,
  ViewChild,
  inject,
  signal,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import {
  AuthoringApiError,
  AuthoringApiService,
  PrivateAuthoredChapterDraft,
  PrivateChapterOutlineItem,
  PublicationReadiness,
  PublicationRequestReceipt,
  PublicationRequestScopeClaim,
  PublicationRequestSubmission,
  PublicationRightsBasisCode,
  newAuthoringIdempotencyKey,
} from '../core/authoring-api.service';

const pageSize = 100;

interface RequestAttempt {
  workId: string;
  targetChapterId: string | null;
  readinessEtag: string;
  submission: PublicationRequestSubmission;
  idempotencyKey: string;
}

interface RightsBasisOption {
  code: PublicationRightsBasisCode;
  label: string;
}

@Component({
  selector: 'app-publication-request-form',
  imports: [RouterLink],
  templateUrl: './publication-request-form.component.html',
  styleUrl: './publication-request-form.component.css',
})
export class PublicationRequestFormComponent implements OnChanges, OnDestroy {
  @Input({ required: true })
  workId = '';

  @Input({ required: true })
  readiness!: PublicationReadiness;

  @Input()
  targetChapterId: string | null = null;

  @Output()
  requestSubmitted = new EventEmitter<PublicationRequestReceipt>();

  @Output()
  readinessUpdated = new EventEmitter<PublicationReadiness>();

  @Output()
  requestPolicyReview = new EventEmitter<void>();

  @Output()
  requestActivityChange = new EventEmitter<boolean>();

  protected readonly formOpen = signal(false);
  protected readonly chapters = signal<PrivateChapterOutlineItem[]>([]);
  protected readonly nextChapterCursor = signal<string | null>(null);
  protected readonly chapterListComplete = signal(false);
  protected readonly chapterListLoading = signal(false);
  protected readonly chapterListError = signal<AuthoringApiError | null>(null);
  protected readonly previewDraft = signal<PrivateAuthoredChapterDraft | null>(null);
  protected readonly previewLoading = signal(false);
  protected readonly previewError = signal<AuthoringApiError | null>(null);
  protected readonly metadataBasis = signal<PublicationRightsBasisCode | ''>('');
  protected readonly chapterTextBasis = signal<PublicationRightsBasisCode | ''>('');
  protected readonly includeCommercialSale = signal(false);
  protected readonly commercialSaleBasis = signal<PublicationRightsBasisCode | ''>('');
  protected readonly includeTranslationAdaptation = signal(false);
  protected readonly translationAdaptationBasis = signal<PublicationRightsBasisCode | ''>('');
  protected readonly declarationConfirmed = signal(false);
  protected readonly submitting = signal(false);
  protected readonly uncertain = signal(false);
  protected readonly requestError = signal<AuthoringApiError | null>(null);
  protected readonly retryCountdown = signal(0);
  protected readonly announcement = signal('');
  protected readonly submittedReceipt = signal<PublicationRequestReceipt | null>(null);

  protected readonly basisOptions: RightsBasisOption[] = [
    { code: 'ORIGINAL_WORK', label: 'Tôi tự sáng tác nội dung này' },
    { code: 'LICENSE_OR_PERMISSION', label: 'Tôi có giấy phép hoặc sự cho phép phù hợp' },
    { code: 'PUBLIC_DOMAIN', label: 'Tôi cho rằng tác phẩm thuộc phạm vi công cộng' },
    { code: 'NEEDS_REVIEW', label: 'Tôi chưa chắc; cần người duyệt xem xét' },
  ];

  private readonly api = inject(AuthoringApiService);
  private readonly renderInjector = inject(Injector);
  private requestAttempt: RequestAttempt | null = null;
  private retryTimer: ReturnType<typeof globalThis.setInterval> | null = null;
  private loadGeneration = 0;

  @ViewChild('requestHeading')
  private requestHeading?: ElementRef<HTMLHeadingElement>;

  @ViewChild('requestTrigger')
  private requestTrigger?: ElementRef<HTMLButtonElement>;

  @ViewChild('previewHeading')
  private previewHeading?: ElementRef<HTMLHeadingElement>;

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['workId']) this.reset();
    if (changes['targetChapterId']) this.reset();
    if (changes['readiness'] && !changes['readiness'].firstChange) {
      const prior = changes['readiness'].previousValue as PublicationReadiness | undefined;
      const current = changes['readiness'].currentValue as PublicationReadiness;
      if (
        (prior?.snapshotEtag !== current.snapshotEtag ||
          prior?.publicationRightsPolicyVersion !== current.publicationRightsPolicyVersion) &&
        !this.uncertain()
      ) {
        this.requestAttempt = null;
        this.declarationConfirmed.set(false);
        this.requestError.set(null);
        this.clearRetryCountdown();
      }
    }
  }

  ngOnDestroy(): void {
    this.clearRetryCountdown();
  }

  protected get canPrepareRequest(): boolean {
    return (
      this.readiness?.canSubmit === true &&
      this.readiness.publicationRightsPolicyAvailable &&
      this.readiness.publicationRightsPolicyAcceptedAt !== null &&
      this.readiness.publicationRightsPolicyVersion !== null &&
      this.readiness.currentRequestState !== 'PENDING_REVIEW'
    );
  }

  protected get isBusy(): boolean {
    return this.chapterListLoading() || this.previewLoading() || this.submitting();
  }

  protected get formLocked(): boolean {
    return this.isBusy || this.uncertain() || this.requestError()?.status === 429;
  }

  protected get hasCompleteReadyChapterList(): boolean {
    if (!this.chapterListComplete()) return false;
    const readyCount = this.chapters().filter(
      (chapter) => chapter.status === 'ready' && chapter.contentAvailable,
    ).length;
    const targetDraftCount = this.targetChapterId
      ? this.chapters().filter(
          (chapter) =>
            chapter.id === this.targetChapterId &&
            chapter.status === 'draft' &&
            chapter.hasSavedDraftContent,
        ).length
      : 0;
    return readyCount + targetDraftCount === this.readiness.readyChapterCount;
  }

  protected get canSubmitRequest(): boolean {
    return (
      this.canPrepareRequest &&
      this.formOpen() &&
      this.hasCompleteReadyChapterList &&
      this.metadataBasis() !== '' &&
      this.chapterTextBasis() !== '' &&
      (!this.includeCommercialSale() || this.commercialSaleBasis() !== '') &&
      (!this.includeTranslationAdaptation() || this.translationAdaptationBasis() !== '') &&
      this.declarationConfirmed() &&
      !this.isBusy &&
      !this.uncertain() &&
      this.retryCountdown() === 0 &&
      !this.requestError()
    );
  }

  protected get retryLabel(): string {
    return this.retryCountdown() > 0
      ? `Bạn có thể thử lại sau ${this.retryCountdown()} giây.`
      : 'Bạn có thể gửi lại đúng yêu cầu đã lưu trong phiên này.';
  }

  protected get requestButtonLabel(): string {
    return this.readiness.currentRequestState === null
      ? 'Chuẩn bị yêu cầu duyệt'
      : 'Gửi phiên bản này để xem xét lại';
  }

  protected async openRequestForm(): Promise<void> {
    if (!this.canPrepareRequest || this.formOpen()) return;
    this.formOpen.set(true);
    this.requestError.set(null);
    this.submittedReceipt.set(null);
    this.requestActivityChange.emit(true);
    afterNextRender(() => this.requestHeading?.nativeElement.focus({ preventScroll: true }), {
      injector: this.renderInjector,
    });
    await this.loadChapterPage(undefined, true);
  }

  protected async loadMoreChapters(): Promise<void> {
    const cursor = this.nextChapterCursor();
    if (!cursor || this.chapterListLoading() || this.uncertain()) return;
    await this.loadChapterPage(cursor);
  }

  protected async previewChapter(chapter: PrivateChapterOutlineItem): Promise<void> {
    if (!this.canPreviewChapter(chapter) || this.isBusy) return;
    const generation = ++this.loadGeneration;
    this.previewLoading.set(true);
    this.previewError.set(null);
    this.previewDraft.set(null);
    this.announcement.set(`Đang tải bản đã lưu của Chương ${chapter.ordinal}.`);
    try {
      const draft = await this.api.getPrivateChapterDraft(this.workId, chapter.id);
      if (generation !== this.loadGeneration) return;
      this.previewDraft.set(draft);
      this.announcement.set(
        `Đã tải bản đã lưu của Chương ${chapter.ordinal}, phiên bản ${draft.revisionNo}.`,
      );
      afterNextRender(() => this.previewHeading?.nativeElement.focus({ preventScroll: true }), {
        injector: this.renderInjector,
      });
    } catch (error) {
      if (generation !== this.loadGeneration) return;
      this.previewError.set(toAuthoringError(error));
    } finally {
      if (generation === this.loadGeneration) this.previewLoading.set(false);
    }
  }

  protected closePreview(): void {
    this.loadGeneration += 1;
    this.previewDraft.set(null);
    this.previewError.set(null);
    this.previewLoading.set(false);
  }

  protected changeMetadataBasis(event: Event): void {
    this.metadataBasis.set(readBasisValue(event));
    this.resetDeclarationConfirmation();
  }

  protected changeChapterTextBasis(event: Event): void {
    this.chapterTextBasis.set(readBasisValue(event));
    this.resetDeclarationConfirmation();
  }

  protected changeCommercialBasis(event: Event): void {
    this.commercialSaleBasis.set(readBasisValue(event));
    this.resetDeclarationConfirmation();
  }

  protected toggleCommercialSale(event: Event): void {
    const checked = (event.currentTarget as HTMLInputElement).checked;
    this.includeCommercialSale.set(checked);
    if (!checked) this.commercialSaleBasis.set('');
    this.resetDeclarationConfirmation();
  }

  protected changeTranslationAdaptationBasis(event: Event): void {
    this.translationAdaptationBasis.set(readBasisValue(event));
    this.resetDeclarationConfirmation();
  }

  protected toggleTranslationAdaptation(event: Event): void {
    const checked = (event.currentTarget as HTMLInputElement).checked;
    this.includeTranslationAdaptation.set(checked);
    if (!checked) this.translationAdaptationBasis.set('');
    this.resetDeclarationConfirmation();
  }

  protected confirmDeclarations(event: Event): void {
    this.declarationConfirmed.set((event.currentTarget as HTMLInputElement).checked);
    this.clearRecoverableRequestError();
  }

  protected requestPolicy(): void {
    this.requestPolicyReview.emit();
  }

  protected async submitRequest(): Promise<void> {
    if (!this.canSubmitRequest) return;
    const attempt = this.requestAttempt ?? this.createAttempt();
    if (!attempt) return;
    this.requestAttempt = attempt;
    await this.performSubmit(attempt);
  }

  protected async retryUncertainRequest(): Promise<void> {
    if (!this.uncertain() || !this.requestAttempt || this.retryCountdown() > 0) return;
    this.uncertain.set(false);
    await this.performSubmit(this.requestAttempt);
  }

  protected async retryRateLimitedRequest(): Promise<void> {
    if (this.retryCountdown() > 0 || !this.requestAttempt || this.submitting()) return;
    this.requestError.set(null);
    await this.performSubmit(this.requestAttempt);
  }

  protected cancelForm(): void {
    if (this.isBusy || this.uncertain()) return;
    this.resetForm();
    afterNextRender(() => this.requestTrigger?.nativeElement.focus({ preventScroll: true }), {
      injector: this.renderInjector,
    });
  }

  protected chapterStateLabel(chapter: PrivateChapterOutlineItem): string {
    if (
      chapter.id === this.targetChapterId &&
      chapter.status === 'draft' &&
      chapter.hasSavedDraftContent
    ) {
      return 'Bản lưu mục tiêu · chưa công khai';
    }
    if (chapter.status === 'ready' && chapter.contentAvailable) return 'Bản đã lưu sẵn sàng';
    if (chapter.hasSavedDraftContent) return 'Có bản nháp đã lưu, chưa sẵn sàng';
    return 'Chưa có nội dung đã lưu';
  }

  protected canPreviewChapter(chapter: PrivateChapterOutlineItem): boolean {
    return (
      (chapter.status === 'ready' && chapter.contentAvailable) ||
      (chapter.id === this.targetChapterId &&
        chapter.status === 'draft' &&
        chapter.hasSavedDraftContent)
    );
  }

  protected basisLabel(code: PublicationRightsBasisCode): string {
    return this.basisOptions.find((option) => option.code === code)?.label ?? 'Cần xem xét';
  }

  protected scopeLabel(scopeCode: PublicationRequestScopeClaim['scopeCode']): string {
    switch (scopeCode) {
      case 'PUBLIC_METADATA':
        return 'Thông tin tác phẩm';
      case 'CHAPTER_TEXT':
        return 'Nội dung chương';
      case 'COMMERCIAL_SALE':
        return 'Quyền thương mại';
      case 'TRANSLATION_ADAPTATION':
        return 'Quyền chuyển ngữ/thích nghi';
    }
  }

  protected requestErrorMessage(): string {
    const error = this.requestError();
    if (!error) return '';
    if (error.status === 401) return 'Phiên đăng nhập đã hết hạn. Hãy đăng nhập lại.';
    if (error.status === 403 && error.code === 'PUBLICATION_RIGHTS_POLICY_ACCEPTANCE_REQUIRED') {
      return 'Cần xem và xác nhận lại chính sách quyền xuất bản hiện hành.';
    }
    if (error.status === 403) return 'Cần xác nhận chính sách tài khoản trước khi tiếp tục.';
    if (error.status === 404) return 'Bản thảo riêng tư này hiện không khả dụng.';
    if (error.status === 412)
      return 'Bản thảo đã đổi sau khi bạn rà soát. Hãy kiểm tra snapshot mới.';
    if (error.status === 409 && error.code === 'PUBLICATION_REQUEST_ALREADY_PENDING') {
      return 'Bản thảo đã có yêu cầu đang chờ duyệt. Đang tải lại trạng thái.';
    }
    if (error.status === 409 && error.code === 'PUBLICATION_RIGHTS_POLICY_STALE') {
      return 'Chính sách quyền xuất bản đã đổi. Hãy xem và xác nhận phiên bản hiện hành.';
    }
    if (error.status === 429) {
      return 'Máy chủ đang giới hạn yêu cầu mới. Chờ hết thời gian bên dưới rồi gửi lại.';
    }
    if (this.uncertain()) {
      return 'Chưa xác định được máy chủ đã ghi nhận yêu cầu hay chưa. Thử lại sẽ gửi đúng yêu cầu với cùng mã.';
    }
    if (error.status >= 500 || error.status === 0) {
      return 'Dịch vụ chưa xác nhận yêu cầu. Bản thảo vẫn riêng tư.';
    }
    return 'Chưa gửi được yêu cầu. Kiểm tra thông tin và thử lại.';
  }

  protected retryReadiness(): void {
    this.readinessRefreshRequested.emit();
  }

  @Output()
  readinessRefreshRequested = new EventEmitter<void>();

  private async loadChapterPage(cursor?: string, replace = false): Promise<void> {
    if (this.chapterListLoading()) return;
    this.chapterListLoading.set(true);
    this.chapterListError.set(null);
    try {
      const page = await this.api.listPrivateChapters(this.workId, cursor, pageSize);
      const next = replace ? page.items : [...this.chapters(), ...page.items];
      if (new Set(next.map((chapter) => chapter.id)).size !== next.length) {
        throw new AuthoringApiError(502, 'INVALID_AUTHORING_OUTLINE_RESPONSE');
      }
      this.chapters.set(next);
      this.nextChapterCursor.set(page.nextCursor);
      this.chapterListComplete.set(page.nextCursor === null);
      if (page.nextCursor === null && !this.hasCompleteReadyChapterList) {
        this.chapterListError.set(new AuthoringApiError(409, 'PUBLICATION_SNAPSHOT_STALE'));
      }
    } catch (error) {
      this.chapterListError.set(toAuthoringError(error));
    } finally {
      this.chapterListLoading.set(false);
    }
  }

  private createAttempt(): RequestAttempt | null {
    const policyVersion = this.readiness.publicationRightsPolicyVersion;
    const metadataBasis = this.metadataBasis();
    const chapterTextBasis = this.chapterTextBasis();
    const commercialSaleBasis = this.commercialSaleBasis();
    if (!policyVersion || !metadataBasis || !chapterTextBasis) return null;
    const scopeClaims: PublicationRequestScopeClaim[] = [
      { scopeCode: 'PUBLIC_METADATA', rightsBasisCode: metadataBasis },
      { scopeCode: 'CHAPTER_TEXT', rightsBasisCode: chapterTextBasis },
    ];
    if (this.includeCommercialSale() && commercialSaleBasis) {
      scopeClaims.push({ scopeCode: 'COMMERCIAL_SALE', rightsBasisCode: commercialSaleBasis });
    }
    const translationAdaptationBasis = this.translationAdaptationBasis();
    if (this.includeTranslationAdaptation() && translationAdaptationBasis) {
      scopeClaims.push({
        scopeCode: 'TRANSLATION_ADAPTATION',
        rightsBasisCode: translationAdaptationBasis,
      });
    }
    const submission: PublicationRequestSubmission = { policyVersion, scopeClaims };
    return {
      workId: this.workId,
      targetChapterId: this.targetChapterId,
      readinessEtag: this.readiness.snapshotEtag,
      submission,
      idempotencyKey: newAuthoringIdempotencyKey(),
    };
  }

  private async performSubmit(attempt: RequestAttempt): Promise<void> {
    this.submitting.set(true);
    this.requestError.set(null);
    this.announcement.set('Đang gửi yêu cầu duyệt quyền xuất bản…');
    try {
      const receipt =
        attempt.targetChapterId === null
          ? await this.api.submitPublicationRequest(
              attempt.workId,
              attempt.readinessEtag,
              attempt.submission,
              attempt.idempotencyKey,
            )
          : await this.api.submitChapterPublicationRequest(
              attempt.workId,
              attempt.targetChapterId,
              attempt.readinessEtag,
              attempt.submission,
              attempt.idempotencyKey,
            );
      if (attempt.workId !== this.workId || attempt.targetChapterId !== this.targetChapterId)
        return;
      this.requestAttempt = null;
      this.uncertain.set(false);
      this.submittedReceipt.set(receipt);
      this.announcement.set(
        `Đã gửi yêu cầu duyệt phiên bản ${receipt.requestVersion}. Trạng thái: đang chờ người duyệt. Bản thảo vẫn riêng tư.`,
      );
      this.requestActivityChange.emit(false);
      this.requestSubmitted.emit(receipt);
    } catch (error) {
      const mapped = toAuthoringError(error);
      this.requestError.set(mapped);
      if (mapped.status === 412) {
        this.requestAttempt = null;
        this.declarationConfirmed.set(false);
        this.uncertain.set(false);
        this.chapters.set([]);
        this.chapterListComplete.set(false);
        this.nextChapterCursor.set(null);
        await this.refreshSnapshotAndChapterList();
      } else if (mapped.status === 409 && mapped.code === 'PUBLICATION_RIGHTS_POLICY_STALE') {
        this.requestAttempt = null;
        this.declarationConfirmed.set(false);
        this.uncertain.set(false);
        this.requestPolicyReview.emit();
      } else if (mapped.status === 409 && mapped.code === 'PUBLICATION_REQUEST_ALREADY_PENDING') {
        this.requestAttempt = null;
        this.uncertain.set(false);
        this.readinessRefreshRequested.emit();
      } else if (mapped.status === 429) {
        this.uncertain.set(false);
        this.startRetryCountdown(mapped.retryAfterSeconds ?? 60);
      } else if (mapped.status === 0 || mapped.status >= 500) {
        this.uncertain.set(true);
        this.announcement.set('Chưa xác định được kết quả gửi yêu cầu.');
      } else {
        this.requestAttempt = null;
        this.uncertain.set(false);
      }
    } finally {
      this.submitting.set(false);
    }
  }

  private async refreshSnapshotAndChapterList(): Promise<void> {
    try {
      const latest =
        this.targetChapterId === null
          ? await this.api.getPublicationReadiness(this.workId)
          : await this.api.getChapterPublicationReadiness(this.workId, this.targetChapterId);
      if (this.workId !== latest.workId) return;
      this.readiness = latest;
      this.readinessUpdated.emit(latest);
      this.requestError.set(null);
      await this.loadChapterPage(undefined, true);
      this.announcement.set('Snapshot đã được tải lại. Hãy rà soát bản đã lưu trước khi gửi lại.');
    } catch (error) {
      this.requestError.set(toAuthoringError(error));
    }
  }

  private startRetryCountdown(seconds: number): void {
    this.clearRetryCountdown();
    const boundedSeconds = Math.max(1, Math.min(seconds, 3_600));
    this.retryCountdown.set(boundedSeconds);
    this.retryTimer = globalThis.setInterval(() => {
      const remaining = this.retryCountdown() - 1;
      this.retryCountdown.set(Math.max(remaining, 0));
      if (remaining <= 0) this.clearRetryCountdown();
    }, 1_000);
  }

  private clearRetryCountdown(): void {
    if (this.retryTimer !== null) globalThis.clearInterval(this.retryTimer);
    this.retryTimer = null;
    this.retryCountdown.set(0);
  }

  private resetDeclarationConfirmation(): void {
    this.declarationConfirmed.set(false);
    if (!this.uncertain()) this.requestAttempt = null;
    this.clearRecoverableRequestError();
  }

  private clearRecoverableRequestError(): void {
    if (this.requestError()?.status !== 429 && !this.uncertain()) {
      this.requestError.set(null);
    }
  }

  private reset(): void {
    this.loadGeneration += 1;
    this.clearRetryCountdown();
    this.formOpen.set(false);
    this.chapters.set([]);
    this.nextChapterCursor.set(null);
    this.chapterListComplete.set(false);
    this.chapterListLoading.set(false);
    this.chapterListError.set(null);
    this.previewDraft.set(null);
    this.previewLoading.set(false);
    this.previewError.set(null);
    this.metadataBasis.set('');
    this.chapterTextBasis.set('');
    this.includeCommercialSale.set(false);
    this.commercialSaleBasis.set('');
    this.includeTranslationAdaptation.set(false);
    this.translationAdaptationBasis.set('');
    this.declarationConfirmed.set(false);
    this.submitting.set(false);
    this.uncertain.set(false);
    this.requestError.set(null);
    this.announcement.set('');
    this.submittedReceipt.set(null);
    this.requestAttempt = null;
    this.requestActivityChange.emit(false);
  }

  private resetForm(): void {
    this.formOpen.set(false);
    this.previewDraft.set(null);
    this.requestError.set(null);
    this.requestAttempt = null;
    this.declarationConfirmed.set(false);
    this.requestActivityChange.emit(false);
  }
}

function readBasisValue(event: Event): PublicationRightsBasisCode | '' {
  const value = (event.currentTarget as HTMLSelectElement).value;
  return value === 'ORIGINAL_WORK' ||
    value === 'LICENSE_OR_PERMISSION' ||
    value === 'PUBLIC_DOMAIN' ||
    value === 'NEEDS_REVIEW'
    ? value
    : '';
}

function toAuthoringError(error: unknown): AuthoringApiError {
  return error instanceof AuthoringApiError
    ? error
    : new AuthoringApiError(0, 'AUTHORING_UNAVAILABLE');
}
