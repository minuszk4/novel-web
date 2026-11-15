import {
  Component,
  DestroyRef,
  ElementRef,
  computed,
  inject,
  signal,
  ViewChild,
} from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import {
  ReaderApiError,
  ReaderApiService,
  ReaderChapter,
  ReaderManifestPage,
  ReaderProgress,
  ReaderWork,
} from '../core/reader-api.service';

const manifestPageSize = 100;
const maxManifestPages = 25;

@Component({
  selector: 'app-account-work-detail-page',
  imports: [RouterLink],
  templateUrl: './account-work-detail-page.component.html',
})
export class AccountWorkDetailPageComponent {
  protected readonly work = signal<ReaderWork | null>(null);
  protected readonly chapters = signal<ReaderChapter[]>([]);
  protected readonly progress = signal<ReaderProgress | null>(null);
  protected readonly state = signal<'loading' | 'ready' | 'error'>('loading');
  protected readonly manifestError = signal<ReaderApiError | null>(null);
  protected readonly progressError = signal<ReaderApiError | null>(null);
  protected readonly pageError = signal<ReaderApiError | null>(null);
  protected readonly nextCursor = signal<string | null>(null);
  protected readonly isLoadingMore = signal(false);
  protected readonly isOpeningReader = signal(false);
  protected readonly notice = signal<string | null>(null);
  protected readonly readableCount = computed(
    () => this.chapters().filter(({ accessState }) => accessState === 'readable').length,
  );
  protected readonly canOpenReader = computed(
    () =>
      this.readableCount() > 0 ||
      (this.work()?.visibility === 'public' &&
        this.chapters().some(({ accessState }) => accessState === 'locked')) ||
      !!this.nextCursor(),
  );
  protected readonly primaryActionLabel = computed(() => {
    if (this.progress()?.chapterId) return 'Tiếp tục đọc';
    return this.work()?.visibility === 'public' ? 'Xem chương đầu' : 'Đọc từ đầu';
  });

  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly api = inject(ReaderApiService);
  private readonly destroyRef = inject(DestroyRef);
  private requestedWorkId: string | null = null;
  private loadToken = 0;
  private loadedPageCount = 0;

  @ViewChild('workHeading')
  private set workHeading(heading: ElementRef<HTMLHeadingElement> | undefined) {
    heading?.nativeElement.focus({ preventScroll: true });
  }

  constructor() {
    this.route.paramMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((params) => {
      const workId = params.get('workId');
      const token = ++this.loadToken;
      this.requestedWorkId = workId;
      void this.loadWork(workId, token);
    });
  }

  async retry(): Promise<void> {
    await this.loadWork(this.requestedWorkId, ++this.loadToken);
  }

  async retryProgress(): Promise<void> {
    const workId = this.requestedWorkId;
    if (!workId) return;
    this.progressError.set(null);
    try {
      this.progress.set(await this.api.getProgress(workId));
    } catch (error) {
      const mapped = asReaderError(error);
      if (mapped.status === 404) {
        this.progress.set(null);
        return;
      }
      this.progressError.set(mapped);
    }
  }

  async loadMoreChapters(): Promise<void> {
    await this.loadNextManifestPage();
  }

  async openReader(): Promise<void> {
    const workId = this.requestedWorkId;
    if (!workId || !this.canOpenReader() || this.isOpeningReader()) return;
    this.isOpeningReader.set(true);
    this.notice.set(null);
    try {
      let target: ReaderChapter | null = null;
      const savedChapterId = this.progress()?.chapterId;
      if (savedChapterId) {
        target = await this.findChapter(savedChapterId);
        if (this.pageError()) return;
        if (!this.canReadOrUnlock(target)) {
          target = null;
          this.notice.set('Chương đã lưu không còn khả dụng. Mở chương đầu tiên hiện có thể đọc.');
        }
      }
      if (!target) target = await this.findFirstOpenableChapter();
      if (this.pageError()) return;
      if (!target) {
        this.notice.set('Tác phẩm hiện chưa có chương nào sẵn sàng để mở.');
        return;
      }
      await this.router.navigate(['/app/reader', workId, target.id]);
    } catch (error) {
      this.notice.set(statusMessage(asReaderError(error)));
    } finally {
      this.isOpeningReader.set(false);
    }
  }

  protected statusMessage(error: ReaderApiError | null): string {
    if (!error || error.status === 0) return 'Không kết nối được. Kiểm tra mạng rồi thử lại.';
    if (error.status === 401) return 'Phiên đăng nhập đã hết hạn. Hãy đăng nhập lại để tiếp tục.';
    if (error.status === 403) return 'Cần xác nhận chính sách hiện hành để tiếp tục.';
    if (error.status === 404)
      return 'Tác phẩm không còn trong thư viện hoặc bạn không có quyền truy cập.';
    if (error.status >= 500) return 'Chưa tải được tác phẩm. Thử lại sau ít phút.';
    return 'Máy chủ trả về dữ liệu chưa thể hiển thị. Hãy tải lại tác phẩm.';
  }

  protected get recoveryUrl(): string {
    const error = this.manifestError() ?? this.pageError() ?? this.progressError();
    return error?.status === 403 ? '/app/setup' : '/sign-in';
  }

