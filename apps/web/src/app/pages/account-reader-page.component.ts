import {
  Component,
  computed,
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
  ReaderAnnotation,
  ReaderAnnotationColor,
  ReaderBookmark,
  ReaderApiService,
  CreateReaderAnnotationRequest,
  ReaderChapter,
  ReaderContentPage,
  ReaderManifestPage,
  ReaderPreferences,
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
const defaultReaderPreferences: ReaderPreferences = {
  schemaVersion: 1,
  theme: 'light',
  fontFamily: 'serif',
  fontSize: 20,
  lineHeight: 1.8,
  contentWidth: 720,
};

type PendingReaderProgress = Omit<SaveReaderProgressRequest, 'expectedVersion'> & {
  workId: string;
};

interface ReaderSelectionDraft extends Omit<CreateReaderAnnotationRequest, 'note' | 'color'> {}

interface ReaderTextPiece {
  text: string;
  color: ReaderAnnotationColor | null;
  annotationId: string | null;
}

interface ReaderRenderedSegment extends ReaderSourceSegment {
  pieces: ReaderTextPiece[];
}

interface ReaderAnnotationConflict {
  annotationId: string;
  latest: ReaderAnnotation;
}

const annotationPageSize = 100;
const maxAnnotationPages = 20;

@Component({
  selector: 'app-account-reader-page',
  imports: [RouterLink],
  templateUrl: './account-reader-page.component.html',
})
export class AccountReaderPageComponent {
  protected readonly theme = signal<'light' | 'sepia' | 'dark'>('light');
  protected readonly fontFamily = signal<'serif' | 'sans'>('serif');
  protected readonly fontSize = signal(20);
  protected readonly lineHeight = signal(1.8);
  protected readonly contentWidth = signal(720);
  protected readonly work = signal<ReaderWork | null>(null);
  protected readonly chapters = signal<ReaderChapter[]>([]);
  protected readonly activeChapter = signal<ReaderChapter | null>(null);
  protected readonly segments = signal<ReaderSourceSegment[]>([]);
  protected readonly renderedSegments = computed(() => this.buildRenderedSegments());
  protected readonly sourceRevisionId = signal<string | null>(null);
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
  protected readonly bookmarksOpen = signal(false);
  protected readonly annotationsOpen = signal(false);
  protected readonly annotations = signal<ReaderAnnotation[]>([]);
  protected readonly chapterAnnotations = signal<ReaderAnnotation[]>([]);
  protected readonly annotationCursor = signal<string | null>(null);
  protected readonly annotationPanelState = signal<'idle' | 'loading' | 'ready' | 'error'>('idle');
  protected readonly annotationPageBusy = signal(false);
  protected readonly annotationError = signal<ReaderApiError | null>(null);
  protected readonly annotationMessage = signal('');
  protected readonly annotationDraft = signal<ReaderSelectionDraft | null>(null);
  protected readonly annotationDraftNote = signal('');
  protected readonly annotationDraftColor = signal<ReaderAnnotationColor>('amber');
  protected readonly annotationEdits = signal<
    Record<string, { note: string; color: ReaderAnnotationColor }>
  >({});
  protected readonly annotationEditStates = signal<
    Record<string, 'idle' | 'saving' | 'saved' | 'pending' | 'conflict'>
  >({});
  protected readonly annotationConflict = signal<ReaderAnnotationConflict | null>(null);
  protected readonly deletingAnnotationIds = signal<ReadonlySet<string>>(new Set());
  protected readonly clearingAnnotations = signal(false);
  protected readonly annotationColors: ReadonlyArray<{
    value: ReaderAnnotationColor;
    label: string;
  }> = [
    { value: 'amber', label: 'Hổ phách' },
    { value: 'mint', label: 'Ngọc lục bảo' },
    { value: 'sky', label: 'Xanh trời' },
    { value: 'rose', label: 'Hồng' },
  ];
  protected readonly bookmarks = signal<ReaderBookmark[]>([]);
  protected readonly nextBookmarkCursor = signal<string | null>(null);
  protected readonly bookmarkOpenRetry = signal<ReaderBookmark | null>(null);
  protected readonly bookmarkPanelState = signal<'idle' | 'loading' | 'ready' | 'error'>('idle');
  protected readonly bookmarkSyncState = signal<'idle' | 'saved' | 'saving' | 'pending'>('idle');
  protected readonly bookmarkError = signal<ReaderApiError | null>(null);
  protected readonly bookmarkMessage = signal('');
  protected readonly bookmarkPageBusy = signal(false);
  protected readonly deletingBookmarkIds = signal<ReadonlySet<string>>(new Set());
  protected readonly progressSyncState = signal<
    'loading' | 'saved' | 'saving' | 'pending' | 'conflict'
  >('loading');
  protected readonly preferencesSyncState = signal<'loading' | 'saved' | 'saving' | 'pending'>(
    'loading',
  );

  private readonly api = inject(ReaderApiService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  private loadedWorkId: string | null = null;
  private requestedWorkId: string | null = null;
  private requestedChapterId: string | null = null;
  private loadToken = 0;
  private panelReturnFocus: HTMLElement | null = null;
  private pendingBookmarkPosition: { chapterId: string; segmentAnchor: string | null } | null =
    null;
  private pendingTextSelection: ReaderSelectionDraft | null = null;
  private readonly annotationTimers = new Map<string, number>();
  private readonly savingAnnotationIds = new Set<string>();
  private readonly progressVersions = new Map<string, number>();
  private progressTimer: number | null = null;
  private pendingProgress: PendingReaderProgress | null = null;
  private savingProgress = false;
  private preferencesTimer: number | null = null;
  private pendingPreferences: ReaderPreferences | null = null;
  private savingPreferences = false;

  @ViewChild('tocPanel') private set tocPanel(panel: ElementRef<HTMLElement> | undefined) {
    if (panel) this.focusPanel(panel.nativeElement);
  }

  @ViewChild('settingsPanel') private set settingsPanel(
    panel: ElementRef<HTMLElement> | undefined,
  ) {
    if (panel) this.focusPanel(panel.nativeElement);
  }

  @ViewChild('bookmarksPanel') private set bookmarksPanel(
    panel: ElementRef<HTMLElement> | undefined,
  ) {
    if (panel) this.focusPanel(panel.nativeElement);
  }

  @ViewChild('annotationsPanel') private set annotationsPanel(
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
      this.bookmarksOpen.set(false);
      this.annotationsOpen.set(false);
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
      if (this.preferencesTimer !== null) window.clearTimeout(this.preferencesTimer);
      for (const timer of this.annotationTimers.values()) window.clearTimeout(timer);
      this.annotationTimers.clear();
      for (const annotation of this.annotations()) void this.flushAnnotationEdit(annotation.id);
      if (this.activeChapter()) void this.flushProgress();
      void this.flushPreferences();
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

  protected preferencesStatusLabel(): string {
    switch (this.preferencesSyncState()) {
      case 'loading':
        return 'Đang tải cài đặt…';
      case 'saving':
        return 'Đang lưu cài đặt…';
      case 'pending':
        return 'Cài đặt chưa đồng bộ';
      default:
        return 'Cài đặt đã đồng bộ';
    }
  }

  protected bookmarkStatusLabel(): string {
    switch (this.bookmarkSyncState()) {
      case 'saving':
        return 'Đang lưu dấu trang…';
      case 'pending':
        return 'Dấu trang chưa đồng bộ';
      case 'saved':
        return 'Dấu trang đã đồng bộ';
      default:
        return 'Dấu trang chưa tải';
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
    if (document.visibilityState === 'hidden') {
      void this.flushProgress();
      void this.flushPreferences();
      this.flushAllAnnotationEdits();
    }
  }

  @HostListener('window:pagehide')
  protected onPageHide(): void {
    void this.flushProgress();
    void this.flushPreferences();
    this.flushAllAnnotationEdits();
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

  protected async loadNextManifestPage(focusErrorRecovery = true): Promise<ReaderChapter[]> {
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
        if (focusErrorRecovery) {
          const status = this.manifestPageError()?.status;
          const focusTargetId =
            status === 401 || status === 403
              ? 'account-reader-page-recovery'
              : 'account-reader-retry-manifest-page';
          requestAnimationFrame(() => {
            document.getElementById(focusTargetId)?.focus({ preventScroll: true });
          });
        }
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

  protected toggleBookmarks(event?: Event): void {
    if (this.bookmarksOpen()) {
      this.closePanel();
      return;
    }
    this.openPanel('bookmarks', event);
    this.bookmarkMessage.set('');
    if (this.bookmarkPanelState() === 'idle') void this.loadBookmarks();
  }

  protected preserveTextSelection(event: PointerEvent): void {
    this.pendingTextSelection = this.readSelectionDraft();
    event.preventDefault();
  }

  protected toggleAnnotations(event?: Event): void {
    if (this.annotationsOpen()) {
      this.closePanel();
      return;
    }
    const selection = this.pendingTextSelection ?? this.readSelectionDraft();
    this.pendingTextSelection = null;
    if (selection) {
      this.annotationDraft.set(selection);
      this.annotationDraftNote.set('');
      this.annotationDraftColor.set('amber');
      this.annotationError.set(null);
    }
    this.openPanel('annotations', event);
    this.annotationMessage.set('');
    if (this.annotationPanelState() === 'idle') void this.loadAnnotations();
  }

  protected onAnnotationDraftNoteInput(event: Event): void {
    this.annotationDraftNote.set((event.target as HTMLTextAreaElement).value);
  }

  protected setAnnotationDraftColor(color: ReaderAnnotationColor): void {
    this.annotationDraftColor.set(color);
  }

  protected async saveAnnotationDraft(): Promise<void> {
    const draft = this.annotationDraft();
    const work = this.work();
    if (!draft || !work || !this.sourceRevisionId()) return;
    this.annotationError.set(null);
    this.annotationMessage.set('Đang lưu ghi chú và đánh dấu…');
    try {
      const saved = await this.api.createAnnotation(work.id, {
        ...draft,
        note: this.annotationDraftNote(),
        color: this.annotationDraftColor(),
      });
      if (work.id !== this.work()?.id) return;
      this.upsertAnnotation(saved);
      this.annotationDraft.set(null);
      this.annotationDraftNote.set('');
      window.getSelection()?.removeAllRanges();
      this.annotationMessage.set(
        'Đã lưu. Nếu đoạn này đã có đánh dấu, ghi chú trước đó được giữ nguyên.',
      );
    } catch (error) {
      const apiError = asReaderApiError(error);
      this.annotationError.set(apiError);
      this.annotationMessage.set(this.annotationStatusMessage(apiError));
    }
  }

  protected async retryAnnotations(): Promise<void> {
    this.annotationPanelState.set('idle');
    await this.loadAnnotations();
  }

  protected async loadMoreAnnotations(): Promise<void> {
    const workId = this.work()?.id;
    const cursor = this.annotationCursor();
    if (!workId || !cursor || this.annotationPageBusy()) return;
    this.annotationPageBusy.set(true);
    try {
      const page = await this.api.getAnnotations(workId, undefined, cursor, 50);
      if (workId !== this.work()?.id) return;
      const ids = new Set(this.annotations().map((item) => item.id));
      this.annotations.update((items) => [
        ...items,
        ...page.items.filter((item) => !ids.has(item.id)),
      ]);
      this.annotationCursor.set(page.nextCursor);
      this.annotationPanelState.set('ready');
      this.annotationError.set(null);
    } catch (error) {
      this.annotationError.set(asReaderApiError(error));
    } finally {
      this.annotationPageBusy.set(false);
    }
  }

  protected annotationNoteFor(annotation: ReaderAnnotation): string {
    return this.annotationEdits()[annotation.id]?.note ?? annotation.note;
  }

  protected annotationColorFor(annotation: ReaderAnnotation): ReaderAnnotationColor {
    return this.annotationEdits()[annotation.id]?.color ?? annotation.color;
  }

  protected onAnnotationNoteInput(annotation: ReaderAnnotation, event: Event): void {
    this.setAnnotationEdit(annotation, (event.target as HTMLTextAreaElement).value, undefined);
  }

  protected setSavedAnnotationColor(
    annotation: ReaderAnnotation,
    color: ReaderAnnotationColor,
  ): void {
    this.setAnnotationEdit(annotation, undefined, color);
  }

  protected annotationSaveLabel(annotation: ReaderAnnotation): string {
    const conflict = this.annotationConflict();
    if (conflict?.annotationId === annotation.id) return 'Cần giải quyết xung đột';
    switch (this.annotationEditStates()[annotation.id]) {
      case 'saving':
        return 'Đang lưu ghi chú…';
      case 'pending':
        return 'Chưa đồng bộ';
      case 'saved':
        return 'Đã lưu';
      default:
        return '';
    }
  }

  protected useLatestAnnotation(annotation: ReaderAnnotation): void {
    const conflict = this.annotationConflict();
    if (!conflict || conflict.annotationId !== annotation.id) return;
    this.upsertAnnotation(conflict.latest);
    this.annotationEdits.update(({ [annotation.id]: _discarded, ...rest }) => rest);
    this.annotationEditStates.update((states) => ({ ...states, [annotation.id]: 'saved' }));
    this.annotationConflict.set(null);
  }

  protected keepMyAnnotationDraft(annotation: ReaderAnnotation): void {
    const conflict = this.annotationConflict();
    if (!conflict || conflict.annotationId !== annotation.id) return;
    this.upsertAnnotation(conflict.latest);
    this.annotationConflict.set(null);
    this.annotationEditStates.update((states) => ({ ...states, [annotation.id]: 'pending' }));
    this.scheduleAnnotationEdit(annotation.id, 0);
  }

  protected async deleteAnnotation(annotation: ReaderAnnotation): Promise<void> {
    if (this.deletingAnnotationIds().has(annotation.id)) return;
    const confirmed = window.confirm('Xóa ghi chú và đánh dấu riêng tư này?');
    if (!confirmed) return;
    this.deletingAnnotationIds.update((items) => new Set(items).add(annotation.id));
    try {
      await this.api.deleteAnnotation(annotation.id);
      this.removeAnnotation(annotation.id);
      this.annotationMessage.set('Đã xóa ghi chú và đánh dấu.');
    } catch (error) {
      this.annotationError.set(asReaderApiError(error));
    } finally {
      this.deletingAnnotationIds.update((items) => {
        const next = new Set(items);
        next.delete(annotation.id);
        return next;
      });
    }
  }

  protected async clearAnnotations(): Promise<void> {
    const workId = this.work()?.id;
    if (!workId || this.clearingAnnotations() || !this.annotations().length) return;
    const confirmed = window.confirm(
      'Xóa tất cả ghi chú và đánh dấu của tác phẩm này trên tài khoản? Thao tác không thể hoàn tác.',
    );
    if (!confirmed) return;
    this.clearingAnnotations.set(true);
    try {
      await this.api.clearAnnotations(workId);
      this.annotations.set([]);
      this.chapterAnnotations.set([]);
      this.annotationEdits.set({});
      this.annotationEditStates.set({});
      this.annotationConflict.set(null);
      this.annotationCursor.set(null);
      this.annotationMessage.set('Đã xóa toàn bộ ghi chú và đánh dấu của tác phẩm.');
    } catch (error) {
      this.annotationError.set(asReaderApiError(error));
    } finally {
      this.clearingAnnotations.set(false);
    }
  }

  protected openAnnotationChapter(annotation: ReaderAnnotation): void {
    const chapter = this.chapters().find((item) => item.id === annotation.chapterId);
    if (!chapter || chapter.accessState !== 'readable') {
      this.annotationMessage.set(
        'Chương này hiện không còn quyền đọc. Bạn vẫn có thể xóa ghi chú.',
      );
      return;
    }
    if (chapter.id !== this.activeChapter()?.id) {
      this.navigateToChapter(chapter);
      return;
    }
    const element = Array.from(document.querySelectorAll<HTMLElement>('[data-source-anchor]')).find(
      (item) => item.dataset['sourceAnchor'] === annotation.startAnchor,
    );
    element?.scrollIntoView({ block: 'center', behavior: 'smooth' });
    this.closePanel(false);
  }

  protected retryBookmarks(): void {
    void this.loadBookmarks();
  }

  protected async loadMoreBookmarks(): Promise<void> {
    const workId = this.work()?.id;
    const cursor = this.nextBookmarkCursor();
    if (!workId || !cursor || this.bookmarkPageBusy()) return;
    this.bookmarkPageBusy.set(true);
    try {
      const page = await this.api.getBookmarks(workId, cursor, 50);
      if (workId !== this.work()?.id) return;
      const existing = new Set(this.bookmarks().map((bookmark) => bookmark.id));
      this.bookmarks.update((items) => [
        ...items,
        ...page.items.filter((item) => !existing.has(item.id)),
      ]);
      this.nextBookmarkCursor.set(page.nextCursor);
      this.bookmarkError.set(null);
      this.bookmarkPanelState.set('ready');
    } catch (error) {
      this.bookmarkError.set(asReaderApiError(error));
    } finally {
      this.bookmarkPageBusy.set(false);
    }
  }

  protected async saveCurrentBookmark(): Promise<void> {
    const work = this.work();
    const chapter = this.activeChapter();
    if (
      !work ||
      !chapter ||
      this.contentState() !== 'ready' ||
      this.bookmarkSyncState() === 'saving'
    ) {
      return;
    }
    const segmentAnchor = this.currentSegmentAnchor();
    const label = [...`Chương ${chapter.ordinal} · ${chapter.title || 'Chưa có tiêu đề'}`]
      .slice(0, 160)
      .join('');
    this.bookmarkSyncState.set('saving');
    this.bookmarkMessage.set('');
    try {
      const saved = await this.api.createBookmark(
        work.id,
        { chapterId: chapter.id, segmentAnchor, label },
        crypto.randomUUID(),
      );
      if (work.id !== this.work()?.id) return;
      this.bookmarks.update((items) => [saved, ...items.filter((item) => item.id !== saved.id)]);
      this.bookmarkPanelState.set('ready');
      this.bookmarkSyncState.set('saved');
      this.bookmarkMessage.set('Đã lưu vị trí đọc hiện tại.');
    } catch (error) {
      this.bookmarkSyncState.set('pending');
      this.bookmarkMessage.set(this.statusMessage(asReaderApiError(error)));
    }
  }

  protected async deleteBookmark(bookmark: ReaderBookmark): Promise<void> {
    if (this.deletingBookmarkIds().has(bookmark.id)) return;
    this.deletingBookmarkIds.update((items) => new Set(items).add(bookmark.id));
    this.bookmarkMessage.set('');
    try {
      await this.api.deleteBookmark(bookmark.id);
      this.bookmarks.update((items) => items.filter((item) => item.id !== bookmark.id));
      this.bookmarkSyncState.set('saved');
      this.bookmarkMessage.set('Đã xóa dấu trang.');
    } catch (error) {
      this.bookmarkMessage.set(this.statusMessage(asReaderApiError(error)));
    } finally {
      this.deletingBookmarkIds.update((items) => {
        const next = new Set(items);
        next.delete(bookmark.id);
        return next;
      });
    }
  }

  protected async openBookmark(bookmark: ReaderBookmark): Promise<void> {
    this.bookmarkMessage.set('');
    this.bookmarkError.set(null);
    this.bookmarkOpenRetry.set(null);
    let chapter = this.chapters().find((item) => item.id === bookmark.chapterId);
    for (
      let pageIndex = 0;
      !chapter && this.nextManifestCursor() && pageIndex < maxManifestPages;
      pageIndex += 1
    ) {
      const added = await this.loadNextManifestPage(false);
      chapter = added.find((item) => item.id === bookmark.chapterId);
      if (this.manifestPageError()) break;
    }
    if (this.manifestPageError()) {
      this.bookmarkError.set(this.manifestPageError());
      this.bookmarkOpenRetry.set(bookmark);
      return;
    }
    if (!chapter || chapter.accessState !== 'readable') {
      this.bookmarkMessage.set('Chương của dấu trang hiện không còn quyền đọc.');
      return;
    }
    const position = { chapterId: chapter.id, segmentAnchor: bookmark.segmentAnchor };
    this.closePanel(false);
    if (chapter.id === this.activeChapter()?.id && this.contentState() === 'ready') {
      this.restoreBookmarkPosition(position);
      return;
    }
    this.pendingBookmarkPosition = position;
    this.navigateToChapter(chapter);
  }

  protected retryOpenBookmark(): void {
    const bookmark = this.bookmarkOpenRetry();
    if (bookmark) void this.openBookmark(bookmark);
  }

  protected closePanel(restoreFocus = true): void {
    const returnFocus = this.panelReturnFocus;
    this.tocOpen.set(false);
    this.settingsOpen.set(false);
    this.bookmarksOpen.set(false);
    this.annotationsOpen.set(false);
    this.panelReturnFocus = null;
    if (restoreFocus && returnFocus?.isConnected) returnFocus.focus({ preventScroll: true });
  }

  protected changeFont(amount: number): void {
    this.fontSize.update((current) => Math.max(16, Math.min(28, current + amount)));
    this.queuePreferencesSave();
  }

  protected setTheme(value: 'light' | 'sepia' | 'dark'): void {
    this.theme.set(value);
    this.queuePreferencesSave();
  }

  protected setFontFamily(value: 'serif' | 'sans'): void {
    this.fontFamily.set(value);
    this.queuePreferencesSave();
  }

  protected setLineHeight(value: string): void {
    const parsed = Number(value);
    if (!Number.isFinite(parsed) || parsed < 1.2 || parsed > 2.2) return;
    this.lineHeight.set(Math.round(parsed * 10) / 10);
    this.queuePreferencesSave();
  }

  protected setContentWidth(value: string): void {
    const parsed = Number(value);
    if (!Number.isInteger(parsed) || parsed < 560 || parsed > 900) return;
    this.contentWidth.set(parsed);
    this.queuePreferencesSave();
  }

  protected resetPreferences(): void {
    this.applyReaderPreferences(defaultReaderPreferences);
    this.queuePreferencesSave();
  }

  protected retryPreferencesSave(): void {
    if (this.preferencesSyncState() === 'pending') void this.flushPreferences();
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
      this.manifestPageError()?.status === 403 ||
      this.bookmarkError()?.status === 403 ||
      this.annotationError()?.status === 403
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
    this.bookmarks.set([]);
    this.nextBookmarkCursor.set(null);
    this.bookmarkOpenRetry.set(null);
    this.bookmarkPanelState.set('idle');
    this.bookmarkSyncState.set('idle');
    this.bookmarkError.set(null);
    this.bookmarkMessage.set('');
    this.annotations.set([]);
    this.chapterAnnotations.set([]);
    this.annotationCursor.set(null);
    this.annotationPanelState.set('idle');
    this.annotationError.set(null);
    this.annotationMessage.set('');
    this.annotationDraft.set(null);
    this.annotationEdits.set({});
    this.annotationEditStates.set({});
    this.annotationConflict.set(null);
    this.sourceRevisionId.set(null);
    this.manifestPageError.set(null);
    this.manifestState.set('loading');
    this.contentState.set('idle');
    this.progressSyncState.set('loading');
    this.preferencesSyncState.set('loading');
    this.applyReaderPreferences(defaultReaderPreferences);
    if (!isUuid(workId)) {
      this.setManifestError(
        new ReaderApiError('INVALID_READER_QUERY', 400, 'INVALID_READER_QUERY'),
      );
      return;
    }

    const [progressResult, preferencesResult] = await Promise.allSettled([
      this.api.getProgress(workId),
      this.api.getReaderPreferences(),
    ]);
    if (token !== this.loadToken) return;
    let restoredProgress: ReaderProgress | null = null;
    if (progressResult.status === 'fulfilled') {
      restoredProgress = progressResult.value;
      this.progressVersions.set(workId, restoredProgress.version);
      this.progressSyncState.set('saved');
    } else {
      // The chapter remains readable if progress storage is temporarily unavailable.
      this.progressVersions.set(workId, 0);
      this.progressSyncState.set('pending');
    }
    if (preferencesResult.status === 'fulfilled') {
      this.applyReaderPreferences(preferencesResult.value);
      this.preferencesSyncState.set('saved');
    } else {
      // Keep defaults available if per-account display settings cannot be loaded.
      this.preferencesSyncState.set('pending');
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
    this.sourceRevisionId.set(null);
    this.chapterAnnotations.set([]);
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
      if (!revisionId || !workId) {
        throw new ReaderApiError('INVALID_READER_RESPONSE', 502, 'INVALID_READER_RESPONSE');
      }
      this.segments.set(segments);
      this.sourceRevisionId.set(revisionId);
      this.contentState.set('ready');
      void this.loadChapterAnnotations(workId, chapter.id, token);
      if (restoreProgress?.chapterId === chapter.id) {
        this.restoreProgressPosition(restoreProgress);
      } else if (this.pendingBookmarkPosition?.chapterId === chapter.id) {
        const position = this.pendingBookmarkPosition;
        this.pendingBookmarkPosition = null;
        this.restoreBookmarkPosition(position);
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

  private openPanel(
    panelName: 'toc' | 'settings' | 'bookmarks' | 'annotations',
    event?: Event,
  ): void {
    if (
      !this.tocOpen() &&
      !this.settingsOpen() &&
      !this.bookmarksOpen() &&
      !this.annotationsOpen()
    ) {
      this.panelReturnFocus = this.getPanelOpener(event);
    }
    this.tocOpen.set(panelName === 'toc');
    this.settingsOpen.set(panelName === 'settings');
    this.bookmarksOpen.set(panelName === 'bookmarks');
    this.annotationsOpen.set(panelName === 'annotations');
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
    if (this.bookmarksOpen()) return document.getElementById('account-reader-bookmarks-panel');
    if (this.annotationsOpen()) return document.getElementById('account-reader-annotations-panel');
    return null;
  }

  private async loadBookmarks(): Promise<void> {
    const workId = this.work()?.id;
    if (!workId) return;
    this.bookmarkPanelState.set('loading');
    this.bookmarkError.set(null);
    try {
      const page = await this.api.getBookmarks(workId, undefined, 50);
      if (workId !== this.work()?.id) return;
      this.bookmarks.set(page.items);
      this.nextBookmarkCursor.set(page.nextCursor);
      this.bookmarkPanelState.set('ready');
      this.bookmarkSyncState.set('saved');
    } catch (error) {
      if (workId !== this.work()?.id) return;
      this.bookmarkError.set(asReaderApiError(error));
      this.bookmarkPanelState.set('error');
      this.bookmarkSyncState.set('pending');
    }
  }

  private async loadAnnotations(): Promise<void> {
    const workId = this.work()?.id;
    if (!workId) return;
    this.annotationPanelState.set('loading');
    this.annotationError.set(null);
    try {
      const page = await this.api.getAnnotations(workId, undefined, undefined, 50);
      if (workId !== this.work()?.id) return;
      this.annotations.set(page.items);
      this.annotationCursor.set(page.nextCursor);
      this.annotationPanelState.set('ready');
    } catch (error) {
      if (workId !== this.work()?.id) return;
      this.annotationError.set(asReaderApiError(error));
      this.annotationPanelState.set('error');
    }
  }

  private async loadChapterAnnotations(
    workId: string,
    chapterId: string,
    token: number,
  ): Promise<void> {
    try {
      let cursor: string | undefined;
      const items: ReaderAnnotation[] = [];
      for (let pageIndex = 0; pageIndex < maxAnnotationPages; pageIndex += 1) {
        const page = await this.api.getAnnotations(workId, chapterId, cursor, annotationPageSize);
        if (token !== this.loadToken || chapterId !== this.activeChapter()?.id) return;
        items.push(...page.items);
        if (!page.nextCursor) {
          this.chapterAnnotations.set(items);
          this.annotationError.set(null);
          return;
        }
        cursor = page.nextCursor;
      }
      this.chapterAnnotations.set(items);
      this.annotationError.set(
        new ReaderApiError(
          'READER_ANNOTATION_LIMIT_REACHED',
          413,
          'READER_ANNOTATION_LIMIT_REACHED',
        ),
      );
    } catch (error) {
      if (token !== this.loadToken || chapterId !== this.activeChapter()?.id) return;
      this.chapterAnnotations.set([]);
      this.annotationError.set(asReaderApiError(error));
    }
  }

  private readSelectionDraft(): ReaderSelectionDraft | null {
    const chapter = this.activeChapter();
    const revisionId = this.sourceRevisionId();
    if (!chapter || !revisionId || this.contentState() !== 'ready') return null;
    const selection = window.getSelection();
    if (!selection || selection.isCollapsed || selection.rangeCount !== 1) return null;
    const range = selection.getRangeAt(0);
    const startElement = this.sourceSegmentForNode(range.startContainer);
    const endElement = this.sourceSegmentForNode(range.endContainer);
    if (!startElement || !endElement) return null;
    const sourceElements = Array.from(
      document.querySelectorAll<HTMLElement>('.account-reader-body [data-source-anchor]'),
    );
    const startIndex = sourceElements.indexOf(startElement);
    const endIndex = sourceElements.indexOf(endElement);
    if (startIndex < 0 || endIndex < startIndex || endIndex - startIndex + 1 > 10) return null;
    const startAnchor = startElement.dataset['sourceAnchor'];
    const endAnchor = endElement.dataset['sourceAnchor'];
    if (!startAnchor || !endAnchor) return null;
    try {
      const startOffset = this.codePointOffsetAt(
        startElement,
        range.startContainer,
        range.startOffset,
      );
      const endOffset = this.codePointOffsetAt(endElement, range.endContainer, range.endOffset);
      const quoteSnapshot = range.toString();
      if (!quoteSnapshot.trim() || [...quoteSnapshot].length > 2000) return null;
      const expectedQuote = sourceElements
        .slice(startIndex, endIndex + 1)
        .map((element, index) => {
          const points = Array.from(element.textContent ?? '');
          const from = index === 0 ? startOffset : 0;
          const to = index === endIndex - startIndex ? endOffset : points.length;
          return points.slice(from, to).join('');
        })
        .join('');
      if (quoteSnapshot !== expectedQuote || !expectedQuote) return null;
      return {
        chapterId: chapter.id,
        sourceRevisionId: revisionId,
        startAnchor,
        startOffset,
        endAnchor,
        endOffset,
        quoteSnapshot,
      };
    } catch {
      return null;
    }
  }

  private sourceSegmentForNode(node: Node): HTMLElement | null {
    const element = node instanceof Element ? node : node.parentElement;
    return element?.closest<HTMLElement>('.account-reader-body [data-source-anchor]') ?? null;
  }

  private codePointOffsetAt(segment: HTMLElement, node: Node, offset: number): number {
    const prefix = document.createRange();
    prefix.selectNodeContents(segment);
    prefix.setEnd(node, offset);
    return [...prefix.toString()].length;
  }

  private buildRenderedSegments(): ReaderRenderedSegment[] {
    const source = this.segments();
    const revisionId = this.sourceRevisionId();
    const annotations = this.chapterAnnotations().filter(
      (item) => item.anchorState === 'CURRENT' && item.chapterRevisionId === revisionId,
    );
    return source.map((segment, segmentIndex) => {
      const points = Array.from(segment.sourceText);
      const intervals: Array<{
        start: number;
        end: number;
        annotationId: string;
        color: ReaderAnnotationColor;
      }> = [];
      for (const annotation of annotations) {
        const startIndex = source.findIndex((item) => item.anchor === annotation.startAnchor);
        const endIndex = source.findIndex((item) => item.anchor === annotation.endAnchor);
        if (
          startIndex < 0 ||
          endIndex < startIndex ||
          segmentIndex < startIndex ||
          segmentIndex > endIndex
        ) {
          continue;
        }
        const start = segmentIndex === startIndex ? annotation.startOffset : 0;
        const end = segmentIndex === endIndex ? annotation.endOffset : points.length;
        if (start < end && start >= 0 && end <= points.length) {
          intervals.push({ start, end, annotationId: annotation.id, color: annotation.color });
        }
      }
      const pieces: ReaderTextPiece[] = [];
      for (let index = 0; index < points.length; index += 1) {
        const active = intervals.find((item) => item.start <= index && index < item.end);
        const color = active?.color ?? null;
        const annotationId = active?.annotationId ?? null;
        const last = pieces.at(-1);
        if (last && last.color === color && last.annotationId === annotationId) {
          last.text += points[index];
        } else {
          pieces.push({ text: points[index], color, annotationId });
        }
      }
      if (!pieces.length) pieces.push({ text: '', color: null, annotationId: null });
      return { ...segment, pieces };
    });
  }

  private setAnnotationEdit(
    annotation: ReaderAnnotation,
    note: string | undefined,
    color: ReaderAnnotationColor | undefined,
  ): void {
    const current = this.annotationEdits()[annotation.id] ?? {
      note: annotation.note,
      color: annotation.color,
    };
    const edit = { note: note ?? current.note, color: color ?? current.color };
    this.annotationEdits.update((edits) => ({ ...edits, [annotation.id]: edit }));
    this.annotationEditStates.update((states) => ({ ...states, [annotation.id]: 'pending' }));
    this.scheduleAnnotationEdit(annotation.id, 700);
  }

  private scheduleAnnotationEdit(annotationId: string, delayMs: number): void {
    const previous = this.annotationTimers.get(annotationId);
    if (previous !== undefined) window.clearTimeout(previous);
    this.annotationTimers.set(
      annotationId,
      window.setTimeout(() => {
        this.annotationTimers.delete(annotationId);
        void this.flushAnnotationEdit(annotationId);
      }, delayMs),
    );
  }

  private async flushAnnotationEdit(annotationId: string): Promise<void> {
    if (this.savingAnnotationIds.has(annotationId)) return;
    const edit = this.annotationEdits()[annotationId];
    const annotation = this.findAnnotation(annotationId);
    if (!edit || !annotation) return;
    if (edit.note === annotation.note && edit.color === annotation.color) {
      this.annotationEdits.update(({ [annotationId]: _discarded, ...rest }) => rest);
      this.annotationEditStates.update((states) => ({ ...states, [annotationId]: 'saved' }));
      return;
    }
    this.savingAnnotationIds.add(annotationId);
    this.annotationEditStates.update((states) => ({ ...states, [annotationId]: 'saving' }));
    try {
      const saved = await this.api.updateAnnotation(
        annotationId,
        annotation.version,
        edit.note,
        edit.color,
      );
      this.upsertAnnotation(saved);
      const currentEdit = this.annotationEdits()[annotationId];
      if (currentEdit?.note === edit.note && currentEdit.color === edit.color) {
        this.annotationEdits.update(({ [annotationId]: _discarded, ...rest }) => rest);
        this.annotationEditStates.update((states) => ({ ...states, [annotationId]: 'saved' }));
      } else {
        this.annotationEditStates.update((states) => ({ ...states, [annotationId]: 'pending' }));
      }
    } catch (error) {
      const apiError = asReaderApiError(error);
      if (apiError.status === 409 && apiError.latestAnnotation) {
        this.upsertAnnotation(apiError.latestAnnotation);
        this.annotationConflict.set({ annotationId, latest: apiError.latestAnnotation });
        this.annotationEditStates.update((states) => ({ ...states, [annotationId]: 'conflict' }));
      } else {
        this.annotationError.set(apiError);
        this.annotationEditStates.update((states) => ({ ...states, [annotationId]: 'pending' }));
      }
    } finally {
      this.savingAnnotationIds.delete(annotationId);
      const currentEdit = this.annotationEdits()[annotationId];
      const latest = this.findAnnotation(annotationId);
      if (
        currentEdit &&
        latest &&
        (currentEdit.note !== latest.note || currentEdit.color !== latest.color) &&
        this.annotationConflict()?.annotationId !== annotationId
      ) {
        this.scheduleAnnotationEdit(annotationId, 0);
      }
    }
  }

  private flushAllAnnotationEdits(): void {
    for (const timer of this.annotationTimers.values()) window.clearTimeout(timer);
    this.annotationTimers.clear();
    for (const annotationId of Object.keys(this.annotationEdits())) {
      void this.flushAnnotationEdit(annotationId);
    }
  }

  private findAnnotation(annotationId: string): ReaderAnnotation | null {
    return (
      this.annotations().find((item) => item.id === annotationId) ??
      this.chapterAnnotations().find((item) => item.id === annotationId) ??
      null
    );
  }

  private upsertAnnotation(annotation: ReaderAnnotation): void {
    const upsert = (items: ReaderAnnotation[]) => {
      const next = [annotation, ...items.filter((item) => item.id !== annotation.id)];
      return next.sort((left, right) => right.updatedAt.localeCompare(left.updatedAt));
    };
    this.annotations.update(upsert);
    if (annotation.chapterId === this.activeChapter()?.id) {
      this.chapterAnnotations.update(upsert);
    }
  }

  private removeAnnotation(annotationId: string): void {
    this.annotations.update((items) => items.filter((item) => item.id !== annotationId));
    this.chapterAnnotations.update((items) => items.filter((item) => item.id !== annotationId));
    const timer = this.annotationTimers.get(annotationId);
    if (timer !== undefined) window.clearTimeout(timer);
    this.annotationTimers.delete(annotationId);
    this.annotationEdits.update(({ [annotationId]: _discarded, ...rest }) => rest);
    this.annotationEditStates.update(({ [annotationId]: _discarded, ...rest }) => rest);
    if (this.annotationConflict()?.annotationId === annotationId) this.annotationConflict.set(null);
  }

  protected annotationStatusMessage(error: ReaderApiError): string {
    if (error.code === 'READER_ANNOTATION_ANCHOR_STALE') {
      return 'Nội dung chương vừa thay đổi. Hãy tải lại chương rồi chọn đoạn văn bản hiện tại.';
    }
    if (error.code === 'READER_ANNOTATION_LIMIT_REACHED') {
      return 'Chương có quá nhiều đánh dấu để tải trong một lần. Mở danh sách ghi chú để tiếp tục tải.';
    }
    return this.statusMessage(error);
  }

  private currentSegmentAnchor(): string | null {
    const container = document.querySelector<HTMLElement>('.account-reader-content');
    if (!container) return null;
    const segments = Array.from(container.querySelectorAll<HTMLElement>('[data-source-anchor]'));
    if (!segments.length) return null;
    const threshold =
      container.getBoundingClientRect().top + Math.min(100, container.clientHeight * 0.2);
    return (
      segments.find((segment) => segment.getBoundingClientRect().bottom > threshold)?.dataset[
        'sourceAnchor'
      ] ??
      segments.at(-1)?.dataset['sourceAnchor'] ??
      null
    );
  }

  private restoreBookmarkPosition(position: {
    chapterId: string;
    segmentAnchor: string | null;
  }): void {
    requestAnimationFrame(() => {
      const container = document.querySelector<HTMLElement>('.account-reader-content');
      if (!container || this.activeChapter()?.id !== position.chapterId) return;
      const heading = document.getElementById('account-reader-title');
      if (position.segmentAnchor === null) {
        container.scrollTo({ top: 0, behavior: 'auto' });
        heading?.focus({ preventScroll: true });
        this.bookmarkMessage.set('Đã mở đầu chương từ dấu trang.');
        return;
      }
      const target = Array.from(
        container.querySelectorAll<HTMLElement>('[data-source-anchor]'),
      ).find((element) => element.dataset['sourceAnchor'] === position.segmentAnchor);
      if (!target) {
        container.scrollTo({ top: 0, behavior: 'auto' });
        heading?.focus({ preventScroll: true });
        this.bookmarkMessage.set(
          'Đoạn đã đánh dấu không còn trong phiên bản chương hiện tại. Bạn đang ở đầu chương.',
        );
        return;
      }
      container.scrollTop +=
        target.getBoundingClientRect().top - container.getBoundingClientRect().top;
      target.tabIndex = -1;
      target.focus({ preventScroll: true });
      this.bookmarkMessage.set('Đã chuyển tới vị trí đã đánh dấu.');
    });
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

  private applyReaderPreferences(preferences: ReaderPreferences): void {
    this.theme.set(preferences.theme);
    this.fontFamily.set(preferences.fontFamily);
    this.fontSize.set(preferences.fontSize);
    this.lineHeight.set(preferences.lineHeight);
    this.contentWidth.set(preferences.contentWidth);
  }

  private currentReaderPreferences(): ReaderPreferences {
    return {
      schemaVersion: 1,
      theme: this.theme(),
      fontFamily: this.fontFamily(),
      fontSize: this.fontSize(),
      lineHeight: this.lineHeight(),
      contentWidth: this.contentWidth(),
    };
  }

  private queuePreferencesSave(): void {
    if (this.preferencesSyncState() === 'loading') return;
    this.pendingPreferences = this.currentReaderPreferences();
    this.preferencesSyncState.set('pending');
    if (this.preferencesTimer !== null) window.clearTimeout(this.preferencesTimer);
    this.preferencesTimer = window.setTimeout(() => {
      this.preferencesTimer = null;
      void this.flushPreferences();
    }, 500);
  }

  private async flushPreferences(): Promise<void> {
    if (this.preferencesTimer !== null) {
      window.clearTimeout(this.preferencesTimer);
      this.preferencesTimer = null;
    }
    if (this.savingPreferences || !this.pendingPreferences) return;
    const preferences = this.pendingPreferences;
    this.pendingPreferences = null;
    this.savingPreferences = true;
    this.preferencesSyncState.set('saving');
    let saved = false;
    try {
      const response = await this.api.saveReaderPreferences(preferences);
      if (!this.pendingPreferences) this.applyReaderPreferences(response);
      this.preferencesSyncState.set('saved');
      saved = true;
    } catch {
      this.pendingPreferences ??= preferences;
      this.preferencesSyncState.set('pending');
    } finally {
      this.savingPreferences = false;
    }
    if (saved && this.pendingPreferences) void this.flushPreferences();
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
