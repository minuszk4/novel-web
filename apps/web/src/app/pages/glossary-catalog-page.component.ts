import { Component, computed, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import {
  GlossaryApiError,
  GlossaryApiService,
  GlossaryCatalogItem,
  GlossaryScope,
} from '../core/glossary-api.service';
import { PolicyOnboardingService } from '../core/policy-onboarding.service';

type CatalogState = 'guest' | 'loading' | 'ready' | 'error';

const scopeOptions: Array<{ value: GlossaryScope | ''; label: string }> = [
  { value: '', label: 'Mọi phạm vi' },
  { value: 'USER_GLOBAL', label: 'Cá nhân' },
  { value: 'LIBRARY', label: 'Thư viện' },
  { value: 'WORK', label: 'Tác phẩm' },
  { value: 'VOLUME', label: 'Quyển' },
  { value: 'CHAPTER', label: 'Chương' },
];

@Component({
  selector: 'app-glossary-catalog-page',
  imports: [RouterLink],
  templateUrl: './glossary-catalog-page.component.html',
})
export class GlossaryCatalogPageComponent {
  protected readonly policy = inject(PolicyOnboardingService);
  private readonly api = inject(GlossaryApiService);
  private readonly router = inject(Router);
  protected readonly state = signal<CatalogState>('loading');
  protected readonly items = signal<GlossaryCatalogItem[]>([]);
  protected readonly nextCursor = signal<string | null>(null);
  protected readonly queryText = signal('');
  protected readonly appliedQuery = signal('');
  protected readonly selectedScope = signal<GlossaryScope | ''>('');
  protected readonly errorMessage = signal<string | null>(null);
  protected readonly requiresSignIn = signal(false);
  protected readonly isLoadingMore = signal(false);
  protected readonly loadMoreError = signal<string | null>(null);
  protected readonly createOpen = signal(false);
  protected readonly createBusy = signal(false);
  protected readonly createCheckRequired = signal(false);
  protected readonly createError = signal<string | null>(null);
  protected readonly createName = signal('');
  protected readonly createSourceLanguage = signal('zh-Hans');
  protected readonly createTargetLanguage = signal('vi');
  protected readonly hasActiveFilter = computed(
    () => !!this.appliedQuery() || !!this.selectedScope(),
  );
  protected readonly createButtonLabel = computed(() =>
    this.createBusy() ? 'Đang tạo…' : 'Tạo glossary cá nhân',
  );
  protected readonly scopeOptions = scopeOptions;
  private listRequestToken = 0;

  constructor() {
    if (this.policy.status() === 'accepted') void this.loadFirstPage();
    else this.state.set('guest');
  }

  updateQuery(event: Event): void {
    this.queryText.set((event.target as HTMLInputElement).value.slice(0, 160));
  }

  updateScope(event: Event): void {
    const value = (event.target as HTMLSelectElement).value;
    const selected = scopeOptions.find((option) => option.value === value);
    this.selectedScope.set(selected?.value ?? '');
    void this.loadFirstPage();
  }

  async submitSearch(event: Event): Promise<void> {
    event.preventDefault();
    await this.loadFirstPage();
  }

  async loadFirstPage(): Promise<void> {
    const token = ++this.listRequestToken;
    if (this.policy.status() !== 'accepted') {
      this.state.set('guest');
      return;
    }
    this.state.set('loading');
    this.errorMessage.set(null);
    this.requiresSignIn.set(false);
    this.loadMoreError.set(null);
    this.isLoadingMore.set(false);
    this.items.set([]);
    this.nextCursor.set(null);
    const query = this.queryText().trim();
    this.appliedQuery.set(query);
    try {
      const page = await this.api.listGlossaries({
        limit: 25,
        query: query || undefined,
        scope: this.selectedScope() || undefined,
      });
      if (token !== this.listRequestToken) return;
      this.items.set(page.items);
      this.nextCursor.set(page.nextCursor);
      this.state.set('ready');
    } catch (error) {
      if (token !== this.listRequestToken) return;
      if (error instanceof GlossaryApiError && error.status === 403) {
        this.state.set('guest');
        await this.router.navigate(['/app/setup'], {
          queryParams: { returnUrl: '/app/glossaries' },
        });
        return;
      }
      this.requiresSignIn.set(error instanceof GlossaryApiError && error.status === 401);
      this.errorMessage.set(catalogErrorMessage(error));
      this.state.set('error');
    }
  }

  async loadMore(): Promise<void> {
    const cursor = this.nextCursor();
    if (!cursor || this.isLoadingMore() || this.state() !== 'ready') return;
    const token = this.listRequestToken;
    this.isLoadingMore.set(true);
    this.loadMoreError.set(null);
    try {
      const page = await this.api.listGlossaries({
        limit: 25,
        cursor,
        query: this.appliedQuery() || undefined,
        scope: this.selectedScope() || undefined,
      });
      if (token !== this.listRequestToken) return;
      const known = new Set(this.items().map((item) => item.id));
      this.items.update((items) => [...items, ...page.items.filter((item) => !known.has(item.id))]);
      this.nextCursor.set(page.nextCursor);
    } catch (error) {
      if (token !== this.listRequestToken) return;
      if (error instanceof GlossaryApiError && error.status === 403) {
        this.state.set('guest');
        await this.router.navigate(['/app/setup'], {
          queryParams: { returnUrl: '/app/glossaries' },
        });
        return;
      }
      this.requiresSignIn.set(error instanceof GlossaryApiError && error.status === 401);
      this.loadMoreError.set(catalogErrorMessage(error));
    } finally {
      if (token === this.listRequestToken) this.isLoadingMore.set(false);
    }
  }

  clearFilters(): void {
    this.queryText.set('');
    this.appliedQuery.set('');
    this.selectedScope.set('');
    void this.loadFirstPage();
  }

  toggleCreate(): void {
    this.createOpen.update((open) => !open);
    if (!this.createCheckRequired()) this.createError.set(null);
  }

  updateCreateName(event: Event): void {
    this.createName.set((event.target as HTMLInputElement).value.slice(0, 120));
    if (!this.createCheckRequired()) this.createError.set(null);
  }

  updateSourceLanguage(event: Event): void {
    this.createSourceLanguage.set((event.target as HTMLInputElement).value.slice(0, 35));
    if (!this.createCheckRequired()) this.createError.set(null);
  }

  updateTargetLanguage(event: Event): void {
    this.createTargetLanguage.set((event.target as HTMLInputElement).value.slice(0, 35));
    if (!this.createCheckRequired()) this.createError.set(null);
  }

  async createGlossary(event: Event): Promise<void> {
    event.preventDefault();
    if (this.createBusy() || this.policy.status() !== 'accepted') return;
    if (this.createCheckRequired()) {
      this.createError.set('Tải lại danh sách và kiểm tra kết quả trước khi gửi yêu cầu tạo mới.');
      return;
    }
    const name = this.createName().trim();
    const sourceLanguage = this.createSourceLanguage().trim();
    const targetLanguage = this.createTargetLanguage().trim();
    if (!name || [...name].length > 120) {
      this.createError.set('Tên glossary cần có từ 1 đến 120 ký tự.');
      return;
    }
    if ([...sourceLanguage].length < 2 || [...sourceLanguage].length > 35) {
      this.createError.set('Nhập mã ngôn ngữ nguồn, ví dụ zh-Hans.');
      return;
    }
    if ([...targetLanguage].length < 2 || [...targetLanguage].length > 35) {
      this.createError.set('Nhập mã ngôn ngữ đích, ví dụ vi.');
      return;
    }
    this.createBusy.set(true);
    this.createError.set(null);
    try {
      const created = await this.api.createUserGlossary({
        name,
        sourceLanguage,
        targetLanguage,
      });
      await this.router.navigate(['/app/glossaries', created.id]);
    } catch (error) {
      this.createError.set(createErrorMessage(error));
      this.createCheckRequired.set(isUncertainCreate(error));
    } finally {
      this.createBusy.set(false);
    }
  }

  async refreshAfterUncertainCreate(): Promise<void> {
    if (!this.createCheckRequired()) return;
    await this.loadFirstPage();
    if (this.state() === 'ready') {
      this.createCheckRequired.set(false);
      this.createError.set(
        'Danh sách đã được tải lại. Hãy kiểm tra glossary vừa tạo trước khi quyết định gửi lại.',
      );
    }
  }

  draftIsAhead(item: GlossaryCatalogItem): boolean {
    return item.canWrite && (item.latestRevisionNumber ?? 0) > (item.activeRevisionNumber ?? 0);
  }

  scopeLabel(scope: GlossaryScope): string {
    return (
      {
        USER_GLOBAL: 'Cá nhân',
        LIBRARY: 'Thư viện',
        WORK: 'Tác phẩm',
        VOLUME: 'Quyển',
        CHAPTER: 'Chương',
      }[scope] ?? 'Glossary'
    );
  }

  languageLabel(language: string): string {
    return (
      {
        vi: 'Tiếng Việt',
        en: 'Tiếng Anh',
        'zh-Hans': 'Tiếng Trung giản thể',
        'zh-Hant': 'Tiếng Trung phồn thể',
        ja: 'Tiếng Nhật',
        ko: 'Tiếng Hàn',
        fr: 'Tiếng Pháp',
        de: 'Tiếng Đức',
        es: 'Tiếng Tây Ban Nha',
      }[language] ?? language
    );
  }
}

function catalogErrorMessage(error: unknown): string {
  if (!(error instanceof GlossaryApiError)) {
    return 'Chưa tải được danh sách glossary. Kiểm tra kết nối rồi thử lại.';
  }
  if (error.status === 401) return 'Phiên đăng nhập đã hết hạn. Hãy đăng nhập lại.';
  if (error.status === 403) return 'Tài khoản chưa có quyền xem danh sách glossary này.';
  if (error.status === 429) return 'Bạn thao tác quá nhanh. Chờ một chút rồi thử lại.';
  return 'Chưa tải được danh sách glossary. Kiểm tra kết nối rồi thử lại.';
}

function createErrorMessage(error: unknown): string {
  if (!(error instanceof GlossaryApiError)) {
    return 'Chưa xác nhận được glossary đã tạo hay chưa. Hãy tải lại danh sách và kiểm tra trước khi tạo lại.';
  }
  if (error.status === 0 || error.status >= 500) {
    return 'Chưa xác nhận được glossary đã tạo hay chưa. Hãy tải lại danh sách và kiểm tra trước khi tạo lại.';
  }
  if (error.status === 401) return 'Phiên đăng nhập đã hết hạn. Hãy đăng nhập lại.';
  if (error.status === 403) return 'Tài khoản chưa được phép tạo glossary cá nhân.';
  if (error.status === 429) return 'Bạn tạo glossary quá nhanh. Chờ một chút rồi thử lại.';
  return 'Không thể tạo glossary với thông tin này. Hãy kiểm tra lại các trường.';
}

function isUncertainCreate(error: unknown): boolean {
  return !(error instanceof GlossaryApiError) || error.status === 0 || error.status >= 500;
}
