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
import {
  WorkSubscriptionApiError,
  WorkSubscriptionApiService,
  WorkSubscriptionReceipt,
} from '../core/work-subscription.service';

type DetailState = 'checking' | 'loading' | 'ready' | 'disabled' | 'unavailable' | 'error';
type SubscriptionState =
  | 'checking'
  | 'signed-out'
  | 'not-following'
  | 'following'
  | 'muted'
  | 'saving'
  | 'policy-required'
  | 'error';

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
  protected readonly subscriptionState = signal<SubscriptionState>('checking');
  protected readonly subscriptionMessage = signal<string | null>(null);

  private readonly api = inject(PublicCatalogApiService);
  private readonly subscriptionApi = inject(WorkSubscriptionApiService);
  private readonly systemStatus = inject(SystemStatusService);
  private readonly route = inject(ActivatedRoute);
  private readonly destroyRef = inject(DestroyRef);
  private workId: string | null = null;
  private subscriptionEtag: string | null = null;
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

  async retrySubscription(): Promise<void> {
    const workId = this.workId;
    if (!workId) return;
    await this.loadSubscription(workId, this.requestToken);
  }

  async follow(): Promise<void> {
    await this.changeNotifications(true);
  }

  async mute(): Promise<void> {
    await this.changeNotifications(false);
  }

  async unfollow(): Promise<void> {
    const workId = this.workId;
    const etag = this.subscriptionEtag;
    if (!workId || !etag || this.subscriptionState() === 'saving') return;
    const token = this.requestToken;
    this.subscriptionState.set('saving');
    this.subscriptionMessage.set(null);
    try {
      this.subscriptionEtag = await this.subscriptionApi.unfollow(workId, etag);
      if (token !== this.requestToken) return;
      this.subscriptionState.set('not-following');
      this.subscriptionMessage.set('Đã bỏ theo dõi truyện này.');
    } catch (error) {
      await this.reconcileSubscription(error, workId, token);
    }
  }

  protected readReturnPath(): string {
    return this.workId ? '/app/works/' + encodeURIComponent(this.workId) : '/app/library';
  }

  protected followReturnPath(): string {
    return this.workId ? '/works/' + encodeURIComponent(this.workId) : '/';
  }

  private async load(): Promise<void> {
    const token = ++this.requestToken;
    const workId = this.workId;
    this.work.set(null);
    this.chapters.set([]);
    this.nextAfterOrdinal.set(null);
    this.loadMoreError.set(null);
    this.loadingMore.set(false);
    this.subscriptionEtag = null;
    this.subscriptionState.set('checking');
    this.subscriptionMessage.set(null);
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
      await this.loadSubscription(workId, token);
    } catch (error) {
      if (token !== this.requestToken) return;
      this.state.set(
        error instanceof PublicCatalogApiError && error.status === 404 ? 'unavailable' : 'error',
      );
    }
  }

  private async changeNotifications(enabled: boolean): Promise<void> {
    const workId = this.workId;
    const etag = this.subscriptionEtag;
    if (!workId || !etag || this.subscriptionState() === 'saving') return;
    const token = this.requestToken;
    this.subscriptionState.set('saving');
    this.subscriptionMessage.set(null);
    try {
      const receipt = await this.subscriptionApi.setNotifications(workId, enabled, etag);
      if (token !== this.requestToken || workId !== this.workId) return;
      this.applySubscription(receipt);
      this.subscriptionMessage.set(
        enabled
          ? 'Đã lưu lựa chọn theo dõi truyện này.'
          : 'Đã lưu lựa chọn tạm tắt thông báo cho truyện này.',
      );
    } catch (error) {
      await this.reconcileSubscription(error, workId, token);
    }
  }

  private async loadSubscription(workId: string, token: number): Promise<boolean> {
    this.subscriptionState.set('checking');
    this.subscriptionMessage.set(null);
    try {
      const receipt = await this.subscriptionApi.get(workId);
      if (token !== this.requestToken || workId !== this.workId) return false;
      this.applySubscription(receipt);
      return true;
    } catch (error) {
      if (token !== this.requestToken || workId !== this.workId) return false;
      const apiError = this.toSubscriptionError(error);
      if (apiError?.status === 401) {
        this.subscriptionState.set('signed-out');
        return false;
      }
      if (apiError?.status === 403 && apiError.code === 'POLICY_ACCEPTANCE_REQUIRED') {
        this.subscriptionState.set('policy-required');
        return false;
      }
      if (apiError?.status === 404) {
        this.work.set(null);
        this.chapters.set([]);
        this.state.set('unavailable');
        this.subscriptionState.set('error');
        return false;
      }
      this.subscriptionState.set('error');
      this.subscriptionMessage.set(this.subscriptionErrorMessage(apiError));
      return false;
    }
  }

  private async reconcileSubscription(
    error: unknown,
    workId: string,
    token: number,
  ): Promise<void> {
    const apiError = this.toSubscriptionError(error);
    if (apiError?.status === 401) {
      this.subscriptionState.set('signed-out');
      this.subscriptionMessage.set(
        'Phiên đăng nhập đã hết hạn. Hãy đăng nhập lại để cập nhật theo dõi.',
      );
      return;
    }
    if (apiError?.status === 403 && apiError.code === 'POLICY_ACCEPTANCE_REQUIRED') {
      this.subscriptionState.set('policy-required');
      return;
    }
    if (apiError?.status === 404) {
      this.work.set(null);
      this.chapters.set([]);
      this.state.set('unavailable');
      return;
    }
    const loaded = await this.loadSubscription(workId, token);
    if (token !== this.requestToken || workId !== this.workId) return;
    if (loaded) {
      if (apiError?.status === 429 && apiError.code === 'SUBSCRIPTION_RATE_LIMITED') {
        this.subscriptionMessage.set(
          apiError.retryAfterSeconds
            ? 'Bạn thao tác quá nhanh. Hãy thử lại sau ' + apiError.retryAfterSeconds + ' giây.'
            : 'Bạn thao tác quá nhanh. Hãy đợi một chút rồi thử lại.',
        );
      } else if (
        apiError?.status === 503 &&
        apiError.code === 'SUBSCRIPTION_RATE_LIMIT_UNAVAILABLE'
      ) {
        this.subscriptionMessage.set('Chưa thể lưu thay đổi. Hãy thử lại sau ít phút.');
      } else {
        this.subscriptionMessage.set(
          apiError?.status === 409 || apiError?.status === 412
            ? 'Trạng thái theo dõi đã thay đổi. Mình đã tải trạng thái mới nhất.'
            : 'Chưa rõ yêu cầu vừa rồi đã lưu chưa. Trạng thái hiện tại đã được tải lại.',
        );
      }
    } else {
      this.subscriptionState.set('error');
      this.subscriptionMessage.set(this.subscriptionErrorMessage(apiError));
    }
  }

  private applySubscription(receipt: WorkSubscriptionReceipt): void {
    this.subscriptionEtag = receipt.etag;
    this.subscriptionState.set(
      !receipt.state.following
        ? 'not-following'
        : receipt.state.notificationsEnabled
          ? 'following'
          : 'muted',
    );
  }

  private toSubscriptionError(error: unknown): WorkSubscriptionApiError | null {
    return error instanceof WorkSubscriptionApiError ? error : null;
  }

  private subscriptionErrorMessage(error: WorkSubscriptionApiError | null): string {
    if (error?.status === 429) {
      return error.retryAfterSeconds
        ? 'Bạn thao tác quá nhanh. Hãy thử lại sau ' + error.retryAfterSeconds + ' giây.'
        : 'Bạn thao tác quá nhanh. Hãy đợi một chút rồi thử lại.';
    }
    if (error?.status === 503) return 'Theo dõi truyện tạm thời chưa khả dụng.';
    return 'Chưa tải được trạng thái theo dõi. Hãy thử lại.';
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
