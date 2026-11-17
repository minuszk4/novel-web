import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import {
  PublicCatalogApiError,
  PublicCatalogApiService,
  PublicWorkChapter,
  PublicWorkDetail,
} from '../core/public-catalog.service';
import { SystemStatusService } from '../core/system-status.service';

type DetailState = 'checking' | 'loading' | 'ready' | 'disabled' | 'unavailable' | 'error';

@Component({
  selector: 'app-public-work-detail-page',
  imports: [RouterLink],
  templateUrl: './public-work-detail-page.component.html',
})
export class PublicWorkDetailPageComponent {
  protected readonly state = signal<DetailState>('checking');
  protected readonly work = signal<PublicWorkDetail | null>(null);
  protected readonly chapters = signal<PublicWorkChapter[]>([]);
  protected readonly nextAfterOrdinal = signal<number | null>(null);
  protected readonly loadMoreError = signal<string | null>(null);
  protected readonly loadingMore = signal(false);

  private readonly api = inject(PublicCatalogApiService);
  private readonly systemStatus = inject(SystemStatusService);
  private readonly route = inject(ActivatedRoute);
  private readonly destroyRef = inject(DestroyRef);
  private workId: string | null = null;
  private requestToken = 0;

  constructor() {
    this.route.paramMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((params) => {
      this.workId = params.get('workId');
      void this.load();
    });
  }

  async retry(): Promise<void> {
    this.systemStatus.refresh();
    await this.load();
  }

  async loadMore(): Promise<void> {
    const workId = this.workId;
    const afterOrdinal = this.nextAfterOrdinal();
    if (!workId || afterOrdinal === null || this.loadingMore()) return;
    const token = this.requestToken;
    this.loadingMore.set(true);
    this.loadMoreError.set(null);
    try {
      const page = await this.api.listChapters(workId, afterOrdinal, 20);
      if (token !== this.requestToken || workId !== this.workId) return;
      const known = new Set(this.chapters().map((chapter) => chapter.ordinal));
      this.chapters.update((items) => [
        ...items,
        ...page.items.filter((chapter) => !known.has(chapter.ordinal)),
      ]);
      this.nextAfterOrdinal.set(page.nextAfterOrdinal);
    } catch (error) {
      if (token === this.requestToken) this.loadMoreError.set(this.errorMessage(error));
    } finally {
      if (token === this.requestToken) this.loadingMore.set(false);
    }
  }

  protected readReturnPath(): string {
    return this.workId ? '/app/works/' + encodeURIComponent(this.workId) : '/app/library';
  }

  private async load(): Promise<void> {
    const token = ++this.requestToken;
    const workId = this.workId;
    this.work.set(null);
    this.chapters.set([]);
    this.nextAfterOrdinal.set(null);
    this.loadMoreError.set(null);
    this.loadingMore.set(false);
    if (
      !workId ||
      !/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(workId)
    ) {
      this.state.set('unavailable');
      return;
    }

    this.state.set('checking');
    const status = await this.systemStatus.waitForStatus();
    if (token !== this.requestToken) return;
    if (!status) {
      this.state.set('error');
      return;
    }
    if (status.features.publicCatalogEnabled !== true) {
      this.state.set('disabled');
      return;
    }

    this.state.set('loading');
    try {
      const [work, page] = await Promise.all([
        this.api.getWork(workId),
        this.api.listChapters(workId, 0, 20),
      ]);
      if (token !== this.requestToken || workId !== this.workId) return;
      this.work.set(work);
      this.chapters.set(page.items);
      this.nextAfterOrdinal.set(page.nextAfterOrdinal);
      this.state.set('ready');
    } catch (error) {
      if (token !== this.requestToken) return;
      this.state.set(
        error instanceof PublicCatalogApiError && error.status === 404 ? 'unavailable' : 'error',
      );
    }
  }

  private errorMessage(error: unknown): string {
    if (!(error instanceof PublicCatalogApiError) || error.status === 0) {
      return 'Không kết nối được. Kiểm tra mạng rồi thử lại.';
    }
    if (error.status === 404) return 'Tác phẩm không còn trong danh mục công khai.';
    if (error.status === 429) {
      return error.retryAfterSeconds
        ? 'Bạn gửi yêu cầu quá nhanh. Hãy thử lại sau ' + error.retryAfterSeconds + ' giây.'
        : 'Bạn gửi yêu cầu quá nhanh. Hãy đợi một chút rồi thử lại.';
    }
    return error.status >= 500
      ? 'Danh mục đang tạm thời không khả dụng. Hãy thử lại sau.'
      : 'Chưa tải được mục lục. Hãy thử lại.';
  }
}
