import { Injectable, signal } from '@angular/core';
import { ImportFileMetadata, LibraryCollection, LibraryReadingStatus, PrivateWork, RankRule } from './models';

const LIBRARY_STORAGE_KEY = 'novel.demo.library.v1';
const readingStatuses = new Set<LibraryReadingStatus>([
  'reading',
  'want-to-read',
  'paused',
  'completed',
  'dropped',
]);

interface PersistedLibraryState {
  version: 1;
  importedWork: PrivateWork | null;
  readingStatusByWork: Record<string, LibraryReadingStatus>;
  collections: LibraryCollection[];
  collectionIdsByWork: Record<string, string[]>;
}

const rankRules: RankRule[] = [
  { key: 'mortal', name: 'Phàm nhân', threshold: 0, group: 'Nhập môn' },
  ...Array.from({ length: 9 }, (_, index) => ({
    key: `qi-${index + 1}`,
    name: `Luyện Khí tầng ${index + 1}`,
    threshold: (index + 1) * 100,
    group: 'Luyện Khí',
  })),
  { key: 'foundation', name: 'Trúc Cơ', threshold: 1_000, group: 'Tứ đại cảnh' },
  { key: 'golden-core', name: 'Kim Đan', threshold: 2_500, group: 'Tứ đại cảnh' },
  { key: 'nascent-soul', name: 'Nguyên Anh', threshold: 5_000, group: 'Tứ đại cảnh' },
  { key: 'spirit', name: 'Hóa Thần', threshold: 9_000, group: 'Thượng giới' },
  { key: 'void', name: 'Luyện Hư', threshold: 15_000, group: 'Thượng giới' },
  { key: 'integration', name: 'Hợp Thể', threshold: 23_000, group: 'Thượng giới' },
  { key: 'mahayana', name: 'Đại Thừa', threshold: 33_000, group: 'Thượng giới' },
  { key: 'tribulation', name: 'Độ Kiếp', threshold: 45_000, group: 'Thượng giới' },
];

@Injectable({ providedIn: 'root' })
export class DemoStateService {
  readonly termsAccepted = signal(false);
  readonly privacyAcknowledged = signal(false);
  readonly selectedFile = signal<ImportFileMetadata | null>(null);
  readonly rightsConfirmed = signal(false);
  readonly importedWork = signal<PrivateWork | null>(null);
  readonly readingStatusByWork = signal<Record<string, LibraryReadingStatus>>({});
  readonly libraryCollections = signal<LibraryCollection[]>([]);
  readonly collectionIdsByWork = signal<Record<string, string[]>>({});
  readonly libraryStorageAvailable = signal(true);
  readonly libraryStorageMessage = signal<string | null>(null);
  readonly cultivationEnabled = signal(false);
  readonly cultivationPaused = signal(false);
  readonly cultivationRanks = rankRules;
  readonly readerFontSize = signal(20);
  readonly readerTheme = signal<'light' | 'sepia' | 'dark'>('light');
  readonly analyticsEnabled = signal(false);
  readonly providerSharingEnabled = signal(false);
  readonly readerProgress = signal<Record<string, number>>({});
  readonly bookmarkedChapter = signal<string | null>(null);

  constructor() {
    this.restoreLibraryState();
  }

  acceptTerms(accepted: boolean): void {
    this.termsAccepted.set(accepted);
  }

  acknowledgePrivacy(acknowledged: boolean): void {
    this.privacyAcknowledged.set(acknowledged);
  }

  get canContinueSetup(): boolean {
    return this.termsAccepted() && this.privacyAcknowledged();
  }

  selectFile(file: ImportFileMetadata | null): void {
    this.selectedFile.set(file);
    this.rightsConfirmed.set(false);
  }

  confirmImportRights(confirmed: boolean): void {
    this.rightsConfirmed.set(confirmed);
  }

  get canReviewImport(): boolean {
    return !!this.selectedFile() && this.rightsConfirmed();
  }

  commitDemoImport(title: string, author: string): void {
    const file = this.selectedFile();
    if (!file || !this.rightsConfirmed()) return;

    const work: PrivateWork = {
      id: `demo-${Date.now()}`,
      title: title.trim() || file.name.replace(/\.[^.]+$/, ''),
      author: author.trim() || 'Chưa có thông tin tác giả',
      sourceName: file.name,
      importedAt: new Date().toISOString(),
      visibility: 'private',
    };
    this.importedWork.set(work);
    this.readingStatusByWork.set({ [work.id]: 'want-to-read' });
    this.collectionIdsByWork.set({ [work.id]: [] });
    this.persistLibraryState();
  }

  readingStatusFor(workId: string): LibraryReadingStatus {
    return this.readingStatusByWork()[workId] ?? 'want-to-read';
  }

  setReadingStatus(workId: string, status: LibraryReadingStatus): void {
    if (!readingStatuses.has(status)) return;
    this.readingStatusByWork.update((current) => ({ ...current, [workId]: status }));
    this.persistLibraryState();
  }

