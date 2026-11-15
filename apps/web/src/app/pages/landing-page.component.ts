import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import {
  PublicCatalogApiError,
  PublicCatalogApiService,
  PublicCatalogWork,
} from '../core/public-catalog.service';
import { SystemStatusService } from '../core/system-status.service';

type CatalogState = 'checking' | 'disabled' | 'loading' | 'ready' | 'error';

@Component({
  selector: 'app-landing-page',
  imports: [RouterLink],
  templateUrl: './landing-page.component.html',
})
export class LandingPageComponent {
  protected readonly catalogState = signal<CatalogState>('checking');
  protected readonly catalogItems = signal<PublicCatalogWork[]>([]);
  protected readonly query = signal('');
  protected readonly language = signal('');
  protected readonly nextCursor = signal<string | null>(null);
  protected readonly loadError = signal<string | null>(null);
  protected readonly filterError = signal<string | null>(null);
  protected readonly pageError = signal<string | null>(null);
  protected readonly announcement = signal('');
  protected readonly isLoadingMore = signal(false);

  private readonly api = inject(PublicCatalogApiService);
  private readonly systemStatus = inject(SystemStatusService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  private featureResolved = false;
  private featureEnabled = false;
  private requestToken = 0;
  private activeQuery = '';
  private activeLanguage = '';

  constructor() {
    this.route.queryParamMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((params) => {
      this.query.set(params.get('q') ?? '');
      this.language.set(params.get('language') ?? '');
      void this.loadCurrentFilters();
    });
  }

  setQuery(event: Event): void {
    this.query.set((event.target as HTMLInputElement).value);
  }

  setLanguage(event: Event): void {
    this.language.set((event.target as HTMLInputElement).value);
  }

  async applyFilters(event?: Event): Promise<void> {
    event?.preventDefault();
    const query = this.normalizeQuery(this.query());
    const language = this.language().trim().toLowerCase();
    if (
      [...query].length > 120 ||
      [...query].some((character) => /[\u0000-\u001f\u007f-\u009f]/u.test(character)) ||
      (language !== '' && !/^[a-z]{2,3}(?:-[a-z0-9]{2,8})*$/i.test(language)) ||
      language.length > 35
    ) {
      this.filterError.set('Kiểm tra lại từ khóa và mã ngôn ngữ nguồn rồi thử lại.');
      return;
    }

    this.filterError.set(null);
    this.query.set(query);
    this.language.set(language);
    this.loadError.set(null);
    if (query === this.activeQuery && language === this.activeLanguage) {
      await this.loadFirstPage(query, language, ++this.requestToken);
      return;
    }
    await this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { q: query || null, language: language || null },
    });
  }

  async clearFilters(): Promise<void> {
    this.query.set('');
    this.language.set('');
    await this.applyFilters();
  }

  async retryCatalog(): Promise<void> {
    this.loadError.set(null);
    if (!this.featureResolved || !this.featureEnabled) {
      this.featureResolved = false;
      this.systemStatus.refresh();
      await this.loadCurrentFilters();
      return;
    }
    await this.loadFirstPage(this.activeQuery, this.activeLanguage, ++this.requestToken);
  }

  async loadMore(): Promise<void> {
    const cursor = this.nextCursor();
    if (!cursor || this.isLoadingMore()) return;
    const token = this.requestToken;
    this.isLoadingMore.set(true);
    this.pageError.set(null);
    try {
      const page = await this.api.listWorks({
        query: this.activeQuery,
        language: this.activeLanguage,
        cursor,
        limit: 20,
      });
      if (token !== this.requestToken) return;
      const seen = new Set(this.catalogItems().map(({ id }) => id));
      this.catalogItems.update((items) => {
        const appended: PublicCatalogWork[] = [];
        for (const work of page.items) {
          if (seen.has(work.id)) continue;
          seen.add(work.id);
          appended.push(work);
        }
        return [...items, ...appended];
      });
      this.nextCursor.set(page.nextCursor);
      this.announcement.set(`${this.catalogItems().length} truyện đã được tải.`);
    } catch (error) {
      if (token !== this.requestToken) return;
      this.pageError.set(this.errorMessage(error));
    } finally {
      if (token === this.requestToken) this.isLoadingMore.set(false);
    }
  }

  protected readReturnPath(workId: string): string {
    return `/app/works/${encodeURIComponent(workId)}`;
  }

  private async loadCurrentFilters(): Promise<void> {
    const token = ++this.requestToken;
    this.loadError.set(null);
    this.filterError.set(null);
    const enabled = await this.ensureCatalogEnabled(token);
    if (!enabled || token !== this.requestToken) return;
    await this.loadFirstPage(
      this.normalizeQuery(this.query()),
      this.language().trim().toLowerCase(),
      token,
    );
  }

  private async ensureCatalogEnabled(token: number): Promise<boolean> {
    if (this.featureResolved) {
      if (!this.featureEnabled) this.catalogState.set('disabled');
      return this.featureEnabled;
    }
    this.catalogState.set('checking');
    const status = await this.systemStatus.waitForStatus();
    if (token !== this.requestToken) return false;
    if (!status) {
      this.catalogState.set('error');
      this.loadError.set('Không thể kiểm tra trạng thái danh mục. Hãy thử lại.');
      return false;
    }
    this.featureResolved = true;
    this.featureEnabled = status.features.publicCatalogEnabled === true;
    if (!this.featureEnabled) this.catalogState.set('disabled');
    return this.featureEnabled;
  }

  private async loadFirstPage(query: string, language: string, token: number): Promise<void> {
    this.activeQuery = query;
    this.activeLanguage = language;
    this.catalogState.set('loading');
    this.catalogItems.set([]);
    this.nextCursor.set(null);
    this.pageError.set(null);
    this.isLoadingMore.set(false);
    this.loadError.set(null);
    try {
      const page = await this.api.listWorks({ query, language, limit: 20 });
      if (token !== this.requestToken) return;
      this.catalogItems.set(page.items);
      this.nextCursor.set(page.nextCursor);
      this.catalogState.set('ready');
      this.announcement.set(
        page.items.length === 0
          ? 'Không tìm thấy truyện phù hợp.'
          : `${page.items.length} truyện đã được tải.`,
      );
    } catch (error) {
      if (token !== this.requestToken) return;
      if (
        error instanceof PublicCatalogApiError &&
        (error.code === 'PUBLIC_CATALOG_DISABLED' || error.status === 404)
      ) {
        this.featureEnabled = false;
        this.featureResolved = false;
        this.catalogState.set('disabled');
        return;
      }
      this.catalogState.set('ready');
      this.loadError.set(this.errorMessage(error));
    }
  }

  private normalizeQuery(value: string): string {
    return value.trim().normalize('NFKC').toLowerCase();
  }

  private errorMessage(error: unknown): string {
    if (!(error instanceof PublicCatalogApiError) || error.status === 0) {
      return 'Không kết nối được với danh mục. Kiểm tra mạng rồi thử lại.';
    }
    if (error.status === 400) return 'Bộ lọc không hợp lệ. Hãy kiểm tra từ khóa và ngôn ngữ nguồn.';
    if (error.status >= 500) return 'Danh mục đang tạm thời không khả dụng. Hãy thử lại sau.';
    return 'Chưa tải được danh mục. Hãy thử lại.';
  }
}
