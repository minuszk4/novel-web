import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import {
  GlossaryActiveRevision,
  GlossaryApiError,
  GlossaryApiService,
  GlossaryCatalogItem,
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
  protected readonly draftIsAhead = computed(() => {
    const item = this.glossary();
    return !!item?.canWrite && (item.latestRevisionNumber ?? 0) > (item.activeRevisionNumber ?? 0);
  });
  protected readonly signInReturnTo = computed(() => {
    const id = this.glossaryId();
    return id ? '/app/glossaries/' + encodeURIComponent(id) : '/app/glossaries';
  });
  private loadToken = 0;

  constructor() {
    this.route.paramMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((params) => {
      const id = params.get('glossaryId');
      this.glossaryId.set(id);
      void this.load(id, ++this.loadToken);
    });
  }

  async retry(): Promise<void> {
    await this.load(this.glossaryId(), ++this.loadToken);
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
      this.state.set('ready');
      return;
    }

    try {
      const active = await this.api.getActiveRevision(id);
      if (token !== this.loadToken) return;
      if (
        active.glossaryId !== item.id ||
        active.activeRevisionId !== item.activeRevisionId ||
        active.revisionNumber !== item.activeRevisionNumber
      ) {
        this.errorMessage.set(
          'Glossary vừa thay đổi khi đang tải. Tải lại để xem bản đang được áp dụng.',
        );
        this.state.set('error');
        return;
      }
      this.revision.set(active);
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
