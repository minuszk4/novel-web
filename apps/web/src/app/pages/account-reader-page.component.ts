import {
  Component,
  DestroyRef,
  ElementRef,
  HostListener,
  inject,
  signal,
  ViewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import {
  ReaderApiError,
  ReaderApiService,
  ReaderChapter,
  ReaderContentPage,
  ReaderManifestPage,
  ReaderProgress,
  ReaderSourceSegment,
  SaveReaderProgressRequest,
  ReaderWork,
} from '../core/reader-api.service';

const manifestPageSize = 100;
const contentPageSize = 1000;
const maxManifestPages = 25;
const maxContentPages = 20;
const maxSegmentsPerChapter = 20_000;

type PendingReaderProgress = Omit<SaveReaderProgressRequest, 'expectedVersion'> & {
  workId: string;
};

@Component({
  selector: 'app-account-reader-page',
  imports: [RouterLink],
  templateUrl: './account-reader-page.component.html',
})
export class AccountReaderPageComponent {
  protected readonly theme = signal<'light' | 'sepia' | 'dark'>('light');
  protected readonly fontSize = signal(20);
  protected readonly work = signal<ReaderWork | null>(null);
  protected readonly chapters = signal<ReaderChapter[]>([]);
  protected readonly activeChapter = signal<ReaderChapter | null>(null);
  protected readonly segments = signal<ReaderSourceSegment[]>([]);
  protected readonly manifestState = signal<'loading' | 'ready' | 'error'>('loading');
  protected readonly contentState = signal<
    'idle' | 'loading' | 'ready' | 'empty' | 'unavailable' | 'error'
  >('idle');
  protected readonly manifestError = signal<ReaderApiError | null>(null);
  protected readonly contentError = signal<ReaderApiError | null>(null);
  protected readonly nextManifestCursor = signal<string | null>(null);
  protected readonly manifestBusy = signal(false);
  protected readonly manifestPageError = signal<ReaderApiError | null>(null);
  protected readonly tocOpen = signal(false);
  protected readonly settingsOpen = signal(false);
  protected readonly progressSyncState = signal<
    'loading' | 'saved' | 'saving' | 'pending' | 'conflict'
  >('loading');

  private readonly api = inject(ReaderApiService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  private loadedWorkId: string | null = null;
  private requestedWorkId: string | null = null;
  private requestedChapterId: string | null = null;
  private loadToken = 0;
  private panelReturnFocus: HTMLElement | null = null;
  private readonly progressVersions = new Map<string, number>();
  private progressTimer: number | null = null;
  private pendingProgress: PendingReaderProgress | null = null;
  private savingProgress = false;

  @ViewChild('tocPanel') private set tocPanel(panel: ElementRef<HTMLElement> | undefined) {
    if (panel) this.focusPanel(panel.nativeElement);
  }

  @ViewChild('settingsPanel') private set settingsPanel(
    panel: ElementRef<HTMLElement> | undefined,
  ) {
    if (panel) this.focusPanel(panel.nativeElement);
  }

  constructor() {
    this.route.paramMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((params) => {
      const workId = params.get('workId');
      const chapterId = params.get('chapterId');
      if (this.activeChapter()) void this.flushProgress();
      this.requestedWorkId = workId;
      this.requestedChapterId = chapterId;
      const token = ++this.loadToken;
      this.tocOpen.set(false);
      this.settingsOpen.set(false);
      this.panelReturnFocus = null;
      if (!workId) {
        this.setManifestError(
          new ReaderApiError('INVALID_READER_QUERY', 400, 'INVALID_READER_QUERY'),
        );
        return;
      }
      if (workId === this.loadedWorkId && this.work()) {
        void this.activateRouteChapter(chapterId, token);
      } else {
        void this.loadWork(workId, chapterId, token);
      }
    });
    this.destroyRef.onDestroy(() => {
      if (this.progressTimer !== null) window.clearTimeout(this.progressTimer);
      if (this.activeChapter()) void this.flushProgress();
    });
  }

  protected progressStatusLabel(): string {
    switch (this.progressSyncState()) {
      case 'loading':
        return 'Đang tải tiến độ…';
      case 'saving':
        return 'Đang lưu tiến độ…';
      case 'pending':
        return 'Tiến độ chưa đồng bộ';
      case 'conflict':
        return 'Tiến độ đã đổi trên thiết bị khác';
      default:
        return 'Tiến độ đã đồng bộ';
    }
  }

  protected onReaderScroll(): void {
    if (this.progressTimer !== null) window.clearTimeout(this.progressTimer);
    this.progressTimer = window.setTimeout(() => {
      this.progressTimer = null;
      void this.flushProgress();
    }, 800);
  }

  @HostListener('document:visibilitychange')
  protected onVisibilityChange(): void {
    if (document.visibilityState === 'hidden') void this.flushProgress();
  }

  @HostListener('window:pagehide')
  protected onPageHide(): void {
    void this.flushProgress();
  }

  protected get previousChapter(): ReaderChapter | null {
    const current = this.activeChapter();
    if (!current) return null;
    const items = this.chapters();
    const index = items.findIndex((chapter) => chapter.id === current.id);
    return (
      [...items.slice(0, index)].reverse().find((chapter) => chapter.accessState === 'readable') ??
      null
    );
  }

  protected get nextChapter(): ReaderChapter | null {
    const current = this.activeChapter();
    if (!current) return null;
    const items = this.chapters();
    const index = items.findIndex((chapter) => chapter.id === current.id);
    return items.slice(index + 1).find((chapter) => chapter.accessState === 'readable') ?? null;
  }

  protected async retry(): Promise<void> {
    if (!this.requestedWorkId) return;
    this.loadedWorkId = null;
    const token = ++this.loadToken;
    await this.loadWork(this.requestedWorkId, this.requestedChapterId, token);
  }

  protected retryManifestPage(): void {
    void this.loadNextManifestPage();
  }

  protected async loadNextManifestPage(): Promise<ReaderChapter[]> {
    const work = this.work();
    const cursor = this.nextManifestCursor();
    if (!work || !cursor || this.manifestBusy()) return [];
    const token = this.loadToken;
    this.manifestBusy.set(true);
    try {
      const page = await this.api.getManifestPage(work.id, cursor, manifestPageSize);
      if (token !== this.loadToken) return [];
      const newChapters = this.appendManifestPage(page);
      this.nextManifestCursor.set(page.nextCursor);
      this.manifestPageError.set(null);
      if (this.tocOpen()) this.focusManifestPageResult(newChapters);
      return newChapters;
    } catch (error) {
      if (token === this.loadToken) {
        this.manifestPageError.set(asReaderApiError(error));
        const status = this.manifestPageError()?.status;
        const focusTargetId =
          status === 401 || status === 403
            ? 'account-reader-page-recovery'
            : 'account-reader-retry-manifest-page';
        requestAnimationFrame(() => {
          document.getElementById(focusTargetId)?.focus({ preventScroll: true });
        });
      }
      return [];
    } finally {
      if (token === this.loadToken) this.manifestBusy.set(false);
    }
  }

  protected async openNextChapter(): Promise<void> {
    const next = this.nextChapter;
    if (next) {
      this.navigateToChapter(next);
      return;
    }
    await this.loadNextManifestChapter();
  }

  protected async retryNextChapterPage(): Promise<void> {
    await this.loadNextManifestChapter();
  }

  private async loadNextManifestChapter(): Promise<void> {
    if (!this.nextManifestCursor() || this.manifestBusy()) return;
    const newChapters = await this.loadNextManifestPage();
    if (this.manifestPageError()) return;
    const firstReadable = newChapters.find((chapter) => chapter.accessState === 'readable');
    if (firstReadable) {
      this.navigateToChapter(firstReadable);
      return;
    }

    const focusTargetId = this.nextManifestCursor()
      ? 'account-reader-next-chapter'
      : 'account-reader-latest-label';
    requestAnimationFrame(() => {
      document.getElementById(focusTargetId)?.focus({ preventScroll: true });
    });
  }

  protected openChapter(chapter: ReaderChapter): void {
    if (chapter.accessState !== 'readable') return;
    this.closePanel(false);
    this.navigateToChapter(chapter);
  }

  protected toggleToc(event?: Event): void {
    if (this.tocOpen()) {
      this.closePanel();
      return;
    }
    this.openPanel('toc', event);
  }

  protected toggleSettings(event?: Event): void {
    if (this.settingsOpen()) {
      this.closePanel();
      return;
    }
    this.openPanel('settings', event);
  }

  protected closePanel(restoreFocus = true): void {
    const returnFocus = this.panelReturnFocus;
    this.tocOpen.set(false);
    this.settingsOpen.set(false);
    this.panelReturnFocus = null;
    if (restoreFocus && returnFocus?.isConnected) returnFocus.focus({ preventScroll: true });
  }

  protected changeFont(amount: number): void {
    this.fontSize.update((current) => Math.max(16, Math.min(28, current + amount)));
  }

  protected resetPreferences(): void {
    this.fontSize.set(20);
    this.theme.set('light');
  }

  protected statusMessage(error: ReaderApiError | null): string {
    if (!error || error.status === 0)
      return 'Không kết nối được Reader. Kiểm tra mạng rồi thử lại.';
    if (error.status === 401) return 'Phiên đăng nhập đã hết hạn. Hãy đăng nhập lại để đọc tiếp.';
    if (error.status === 403) return 'Cần xác nhận chính sách hiện hành để tiếp tục đọc.';
    if (error.status === 404)
      return 'Tác phẩm hoặc chương này không còn trong phạm vi bạn được đọc.';
    if (error.status === 400) return 'Liên kết chương không còn hợp lệ. Hãy tải lại mục lục.';
    if (error.status >= 500) return 'Reader đang tạm thời không khả dụng. Thử lại sau ít phút.';
    return 'Không thể tải nội dung Reader. Hãy thử lại.';
  }

  protected get recoveryUrl(): string {
    if (
      this.contentError()?.status === 403 ||
      this.manifestError()?.status === 403 ||
      this.manifestPageError()?.status === 403
    ) {
      return '/app/setup';
    }
    return '/sign-in';
  }

  protected get recoveryQueryParams(): Record<string, string> {
    const workId = this.requestedWorkId;
    if (!workId) return {};
    const chapterId = this.activeChapter()?.id ?? this.requestedChapterId;
    const returnPath = chapterId ? `/app/reader/${workId}/${chapterId}` : `/app/reader/${workId}`;
    return this.recoveryUrl === '/app/setup' ? { returnUrl: returnPath } : { returnTo: returnPath };
  }

  protected get recoveryLabel(): string {
    return this.recoveryUrl === '/app/setup' ? 'Xem chính sách tài khoản' : 'Đăng nhập lại';
  }

  @HostListener('document:keydown', ['$event'])
  protected onKeydown(event: KeyboardEvent): void {
    const panel = this.openPanelElement();
    if (event.key === 'Escape' && panel) {
      this.closePanel();
      event.preventDefault();
      return;
    }
    if (event.key === 'Tab' && panel) {
      this.keepTabFocusInsidePanel(event, panel);
      return;
    }

    const target = event.target as HTMLElement | null;
    if (target?.closest('input, textarea, select, [contenteditable="true"]')) return;
    if (event.ctrlKey || event.metaKey || !event.altKey || !event.shiftKey) return;
    if (event.key.toLowerCase() === 't') {
      this.toggleToc(event);
      event.preventDefault();
    } else if (event.key.toLowerCase() === 'j') {
      void this.openNextChapter();
      event.preventDefault();
    } else if (event.key.toLowerCase() === 'k' && this.previousChapter) {
      this.navigateToChapter(this.previousChapter);
      event.preventDefault();
    }
  }

  private async loadWork(workId: string, chapterId: string | null, token: number): Promise<void> {
    this.loadedWorkId = null;
    this.work.set(null);
    this.chapters.set([]);
    this.activeChapter.set(null);
    this.segments.set([]);
    this.nextManifestCursor.set(null);
    this.manifestError.set(null);
    this.contentError.set(null);
    this.manifestPageError.set(null);
    this.manifestState.set('loading');
    this.contentState.set('idle');
    this.progressSyncState.set('loading');
    if (!isUuid(workId)) {
      this.setManifestError(
        new ReaderApiError('INVALID_READER_QUERY', 400, 'INVALID_READER_QUERY'),
      );
      return;
    }

    let restoredProgress: ReaderProgress | null = null;
    try {
      restoredProgress = await this.api.getProgress(workId);
      if (token !== this.loadToken) return;
      this.progressVersions.set(workId, restoredProgress.version);
      this.progressSyncState.set('saved');
    } catch {
      // The chapter remains readable if progress storage is temporarily unavailable.
      this.progressVersions.set(workId, 0);
      this.progressSyncState.set('pending');
    }

    let cursor: string | undefined;
    try {
      for (let pageIndex = 0; pageIndex < maxManifestPages; pageIndex += 1) {
        const page = await this.api.getManifestPage(workId, cursor, manifestPageSize);
        if (token !== this.loadToken) return;
        if (!this.work()) this.work.set(page.work);
        this.appendManifestPage(page);
        this.nextManifestCursor.set(page.nextCursor);
        const target = chapterId
          ? this.chapters().find((chapter) => chapter.id === chapterId)
          : restoredProgress?.chapterId
            ? this.chapters().find(
                (chapter) =>
                  chapter.id === restoredProgress?.chapterId && chapter.accessState === 'readable',
              )
            : this.chapters().find((chapter) => chapter.accessState === 'readable');
        if (target) {
          this.loadedWorkId = workId;
          this.manifestState.set('ready');
          await this.activateChapter(
            target,
            token,
            chapterId === null && restoredProgress?.chapterId === target.id
              ? restoredProgress
              : null,
          );
          return;
        }
        if (!page.nextCursor) break;
        cursor = page.nextCursor;
      }
      if (token !== this.loadToken) return;
      this.loadedWorkId = workId;
      this.manifestState.set('ready');
      this.activeChapter.set(null);
      if (chapterId) {
        this.setManifestError(new ReaderApiError('NOT_FOUND', 404, 'NOT_FOUND'));
      } else {
        const fallback = this.chapters().find((chapter) => chapter.accessState === 'readable');
        if (fallback) {
          await this.activateChapter(fallback, token, null);
        } else {
          this.contentState.set('empty');
        }
      }
    } catch (error) {
      if (token === this.loadToken) this.setManifestError(asReaderApiError(error));
    }
  }

  private async activateRouteChapter(chapterId: string | null, token: number): Promise<void> {
    if (!chapterId) {
      const firstReadable = this.chapters().find((chapter) => chapter.accessState === 'readable');
      if (!firstReadable && this.nextManifestCursor()) {
        const newChapters = await this.loadNextManifestPage();
        const nextReadable = newChapters.find((chapter) => chapter.accessState === 'readable');
        if (nextReadable) await this.activateChapter(nextReadable, token);
        return;
      }
      if (firstReadable) await this.activateChapter(firstReadable, token);
      else this.contentState.set('empty');
      return;
    }
    let chapter = this.chapters().find((item) => item.id === chapterId);
    while (!chapter && this.nextManifestCursor() && !this.manifestBusy()) {
      const newChapters = await this.loadNextManifestPage();
      if (token !== this.loadToken) return;
      chapter = newChapters.find((item) => item.id === chapterId);
    }
    if (token !== this.loadToken) return;
    if (!chapter) {
      this.setManifestError(new ReaderApiError('NOT_FOUND', 404, 'NOT_FOUND'));
      return;
    }
    await this.activateChapter(chapter, token);
  }

  private async activateChapter(
    chapter: ReaderChapter,
    token: number,
    restoreProgress: ReaderProgress | null = null,
  ): Promise<void> {
    if (this.activeChapter() && this.activeChapter()?.id !== chapter.id) {
      void this.flushProgress();
    }
    this.activeChapter.set(chapter);
    this.segments.set([]);
    this.contentError.set(null);
    this.manifestError.set(null);
    if (chapter.accessState !== 'readable') {
      this.contentState.set('unavailable');
      this.focusReaderHeading();
      return;
    }
    this.contentState.set('loading');
    try {
      let cursor: string | undefined;
      let revisionId: string | undefined;
      let workId: string | null = null;
      const segments: ReaderSourceSegment[] = [];
      for (let pageIndex = 0; pageIndex < maxContentPages; pageIndex += 1) {
        const page: ReaderContentPage = await this.api.getContentPage(
          chapter.id,
          revisionId,
          cursor,
          contentPageSize,
        );
        if (token !== this.loadToken) return;
        if (workId && page.workId !== workId)
          throw new ReaderApiError('INVALID_READER_RESPONSE', 502, 'INVALID_READER_RESPONSE');
        if (revisionId && page.sourceRevisionId !== revisionId) {
          throw new ReaderApiError('INVALID_READER_RESPONSE', 502, 'INVALID_READER_RESPONSE');
        }
        workId = page.workId;
        revisionId = page.sourceRevisionId;
        segments.push(...page.segments);
        if (segments.length > maxSegmentsPerChapter) {
          throw new ReaderApiError(
            'READER_CONTENT_LIMIT_EXCEEDED',
            502,
            'READER_CONTENT_LIMIT_EXCEEDED',
          );
        }
        if (!page.nextSegmentCursor) break;
        if (pageIndex === maxContentPages - 1) {
          throw new ReaderApiError(
            'READER_CONTENT_LIMIT_EXCEEDED',
            502,
            'READER_CONTENT_LIMIT_EXCEEDED',
          );
        }
        cursor = page.nextSegmentCursor;
      }
      if (token !== this.loadToken) return;
      if (workId !== this.work()?.id) {
        throw new ReaderApiError('INVALID_READER_RESPONSE', 502, 'INVALID_READER_RESPONSE');
      }
      this.segments.set(segments);
      this.contentState.set('ready');
      if (restoreProgress?.chapterId === chapter.id) {
        this.restoreProgressPosition(restoreProgress);
      } else {
        this.focusReaderHeading();
      }
    } catch (error) {
      if (token !== this.loadToken) return;
      this.contentError.set(asReaderApiError(error));
      this.contentState.set('error');
    }
  }

  private appendManifestPage(page: ReaderManifestPage): ReaderChapter[] {
    const current = this.chapters();
    const ids = new Set(current.map((chapter) => chapter.id));
    const newChapters = page.chapters.filter((chapter) => !ids.has(chapter.id));
    this.chapters.set(
      [...current, ...newChapters].sort((left, right) => left.ordinal - right.ordinal),
    );
    return newChapters;
  }

  private navigateToChapter(chapter: ReaderChapter): void {
    const workId = this.work()?.id;
    if (!workId || chapter.accessState !== 'readable') return;
    this.closePanel(false);
    void this.router.navigate(['/app/reader', workId, chapter.id]);
  }

  private setManifestError(error: ReaderApiError): void {
    this.manifestError.set(error);
    this.manifestState.set('error');
    this.contentState.set('idle');
  }

  private openPanel(panelName: 'toc' | 'settings', event?: Event): void {
    if (!this.tocOpen() && !this.settingsOpen()) this.panelReturnFocus = this.getPanelOpener(event);
    this.tocOpen.set(panelName === 'toc');
    this.settingsOpen.set(panelName === 'settings');
  }

  private getPanelOpener(event?: Event): HTMLElement | null {
    const currentTarget = event?.currentTarget;
    if (currentTarget instanceof HTMLElement) return currentTarget;
    const eventTarget = event?.target;
    if (eventTarget instanceof HTMLElement) return eventTarget;
    return document.activeElement instanceof HTMLElement ? document.activeElement : null;
  }

  private openPanelElement(): HTMLElement | null {
    if (this.tocOpen()) return document.getElementById('account-reader-toc-panel');
    if (this.settingsOpen()) return document.getElementById('account-reader-settings-panel');
    return null;
  }

  private focusPanel(panel: HTMLElement): void {
    const initialTarget = panel.querySelector<HTMLElement>('[data-panel-initial-focus]') ?? panel;
    initialTarget.focus({ preventScroll: true });
  }

  private focusManifestPageResult(newChapters: ReaderChapter[]): void {
    const chapterId = newChapters.find((chapter) => chapter.accessState === 'readable')?.id;
    requestAnimationFrame(() => {
      if (!this.tocOpen()) return;
      const panel = document.getElementById('account-reader-toc-panel');
      const chapter = chapterId
        ? panel?.querySelector<HTMLElement>(`[data-reader-chapter-id="${chapterId}"]`)
        : null;
      const nextPage = panel?.querySelector<HTMLElement>('.account-reader-load-more');
      const closeButton = panel?.querySelector<HTMLElement>('[data-panel-initial-focus]');
      (chapter ?? nextPage ?? closeButton)?.focus({ preventScroll: true });
    });
  }

  private focusReaderHeading(): void {
    if (typeof window === 'undefined') return;
    requestAnimationFrame(() => {
      document
        .querySelector<HTMLElement>('.account-reader-content')
        ?.scrollTo({ top: 0, behavior: 'auto' });
      document.getElementById('account-reader-title')?.focus({ preventScroll: true });
    });
  }

  private restoreProgressPosition(progress: ReaderProgress): void {
    requestAnimationFrame(() => {
      const container = document.querySelector<HTMLElement>('.account-reader-content');
      if (!container) return;
      const anchorTarget = progress.segmentAnchor
        ? Array.from(container.querySelectorAll<HTMLElement>('[data-source-anchor]')).find(
            (element) => element.dataset['sourceAnchor'] === progress.segmentAnchor,
          )
        : undefined;
      if (anchorTarget) {
        container.scrollTop +=
          anchorTarget.getBoundingClientRect().top - container.getBoundingClientRect().top;
      } else {
        const availableScroll = Math.max(0, container.scrollHeight - container.clientHeight);
        container.scrollTop = progress.scrollRatio * availableScroll;
      }
      document.getElementById('account-reader-title')?.focus({ preventScroll: true });
    });
  }

  private async flushProgress(): Promise<void> {
    if (this.progressTimer !== null) {
      window.clearTimeout(this.progressTimer);
      this.progressTimer = null;
    }
    const workId = this.work()?.id;
    const chapter = this.activeChapter();
    if (workId && chapter && this.contentState() === 'ready') {
      const container = document.querySelector<HTMLElement>('.account-reader-content');
      if (container) {
        const availableScroll = Math.max(0, container.scrollHeight - container.clientHeight);
        const scrollRatio =
          availableScroll === 0
            ? 0
            : Math.min(1, Math.max(0, container.scrollTop / availableScroll));
        const containerTop = container.getBoundingClientRect().top;
        const visibleSegment = Array.from(
          container.querySelectorAll<HTMLElement>('[data-source-anchor]'),
        ).find((element) => element.getBoundingClientRect().bottom > containerTop);
        this.pendingProgress = {
          workId,
          chapterId: chapter.id,
          segmentAnchor: visibleSegment?.dataset['sourceAnchor'] || null,
          scrollRatio,
        };
      }
    }
    if (this.savingProgress || !this.pendingProgress) return;

    this.savingProgress = true;
    while (this.pendingProgress) {
      const position: PendingReaderProgress = this.pendingProgress;
      this.pendingProgress = null;
      this.progressSyncState.set('saving');
      try {
        const saved = await this.api.saveProgress(position.workId, {
          chapterId: position.chapterId,
          segmentAnchor: position.segmentAnchor,
          scrollRatio: position.scrollRatio,
          expectedVersion: this.progressVersions.get(position.workId) ?? 0,
        });
        this.progressVersions.set(position.workId, saved.version);
        this.progressSyncState.set('saved');
      } catch (error) {
        const apiError = asReaderApiError(error);
        if (apiError.status === 409 && apiError.latestProgress) {
          this.progressVersions.set(position.workId, apiError.latestProgress.version);
          this.progressSyncState.set('conflict');
        } else {
          this.pendingProgress ??= position;
          this.progressSyncState.set('pending');
        }
        if (!this.pendingProgress) break;
      }
    }
    this.savingProgress = false;
  }

  private keepTabFocusInsidePanel(event: KeyboardEvent, panel: HTMLElement): void {
    const focusable = Array.from(
      panel.querySelectorAll<HTMLElement>(
        'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
      ),
    );
    if (!focusable.length) {
      event.preventDefault();
      panel.focus({ preventScroll: true });
      return;
    }
    const first = focusable[0];
    const last = focusable[focusable.length - 1];
    const active = document.activeElement;
    if (event.shiftKey && (active === first || !panel.contains(active))) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && (active === last || !panel.contains(active))) {
      event.preventDefault();
      first.focus();
    }
  }
}

function asReaderApiError(error: unknown): ReaderApiError {
  return error instanceof ReaderApiError
    ? error
    : new ReaderApiError('NETWORK_OR_INVALID_RESPONSE', 0, null);
}

function isUuid(value: string): boolean {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value);
}
