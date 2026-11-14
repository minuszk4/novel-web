import { DecimalPipe } from '@angular/common';
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
  AuthoringApiError,
  AuthoringApiService,
  PrivateAuthoredChapterDraft,
  PrivateDraftSegment,
  PrivateDraftSourceFormat,
  SavePrivateDraftRevisionInput,
  newAuthoringIdempotencyKey,
} from '../core/authoring-api.service';
import { PolicyOnboardingService } from '../core/policy-onboarding.service';

type PageState = 'loading' | 'guest' | 'error' | 'ready';
type SaveState = 'saved' | 'dirty' | 'saving' | 'uncertain' | 'conflict' | 'error';

interface SaveAttempt {
  input: SavePrivateDraftRevisionInput;
  ifMatch: string;
  idempotencyKey: string;
  content: string;
}

const autosaveDelayMs = 900;
const maxDraftBytes = 10_485_760;

@Component({
  selector: 'app-authoring-chapter-editor-page',
  imports: [DecimalPipe, RouterLink],
  templateUrl: './authoring-chapter-editor-page.component.html',
  styleUrl: './authoring-chapter-editor-page.component.css',
})
export class AuthoringChapterEditorPageComponent {
  protected readonly policy = inject(PolicyOnboardingService);
  protected readonly pageState = signal<PageState>('loading');
  protected readonly saveState = signal<SaveState>('saved');
  protected readonly draft = signal<PrivateAuthoredChapterDraft | null>(null);
  protected readonly remoteDraft = signal<PrivateAuthoredChapterDraft | null>(null);
  protected readonly content = signal('');
  protected readonly pageError = signal<AuthoringApiError | null>(null);
  protected readonly saveError = signal<AuthoringApiError | null>(null);
  protected readonly conflictError = signal<AuthoringApiError | null>(null);
  protected readonly announcement = signal('');
  protected readonly isSaving = signal(false);
  protected readonly isLoadingRemote = signal(false);
  protected readonly byteLength = signal(0);

  private readonly route = inject(ActivatedRoute);
  private readonly api = inject(AuthoringApiService);
  private readonly destroyRef = inject(DestroyRef);
  private workId: string | null = null;
  private chapterId: string | null = null;
  private savedContent = '';
  private currentEtag: string | null = null;
  private sourceFormat: PrivateDraftSourceFormat = 'plain_text';
  private attempt: SaveAttempt | null = null;
  private timer: ReturnType<typeof setTimeout> | null = null;
  private loadToken = 0;

  @ViewChild('editorHeading')
  private set editorHeading(heading: ElementRef<HTMLHeadingElement> | undefined) {
    heading?.nativeElement.focus({ preventScroll: true });
  }

  constructor() {
    this.route.paramMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((params) => {
      this.workId = params.get('workId');
      this.chapterId = params.get('chapterId');
      this.attempt = null;
      this.clearAutosave();
      void this.load(++this.loadToken);
    });
    this.destroyRef.onDestroy(() => this.clearAutosave());
  }

  protected get returnTo(): string {
    return this.workId ? `/app/authoring/${this.workId}/chapters` : '/app/library';
  }

