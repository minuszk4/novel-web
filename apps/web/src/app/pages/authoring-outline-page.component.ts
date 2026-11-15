import {
  afterNextRender,
  Component,
  DestroyRef,
  ElementRef,
  Injector,
  ViewChild,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import {
  AuthoringApiError,
  AuthoringApiService,
  PrivateChapterOutlineItem,
  PrivateManuscriptDetails,
  UpdatePrivateManuscriptInput,
  newAuthoringIdempotencyKey,
} from '../core/authoring-api.service';
import { PolicyOnboardingService } from '../core/policy-onboarding.service';

type OutlinePageState = 'loading' | 'ready' | 'error';

interface ChapterCreateAttempt {
  title: string;
  idempotencyKey: string;
}

interface ManuscriptMetadataAttempt {
  values: UpdatePrivateManuscriptInput;
  etag: string;
}

type ManuscriptMetadataFieldErrors = Partial<Record<keyof UpdatePrivateManuscriptInput, string>>;

@Component({
  selector: 'app-authoring-outline-page',
  imports: [RouterLink],
  templateUrl: './authoring-outline-page.component.html',
  styleUrl: './authoring-outline-page.component.css',
})
export class AuthoringOutlinePageComponent {
  protected readonly policy = inject(PolicyOnboardingService);
  protected readonly pageState = signal<OutlinePageState>('loading');
  protected readonly manuscript = signal<PrivateManuscriptDetails | null>(null);
  protected readonly isMetadataFormOpen = signal(false);
  protected readonly metadataDraft = signal<UpdatePrivateManuscriptInput | null>(null);
  protected readonly metadataFieldErrors = signal<ManuscriptMetadataFieldErrors>({});
  protected readonly metadataError = signal<string | null>(null);
  protected readonly metadataApiFailure = signal<AuthoringApiError | null>(null);
  protected readonly metadataAnnouncement = signal('');
  protected readonly metadataConflict = signal<PrivateManuscriptDetails | null>(null);
  protected readonly isSavingMetadata = signal(false);
  protected readonly isMetadataUncertain = signal(false);
  protected readonly chapters = signal<PrivateChapterOutlineItem[]>([]);
  protected readonly nextCursor = signal<string | null>(null);
  protected readonly pageError = signal<AuthoringApiError | null>(null);
  protected readonly moreError = signal<AuthoringApiError | null>(null);
  protected readonly isLoadingMore = signal(false);
  protected readonly isAddFormOpen = signal(false);
  protected readonly chapterTitle = signal('');
  protected readonly titleError = signal<string | null>(null);
  protected readonly createError = signal<string | null>(null);
  protected readonly createApiFailure = signal<AuthoringApiError | null>(null);
  protected readonly createAnnouncement = signal('');
  protected readonly isCreating = signal(false);
  protected readonly isCreateUncertain = signal(false);
  protected readonly requestedWorkIdView = signal<string | null>(null);

  private readonly route = inject(ActivatedRoute);
  private readonly api = inject(AuthoringApiService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly injector = inject(Injector);
  private requestedWorkId: string | null = null;
  private loadToken = 0;
  private pendingCreateAttempt: ChapterCreateAttempt | null = null;
  private pendingMetadataAttempt: ManuscriptMetadataAttempt | null = null;
  private readonly consumedCursors = new Set<string>();

  @ViewChild('pageHeading')
  private set pageHeading(heading: ElementRef<HTMLHeadingElement> | undefined) {
    heading?.nativeElement.focus({ preventScroll: true });
  }

  @ViewChild('chapterTitleInput')
  private set chapterTitleInput(input: ElementRef<HTMLInputElement> | undefined) {
    input?.nativeElement.focus({ preventScroll: true });
  }

  @ViewChild('metadataTitleInput')
  private set metadataTitleInput(input: ElementRef<HTMLInputElement> | undefined) {
    input?.nativeElement.focus({ preventScroll: true });
  }

  @ViewChild('addChapterButton')
  private addChapterButton?: ElementRef<HTMLButtonElement>;

  @ViewChild('editMetadataButton')
  private editMetadataButton?: ElementRef<HTMLButtonElement>;

  constructor() {
    this.route.paramMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((params) => {
      this.requestedWorkId = params.get('workId');
      this.requestedWorkIdView.set(this.requestedWorkId);
      this.manuscript.set(null);
      this.isMetadataFormOpen.set(false);
      this.metadataDraft.set(null);
      this.metadataFieldErrors.set({});
      this.metadataError.set(null);
      this.metadataApiFailure.set(null);
      this.metadataAnnouncement.set('');
      this.metadataConflict.set(null);
      this.isSavingMetadata.set(false);
      this.isMetadataUncertain.set(false);
      this.pendingMetadataAttempt = null;
      this.isAddFormOpen.set(false);
      this.chapterTitle.set('');
      this.isCreating.set(false);
      this.isLoadingMore.set(false);
      this.isCreateUncertain.set(false);
      this.titleError.set(null);
      this.createError.set(null);
      this.createApiFailure.set(null);
      this.createAnnouncement.set('');
      this.pendingCreateAttempt = null;
      void this.loadFirstPage(++this.loadToken);
    });
  }

  protected get returnTo(): string {
    return this.outlinePath(this.requestedWorkId);
  }

  protected get chapterTitleLength(): number {
    return Array.from(this.chapterTitle()).length;
  }

  protected get metadataTitleLength(): number {
    return Array.from(this.metadataDraft()?.title ?? '').length;
  }

  protected get metadataAuthorLength(): number {
    return Array.from(this.metadataDraft()?.author ?? '').length;
  }

  protected get recoveryUrl(): string | null {
    switch (
      this.pageError()?.status ??
      this.moreError()?.status ??
      this.createApiError()?.status ??
      this.metadataApiFailure()?.status
    ) {
      case 401:
        return '/sign-in';
      case 403:
        return '/app/setup';
      case 404:
        return '/app/library';
      default:
        return null;
    }
  }

  protected get recoveryQueryParams(): Record<string, string> {
    if (this.recoveryUrl === '/sign-in') return { returnTo: this.returnTo };
    if (this.recoveryUrl === '/app/setup') return { returnUrl: this.returnTo };
    return {};
  }

  protected get recoveryLabel(): string {
    if (this.recoveryUrl === '/sign-in') return 'Đăng nhập lại';
    if (this.recoveryUrl === '/app/setup') return 'Xem chính sách tài khoản';
    return 'Về thư viện';
  }

  async retryFirstPage(): Promise<void> {
    await this.loadFirstPage(++this.loadToken);
  }

  openAddForm(): void {
    if (
      this.policy.status() !== 'accepted' ||
      this.pageState() !== 'ready' ||
      this.isMetadataFormOpen()
    )
      return;
    this.isAddFormOpen.set(true);
    this.titleError.set(null);
    this.createError.set(null);
    this.createApiFailure.set(null);
    this.createAnnouncement.set('');
  }

  closeAddForm(): void {
    if (this.isCreating() || this.isCreateUncertain()) return;
    this.isAddFormOpen.set(false);
    this.chapterTitle.set('');
    this.titleError.set(null);
    this.createError.set(null);
    this.createApiFailure.set(null);
    this.pendingCreateAttempt = null;
    this.focusAddChapterButtonAfterRender();
  }

  openMetadataForm(): void {
    const current = this.manuscript();
    if (
      !current ||
      this.policy.status() !== 'accepted' ||
      this.pageState() !== 'ready' ||
      this.isAddFormOpen()
    ) {
      return;
    }
    this.metadataDraft.set({
      title: current.title,
      author: current.author,
      language: current.language,
    });
    this.metadataFieldErrors.set({});
    this.metadataError.set(null);
    this.metadataApiFailure.set(null);
    this.metadataConflict.set(null);
    this.metadataAnnouncement.set('');
    this.isMetadataUncertain.set(false);
    this.pendingMetadataAttempt = null;
    this.isMetadataFormOpen.set(true);
  }

  cancelMetadataEdit(): void {
    if (this.isSavingMetadata() || this.isMetadataUncertain()) return;
    this.isMetadataFormOpen.set(false);
    this.metadataDraft.set(null);
    this.metadataFieldErrors.set({});
    this.metadataError.set(null);
    this.metadataApiFailure.set(null);
    this.metadataConflict.set(null);
    this.pendingMetadataAttempt = null;
    this.focusMetadataButtonAfterRender();
  }

  updateMetadataField(field: keyof UpdatePrivateManuscriptInput, event: Event): void {
    if (this.isSavingMetadata() || this.isMetadataUncertain() || this.metadataConflict()) return;
    const input = event.target as HTMLInputElement;
    const current = this.metadataDraft();
    if (!current) return;
    this.metadataDraft.set({ ...current, [field]: input.value });
    const fieldErrors = { ...this.metadataFieldErrors() };
    delete fieldErrors[field];
    this.metadataFieldErrors.set(fieldErrors);
    this.metadataError.set(null);
    this.metadataApiFailure.set(null);
    this.pendingMetadataAttempt = null;
  }

  async saveMetadata(event?: Event): Promise<void> {
    event?.preventDefault();
    const current = this.manuscript();
    const draft = this.metadataDraft();
    const workId = this.requestedWorkId;
    if (
      !current ||
      !draft ||
      !workId ||
      this.policy.status() !== 'accepted' ||
      this.isSavingMetadata()
    ) {
      return;
    }

    let attempt = this.pendingMetadataAttempt;
    if (!this.isMetadataUncertain() || !attempt) {
      const values = {
        title: draft.title.trim(),
        author: draft.author.trim(),
        language: draft.language.trim(),
      };
      if (!this.validateMetadata(values)) return;
      attempt = { values, etag: current.etag };
      this.pendingMetadataAttempt = attempt;
    }
    await this.persistMetadata(attempt, this.loadToken);
  }

  async saveMetadataOnLatest(): Promise<void> {
    const latest = this.metadataConflict();
    const draft = this.metadataDraft();
    if (!latest || !draft || this.isSavingMetadata()) return;
    const values = {
      title: draft.title.trim(),
      author: draft.author.trim(),
      language: draft.language.trim(),
    };
    if (!this.validateMetadata(values)) return;
    this.metadataConflict.set(null);
    this.pendingMetadataAttempt = { values, etag: latest.etag };
    this.isMetadataUncertain.set(false);
    await this.persistMetadata(this.pendingMetadataAttempt, this.loadToken);
  }

  useServerMetadata(): void {
    const latest = this.metadataConflict();
    if (!latest || this.isSavingMetadata()) return;
    const draft = this.metadataDraft();
    const current = this.manuscript();
    const dirty =
      this.isMetadataUncertain() ||
      (!!draft &&
        !!current &&
        (draft.title.trim() !== current.title ||
          draft.author.trim() !== current.author ||
          draft.language.trim() !== current.language));
    if (
      dirty &&
      !window.confirm(
        'Dùng thông tin trên máy chủ sẽ bỏ các thay đổi bản thảo chưa lưu của bạn. Bạn có muốn tiếp tục?',
      )
    ) {
      return;
    }
    this.manuscript.set(latest);
    this.metadataConflict.set(null);
    this.metadataDraft.set(null);
    this.metadataFieldErrors.set({});
    this.isMetadataUncertain.set(false);
    this.pendingMetadataAttempt = null;
    this.isMetadataFormOpen.set(false);
    this.metadataError.set(null);
    this.metadataApiFailure.set(null);
    this.metadataAnnouncement.set('Đã dùng thông tin bản thảo mới nhất trên máy chủ.');
    this.focusMetadataButtonAfterRender();
  }

  async reloadMetadataConflict(): Promise<void> {
    const workId = this.requestedWorkId;
    if (!workId) return;
    this.metadataApiFailure.set(null);
    try {
      this.metadataConflict.set(await this.api.getPrivateManuscript(workId));
      this.metadataError.set(null);
    } catch (error) {
      const mapped = toAuthoringError(error);
      this.metadataApiFailure.set(mapped);
      this.metadataError.set(metadataErrorMessage(mapped));
    }
  }

  canDeactivate(): boolean {
    if (this.isSavingMetadata()) return false;
    const draft = this.metadataDraft();
    const current = this.manuscript();
    const dirty =
      this.isMetadataUncertain() ||
      (!!draft &&
        !!current &&
        (draft.title.trim() !== current.title ||
          draft.author.trim() !== current.author ||
          draft.language.trim() !== current.language));
    if (!this.isMetadataFormOpen() || !dirty) return true;
    return window.confirm(
      'Rời trang sẽ bỏ thông tin bản thảo chưa lưu hoặc chưa xác nhận. Bạn có muốn rời trang?',
    );
  }

  updateChapterTitle(event: Event): void {
    if (this.isCreateUncertain()) return;
    const input = event.target as HTMLInputElement;
    this.chapterTitle.set(input.value);
    this.titleError.set(null);
    this.createError.set(null);
    this.createApiFailure.set(null);
    this.pendingCreateAttempt = null;
  }

  async loadMore(): Promise<void> {
    const workId = this.requestedWorkId;
    const cursor = this.nextCursor();
    if (!workId || !cursor || this.isLoadingMore() || this.pageState() !== 'ready') return;
    if (this.consumedCursors.has(cursor)) {
      this.moreError.set(new AuthoringApiError(502, 'INVALID_AUTHORING_OUTLINE_RESPONSE'));
      this.nextCursor.set(null);
      return;
    }

    const token = this.loadToken;
    this.moreError.set(null);
    this.isLoadingMore.set(true);
    try {
      const page = await this.api.listPrivateChapters(workId, cursor, 25);
      if (token !== this.loadToken) return;
      this.consumedCursors.add(cursor);
      this.chapters.set(mergeChapterPages(this.chapters(), page.items));
      if (page.nextCursor && this.consumedCursors.has(page.nextCursor)) {
        this.moreError.set(new AuthoringApiError(502, 'INVALID_AUTHORING_OUTLINE_RESPONSE'));
        this.nextCursor.set(null);
      } else {
        this.nextCursor.set(page.nextCursor);
      }
    } catch (error) {
      if (token === this.loadToken) this.moreError.set(toAuthoringError(error));
    } finally {
      if (token === this.loadToken) this.isLoadingMore.set(false);
    }
  }

  async addChapter(event: Event): Promise<void> {
    event.preventDefault();
    const workId = this.requestedWorkId;
    if (this.policy.status() !== 'accepted' || !workId || this.isCreating()) return;
    const token = this.loadToken;

    let attempt = this.pendingCreateAttempt;
    if (!this.isCreateUncertain() || !attempt) {
      const title = this.chapterTitle().trim();
      if (!this.validateChapterTitle(title)) return;
      attempt = { title, idempotencyKey: newAuthoringIdempotencyKey() };
      this.pendingCreateAttempt = attempt;
    }

    this.isCreating.set(true);
    this.createError.set(null);
    this.createApiFailure.set(null);
    this.titleError.set(null);
    try {
      const chapter = await this.api.createPrivateChapter(
        workId,
        attempt.title,
        attempt.idempotencyKey,
      );
      if (token !== this.loadToken) return;
      this.pendingCreateAttempt = null;
      this.isCreateUncertain.set(false);
      this.createApiFailure.set(null);
      this.isAddFormOpen.set(false);
      this.chapterTitle.set('');
      this.chapters.set(
        mergeChapterPages(this.chapters(), [{ ...chapter, contentAvailable: false }]),
      );
      const manuscript = this.manuscript();
      if (manuscript) {
        this.manuscript.set({ ...manuscript, chapterCount: manuscript.chapterCount + 1 });
      }
      this.createAnnouncement.set(
        `Đã thêm Chương ${chapter.ordinal}: ${chapter.title}. Chương hiện chưa có nội dung.`,
      );
      this.focusAddChapterButtonAfterRender();
    } catch (error) {
      if (token !== this.loadToken) return;
      const mapped = toAuthoringError(error);
      this.createApiFailure.set(mapped);
      if (isUncertainOutcome(mapped)) {
        this.isCreateUncertain.set(true);
        this.createError.set(
          'Chưa xác nhận được kết quả. Giữ nguyên tên chương rồi thử xác nhận lại bằng cùng mã an toàn; đừng tải lại trang lúc này.',
        );
      } else {
        this.pendingCreateAttempt = null;
        this.isCreateUncertain.set(false);
        if (mapped.status === 400 || mapped.code === 'INVALID_CHAPTER') {
          this.titleError.set('Tên chương cần có từ 1 đến 300 ký tự.');
        }
        this.createError.set(createChapterErrorMessage(mapped));
      }
    } finally {
      if (token === this.loadToken) this.isCreating.set(false);
    }
  }

  protected chapterAvailability(chapter: PrivateChapterOutlineItem): string {
    if (chapter.contentAvailable) return 'Có nội dung đọc được';
    if (chapter.status === 'draft') return 'Chưa có nội dung';
    return 'Chưa khả dụng';
  }

  protected pageErrorMessage(error: AuthoringApiError | null): string {
    if (!error) return 'Không tải được dàn ý. Hãy thử lại.';
    if (error.status === 401) return 'Phiên đăng nhập đã hết hạn. Hãy đăng nhập lại để tiếp tục.';
    if (error.status === 403) return 'Cần xác nhận chính sách hiện hành để tiếp tục.';
    if (error.status === 404) return 'Dàn ý không khả dụng. Bạn có thể quay lại thư viện.';
    if (error.status === 0 || error.status >= 500) {
      return 'Chưa tải được dàn ý. Kiểm tra kết nối rồi thử lại.';
    }
    return 'Máy chủ trả về dàn ý chưa thể hiển thị. Hãy tải lại.';
  }

  private async loadFirstPage(token: number): Promise<void> {
    this.pageError.set(null);
    this.moreError.set(null);
    this.manuscript.set(null);
    this.chapters.set([]);
    this.nextCursor.set(null);
    this.createAnnouncement.set('');
    this.consumedCursors.clear();
    if (this.policy.status() !== 'accepted') {
      this.pageState.set('ready');
      return;
    }

    const workId = this.requestedWorkId;
    if (!workId) {
      this.pageState.set('error');
      this.pageError.set(new AuthoringApiError(400, 'INVALID_AUTHORING_QUERY'));
      return;
    }

    this.pageState.set('loading');
    try {
      const [manuscript, page] = await Promise.all([
        this.api.getPrivateManuscript(workId),
        this.api.listPrivateChapters(workId, undefined, 25),
      ]);
      if (token !== this.loadToken) return;
      this.manuscript.set(manuscript);
      this.chapters.set(page.items);
      this.nextCursor.set(page.nextCursor);
      this.pageState.set('ready');
    } catch (error) {
      if (token !== this.loadToken) return;
      this.pageError.set(toAuthoringError(error));
      this.pageState.set('error');
    }
  }

  private validateMetadata(values: UpdatePrivateManuscriptInput): boolean {
    const errors: ManuscriptMetadataFieldErrors = {};
    if (!values.title || Array.from(values.title).length > 300) {
      errors.title = 'Tên bản thảo cần có từ 1 đến 300 ký tự.';
    }
    if (Array.from(values.author).length > 200) {
      errors.author = 'Bút danh không được dài quá 200 ký tự.';
    }
    if (Array.from(values.language).length < 2 || Array.from(values.language).length > 35) {
      errors.language = 'Mã ngôn ngữ cần có từ 2 đến 35 ký tự.';
    }
    this.metadataFieldErrors.set(errors);
    if (Object.keys(errors).length > 0) {
      this.metadataError.set(null);
      const firstInvalid = (Object.keys(errors) as Array<keyof UpdatePrivateManuscriptInput>)[0];
      this.focusMetadataField(firstInvalid);
      return false;
    }
    this.metadataError.set(null);
    return true;
  }

  private async persistMetadata(attempt: ManuscriptMetadataAttempt, token: number): Promise<void> {
    const workId = this.requestedWorkId;
    if (!workId) return;
    this.isSavingMetadata.set(true);
    this.metadataError.set(null);
    this.metadataApiFailure.set(null);
    try {
      const updated = await this.api.updatePrivateManuscript(workId, attempt.values, attempt.etag);
      if (token !== this.loadToken) return;
      this.manuscript.set(updated);
      this.metadataDraft.set(null);
      this.metadataFieldErrors.set({});
      this.metadataConflict.set(null);
      this.isMetadataUncertain.set(false);
      this.pendingMetadataAttempt = null;
      this.isMetadataFormOpen.set(false);
      this.metadataAnnouncement.set('Đã lưu thông tin bản thảo.');
      this.focusMetadataButtonAfterRender();
    } catch (error) {
      if (token !== this.loadToken) return;
      const mapped = toAuthoringError(error);
      this.metadataApiFailure.set(mapped);
      if (mapped.status === 412 && mapped.code === 'MANUSCRIPT_METADATA_STALE') {
        this.isMetadataUncertain.set(false);
        this.pendingMetadataAttempt = null;
        await this.reloadMetadataConflict();
        this.metadataError.set(
          this.metadataConflict()
            ? 'Bản thảo đã được cập nhật ở nơi khác. Chọn rõ phiên bản muốn giữ.'
            : 'Bản thảo đã đổi nhưng chưa tải được thông tin mới nhất. Hãy thử tải lại trước khi lưu.',
        );
      } else if (isUncertainOutcome(mapped)) {
        this.isMetadataUncertain.set(true);
        this.metadataError.set(
          'Chưa xác nhận được kết quả. Có thể thử lại đúng các giá trị này; không đổi ETag hay nội dung trong lúc xác nhận.',
        );
      } else {
        this.isMetadataUncertain.set(false);
        this.pendingMetadataAttempt = null;
        this.metadataError.set(metadataErrorMessage(mapped));
      }
    } finally {
      if (token === this.loadToken) this.isSavingMetadata.set(false);
    }
  }

  private validateChapterTitle(title: string): boolean {
    if (!title || Array.from(title).length > 300) {
      this.titleError.set('Tên chương cần có từ 1 đến 300 ký tự.');
      return false;
    }
    return true;
  }

  private createApiError(): AuthoringApiError | null {
    return this.pageError() ?? this.moreError() ?? this.createApiFailure();
  }

  private outlinePath(workId: string | null): string {
    return workId ? `/app/authoring/${workId}/chapters` : '/app/authoring/new';
  }

  private focusAddChapterButtonAfterRender(): void {
    afterNextRender(() => this.addChapterButton?.nativeElement.focus({ preventScroll: true }), {
      injector: this.injector,
    });
  }

  private focusMetadataButtonAfterRender(): void {
    afterNextRender(() => this.editMetadataButton?.nativeElement.focus({ preventScroll: true }), {
      injector: this.injector,
    });
  }

  private focusMetadataField(field: keyof UpdatePrivateManuscriptInput): void {
    const inputId = {
      title: 'authoring-metadata-title',
      author: 'authoring-metadata-author',
      language: 'authoring-metadata-language',
    }[field];
    document.getElementById(inputId)?.focus({ preventScroll: true });
  }
}

function mergeChapterPages(
  existing: PrivateChapterOutlineItem[],
  incoming: PrivateChapterOutlineItem[],
): PrivateChapterOutlineItem[] {
  const items = new Map(existing.map((chapter) => [chapter.id, chapter]));
  for (const chapter of incoming) items.set(chapter.id, chapter);
  return [...items.values()].sort((left, right) => left.ordinal - right.ordinal);
}

function toAuthoringError(error: unknown): AuthoringApiError {
  return error instanceof AuthoringApiError
    ? error
    : new AuthoringApiError(0, 'AUTHORING_UNAVAILABLE');
}

function isUncertainOutcome(error: AuthoringApiError): boolean {
  return error.status === 0 || error.status >= 500;
}

function createChapterErrorMessage(error: AuthoringApiError): string {
  if (error.status === 401) return 'Phiên đăng nhập hết hạn. Hãy đăng nhập lại để thêm chương.';
  if (error.code === 'POLICY_ACCEPTANCE_REQUIRED') {
    return 'Hãy xác nhận Điều khoản và Chính sách quyền riêng tư hiện hành trước khi thêm chương.';
  }
  if (error.status === 403) return 'Tài khoản hiện không thể thêm chương vào tác phẩm này.';
  if (error.status === 404) return 'Dàn ý không khả dụng. Hãy quay lại thư viện.';
  if (error.status === 409 && error.code === 'IDEMPOTENCY_KEY_REUSED') {
    return 'Yêu cầu bị xung đột. Kiểm tra tên chương rồi gửi lại để tạo yêu cầu mới.';
  }
  if (error.status === 400) return 'Tên chương hoặc yêu cầu chưa hợp lệ. Hãy kiểm tra lại.';
  if (error.status === 413) return 'Tên chương vượt giới hạn 300 ký tự.';
  if (error.status === 0 || error.status >= 500) {
    return 'Chưa xác nhận được thao tác. Có thể thử lại bằng cùng mã an toàn.';
  }
  return 'Không thể thêm chương lúc này. Hãy thử lại sau.';
}

function metadataErrorMessage(error: AuthoringApiError): string {
  if (error.status === 401) return 'Phiên đăng nhập hết hạn. Hãy đăng nhập lại trước khi lưu.';
  if (error.code === 'POLICY_ACCEPTANCE_REQUIRED') {
    return 'Hãy xác nhận chính sách hiện hành trước khi sửa thông tin bản thảo.';
  }
  if (error.status === 403) return 'Tài khoản hiện không thể sửa bản thảo này.';
  if (error.status === 404) return 'Bản thảo không còn khả dụng. Hãy quay lại thư viện.';
  if (error.status === 400) return 'Thông tin chưa hợp lệ. Hãy kiểm tra các trường rồi thử lại.';
  if (error.status === 428)
    return 'Thiếu phiên bản bản thảo hiện tại. Hãy tải lại thông tin trước khi lưu.';
  if (error.status === 0 || error.status >= 500) {
    return 'Chưa xác nhận được thao tác. Có thể thử xác nhận lại cùng nội dung.';
  }
  return 'Không thể lưu thông tin lúc này. Hãy thử lại sau.';
}
