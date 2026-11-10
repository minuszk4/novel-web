import { Component, HostListener, computed, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { DemoStateService } from '../core/demo-state.service';
import { AccountLibraryCollection, AccountWorkState, LibraryApiError, LibraryApiService } from '../core/library-api.service';
import { LibraryReadingStatus } from '../core/models';
import { PolicyOnboardingService } from '../core/policy-onboarding.service';

type LibraryFilter = 'all' | LibraryReadingStatus;
type SyncPhase = 'idle' | 'loading' | 'ready' | 'error' | 'saving';

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
  protected readonly activeFilter = signal<LibraryFilter>('all');
  protected readonly collectionName = signal('');
  protected readonly collectionError = signal<string | null>(null);
  protected readonly workError = signal<string | null>(null);
  protected readonly pendingDeleteCollectionId = signal<string | null>(null);
  protected readonly accountCollections = signal<AccountLibraryCollection[]>([]);
  protected readonly collectionSync = signal<SyncPhase>('idle');
  protected readonly collectionSyncError = signal<string | null>(null);
  protected readonly workState = signal<AccountWorkState | null>(null);
  protected readonly workSync = signal<SyncPhase>('idle');
  protected readonly isOnline = signal(typeof navigator === 'undefined' || navigator.onLine);
  protected readonly isAccountMode = computed(() => this.policy.status() === 'accepted');
  protected readonly filters: { value: LibraryFilter; label: string }[] = [
    { value: 'all', label: 'Tất cả' },
    ...readingStatusOptions,
  ];
  protected readonly readingStatuses = readingStatusOptions;
  protected readonly collections = computed(() => {
    if (this.isAccountMode()) return this.accountCollections();
    return this.demo.libraryCollections().map((collection) => ({
      ...collection,
      workCount: this.demo.importedWork() && this.demo.isWorkInCollection(this.demo.importedWork()!.id, collection.id) ? 1 : 0,
    }));
  });
  protected readonly visibleWork = computed(() => {
    const work = this.demo.importedWork();
    if (!work) return null;
    const filter = this.activeFilter();
    return filter === 'all' || this.readingStatusFor(work.id) === filter ? work : null;
  });
  protected readonly canCreateCollection = computed(() => {
    if (this.isAccountMode()) {
      return this.collectionSync() === 'ready' && this.accountCollections().length < 12;
    }
    return this.demo.libraryCollections().length < 12;
  });

  constructor() {
    if (this.isAccountMode()) {
      void this.loadAccountCollections();
      const work = this.demo.importedWork();
      if (work && isUuid(work.id)) void this.loadWorkState(work.id);
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
    const work = this.demo.importedWork();
    if (!work) return 0;
    return filter === 'all' || this.readingStatusFor(work.id) === filter ? 1 : 0;
  }

  statusLabel(status: LibraryReadingStatus): string {
    return readingStatusOptions.find((option) => option.value === status)?.label ?? 'Đọc sau';
  }

  readingStatusFor(workId: string): LibraryReadingStatus {
    if (this.isAccountWork(workId)) {
      return this.workState()?.readingStatus ?? this.demo.readingStatusFor(workId);
    }
    return this.demo.readingStatusFor(workId);
  }

  isWorkInCollection(workId: string, collectionId: string): boolean {
    if (this.isAccountWork(workId)) {
      return this.workState()?.collectionIds.includes(collectionId) ?? false;
    }
    return this.demo.isWorkInCollection(workId, collectionId);
  }

  canToggleCollection(workId: string): boolean {
    if (!this.isAccountMode()) return !!this.demo.importedWork();
    return this.isAccountWork(workId) && this.workSync() === 'ready' && !this.isSavingWorkState();
  }

  isSavingWorkState(): boolean {
    return this.workSync() === 'saving';
  }

  setReadingStatus(event: Event, workId: string): void {
    const requested = (event.target as HTMLSelectElement).value as LibraryReadingStatus;
    if (!readingStatusOptions.some(({ value }) => value === requested)) return;

    if (!this.isAccountWork(workId)) {
      this.demo.setReadingStatus(workId, requested);
      return;
    }
    const state = this.workState();
    if (state) void this.saveWorkState(workId, { readingStatus: requested, collectionIds: state.collectionIds });
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
          this.collectionError.set(error.code === 'LIBRARY_COLLECTION_LIMIT'
            ? 'Bạn đã có đủ 12 bộ sưu tập.'
            : 'Tên này đã được dùng. Danh sách đã được tải lại.');
        } else if (isUncertainMutation(error)) {
          await this.loadAccountCollections();
          const saved = this.accountCollections().some((item) => item.name.toLocaleLowerCase('vi') === name.toLocaleLowerCase('vi'));
          if (saved) this.collectionName.set('');
          else this.collectionError.set(collectionErrorMessage(error, 'Không xác nhận được thay đổi. Tải lại danh sách trước khi thử lại.'));
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

  async confirmDeleteCollection(collection: Pick<AccountLibraryCollection, 'id' | 'name'>): Promise<void> {
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
        if (!deleted) this.collectionError.set(collectionErrorMessage(error, 'Không xóa được bộ sưu tập.'));
      }
      if (deleted) {
        this.accountCollections.update((items) => items.filter((item) => item.id !== collection.id));
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
    const state = this.workState();
    if (!state || this.isSavingWorkState()) return;
    const collectionIds = new Set(state.collectionIds);
    if (collectionIds.has(collectionId)) collectionIds.delete(collectionId);
    else collectionIds.add(collectionId);
    void this.saveWorkState(workId, { readingStatus: state.readingStatus, collectionIds: [...collectionIds] });
  }

  async retryCollections(): Promise<void> {
    await this.loadAccountCollections();
  }

  async retryWorkState(): Promise<void> {
    const work = this.demo.importedWork();
    if (work && isUuid(work.id) && this.isAccountMode()) await this.loadWorkState(work.id);
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

  private async loadWorkState(workId: string): Promise<void> {
    this.workSync.set('loading');
    this.workError.set(null);
    try {
      this.workState.set(await this.api.getWorkState(workId));
      this.workSync.set('ready');
    } catch (error) {
      this.workSync.set('error');
      this.workError.set(workErrorMessage(error));
    }
  }

  private async reloadCurrentWorkState(): Promise<void> {
    const work = this.demo.importedWork();
    if (work && this.isAccountWork(work.id)) await this.loadWorkState(work.id);
  }

  private async saveWorkState(workId: string, changes: { readingStatus: LibraryReadingStatus; collectionIds: string[] }): Promise<void> {
    const current = this.workState();
    if (!current || this.workSync() !== 'ready') return;
    this.workSync.set('saving');
    this.workError.set(null);
    try {
      this.workState.set(await this.api.replaceWorkState(workId, current.etag, changes));
      this.workSync.set('ready');
    } catch (error) {
      if (error instanceof LibraryApiError && error.status === 409 && error.latestState) {
        this.workState.set(error.latestState);
        this.workSync.set('ready');
        this.workError.set('Trạng thái đã đổi trên thiết bị khác. Đã tải phiên bản mới; hãy kiểm tra lại lựa chọn.');
      } else if (error instanceof LibraryApiError && (error.status === 0 || error.status >= 500)) {
        try {
          this.workState.set(await this.api.getWorkState(workId));
          this.workSync.set('ready');
          this.workError.set('Chưa xác nhận được thao tác. Đã tải trạng thái mới nhất; hãy kiểm tra trước khi thử lại.');
        } catch (reloadError) {
          this.workSync.set('error');
          this.workError.set(workErrorMessage(reloadError));
        }
      } else {
        this.workSync.set('error');
        this.workError.set(workErrorMessage(error));
      }
    }
  }

  isAccountWork(workId: string): boolean {
    const work = this.demo.importedWork();
    return this.isAccountMode() && !!work && work.id === workId && isUuid(workId);
  }
}

function isApiError(value: unknown): value is LibraryApiError {
  return value instanceof LibraryApiError;
}

function isUncertainMutation(error: unknown): boolean {
  return error instanceof LibraryApiError && (error.status === 0 || error.status >= 500);
}

function collectionErrorMessage(error: unknown, fallback: string): string {
  if (!(error instanceof LibraryApiError)) return fallback;
  if (error.status === 401 || error.code === 'UNAUTHENTICATED') return 'Phiên đăng nhập hết hạn. Đăng nhập lại rồi thử tải bộ sưu tập.';
  if (error.code === 'POLICY_ACCEPTANCE_REQUIRED') return 'Hãy xác nhận chính sách hiện hành trước khi sửa thư viện tài khoản.';
  if (error.code === 'POLICY_NOT_CONFIGURED') return 'Chính sách tài khoản chưa được cấu hình; chưa thể đồng bộ bộ sưu tập.';
  if (error.status === 0) return 'Không kết nối được máy chủ. Kiểm tra mạng rồi thử tải lại.';
  if (error.status >= 500) return 'Máy chủ chưa tải được dữ liệu. Thử lại sau ít phút.';
  if (error.code === 'INVALID_API_RESPONSE') return 'Máy chủ trả về dữ liệu không hợp lệ. Tải lại để kiểm tra.';
  return fallback;
}

function workErrorMessage(error: unknown): string {
  if (!(error instanceof LibraryApiError)) return 'Không tải được trạng thái đọc. Hãy thử lại.';
  if (error.status === 404) return 'Tác phẩm không còn trong thư viện tài khoản hoặc bạn không còn quyền truy cập.';
  if (error.status === 401 || error.code === 'UNAUTHENTICATED') return 'Phiên đăng nhập hết hạn. Đăng nhập lại để đồng bộ trạng thái đọc.';
  if (error.code === 'POLICY_ACCEPTANCE_REQUIRED') return 'Hãy xác nhận chính sách hiện hành trước khi sửa trạng thái đọc.';
  if (error.status === 0) return 'Không kết nối được máy chủ. Trạng thái chưa được xác nhận.';
  if (error.status >= 500) return 'Máy chủ chưa lưu được trạng thái đọc. Thử tải lại trước khi sửa tiếp.';
  return 'Không đồng bộ được trạng thái đọc. Hãy tải lại để thử lại.';
}

function isUuid(value: string): boolean {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value);
}
