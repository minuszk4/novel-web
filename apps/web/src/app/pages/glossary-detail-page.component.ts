import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import {
  GlossaryActiveRevision,
  GlossaryApiError,
  GlossaryApiService,
  GlossaryCatalogItem,
  GlossaryExportDownload,
  GlossaryExportFormat,
  GlossaryExportRevision,
} from '../core/glossary-api.service';
import { PolicyOnboardingService } from '../core/policy-onboarding.service';

type DetailState = 'guest' | 'loading' | 'ready' | 'error' | 'not-found';

@Component({
  selector: 'app-glossary-detail-page',
  imports: [RouterLink],
  templateUrl: './glossary-detail-page.component.html',
})
export class GlossaryDetailPageComponent {
  protected readonly policy = inject(PolicyOnboardingService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly api = inject(GlossaryApiService);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly state = signal<DetailState>('loading');
  protected readonly glossary = signal<GlossaryCatalogItem | null>(null);
  protected readonly revision = signal<GlossaryActiveRevision | null>(null);
  protected readonly errorMessage = signal<string | null>(null);
  protected readonly requiresSignIn = signal(false);
  protected readonly glossaryId = signal<string | null>(null);
  protected readonly exportFormat = signal<GlossaryExportFormat>('json');
  protected readonly exportRevision = signal<GlossaryExportRevision>('active');
  protected readonly exportState = signal<'idle' | 'downloading' | 'success' | 'error'>('idle');
  protected readonly exportStatusMessage = signal<string | null>(null);
  protected readonly exportErrorMessage = signal<string | null>(null);
  protected readonly exportRequiresSignIn = signal(false);
  protected readonly exportPolicyRequired = signal(false);
  protected readonly exportNeedsReload = signal(false);
  protected readonly canExportActive = computed(() => {
    const item = this.glossary();
    const revision = this.revision();
    return !!item?.activeRevisionId && revision?.activeRevisionId === item.activeRevisionId;
  });
  protected readonly canExportLatest = computed(() => {
    const item = this.glossary();
    return !!item?.canWrite && item.latestRevisionNumber !== null;
  });
  protected readonly canExport = computed(() => this.canExportActive() || this.canExportLatest());
  protected readonly selectedExportAvailable = computed(() =>
    this.exportRevision() === 'active' ? this.canExportActive() : this.canExportLatest(),
  );
  protected readonly spreadsheetSafetyNote = computed(() =>
    this.exportFormat() === 'json'
      ? 'JSON giữ nguyên chính xác các giá trị và là lựa chọn phù hợp để sao lưu hoặc nhập lại.'
      : 'Ô có thể bị hiểu là công thức được thêm dấu nháy đơn để mở an toàn bằng bảng tính; dùng JSON nếu cần nhập lại đúng nguyên văn.',
  );
  protected readonly draftIsAhead = computed(() => {
    const item = this.glossary();
    return !!item?.canWrite && (item.latestRevisionNumber ?? 0) > (item.activeRevisionNumber ?? 0);
  });
  protected readonly signInReturnTo = computed(() => {
    const id = this.glossaryId();
    return id ? '/app/glossaries/' + encodeURIComponent(id) : '/app/glossaries';
  });
  private loadToken = 0;
  private exportToken = 0;
  private readonly objectUrls = new Set<string>();
  private readonly revokeTimers = new Set<number>();

  constructor() {
    this.destroyRef.onDestroy(() => {
      this.exportToken++;
      this.clearDownloadResources();
    });
    this.route.paramMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((params) => {
      const id = params.get('glossaryId');
      this.exportToken++;
      this.clearDownloadResources();
      this.resetExportState();
      this.glossaryId.set(id);
      void this.load(id, ++this.loadToken);
    });
  }

  async retry(): Promise<void> {
    await this.load(this.glossaryId(), ++this.loadToken);
  }

  setExportFormat(event: Event): void {
    const value = (event.target as HTMLSelectElement | null)?.value;
    if (value === 'json' || value === 'csv' || value === 'tsv') {
      this.exportFormat.set(value);
      this.resetExportState();
    }
  }

  setExportRevision(event: Event): void {
    const value = (event.target as HTMLSelectElement | null)?.value;
    const item = this.glossary();
    if (value === 'active' && item?.activeRevisionId) {
      this.exportRevision.set('active');
      this.resetExportState();
    } else if (value === 'latest' && item?.canWrite && item.latestRevisionNumber !== null) {
      this.exportRevision.set('latest');
      this.resetExportState();
    }
  }

  async downloadExport(): Promise<void> {
    const item = this.glossary();
    const id = this.glossaryId();
    const selectedRevision = this.exportRevision();
    if (
      !item ||
      !id ||
      this.exportState() === 'downloading' ||
      !this.selectedExportAvailable() ||
      (selectedRevision === 'active' && !item.activeRevisionId) ||
      (selectedRevision === 'latest' && (!item.canWrite || item.latestRevisionNumber === null))
    ) {
      return;
    }

    const token = ++this.exportToken;
    const revisionNumber =
      selectedRevision === 'active' ? item.activeRevisionNumber : item.latestRevisionNumber;
    this.exportState.set('downloading');
    this.exportStatusMessage.set('Đang chuẩn bị tệp…');
    this.exportErrorMessage.set(null);
    this.exportRequiresSignIn.set(false);
    this.exportPolicyRequired.set(false);
    this.exportNeedsReload.set(false);
    try {
      const download = await this.api.exportRevision(id, this.exportFormat(), selectedRevision);
      if (token !== this.exportToken) return;
      if (download.etag !== `"v${item.version}"`) {
        throw new GlossaryApiError(
          'The glossary changed after this page was loaded.',
          409,
          'GLOSSARY_EXPORT_VERSION_STALE',
        );
      }
      this.startBrowserDownload(download);
      this.exportState.set('success');
      this.exportStatusMessage.set(`Đã tải xuống bản ${revisionNumber}.`);
    } catch (error) {
      if (token !== this.exportToken) return;
      this.exportState.set('error');
      this.exportStatusMessage.set(null);
      this.exportRequiresSignIn.set(error instanceof GlossaryApiError && error.status === 401);
      this.exportPolicyRequired.set(error instanceof GlossaryApiError && error.status === 403);
      this.exportNeedsReload.set(
        error instanceof GlossaryApiError && (error.status === 404 || error.status === 409),
      );
      this.exportErrorMessage.set(exportErrorMessage(error));
    }
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

  private async load(id: string | null, token: number): Promise<void> {
    this.glossary.set(null);
    this.revision.set(null);
    this.errorMessage.set(null);
    this.requiresSignIn.set(false);
    this.resetExportState();
    if (this.policy.status() !== 'accepted') {
      this.state.set('guest');
      return;
    }
    if (!id) {
      this.state.set('not-found');
      return;
    }

    this.state.set('loading');
    let item: GlossaryCatalogItem;
    try {
      item = await this.api.getGlossary(id);
    } catch (error) {
      if (token !== this.loadToken) return;
      if (error instanceof GlossaryApiError && error.status === 404) {
        this.state.set('not-found');
        return;
      }
      if (error instanceof GlossaryApiError && error.status === 403) {
        this.state.set('guest');
        await this.router.navigate(['/app/setup'], {
          queryParams: { returnUrl: this.signInReturnTo() },
        });
        return;
      }
      this.requiresSignIn.set(error instanceof GlossaryApiError && error.status === 401);
      this.errorMessage.set(detailErrorMessage(error));
      this.state.set('error');
      return;
    }
    if (token !== this.loadToken) return;
    this.glossary.set(item);
    if (!item.activeRevisionId) {
      this.chooseDefaultExportRevision();
      this.state.set('ready');
      return;
    }

    try {
      const active = await this.api.getActiveRevision(id);
      if (token !== this.loadToken) return;
      if (
        active.glossaryId !== item.id ||
        active.activeRevisionId !== item.activeRevisionId ||
        active.revisionNumber !== item.activeRevisionNumber ||
        active.version !== item.version
      ) {
        this.errorMessage.set(
          'Glossary vừa thay đổi khi đang tải. Tải lại để xem bản đang được áp dụng.',
        );
        this.state.set('error');
        return;
      }
      this.revision.set(active);
      this.chooseDefaultExportRevision();
      this.state.set('ready');
    } catch (error) {
      if (token !== this.loadToken) return;
      if (error instanceof GlossaryApiError && error.status === 403) {
        this.state.set('guest');
        this.revision.set(null);
        await this.router.navigate(['/app/setup'], {
          queryParams: { returnUrl: this.signInReturnTo() },
        });
        return;
      }
      this.requiresSignIn.set(error instanceof GlossaryApiError && error.status === 401);
      this.errorMessage.set(
        error instanceof GlossaryApiError && error.status === 404
          ? 'Bản đang áp dụng vừa thay đổi. Tải lại để đồng bộ chi tiết glossary.'
          : detailErrorMessage(error),
      );
      this.state.set('error');
    }
  }

  private chooseDefaultExportRevision(): void {
    if (this.canExportActive()) {
      this.exportRevision.set('active');
    } else if (this.canExportLatest()) {
      this.exportRevision.set('latest');
    } else {
      this.exportRevision.set('active');
    }
  }

  private startBrowserDownload(download: GlossaryExportDownload): void {
    const objectUrl = URL.createObjectURL(download.blob);
    this.objectUrls.add(objectUrl);
    const anchor = document.createElement('a');
    anchor.href = objectUrl;
    anchor.download = download.fileName;
    anchor.hidden = true;
    document.body.append(anchor);
    anchor.click();
    anchor.remove();
    const timer = window.setTimeout(() => {
      URL.revokeObjectURL(objectUrl);
      this.objectUrls.delete(objectUrl);
      this.revokeTimers.delete(timer);
    }, 1000);
    this.revokeTimers.add(timer);
  }

  private resetExportState(): void {
    this.exportState.set('idle');
    this.exportStatusMessage.set(null);
    this.exportErrorMessage.set(null);
    this.exportRequiresSignIn.set(false);
    this.exportPolicyRequired.set(false);
    this.exportNeedsReload.set(false);
  }

  private clearDownloadResources(): void {
    for (const timer of this.revokeTimers) window.clearTimeout(timer);
    this.revokeTimers.clear();
    for (const objectUrl of this.objectUrls) URL.revokeObjectURL(objectUrl);
    this.objectUrls.clear();
  }
}

function detailErrorMessage(error: unknown): string {
  if (!(error instanceof GlossaryApiError)) {
    return 'Chưa tải được glossary. Kiểm tra kết nối rồi thử lại.';
  }
  if (error.status === 401) return 'Phiên đăng nhập đã hết hạn. Hãy đăng nhập lại.';
  if (error.status === 403) return 'Tài khoản hiện không có quyền xem glossary này.';
  if (error.status === 429) return 'Bạn thao tác quá nhanh. Chờ một chút rồi thử lại.';
  if (error.status === 0 || error.status >= 500) {
    return 'Máy chủ chưa phản hồi. Thử tải lại sau ít phút.';
  }
  return 'Chưa tải được glossary. Kiểm tra kết nối rồi thử lại.';
}

function exportErrorMessage(error: unknown): string {
  if (!(error instanceof GlossaryApiError)) {
    return 'Chưa tải được tệp. Glossary vẫn không thay đổi; bạn có thể thử lại.';
  }
  if (error.status === 401) return 'Phiên đăng nhập đã hết hạn. Đăng nhập lại để tải tệp.';
  if (error.status === 403) return 'Cần xác nhận chính sách hiện hành trước khi xuất glossary.';
  if (error.status === 404) {
    return 'Bản đã chọn không còn khả dụng hoặc chưa có thuật ngữ. Tải lại trang để đồng bộ.';
  }
  if (error.status === 409) {
    return 'Glossary đã thay đổi sau khi tải trang. Tải lại để chọn phiên bản mới nhất.';
  }
  if (error.status === 0 || error.status >= 500) {
    return 'Máy chủ chưa chuẩn bị được tệp. Không có nội dung nào thay đổi; bạn có thể thử lại.';
  }
  return 'Chưa tải được tệp. Glossary vẫn không thay đổi; bạn có thể thử lại.';
}
