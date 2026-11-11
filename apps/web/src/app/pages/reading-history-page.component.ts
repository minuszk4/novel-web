import { DatePipe } from '@angular/common';
import { Component, ElementRef, ViewChild, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ReaderApiError, ReaderApiService, ReaderHistoryItem } from '../core/reader-api.service';

type DeleteTarget = { type: 'work'; item: ReaderHistoryItem } | { type: 'all' };

@Component({
  selector: 'app-reading-history-page',
  imports: [DatePipe, RouterLink],
  templateUrl: './reading-history-page.component.html',
})
export class ReadingHistoryPageComponent {
  private readonly api = inject(ReaderApiService);
  @ViewChild('confirmDialog') private confirmDialog?: ElementRef<HTMLDialogElement>;
  @ViewChild('pageHeading') private pageHeading?: ElementRef<HTMLElement>;

  protected readonly phase = signal<'loading' | 'ready' | 'error'>('loading');
  protected readonly items = signal<ReaderHistoryItem[]>([]);
  protected readonly nextCursor = signal<string | null>(null);
  protected readonly loadingMore = signal(false);
  protected readonly deleting = signal(false);
  protected readonly errorMessage = signal<string | null>(null);
  protected readonly recovery = signal<'sign-in' | 'settings' | null>(null);
  protected readonly statusMessage = signal<string | null>(null);
  protected readonly pendingDelete = signal<DeleteTarget | null>(null);

  constructor() {
    void this.loadFirstPage();
  }

  protected async loadFirstPage(): Promise<void> {
    this.phase.set('loading');
    this.errorMessage.set(null);
    this.recovery.set(null);
    try {
      const page = await this.api.getReadingHistory();
      this.items.set(page.items);
      this.nextCursor.set(page.nextCursor);
      this.phase.set('ready');
    } catch (error) {
      this.setError(error);
      this.phase.set('error');
    }
  }

  protected async loadMore(): Promise<void> {
    const cursor = this.nextCursor();
    if (!cursor || this.loadingMore()) return;
    this.loadingMore.set(true);
    this.errorMessage.set(null);
    try {
      const page = await this.api.getReadingHistory(cursor);
      const knownIds = new Set(this.items().map(({ work }) => work.id));
      this.items.update((items) => [
        ...items,
        ...page.items.filter(({ work }) => !knownIds.has(work.id)),
      ]);
      this.nextCursor.set(page.nextCursor);
    } catch (error) {
      this.setError(error);
    } finally {
      this.loadingMore.set(false);
    }
  }

  protected requestDelete(item: ReaderHistoryItem): void {
    this.statusMessage.set(null);
    this.pendingDelete.set({ type: 'work', item });
    this.confirmDialog?.nativeElement.showModal();
  }

  protected requestClearAll(): void {
    this.statusMessage.set(null);
    this.pendingDelete.set({ type: 'all' });
    this.confirmDialog?.nativeElement.showModal();
  }

  protected cancelDelete(): void {
    this.pendingDelete.set(null);
  }

  protected async confirmDelete(): Promise<void> {
    const target = this.pendingDelete();
    if (!target || this.deleting()) return;
    this.deleting.set(true);
    this.errorMessage.set(null);
    try {
      if (target.type === 'work') {
        await this.api.deleteReadingHistoryWork(target.item.work.id);
      } else {
        await this.api.clearReadingHistory();
      }
      await this.refreshAfterDelete(target);
      this.statusMessage.set(
        target.type === 'all'
          ? 'Đã xóa lịch sử đọc.'
          : `Đã xóa “${target.item.work.title}” khỏi lịch sử.`,
      );
      this.confirmDialog?.nativeElement.close();
      this.pendingDelete.set(null);
      this.pageHeading?.nativeElement.focus();
    } catch (error) {
      await this.reconcileUncertainDelete(target, error);
    } finally {
      this.deleting.set(false);
    }
  }

  protected readRoute(item: ReaderHistoryItem): string[] {
    return ['/app/reader', item.work.id, item.chapter.id];
  }

  protected deleteTitle(): string {
    const target = this.pendingDelete();
    return target?.type === 'all' ? 'Xóa toàn bộ lịch sử đọc?' : 'Xóa mục này khỏi lịch sử đọc?';
  }

  protected deleteDescription(): string {
    const target = this.pendingDelete();
    if (target?.type === 'all') {
      return 'Thao tác này xóa mọi vị trí đọc đã lưu, kể cả vị trí quá 365 ngày không còn hiển thị. Tác phẩm vẫn còn trong thư viện. Nếu tiếp tục đọc, vị trí mới sẽ tạo lại mục lịch sử.';
    }
    return 'Mục lịch sử này sẽ bị xóa cùng vị trí đọc đã lưu. Tác phẩm vẫn còn trong thư viện. Nếu tiếp tục đọc, vị trí mới sẽ tạo lại mục lịch sử.';
  }

  protected pendingWorkTitle(): string | null {
    const target = this.pendingDelete();
    return target?.type === 'work' ? target.item.work.title : null;
  }

  private async refreshAfterDelete(target: DeleteTarget): Promise<void> {
    const page = await this.api.getReadingHistory();
    this.items.set(page.items);
    this.nextCursor.set(page.nextCursor);
    // A removed item may still be present on a later page; the API delete is authoritative.
    if (target.type === 'all') {
      this.items.set([]);
      this.nextCursor.set(null);
    }
  }

  private async reconcileUncertainDelete(target: DeleteTarget, error: unknown): Promise<void> {
    try {
      const page = await this.api.getReadingHistory();
      this.items.set(page.items);
      this.nextCursor.set(page.nextCursor);
      const deleteConfirmed =
        target.type === 'all' && page.items.length === 0 && page.nextCursor === null;
      if (deleteConfirmed) {
        this.statusMessage.set('Đã xóa lịch sử đọc.');
        this.confirmDialog?.nativeElement.close();
        this.pendingDelete.set(null);
        this.pageHeading?.nativeElement.focus();
        return;
      }
    } catch {
      // Preserve the dialog and report the original request error; a retry remains explicit.
    }
    this.setError(error);
    if (!(error instanceof ReaderApiError) || error.status === 0 || error.status >= 500) {
      this.errorMessage.set(
        'Chưa xác nhận được thao tác xóa. Danh sách đã được tải lại; kiểm tra trước khi thử lại.',
      );
    }
  }

  private setError(error: unknown): void {
    const apiError = error instanceof ReaderApiError ? error : null;
    this.recovery.set(
      apiError?.status === 401 ? 'sign-in' : apiError?.status === 403 ? 'settings' : null,
    );
    this.errorMessage.set(
      apiError?.status === 401
        ? 'Phiên đăng nhập đã hết hạn. Đăng nhập lại để xem lịch sử.'
        : apiError?.status === 403
          ? 'Tài khoản cần hoàn tất xác nhận chính sách để xem lịch sử.'
          : 'Không tải được lịch sử lúc này. Kiểm tra kết nối rồi thử lại.',
    );
  }
}
