import { Injectable, signal } from '@angular/core';
import { ImportFileMetadata, PrivateWork, RankRule } from './models';

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
  readonly cultivationEnabled = signal(false);
  readonly cultivationPaused = signal(false);
  readonly cultivationRanks = rankRules;
  readonly readerFontSize = signal(20);
  readonly readerTheme = signal<'light' | 'sepia' | 'dark'>('light');
  readonly analyticsEnabled = signal(false);
  readonly providerSharingEnabled = signal(false);
  readonly readerProgress = signal<Record<string, number>>({});
  readonly bookmarkedChapter = signal<string | null>(null);

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

    this.importedWork.set({
      id: `demo-${Date.now()}`,
      title: title.trim() || file.name.replace(/\.[^.]+$/, ''),
      author: author.trim() || 'Chưa có thông tin tác giả',
      sourceName: file.name,
      importedAt: new Date().toISOString(),
      visibility: 'private',
    });
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