  protected get recoveryUrl(): string | null {
    switch (this.pageError()?.status ?? this.conflictError()?.status ?? this.saveError()?.status) {
      case 401:
        return '/sign-in';
      case 403:
        return '/app/setup';
      case 404:
        return this.returnTo;
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
    if (this.recoveryUrl === '/app/setup') return 'Kiểm tra chính sách tài khoản';
    return 'Về dàn ý';
  }

  protected get saveStatusText(): string {
    switch (this.saveState()) {
      case 'dirty':
        return 'Có thay đổi chưa lưu';
      case 'saving':
        return 'Đang lưu phiên bản…';
      case 'uncertain':
        return 'Chưa xác nhận được lần lưu';
      case 'conflict':
        return 'Bản thảo đã được thay đổi ở nơi khác';
      case 'error':
        return 'Chưa lưu được thay đổi';
      default:
        return this.draft() ? `Đã lưu · phiên bản ${this.draft()!.revisionNo}` : 'Đã lưu';
    }
  }

  protected get contentLimitError(): string | null {
    if (!this.content().trim()) return 'Nhập nội dung trước khi lưu phiên bản đầu tiên.';
    if (this.byteLength() > maxDraftBytes) return 'Nội dung vượt giới hạn 10 MiB.';
    return null;
  }

  protected get canSave(): boolean {
    return (
      this.pageState() === 'ready' &&
      this.saveState() !== 'conflict' &&
      this.saveState() !== 'uncertain' &&
      !this.isSaving() &&
      this.content() !== this.savedContent &&
      this.contentLimitError === null
    );
  }

  protected errorMessage(error: AuthoringApiError): string {
    return saveErrorMessage(error);
  }

  async retryLoad(): Promise<void> {
    await this.load(++this.loadToken);
  }

  async saveNow(): Promise<void> {
    const workId = this.workId;
    const chapterId = this.chapterId;
    const etag = this.currentEtag;
    if (!workId || !chapterId || !etag || this.isSaving()) return;
    if (this.saveState() === 'conflict') return;

    if (!this.attempt) {
      if (!this.canSave) return;
      const content = this.content();
      this.attempt = {
        input: makeRevisionInput(chapterId, content, this.sourceFormat),
        ifMatch: etag,
        idempotencyKey: newAuthoringIdempotencyKey(),
        content,
      };
    }

    const attempt = this.attempt;
    const token = this.loadToken;
    this.clearAutosave();
    this.isSaving.set(true);
    this.saveState.set('saving');
    this.saveError.set(null);
    this.announcement.set('Đang lưu nội dung riêng tư.');
    try {
      const result = await this.api.savePrivateChapterDraftRevision(
        workId,
        chapterId,
        attempt.ifMatch,
        attempt.input,
        attempt.idempotencyKey,
      );
      if (token !== this.loadToken) return;
      this.currentEtag = result.etag;
      this.savedContent = attempt.content;
      this.draft.update((current) =>
        current
          ? {
              ...current,
              revisionNo: result.revisionNo,
              sourceFormat: result.sourceFormat,
              normalizedContent: attempt.content,
              segments: attempt.input.segments,
              updatedAt: result.createdAt,
              etag: result.etag,
            }
          : current,
      );
      this.attempt = null;
      if (this.content() === this.savedContent) {
        this.saveState.set('saved');
        this.announcement.set(`Đã lưu phiên bản ${result.revisionNo}.`);
      } else {
        this.saveState.set('dirty');
        this.announcement.set('Đã lưu phiên bản. Có thay đổi mới đang chờ lưu.');
        this.scheduleAutosave();
      }
    } catch (error) {
      if (token !== this.loadToken) return;
      const mapped = toAuthoringError(error);
      this.saveError.set(mapped);
      if (isUncertainOutcome(mapped)) {
        this.saveState.set('uncertain');
        this.announcement.set('Chưa xác nhận được lần lưu. Có thể thử xác nhận lại an toàn.');
      } else if (mapped.status === 412) {
        this.attempt = null;
        this.saveError.set(null);
        this.saveState.set('conflict');
        this.announcement.set(
          'Có phiên bản mới trên máy chủ. Nội dung đang viết vẫn được giữ ở màn hình này.',
        );
        await this.loadRemoteVersion(token);
      } else {
        this.attempt = null;
        this.saveState.set('error');
        this.announcement.set(saveErrorMessage(mapped));
      }
    } finally {
      if (token === this.loadToken) this.isSaving.set(false);
    }
  }

  retryUncertainSave(): void {
    if (this.saveState() !== 'uncertain' || !this.attempt) return;
    void this.saveNow();
  }

  async reloadRemoteVersion(): Promise<void> {
    await this.loadRemoteVersion(this.loadToken);
  }

  useRemoteVersion(): void {
    const remote = this.remoteDraft();
    if (!remote) return;
    this.draft.set(remote);
    this.sourceFormat = remote.sourceFormat ?? 'plain_text';
    this.currentEtag = remote.etag;
    this.content.set(remote.normalizedContent);
    this.savedContent = remote.normalizedContent;
    this.byteLength.set(utf8Bytes(remote.normalizedContent));
    this.remoteDraft.set(null);
    this.conflictError.set(null);
    this.saveError.set(null);
    this.saveState.set('saved');
    this.announcement.set(`Đã chuyển sang phiên bản ${remote.revisionNo} trên máy chủ.`);
  }

  async saveMyVersionAsNext(): Promise<void> {
    const remote = this.remoteDraft();
    if (!remote || this.isSaving()) return;
    this.currentEtag = remote.etag;
    this.draft.set(remote);
    this.savedContent = remote.normalizedContent;
    this.sourceFormat = remote.sourceFormat ?? 'plain_text';
    this.remoteDraft.set(null);
    this.conflictError.set(null);
    this.saveState.set('dirty');
    this.announcement.set('Đang lưu nội dung của bạn thành phiên bản tiếp theo.');
    await this.saveNow();
  }

  updateContent(event: Event): void {
    if (this.saveState() === 'uncertain' || this.pageState() !== 'ready') return;
    const textarea = event.target as HTMLTextAreaElement;
    this.content.set(textarea.value);
    this.byteLength.set(utf8Bytes(textarea.value));
    this.saveError.set(null);
    if (this.saveState() !== 'conflict') {
      this.saveState.set(textarea.value === this.savedContent ? 'saved' : 'dirty');
      if (textarea.value !== this.savedContent && !this.contentLimitError) {
        this.scheduleAutosave();
      } else {
        this.clearAutosave();
      }
    }
  }

  canDeactivate(): boolean {
    const needsWarning =
      this.content() !== this.savedContent ||
      this.isSaving() ||
      this.saveState() === 'uncertain' ||
      this.saveState() === 'conflict';
    return (
      !needsWarning ||
      window.confirm(
        'Nội dung chưa được xác nhận là đã lưu. Nếu rời trang, phần thay đổi mới có thể bị mất. Bạn có muốn rời trang?',
      )
    );
  }

  @HostListener('window:beforeunload', ['$event'])
  protectPageExit(event: BeforeUnloadEvent): void {
    if (
      this.content() !== this.savedContent ||
      this.isSaving() ||
      this.saveState() === 'uncertain' ||
      this.saveState() === 'conflict'
    ) {
      event.preventDefault();
      event.returnValue = true;
    }
  }

  private async load(token: number): Promise<void> {
    this.pageError.set(null);
    this.saveError.set(null);
    this.conflictError.set(null);
    this.remoteDraft.set(null);
    this.isSaving.set(false);
    this.isLoadingRemote.set(false);
    this.announcement.set('');
    if (this.policy.status() !== 'accepted') {
      this.pageState.set('guest');
      return;
    }
    if (!this.workId || !this.chapterId) {
      this.pageState.set('error');
      this.pageError.set(new AuthoringApiError(400, 'INVALID_DRAFT_QUERY'));
      return;
    }

    this.pageState.set('loading');
    try {
      const draft = await this.api.getPrivateChapterDraft(this.workId, this.chapterId);
      if (token !== this.loadToken) return;
      this.draft.set(draft);
      this.sourceFormat = draft.sourceFormat ?? 'plain_text';
      this.currentEtag = draft.etag;
      this.savedContent = draft.normalizedContent;
      this.content.set(draft.normalizedContent);
      this.byteLength.set(utf8Bytes(draft.normalizedContent));
      this.saveState.set('saved');
      this.pageState.set('ready');
    } catch (error) {
      if (token !== this.loadToken) return;
      this.pageError.set(toAuthoringError(error));
      this.pageState.set('error');
    }
  }

  private async loadRemoteVersion(token: number): Promise<void> {
    if (!this.workId || !this.chapterId) return;
    this.isLoadingRemote.set(true);
    this.conflictError.set(null);
    try {
      const remote = await this.api.getPrivateChapterDraft(this.workId, this.chapterId);
      if (token !== this.loadToken) return;
      this.remoteDraft.set(remote);
      this.announcement.set(
        `Tải được phiên bản ${remote.revisionNo} trên máy chủ. Hãy chọn phiên bản muốn giữ.`,
      );
    } catch (error) {
      if (token !== this.loadToken) return;
      this.conflictError.set(toAuthoringError(error));
    } finally {
      if (token === this.loadToken) this.isLoadingRemote.set(false);
    }
  }

  private scheduleAutosave(): void {
    this.clearAutosave();
    this.timer = setTimeout(() => {
      this.timer = null;
      void this.saveNow();
    }, autosaveDelayMs);
  }

  private clearAutosave(): void {
    if (this.timer !== null) clearTimeout(this.timer);
    this.timer = null;
  }
}

function makeRevisionInput(
  chapterId: string,
  content: string,
  sourceFormat: PrivateDraftSourceFormat,
): SavePrivateDraftRevisionInput {
  const paragraphs = content.split(/\n{2,}/).filter((paragraph) => paragraph.length > 0);
  const segments: PrivateDraftSegment[] = paragraphs.map((sourceText, index) => ({
    ordinal: index + 1,
    type: 'paragraph',
    sourceText,
    stableAnchor: `chapter-${chapterId.slice(0, 8)}-p${index + 1}`,
  }));
  return { sourceFormat, normalizedContent: content, segments };
}

function utf8Bytes(value: string): number {
  return new TextEncoder().encode(value).byteLength;
}

function toAuthoringError(error: unknown): AuthoringApiError {
  return error instanceof AuthoringApiError
    ? error
    : new AuthoringApiError(0, 'AUTHORING_UNAVAILABLE');
}

function isUncertainOutcome(error: AuthoringApiError): boolean {
  return error.status === 0 || error.status >= 500;
}

function saveErrorMessage(error: AuthoringApiError): string {
  if (error.status === 401) return 'Phiên đăng nhập đã hết hạn. Hãy đăng nhập lại để tiếp tục.';
  if (error.status === 403) return 'Tài khoản hiện không có quyền sửa chương này.';
  if (error.status === 404)
    return 'Chương không còn khả dụng. Nội dung đang viết vẫn được giữ trên màn hình.';
  if (error.status === 409)
    return 'Yêu cầu lưu bị trùng lặp. Hãy tải lại phiên bản hiện tại trước khi thử lại.';
  if (error.status === 413) return 'Nội dung vượt giới hạn lưu trữ 10 MiB.';
  if (error.status === 400)
    return 'Nội dung chưa đúng định dạng cho phép. Hãy kiểm tra rồi thử lưu lại.';
  if (error.status === 0 || error.status >= 500) return 'Chưa xác nhận được kết quả lưu.';
  return 'Chưa lưu được nội dung. Hãy thử lại hoặc tải phiên bản hiện tại.';
}
