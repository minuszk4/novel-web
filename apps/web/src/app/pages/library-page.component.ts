import { Component, HostListener, computed, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { DemoStateService } from '../core/demo-state.service';
import { LibraryReadingStatus } from '../core/models';

type LibraryFilter = 'all' | LibraryReadingStatus;

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
  protected readonly activeFilter = signal<LibraryFilter>('all');
  protected readonly collectionName = signal('');
  protected readonly collectionError = signal<string | null>(null);
  protected readonly isOnline = signal(typeof navigator === 'undefined' || navigator.onLine);
  protected readonly filters: { value: LibraryFilter; label: string }[] = [
    { value: 'all', label: 'Tất cả' },
    ...readingStatusOptions,
  ];
  protected readonly readingStatuses = readingStatusOptions;
  protected readonly visibleWork = computed(() => {
    const work = this.demo.importedWork();
    if (!work) return null;
    const filter = this.activeFilter();
    return filter === 'all' || this.demo.readingStatusFor(work.id) === filter ? work : null;
  });

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
    return filter === 'all' || this.demo.readingStatusFor(work.id) === filter ? 1 : 0;
  }

  statusLabel(status: LibraryReadingStatus): string {
    return readingStatusOptions.find((option) => option.value === status)?.label ?? 'Đọc sau';
  }

  setReadingStatus(event: Event, workId: string): void {
    this.demo.setReadingStatus(workId, (event.target as HTMLSelectElement).value as LibraryReadingStatus);
  }

  updateCollectionName(event: Event): void {
    this.collectionName.set((event.target as HTMLInputElement).value);
    this.collectionError.set(null);
  }

  createCollection(): void {
    const name = this.collectionName().trim();
    if (!name) {
      this.collectionError.set('Nhập tên bộ sưu tập trước khi tạo.');
      return;
    }
    if (name.length > 40) {
      this.collectionError.set('Tên bộ sưu tập tối đa 40 ký tự.');
      return;
    }
    if (!this.demo.createLibraryCollection(name)) {
      this.collectionError.set('Tên bị trùng hoặc bạn đã đạt giới hạn 12 bộ sưu tập.');
      return;
    }
    this.collectionName.set('');
    this.collectionError.set(null);
  }

  toggleCollection(workId: string, collectionId: string): void {
    this.demo.toggleWorkCollection(workId, collectionId);
  }
}