  createLibraryCollection(requestedName: string): boolean {
    const name = requestedName.trim();
    if (!name || name.length > 40 || this.libraryCollections().length >= 12
        || this.libraryCollections().some((collection) => collection.name.toLocaleLowerCase('vi')
          === name.toLocaleLowerCase('vi'))) {
      return false;
    }
    const id = `demo-collection-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
    this.libraryCollections.update((collections) => [...collections, { id, name }]);
    this.persistLibraryState();
    return true;
  }

  deleteLibraryCollection(collectionId: string): void {
    if (!this.libraryCollections().some((collection) => collection.id === collectionId)) return;
    this.libraryCollections.update((collections) => collections.filter((collection) => collection.id !== collectionId));
    this.collectionIdsByWork.update((assignments) => Object.fromEntries(
      Object.entries(assignments).map(([workId, collectionIds]) => [
        workId,
        collectionIds.filter((id) => id !== collectionId),
      ]),
    ));
    this.persistLibraryState();
  }

  toggleWorkCollection(workId: string, collectionId: string): void {
    if (!this.libraryCollections().some((collection) => collection.id === collectionId)) return;
    this.collectionIdsByWork.update((current) => {
      const assigned = new Set(current[workId] ?? []);
      if (assigned.has(collectionId)) assigned.delete(collectionId);
      else assigned.add(collectionId);
      return { ...current, [workId]: [...assigned] };
    });
    this.persistLibraryState();
  }

  isWorkInCollection(workId: string, collectionId: string): boolean {
    return this.collectionIdsByWork()[workId]?.includes(collectionId) ?? false;
  }

  private restoreLibraryState(): void {
    try {
      const stored = localStorage.getItem(LIBRARY_STORAGE_KEY);
      if (stored === null) return;
      const parsed: unknown = JSON.parse(stored);
      if (!isPersistedLibraryState(parsed)) {
        this.libraryStorageMessage.set('Dữ liệu demo cục bộ không hợp lệ; trạng thái thư viện chỉ còn trong phiên này.');
        return;
      }
      const collectionIds = new Set(parsed.collections.map((collection) => collection.id));
      this.importedWork.set(parsed.importedWork);
      this.readingStatusByWork.set(parsed.readingStatusByWork);
      this.libraryCollections.set(parsed.collections);
      this.collectionIdsByWork.set(Object.fromEntries(
        Object.entries(parsed.collectionIdsByWork).map(([workId, ids]) => [
          workId,
          [...new Set(ids.filter((id) => collectionIds.has(id)))],
        ]),
      ));
    } catch {
      this.libraryStorageAvailable.set(false);
      this.libraryStorageMessage.set('Trình duyệt không cho phép đọc dữ liệu demo đã lưu. Thay đổi sẽ chỉ còn trong phiên này.');
    }
  }

  private persistLibraryState(): void {
    const state: PersistedLibraryState = {
      version: 1,
      importedWork: this.importedWork(),
      readingStatusByWork: this.readingStatusByWork(),
      collections: this.libraryCollections(),
      collectionIdsByWork: this.collectionIdsByWork(),
    };
    try {
      localStorage.setItem(LIBRARY_STORAGE_KEY, JSON.stringify(state));
      this.libraryStorageAvailable.set(true);
      this.libraryStorageMessage.set(null);
    } catch {
      this.libraryStorageAvailable.set(false);
      this.libraryStorageMessage.set('Không lưu được vào trình duyệt. Thay đổi sẽ mất khi đóng hoặc tải lại trang.');
    }
  }

  toggleCultivation(): void {
    const enabling = !this.cultivationEnabled();
    this.cultivationEnabled.set(enabling);
    if (enabling) this.cultivationPaused.set(false);
  }

  toggleCultivationPause(): void {
    if (!this.cultivationEnabled()) return;
    this.cultivationPaused.update((paused) => !paused);
  }

  changeReaderFontSize(size: number): void {
    this.readerFontSize.set(Math.max(16, Math.min(28, size)));
  }

  setReaderTheme(theme: 'light' | 'sepia' | 'dark'): void {
    this.readerTheme.set(theme);
  }

  resetReaderPreferences(): void {
    this.readerFontSize.set(20);
    this.readerTheme.set('light');
  }

  saveReaderProgress(chapterId: string, percent: number): void {
    const bounded = Math.max(0, Math.min(100, Math.round(percent)));
    this.readerProgress.update((progress) => ({ ...progress, [chapterId]: bounded }));
  }

  progressFor(chapterId: string): number {
    return this.readerProgress()[chapterId] ?? 0;
  }

  toggleBookmark(chapterId: string): void {
    this.bookmarkedChapter.update((current) => (current === chapterId ? null : chapterId));
  }
}

function isPersistedLibraryState(value: unknown): value is PersistedLibraryState {
  if (!isRecord(value) || value['version'] !== 1) return false;
  const importedWork = value['importedWork'];
  const statuses = value['readingStatusByWork'];
  const collections = value['collections'];
  const assignments = value['collectionIdsByWork'];
  return (importedWork === null || isPrivateWork(importedWork))
    && isRecord(statuses)
    && Object.values(statuses).every((status) => typeof status === 'string' && readingStatuses.has(status as LibraryReadingStatus))
    && Array.isArray(collections)
    && collections.length <= 12
    && collections.every(isLibraryCollection)
    && isRecord(assignments)
    && Object.values(assignments).every((ids) => Array.isArray(ids) && ids.every((id) => typeof id === 'string'));
}

function isPrivateWork(value: unknown): value is PrivateWork {
  if (!isRecord(value)) return false;
  return typeof value['id'] === 'string'
    && typeof value['title'] === 'string'
    && typeof value['author'] === 'string'
    && typeof value['sourceName'] === 'string'
    && typeof value['importedAt'] === 'string'
    && value['visibility'] === 'private';
}

function isLibraryCollection(value: unknown): value is LibraryCollection {
  return isRecord(value)
    && typeof value['id'] === 'string'
    && typeof value['name'] === 'string'
    && value['name'].trim().length > 0
    && value['name'].length <= 40;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