  protected get recoveryQueryParams(): Record<string, string> {
    const workId = this.requestedWorkId;
    if (!workId) return {};
    const returnPath = `/app/works/${workId}`;
    return this.recoveryUrl === '/app/setup' ? { returnUrl: returnPath } : { returnTo: returnPath };
  }

  protected get recoveryLabel(): string {
    return this.recoveryUrl === '/app/setup' ? 'Xem chính sách tài khoản' : 'Đăng nhập lại';
  }

  private async loadWork(workId: string | null, token: number): Promise<void> {
    this.state.set('loading');
    this.manifestError.set(null);
    this.progressError.set(null);
    this.pageError.set(null);
    this.notice.set(null);
    this.work.set(null);
    this.chapters.set([]);
    this.progress.set(null);
    this.nextCursor.set(null);
    this.loadedPageCount = 0;
    if (!workId) {
      this.state.set('error');
      this.manifestError.set(
        new ReaderApiError('INVALID_READER_QUERY', 400, 'INVALID_READER_QUERY'),
      );
      return;
    }

    const [manifestResult, progressResult] = await Promise.allSettled([
      this.api.getManifestPage(workId, undefined, manifestPageSize),
      this.api.getProgress(workId),
    ]);
    if (token !== this.loadToken) return;
    if (manifestResult.status === 'rejected') {
      this.state.set('error');
      this.manifestError.set(asReaderError(manifestResult.reason));
      return;
    }

    try {
      this.applyManifestPage(manifestResult.value, true);
    } catch (error) {
      this.state.set('error');
      this.manifestError.set(asReaderError(error));
      return;
    }
    if (progressResult.status === 'fulfilled') this.progress.set(progressResult.value);
    else {
      const error = asReaderError(progressResult.reason);
      if (error.status !== 404) this.progressError.set(error);
    }
    this.state.set('ready');
  }

  private async loadNextManifestPage(): Promise<boolean> {
    const workId = this.requestedWorkId;
    const cursor = this.nextCursor();
    if (!workId || !cursor || this.isLoadingMore() || this.loadedPageCount >= maxManifestPages) {
      return false;
    }
    this.isLoadingMore.set(true);
    this.pageError.set(null);
    try {
      const page = await this.api.getManifestPage(workId, cursor, manifestPageSize);
      if (workId !== this.requestedWorkId) return false;
      this.applyManifestPage(page, false);
      return true;
    } catch (error) {
      this.pageError.set(asReaderError(error));
      return false;
    } finally {
      this.isLoadingMore.set(false);
    }
  }

  private applyManifestPage(page: ReaderManifestPage, firstPage: boolean): void {
    const currentWork = this.work();
    const currentChapters = this.chapters();
    const ids = new Set(currentChapters.map(({ id }) => id));
    const lastOrdinal = currentChapters.at(-1)?.ordinal ?? 0;
    if (
      (!firstPage && page.work.id !== currentWork?.id) ||
      page.chapters.some((chapter) => ids.has(chapter.id) || chapter.ordinal <= lastOrdinal) ||
      (!firstPage && !page.chapters.length && !!page.nextCursor) ||
      (page.nextCursor !== null && page.nextCursor === this.nextCursor())
    ) {
      throw new ReaderApiError('INVALID_READER_RESPONSE', 502, 'INVALID_READER_RESPONSE');
    }
    this.work.set(page.work);
    this.chapters.set(firstPage ? page.chapters : [...currentChapters, ...page.chapters]);
    this.nextCursor.set(page.nextCursor);
    this.loadedPageCount += 1;
  }

  private async findChapter(chapterId: string): Promise<ReaderChapter | null> {
    for (let pageIndex = 0; pageIndex < maxManifestPages; pageIndex += 1) {
      const chapter = this.chapters().find((item) => item.id === chapterId);
      if (chapter) return chapter;
      if (!this.nextCursor() || !(await this.loadNextManifestPage())) return null;
    }
    return null;
  }

  protected canReadOrUnlock(chapter: ReaderChapter | null): boolean {
    return (
      chapter?.accessState === 'readable' ||
      (chapter?.accessState === 'locked' && this.work()?.visibility === 'public')
    );
  }

  private async findFirstOpenableChapter(): Promise<ReaderChapter | null> {
    for (let pageIndex = 0; pageIndex < maxManifestPages; pageIndex += 1) {
      const chapter = this.chapters().find((item) => this.canReadOrUnlock(item));
      if (chapter) return chapter;
      if (!this.nextCursor() || !(await this.loadNextManifestPage())) return null;
    }
    return null;
  }
}

function asReaderError(error: unknown): ReaderApiError {
  return error instanceof ReaderApiError
    ? error
    : new ReaderApiError('READER_REQUEST_FAILED', 0, null);
}

function statusMessage(error: ReaderApiError): string {
  if (error.status === 0) return 'Không kết nối được. Chương chưa mở; hãy thử lại.';
  if (error.status === 401) return 'Phiên đăng nhập đã hết hạn. Hãy đăng nhập lại.';
  if (error.status === 403) return 'Cần xác nhận chính sách hiện hành để tiếp tục.';
  if (error.status === 404) return 'Tác phẩm hoặc chương không còn trong phạm vi được đọc.';
  if (error.status >= 500) return 'Reader đang tạm thời không khả dụng. Thử lại sau ít phút.';
  return 'Chưa mở được chương này. Hãy tải lại mục lục.';
}
