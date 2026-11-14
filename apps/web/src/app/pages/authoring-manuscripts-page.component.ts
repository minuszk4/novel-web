import { Component, ElementRef, ViewChild, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import {
  AuthoringApiError,
  AuthoringApiService,
  PrivateManuscriptListItem,
} from '../core/authoring-api.service';
import { PolicyOnboardingService } from '../core/policy-onboarding.service';

type ManuscriptListState = 'loading' | 'ready' | 'error';

@Component({
  selector: 'app-authoring-manuscripts-page',
  imports: [RouterLink],
  templateUrl: './authoring-manuscripts-page.component.html',
  styleUrl: './authoring-manuscripts-page.component.css',
})
export class AuthoringManuscriptsPageComponent {
  protected readonly policy = inject(PolicyOnboardingService);
  protected readonly pageState = signal<ManuscriptListState>(
    this.policy.status() === 'accepted' ? 'loading' : 'ready',
  );
  protected readonly manuscripts = signal<PrivateManuscriptListItem[]>([]);
  protected readonly nextCursor = signal<string | null>(null);
  protected readonly pageError = signal<AuthoringApiError | null>(null);
  protected readonly moreError = signal<AuthoringApiError | null>(null);
  protected readonly isLoadingMore = signal(false);
  protected readonly announcement = signal('');

  private readonly api = inject(AuthoringApiService);
  private loadToken = 0;
  private readonly consumedCursors = new Set<string>();

  @ViewChild('pageHeading')
  private set pageHeading(heading: ElementRef<HTMLHeadingElement> | undefined) {
    heading?.nativeElement.focus({ preventScroll: true });
  }

  constructor() {
    if (this.policy.status() === 'accepted') void this.loadFirstPage();
  }

  protected get recoveryUrl(): string | null {
    switch (this.pageError()?.status ?? this.moreError()?.status) {
      case 401:
        return '/sign-in';
      case 403:
        return '/app/setup';
      default:
        return null;
    }
  }

  protected get recoveryQueryParams(): Record<string, string> {
    if (this.recoveryUrl === '/sign-in') return { returnTo: '/app/authoring' };
    if (this.recoveryUrl === '/app/setup') return { returnUrl: '/app/authoring' };
    return {};
  }

  async retryFirstPage(): Promise<void> {
    await this.loadFirstPage();
  }

  async loadMore(): Promise<void> {
    const cursor = this.nextCursor();
    if (!cursor || this.isLoadingMore() || this.pageState() !== 'ready') return;
    if (this.consumedCursors.has(cursor)) {
      this.moreError.set(new AuthoringApiError(502, 'INVALID_AUTHORING_MANUSCRIPT_LIST_RESPONSE'));
      this.nextCursor.set(null);
      return;
    }

    const token = this.loadToken;
    this.moreError.set(null);
    this.isLoadingMore.set(true);
    try {
      const page = await this.api.listPrivateManuscripts(cursor, 25);
      if (token !== this.loadToken) return;
      const existingIds = new Set(this.manuscripts().map(({ id }) => id));
      if (page.items.some(({ id }) => existingIds.has(id))) {
        this.moreError.set(
          new AuthoringApiError(502, 'INVALID_AUTHORING_MANUSCRIPT_LIST_RESPONSE'),
        );
        this.nextCursor.set(null);
        return;
      }
      this.consumedCursors.add(cursor);
      this.manuscripts.update((items) => [...items, ...page.items]);
      if (page.nextCursor && this.consumedCursors.has(page.nextCursor)) {
        this.moreError.set(
          new AuthoringApiError(502, 'INVALID_AUTHORING_MANUSCRIPT_LIST_RESPONSE'),
        );
        this.nextCursor.set(null);
      } else {
        this.nextCursor.set(page.nextCursor);
      }
      this.announcement.set(`Đã tải ${this.manuscripts().length} bản thảo.`);
    } catch (error) {
      if (token === this.loadToken) this.moreError.set(toAuthoringError(error));
    } finally {
      if (token === this.loadToken) this.isLoadingMore.set(false);
    }
  }

  protected pageErrorMessage(error: AuthoringApiError): string {
    if (error.code === 'POLICY_ACCEPTANCE_REQUIRED') {
      return 'Hãy xem và xác nhận chính sách hiện hành để mở danh sách bản thảo.';
    }
    if (error.status === 401) return 'Phiên đăng nhập đã hết hạn. Hãy đăng nhập lại.';
    if (error.status === 403) return 'Tài khoản chưa thể mở danh sách. Hãy kiểm tra chính sách.';
    if (error.status === 400) return 'Trang danh sách không còn hợp lệ. Hãy tải lại từ đầu.';
    if (error.status === 502) return 'Máy chủ trả về danh sách không hợp lệ. Hãy thử tải lại.';
    if (error.status === 503) return 'Danh sách tạm thời chưa sử dụng được. Hãy thử lại sau.';
    return 'Chưa tải được danh sách. Kiểm tra kết nối rồi thử lại.';
  }

  protected createdDate(value: string): string {
    return new Intl.DateTimeFormat('vi-VN', { dateStyle: 'medium' }).format(new Date(value));
  }

  private async loadFirstPage(): Promise<void> {
    if (this.policy.status() !== 'accepted') return;
    const token = ++this.loadToken;
    this.pageState.set('loading');
    this.pageError.set(null);
    this.moreError.set(null);
    this.manuscripts.set([]);
    this.nextCursor.set(null);
    this.consumedCursors.clear();
    this.announcement.set('');
    try {
      const page = await this.api.listPrivateManuscripts(undefined, 25);
      if (token !== this.loadToken) return;
      this.manuscripts.set(page.items);
      this.nextCursor.set(page.nextCursor);
      this.pageState.set('ready');
      this.announcement.set(
        page.items.length === 0
          ? 'Chưa có bản thảo riêng tư nào.'
          : `Đã tải ${page.items.length} bản thảo.`,
      );
    } catch (error) {
      if (token !== this.loadToken) return;
      this.pageError.set(toAuthoringError(error));
      this.pageState.set('error');
    }
  }
}

function toAuthoringError(error: unknown): AuthoringApiError {
  return error instanceof AuthoringApiError ? error : new AuthoringApiError(0, null);
}
