import { Component, DestroyRef, HostListener, computed, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { DemoStateService } from '../core/demo-state.service';
import {
  AccountLibraryCollection,
  AccountLibrarySummary,
  AccountWorkState,
  AccountWorkSummary,
  LibraryApiError,
  LibraryApiService,
} from '../core/library-api.service';
import { LibraryReadingStatus, PrivateWork } from '../core/models';
import { PolicyOnboardingService } from '../core/policy-onboarding.service';

type LibraryFilter = 'all' | LibraryReadingStatus;
type SyncPhase = 'idle' | 'loading' | 'ready' | 'error' | 'saving';
type LibraryWorkView = Pick<
  AccountWorkSummary,
  'id' | 'title' | 'author' | 'chapterCount' | 'visibility'
> & {
  details: string;
  addedAt: string;
  isAccountWork: boolean;
};

const readingStatusOptions: { value: LibraryReadingStatus; label: string }[] = [
  { value: 'reading', label: 'Đang đọc' },
  { value: 'want-to-read', label: 'Đọc sau' },
  { value: 'paused', label: 'Tạm dừng' },
  { value: 'completed', label: 'Hoàn thành' },
  { value: 'dropped', label: 'Đã bỏ' },
];

@Component({
  selector: 'app-library-page',
  imports: [DatePipe, RouterLink],
  templateUrl: './library-page.component.html',
})
export class LibraryPageComponent {
  protected readonly demo = inject(DemoStateService);
  protected readonly policy = inject(PolicyOnboardingService);
  private readonly api = inject(LibraryApiService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  private requestedLibraryId: string | null = null;
  protected readonly activeFilter = signal<LibraryFilter>('all');
  protected readonly collectionName = signal('');
  protected readonly collectionError = signal<string | null>(null);
  protected readonly pendingDeleteCollectionId = signal<string | null>(null);
  protected readonly accountCollections = signal<AccountLibraryCollection[]>([]);
  protected readonly collectionSync = signal<SyncPhase>('idle');
  protected readonly collectionSyncError = signal<string | null>(null);
  protected readonly accountLibraries = signal<AccountLibrarySummary[]>([]);
  protected readonly selectedLibraryId = signal<string | null>(null);
  protected readonly accountWorks = signal<AccountWorkSummary[]>([]);
  protected readonly nextWorkCursor = signal<string | null>(null);
  protected readonly catalogSync = signal<SyncPhase>('idle');
  protected readonly catalogError = signal<string | null>(null);
  protected readonly isLoadingMoreWorks = signal(false);
  protected readonly loadMoreError = signal<string | null>(null);
  protected readonly workStates = signal<Record<string, AccountWorkState>>({});
  protected readonly workStateSync = signal<Record<string, SyncPhase>>({});
  protected readonly workStateErrors = signal<Record<string, string>>({});
  protected readonly isOnline = signal(typeof navigator === 'undefined' || navigator.onLine);
  private readonly workStateLoadQueue: { workId: string; resolve: () => void }[] = [];
  private readonly workStateLoads = new Map<string, Promise<void>>();
  private activeWorkStateLoads = 0;
  protected readonly isAccountMode = computed(() => this.policy.status() === 'accepted');
  protected readonly filters: { value: LibraryFilter; label: string }[] = [
    { value: 'all', label: 'Tất cả' },
    ...readingStatusOptions,
  ];
  protected readonly collections = computed(() => {
    if (this.isAccountMode()) return this.accountCollections();
    return this.demo.libraryCollections().map((collection) => ({
      ...collection,
      workCount:
        this.demo.importedWork() &&
        this.demo.isWorkInCollection(this.demo.importedWork()!.id, collection.id)
          ? 1
          : 0,
    }));
  });
  protected readonly visibleWorks = computed<LibraryWorkView[]>(() => {
    const accountMode = this.isAccountMode();
    const works: LibraryWorkView[] = accountMode
      ? this.accountWorks().map((work) => ({
          id: work.id,
          title: work.title,
          author: work.author,
          chapterCount: work.chapterCount,
          visibility: work.visibility,
          details: `${work.language} · ${work.chapterCount} chương`,
          addedAt: work.createdAt,
          isAccountWork: true,
        }))
      : this.demo.importedWork()
        ? [demoWorkView(this.demo.importedWork()!)]
        : [];
    const filter = this.activeFilter();
    return works.filter((work) => filter === 'all' || this.readingStatusFor(work.id) === filter);
  });
  protected readonly canCreateCollection = computed(() => {
    if (this.isAccountMode()) {
      return this.collectionSync() === 'ready' && this.accountCollections().length < 12;
    }
    return this.demo.libraryCollections().length < 12;
  });

  constructor() {
    this.requestedLibraryId = this.route.snapshot.queryParamMap.get('libraryId');
    let initialQuery = true;
    this.route.queryParamMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((params) => {
      const libraryId = params.get('libraryId');
      if (initialQuery) {
        initialQuery = false;
        return;
      }
      if (libraryId !== this.requestedLibraryId) {
        this.requestedLibraryId = libraryId;
        if (this.isAccountMode()) void this.loadAccountCatalog();
      }
    });
    if (this.isAccountMode()) {
      void this.loadAccountCollections();
      void this.loadAccountCatalog();
    }
  }

  @HostListener('window:online')
  handleOnline(): void {
    this.isOnline.set(true);
  }

  @HostListener('window:offline')
  handleOffline(): void {
    this.isOnline.set(false);
  }

  countFor(filter: LibraryFilter): number {
    if (filter === 'all')
      return this.isAccountMode() ? this.accountWorks().length : Number(!!this.demo.importedWork());
    return this.isAccountMode()
      ? this.accountWorks().filter((work) => this.readingStatusFor(work.id) === filter).length
      : Number(
          !!this.demo.importedWork() &&
            this.readingStatusFor(this.demo.importedWork()!.id) === filter,
        );
  }

  statusLabel(status: LibraryReadingStatus): string {
    return readingStatusOptions.find((option) => option.value === status)?.label ?? 'Đọc sau';
  }

  filterLabel(filter: LibraryFilter): string {
    return filter === 'all' ? 'tất cả trạng thái' : this.statusLabel(filter);
  }

  readingStatusFor(workId: string): LibraryReadingStatus {
    if (this.isAccountWork(workId)) {
      return this.workStates()[workId]?.readingStatus ?? 'want-to-read';
    }
    return this.demo.readingStatusFor(workId);
  }

  isWorkInCollection(workId: string, collectionId: string): boolean {
    if (this.isAccountWork(workId)) {
      return this.workStates()[workId]?.collectionIds.includes(collectionId) ?? false;
    }
    return this.demo.isWorkInCollection(workId, collectionId);
  }

  canToggleCollection(workId: string): boolean {
    if (!this.isAccountMode()) return !!this.demo.importedWork();
    return (
      this.isAccountWork(workId) &&
      this.workStatePhase(workId) === 'ready' &&
      !this.isSavingWorkState(workId)
    );
  }

  workStatePhase(workId: string): SyncPhase {
    return this.workStateSync()[workId] ?? 'loading';
  }

  workStateError(workId: string): string | null {
    return this.workStateErrors()[workId] ?? null;
  }

  isSavingWorkState(workId?: string): boolean {
    const phases = this.workStateSync();
    return workId
      ? phases[workId] === 'saving'
      : Object.values(phases).some((phase) => phase === 'saving');
  }

  setReadingStatus(event: Event, workId: string): void {
    const status = (event.target as HTMLSelectElement).value as LibraryReadingStatus;
    if (!readingStatusOptions.some(({ value }) => value === status)) return;

    if (!this.isAccountWork(workId)) {
      this.demo.setReadingStatus(workId, status);
      return;
    }
    const state = this.workStates()[workId];
    if (state)
      void this.saveWorkState(workId, {
        readingStatus: status,
        collectionIds: state.collectionIds,
      });
  }

  updateCollectionName(event: Event): void {
    this.collectionName.set((event.target as HTMLInputElement).value);
    this.collectionError.set(null);
  }

  async createCollection(): Promise<void> {
    const name = this.collectionName().trim();
    const nameLength = [...name].length;
    if (!name) {
      this.collectionError.set('Nhập tên bộ sưu tập trước khi tạo.');
      return;
    }
    if (nameLength > 40) {
      this.collectionError.set('Tên bộ sưu tập tối đa 40 ký tự.');
      return;
    }
    if (!this.canCreateCollection()) {
      this.collectionError.set('Tải lại danh sách trước khi thử tạo bộ sưu tập.');
      return;
    }

    this.collectionError.set(null);
    if (this.isAccountMode()) {
      this.collectionSync.set('saving');
      try {
        const created = await this.api.createCollection(name);
        this.accountCollections.update((items) => [...items, created]);
        this.collectionSync.set('ready');
        this.collectionName.set('');
        this.collectionSyncError.set(null);
      } catch (error) {
        this.collectionSync.set('ready');
        if (isApiError(error) && error.status === 409) {
          await this.loadAccountCollections();
          this.collectionError.set(
            error.code === 'LIBRARY_COLLECTION_LIMIT'
              ? 'Bạn đã có đủ 12 bộ sưu tập.'
              : 'Tên này đã được dùng. Danh sách đã được tải lại.',
          );
        } else if (isUncertainMutation(error)) {
          await this.loadAccountCollections();
          const saved = this.accountCollections().some(
            (item) => item.name.toLocaleLowerCase('vi') === name.toLocaleLowerCase('vi'),
          );
          if (saved) this.collectionName.set('');
          else
            this.collectionError.set(
              collectionErrorMessage(
                error,
                'Không xác nhận được thay đổi. Tải lại danh sách trước khi thử lại.',
              ),
            );
        } else {
          this.collectionError.set(collectionErrorMessage(error, 'Không tạo được bộ sưu tập.'));
        }
      }
      return;
    }

    if (!this.demo.createLibraryCollection(name)) {
      this.collectionError.set('Tên bị trùng hoặc bạn đã đạt giới hạn 12 bộ sưu tập.');
      return;
    }
    this.collectionName.set('');
  }

  requestDeleteCollection(collectionId: string): void {
    this.pendingDeleteCollectionId.set(collectionId);
    this.collectionError.set(null);
  }

  cancelDeleteCollection(): void {
    this.pendingDeleteCollectionId.set(null);
  }

  async confirmDeleteCollection(
    collection: Pick<AccountLibraryCollection, 'id' | 'name'>,
  ): Promise<void> {
    if (this.pendingDeleteCollectionId() !== collection.id) return;
    this.collectionError.set(null);
    if (this.isAccountMode()) {
      this.collectionSync.set('saving');
      let deleted = false;
      try {
        await this.api.deleteCollection(collection.id);
        deleted = true;
      } catch (error) {
        if ((isApiError(error) && error.status === 404) || isUncertainMutation(error)) {
          await this.loadAccountCollections();
          deleted = !this.accountCollections().some((item) => item.id === collection.id);
        }
        if (!deleted)
          this.collectionError.set(collectionErrorMessage(error, 'Không xóa được bộ sưu tập.'));
      }
      if (deleted) {
        this.accountCollections.update((items) =>
          items.filter((item) => item.id !== collection.id),
        );
        this.collectionSync.set('ready');
        this.collectionSyncError.set(null);
        await this.reloadCurrentWorkState();
      } else if (this.collectionSync() === 'saving') {
        this.collectionSync.set('ready');
      }
    } else {
      this.demo.deleteLibraryCollection(collection.id);
    }
    this.pendingDeleteCollectionId.set(null);
  }

  toggleCollection(workId: string, collectionId: string): void {
    if (!this.isAccountWork(workId)) {
      this.demo.toggleWorkCollection(workId, collectionId);
      return;
    }
    const state = this.workStates()[workId];
    if (!state || this.isSavingWorkState(workId)) return;
    const collectionIds = new Set(state.collectionIds);
    if (collectionIds.has(collectionId)) collectionIds.delete(collectionId);
    else collectionIds.add(collectionId);
    void this.saveWorkState(workId, {
      readingStatus: state.readingStatus,
      collectionIds: [...collectionIds],
    });
  }

  async retryCollections(): Promise<void> {
    await this.loadAccountCollections();
  }

  async retryCatalog(): Promise<void> {
    await this.loadAccountCatalog();
  }

  async selectLibrary(event: Event): Promise<void> {
    const libraryId = (event.target as HTMLSelectElement).value;
    if (!this.accountLibraries().some((library) => library.id === libraryId)) return;
    this.selectedLibraryId.set(libraryId);
    this.requestedLibraryId = libraryId;
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { libraryId },
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
    await this.loadWorks(libraryId);
  }

  async loadMoreWorks(): Promise<void> {
    const libraryId = this.selectedLibraryId();
    const cursor = this.nextWorkCursor();
    if (!libraryId || !cursor || this.isLoadingMoreWorks()) return;
    this.isLoadingMoreWorks.set(true);
    this.loadMoreError.set(null);
    try {
      const page = await this.api.listWorks(libraryId, { cursor, limit: 25 });
      if (this.selectedLibraryId() !== libraryId) return;
      const loadedIds = new Set(this.accountWorks().map((work) => work.id));
      const newWorks = page.items.filter((work) => !loadedIds.has(work.id));
      this.accountWorks.update((items) => [...items, ...newWorks]);
      this.nextWorkCursor.set(page.nextCursor);
      void this.loadWorkStates(newWorks);
    } catch (error) {
      if (this.selectedLibraryId() === libraryId)
        this.loadMoreError.set(catalogErrorMessage(error));
    } finally {
      this.isLoadingMoreWorks.set(false);
    }
  }

  retryWorkState(workId: string): void {
    if (this.isAccountWork(workId)) void this.queueWorkStateLoad(workId);
  }

  private async loadAccountCatalog(): Promise<void> {
    this.catalogSync.set('loading');
    this.catalogError.set(null);
    this.accountWorks.set([]);
    this.nextWorkCursor.set(null);
    this.loadMoreError.set(null);
    this.workStates.set({});
    this.workStateSync.set({});
    this.workStateErrors.set({});
    try {
      const libraries: AccountLibrarySummary[] = [];
      let cursor: string | undefined;
      for (let pageNumber = 0; pageNumber < 10; pageNumber += 1) {
        const page = await this.api.listLibraries(cursor, 100);
        libraries.push(...page.items);
        cursor = page.nextCursor ?? undefined;
        if (!cursor) break;
      }
      this.accountLibraries.set(libraries);
      const selected = this.requestedLibraryId
        ? (libraries.find((library) => library.id === this.requestedLibraryId) ?? null)
        : (libraries.find((library) => library.isDefault) ?? libraries[0] ?? null);
      this.selectedLibraryId.set(selected?.id ?? null);
      if (!selected) {
        this.accountWorks.set([]);
        if (this.requestedLibraryId) {
          this.catalogError.set('Thư viện này không tồn tại hoặc bạn không còn quyền truy cập.');
          this.catalogSync.set('error');
          return;
        }
        this.catalogSync.set('ready');
        return;
      }
      await this.loadWorks(selected.id);
    } catch (error) {
      this.catalogSync.set('error');
      this.catalogError.set(catalogErrorMessage(error));
    }
  }

  private async loadWorks(libraryId: string): Promise<void> {
    this.catalogSync.set('loading');
    this.catalogError.set(null);
    this.accountWorks.set([]);
    this.nextWorkCursor.set(null);
    this.loadMoreError.set(null);
    this.workStates.set({});
    this.workStateSync.set({});
    this.workStateErrors.set({});
    try {
      const page = await this.api.listWorks(libraryId, { limit: 25 });
      if (this.selectedLibraryId() !== libraryId) return;
      this.accountWorks.set(page.items);
      this.nextWorkCursor.set(page.nextCursor);
      this.catalogSync.set('ready');
      void this.loadWorkStates(page.items);
    } catch (error) {
      if (this.selectedLibraryId() !== libraryId) return;
      this.catalogSync.set('error');
      this.catalogError.set(catalogErrorMessage(error));
    }
  }

  private async loadWorkStates(works: AccountWorkSummary[]): Promise<void> {
    const uniqueWorks = [...new Map(works.map((work) => [work.id, work])).values()];
    await Promise.all(uniqueWorks.map((work) => this.queueWorkStateLoad(work.id)));
  }

  private queueWorkStateLoad(workId: string): Promise<void> {
    const existing = this.workStateLoads.get(workId);
    if (existing) return existing;

    let resolve!: () => void;
    const completion = new Promise<void>((done) => {
      resolve = done;
    });
    this.workStateLoads.set(workId, completion);
    this.workStateLoadQueue.push({ workId, resolve });
    this.pumpWorkStateLoadQueue();
    return completion;
  }

  private pumpWorkStateLoadQueue(): void {
    while (this.activeWorkStateLoads < 4 && this.workStateLoadQueue.length > 0) {
      const next = this.workStateLoadQueue.shift()!;
      this.activeWorkStateLoads += 1;
      void this.fetchWorkState(next.workId).finally(() => {
        this.activeWorkStateLoads -= 1;
        this.workStateLoads.delete(next.workId);
        next.resolve();
        this.pumpWorkStateLoadQueue();
      });
    }
  }

  private async loadAccountCollections(): Promise<void> {
    this.collectionSync.set('loading');
    this.collectionSyncError.set(null);
    try {
      this.accountCollections.set(await this.api.listCollections());
      this.collectionSync.set('ready');
      this.collectionSyncError.set(null);
      this.collectionError.set(null);
    } catch (error) {
      this.collectionSync.set('error');
      const message = collectionErrorMessage(error, 'Không tải được bộ sưu tập của tài khoản.');
      this.collectionSyncError.set(message);
    }
  }

  private async fetchWorkState(workId: string): Promise<void> {
    this.setWorkStatePhase(workId, 'loading', null);
    try {
      const state = await this.api.getWorkState(workId);
      this.workStates.update((items) => ({ ...items, [workId]: state }));
      this.setWorkStatePhase(workId, 'ready', null);
    } catch (error) {
      this.setWorkStatePhase(workId, 'error', workErrorMessage(error));
    }
  }

  private async reloadCurrentWorkState(): Promise<void> {
    await this.loadWorkStates(this.accountWorks());
  }

  private async saveWorkState(
    workId: string,
    changes: { readingStatus: LibraryReadingStatus; collectionIds: string[] },
  ): Promise<void> {
    const current = this.workStates()[workId];
    if (!current || this.workStatePhase(workId) !== 'ready') return;
    this.setWorkStatePhase(workId, 'saving', null);
    try {
      const saved = await this.api.replaceWorkState(workId, current.etag, changes);
      this.workStates.update((items) => ({ ...items, [workId]: saved }));
      this.setWorkStatePhase(workId, 'ready', null);
    } catch (error) {
      if (error instanceof LibraryApiError && error.status === 409 && error.latestState) {
        this.workStates.update((items) => ({ ...items, [workId]: error.latestState! }));
        this.setWorkStatePhase(
          workId,
          'ready',
          'Trạng thái đã đổi trên thiết bị khác. Đã tải phiên bản mới; hãy kiểm tra lại lựa chọn.',
        );
      } else if (error instanceof LibraryApiError && (error.status === 0 || error.status >= 500)) {
        await this.queueWorkStateLoad(workId);
        if (this.workStatePhase(workId) === 'ready') {
          this.setWorkStatePhase(
            workId,
            'ready',
            'Chưa xác nhận được thao tác. Đã tải trạng thái mới nhất; hãy kiểm tra trước khi thử lại.',
          );
        }
      } else {
        this.setWorkStatePhase(workId, 'error', workErrorMessage(error));
      }
    }
  }

  private setWorkStatePhase(workId: string, phase: SyncPhase, message: string | null): void {
    this.workStateSync.update((items) => ({ ...items, [workId]: phase }));
    this.workStateErrors.update((items) => {
      if (message === null) {
        const remaining = { ...items };
        delete remaining[workId];
        return remaining;
      }
      return { ...items, [workId]: message };
    });
  }

  isAccountWork(workId: string): boolean {
    return (
      this.isAccountMode() &&
      isUuid(workId) &&
      this.accountWorks().some((work) => work.id === workId)
    );
  }
}

function demoWorkView(work: PrivateWork): LibraryWorkView {
  return {
    id: work.id,
    title: work.title,
    author: work.author,
    chapterCount: 0,
    visibility: 'private',
    details: `Tệp ${work.sourceName} · chưa có chương để đọc`,
    addedAt: work.importedAt,
    isAccountWork: false,
  };
}

function isApiError(value: unknown): value is LibraryApiError {
  return value instanceof LibraryApiError;
}

function isUncertainMutation(error: unknown): boolean {
  return error instanceof LibraryApiError && (error.status === 0 || error.status >= 500);
}

function collectionErrorMessage(error: unknown, fallback: string): string {
  if (!(error instanceof LibraryApiError)) return fallback;
  if (error.status === 401 || error.code === 'UNAUTHENTICATED')
    return 'Phiên đăng nhập hết hạn. Đăng nhập lại rồi thử tải bộ sưu tập.';
  if (error.code === 'POLICY_ACCEPTANCE_REQUIRED')
    return 'Hãy xác nhận chính sách hiện hành trước khi sửa thư viện tài khoản.';
  if (error.code === 'POLICY_NOT_CONFIGURED')
    return 'Chính sách tài khoản chưa được cấu hình; chưa thể đồng bộ bộ sưu tập.';
  if (error.status === 0) return 'Không kết nối được máy chủ. Kiểm tra mạng rồi thử tải lại.';
  if (error.status >= 500) return 'Máy chủ chưa tải được dữ liệu. Thử lại sau ít phút.';
  if (error.code === 'INVALID_API_RESPONSE')
    return 'Máy chủ trả về dữ liệu không hợp lệ. Tải lại để kiểm tra.';
  return fallback;
}

function catalogErrorMessage(error: unknown): string {
  if (!(error instanceof LibraryApiError)) return 'Không tải được thư viện tài khoản. Hãy thử lại.';
  if (error.status === 404) return 'Thư viện không còn khả dụng hoặc bạn không còn quyền truy cập.';
  if (error.status === 401 || error.code === 'UNAUTHENTICATED')
    return 'Phiên đăng nhập hết hạn. Đăng nhập lại rồi thử tải thư viện.';
  if (error.code === 'POLICY_ACCEPTANCE_REQUIRED')
    return 'Hãy xác nhận chính sách hiện hành trước khi xem thư viện tài khoản.';
  if (error.code === 'POLICY_NOT_CONFIGURED')
    return 'Chính sách tài khoản chưa được cấu hình; chưa thể tải thư viện.';
  if (error.status === 0) return 'Không kết nối được máy chủ. Kiểm tra mạng rồi thử tải lại.';
  if (error.status >= 500) return 'Máy chủ chưa tải được thư viện. Thử lại sau ít phút.';
  if (error.code === 'INVALID_API_RESPONSE')
    return 'Máy chủ trả về dữ liệu thư viện không hợp lệ. Tải lại để kiểm tra.';
  return 'Không tải được thư viện tài khoản. Hãy thử lại.';
}

function workErrorMessage(error: unknown): string {
  if (!(error instanceof LibraryApiError)) return 'Không tải được trạng thái đọc. Hãy thử lại.';
  if (error.status === 404)
    return 'Tác phẩm không còn trong thư viện tài khoản hoặc bạn không còn quyền truy cập.';
  if (error.status === 401 || error.code === 'UNAUTHENTICATED')
    return 'Phiên đăng nhập hết hạn. Đăng nhập lại để đồng bộ trạng thái đọc.';
  if (error.code === 'POLICY_ACCEPTANCE_REQUIRED')
    return 'Hãy xác nhận chính sách hiện hành trước khi sửa trạng thái đọc.';
  if (error.status === 0) return 'Không kết nối được máy chủ. Trạng thái chưa được xác nhận.';
  if (error.status >= 500)
    return 'Máy chủ chưa lưu được trạng thái đọc. Thử tải lại trước khi sửa tiếp.';
  return 'Không đồng bộ được trạng thái đọc. Hãy tải lại để thử lại.';
}

function isUuid(value: string): boolean {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value);
}
