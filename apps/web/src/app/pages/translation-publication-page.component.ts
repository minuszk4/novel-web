import { Component, ElementRef, OnInit, ViewChild, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { PolicyOnboardingService } from '../core/policy-onboarding.service';
import {
  TranslationAcceptedItem,
  TranslationPublicationApiError,
  TranslationPublicationApiService,
  TranslationReleaseReceipt,
  TranslationVersionHistoryItem,
} from '../core/translation-publication-api.service';
import { SystemStatusService } from '../core/system-status.service';

type PublicationAccessState = 'unauthenticated' | 'policy-required' | 'owner-required';
type ReleaseAttempt = { etag: string; idempotencyKey: string };
type RollbackAttempt = { currentEtag: string; idempotencyKey: string };

@Component({
  selector: 'app-translation-publication-page',
  imports: [RouterLink],
  templateUrl: './translation-publication-page.component.html',
  styleUrl: './translation-publication-page.component.css',
})
export class TranslationPublicationPageComponent implements OnInit {
  protected readonly policy = inject(PolicyOnboardingService);
  protected readonly status = inject(SystemStatusService);
  protected readonly items = signal<TranslationAcceptedItem[]>([]);
  protected readonly nextCursor = signal<string | null>(null);
  protected readonly loading = signal(false);
  protected readonly loadingMore = signal(false);
  protected readonly errorMessage = signal<string | null>(null);
  protected readonly accessState = signal<PublicationAccessState | null>(null);
  protected readonly featureAvailable = signal(false);
  protected readonly pendingConfirmation = signal<TranslationAcceptedItem | null>(null);
  protected readonly pendingReceipt = signal<TranslationReleaseReceipt | null>(null);
  protected readonly releasingSubmissionId = signal<string | null>(null);
  protected readonly releaseError = signal<string | null>(null);
  protected readonly announcement = signal('');
  protected readonly historyItems = signal<TranslationVersionHistoryItem[]>([]);
  protected readonly historyCursor = signal<string | null>(null);
  protected readonly historyLoading = signal(false);
  protected readonly pendingRollback = signal<TranslationVersionHistoryItem | null>(null);
  protected readonly rollingBackVersionId = signal<string | null>(null);
  protected readonly rollbackError = signal<string | null>(null);
  protected readonly receiptAction = signal<'publish' | 'rollback'>('publish');

  private readonly route = inject(ActivatedRoute);
  private readonly api = inject(TranslationPublicationApiService);
  private readonly releaseAttempts = new Map<string, ReleaseAttempt>();
  private readonly rollbackAttempts = new Map<string, RollbackAttempt>();
  private workId: string | null = null;
  private requestToken = 0;
  private historyRequestToken = 0;
  private readonly consumedCursors = new Set<string>();
  private readonly consumedHistoryCursors = new Set<string>();

  @ViewChild('pageHeading')
  private set pageHeading(heading: ElementRef<HTMLHeadingElement> | undefined) {
    heading?.nativeElement.focus({ preventScroll: true });
  }

  ngOnInit(): void {
    this.workId = this.route.snapshot.paramMap.get('workId');
    void this.initializeFeature();
  }

  protected async refresh(): Promise<void> {
    await this.loadPage(true);
  }

  protected async loadMore(): Promise<void> {
    if (!this.nextCursor() || this.loading() || this.loadingMore()) return;
    await this.loadPage(false);
  }

  protected async refreshHistory(): Promise<void> {
    await this.loadHistory(true);
  }

  protected async loadMoreHistory(): Promise<void> {
    if (!this.historyCursor() || this.historyLoading()) return;
    await this.loadHistory(false);
  }

  protected retryUncertainRollback(item: TranslationVersionHistoryItem): void {
    if (!this.rollbackAttempts.has(item.versionId) || this.rollingBackVersionId()) return;
    this.pendingRollback.set(item);
    void this.confirmRollback(item);
  }

  protected requestRollback(item: TranslationVersionHistoryItem): void {
    if (!item.rollbackAllowed || !item.currentVersionEtag || this.rollingBackVersionId()) return;
    this.rollbackError.set(null);
    this.pendingRollback.set(item);
  }

  protected cancelRollback(): void {
    this.pendingRollback.set(null);
  }

  protected async confirmRollback(item: TranslationVersionHistoryItem): Promise<void> {
    if (this.pendingRollback()?.versionId !== item.versionId || !item.currentVersionEtag) return;
    this.pendingRollback.set(null);
    this.rollbackError.set(null);
    const attempt =
      this.rollbackAttempts.get(item.versionId) ??
      ({
        currentEtag: item.currentVersionEtag,
        idempotencyKey: crypto.randomUUID(),
      } satisfies RollbackAttempt);
    this.rollbackAttempts.set(item.versionId, attempt);
    this.rollingBackVersionId.set(item.versionId);
    try {
      const receipt = await this.api.rollback(
        item.versionId,
        attempt.currentEtag,
        attempt.idempotencyKey,
      );
      this.rollbackAttempts.delete(item.versionId);
      this.pendingReceipt.set(receipt);
      this.receiptAction.set('rollback');
      this.announcement.set(`Đã khôi phục nội dung bản dịch phiên bản ${item.versionNo}.`);
      await Promise.all([this.loadPage(true, true), this.loadHistory(true)]);
    } catch (error) {
      const apiError = asPublicationError(error);
      if (apiError.status === 409 || apiError.status === 412) {
        this.rollbackAttempts.delete(item.versionId);
        this.rollbackError.set(
          apiError.code === 'TRANSLATION_ADAPTATION_RIGHTS_REQUIRED'
            ? 'Quyền khai thác bản dịch đã hết hiệu lực. Không thể khôi phục bản này.'
            : 'Phiên bản hiện hành đã thay đổi hoặc nguồn truyện không còn tương thích. Hãy tải lại lịch sử.',
        );
        await this.loadHistory(true);
      } else if (apiError.status === 401 || apiError.status === 403) {
        this.rollbackAttempts.delete(item.versionId);
        this.accessState.set(apiError.status === 401 ? 'unauthenticated' : 'policy-required');
      } else {
        this.pendingRollback.set(item);
        this.rollbackError.set('Chưa xác nhận được kết quả. Có thể thử lại đúng yêu cầu này.');
      }
    } finally {
      this.rollingBackVersionId.set(null);
    }
  }

  protected requestRelease(item: TranslationAcceptedItem): void {
    if (!item.rightsCurrent || this.releasingSubmissionId()) return;
    this.releaseError.set(null);
    this.pendingConfirmation.set(item);
  }

  protected cancelRelease(): void {
    this.pendingConfirmation.set(null);
  }

  protected retryUncertainRelease(item: TranslationAcceptedItem): void {
    if (!this.releaseAttempts.has(item.submissionId) || this.releasingSubmissionId()) return;
    this.pendingConfirmation.set(item);
    void this.confirmRelease(item);
  }

  protected async confirmRelease(item: TranslationAcceptedItem): Promise<void> {
    if (this.pendingConfirmation()?.submissionId !== item.submissionId) return;
    this.pendingConfirmation.set(null);
    this.pendingReceipt.set(null);
    this.releaseError.set(null);
    const attempt =
      this.releaseAttempts.get(item.submissionId) ??
      ({
        etag: `"translation-review-${item.snapshotDigest}"`,
        idempotencyKey: crypto.randomUUID(),
      } satisfies ReleaseAttempt);
    this.releaseAttempts.set(item.submissionId, attempt);
    this.releasingSubmissionId.set(item.submissionId);
    try {
      const receipt = await this.api.release(
        item.submissionId,
        attempt.etag,
        attempt.idempotencyKey,
      );
      this.releaseAttempts.delete(item.submissionId);
      this.pendingReceipt.set(receipt);
      this.receiptAction.set('publish');
      this.announcement.set(
        receipt.replayed
          ? `Bản dịch phiên bản ${receipt.versionNo} đã được phát hành trước đó.`
          : `Đã phát hành bản dịch phiên bản ${receipt.versionNo}.`,
      );
      await Promise.all([this.loadPage(true, true), this.loadHistory(true)]);
    } catch (error) {
      const apiError = asPublicationError(error);
      if (apiError.status === 412 || apiError.status === 409) {
        this.releaseAttempts.delete(item.submissionId);
        this.releaseError.set(
          apiError.code === 'TRANSLATION_ADAPTATION_RIGHTS_REQUIRED'
            ? 'Chưa có quyền khai thác bản dịch còn hiệu lực. Bản dịch vẫn được giữ riêng tư.'
            : apiError.code === 'TRANSLATION_SOURCE_STALE'
              ? 'Chương nguồn đã thay đổi. Hãy tạo và duyệt bản dịch cho phiên bản hiện tại.'
              : 'Bản duyệt đã thay đổi hoặc không còn đủ điều kiện. Danh sách đã được làm mới.',
        );
        await this.loadPage(true, true);
      } else {
        if (apiError.status === 0 || apiError.status === 503) {
          this.pendingConfirmation.set(item);
        }
        this.releaseError.set(
          apiError.status === 0 || apiError.status === 503
            ? 'Chưa xác nhận được kết quả phát hành. Có thể thử lại an toàn bằng cùng một yêu cầu.'
            : apiError.status === 401
              ? 'Phiên đăng nhập đã hết hạn. Đăng nhập lại rồi thử tiếp.'
              : 'Không thể phát hành bản dịch lúc này. Chưa có xác nhận thành công.',
        );
      }
      this.announcement.set(this.releaseError() ?? 'Không thể phát hành bản dịch.');
    } finally {
      this.releasingSubmissionId.set(null);
    }
  }

  protected formatDate(value: string): string {
    return new Intl.DateTimeFormat('vi-VN', { dateStyle: 'medium', timeStyle: 'short' }).format(
      new Date(value),
    );
  }

  private async loadPage(reset: boolean, keepReceipt = false): Promise<void> {
    if (!this.workId || !this.featureAvailable() || this.loading() || this.loadingMore()) return;
    const cursor = reset ? null : this.nextCursor();
    if (!reset && !cursor) return;
    if (!reset && this.consumedCursors.has(cursor!)) {
      this.errorMessage.set('Danh sách không thể tiếp tục. Tải lại để nhận trang đầu mới.');
      this.nextCursor.set(null);
      return;
    }
    const token = ++this.requestToken;
    if (reset) {
      this.items.set([]);
      this.nextCursor.set(null);
      this.accessState.set(null);
      this.errorMessage.set(null);
      this.loading.set(true);
      if (!keepReceipt) this.pendingReceipt.set(null);
    } else {
      this.loadingMore.set(true);
      this.errorMessage.set(null);
    }
    try {
      const page = await this.api.getAccepted(this.workId, cursor, 20);
      if (token !== this.requestToken) return;
      const known = new Set(this.items().map(({ submissionId }) => submissionId));
      if (page.items.some(({ submissionId }) => known.has(submissionId))) {
        this.nextCursor.set(null);
        this.errorMessage.set('Danh sách có mục trùng. Tải lại trước khi phát hành.');
        return;
      }
      if (cursor) this.consumedCursors.add(cursor);
      this.items.update((items) => [...items, ...page.items]);
      this.nextCursor.set(page.nextCursor);
      this.announcement.set(`${this.items().length} bản dịch đã duyệt đang chờ bạn.`);
    } catch (error) {
      const apiError = asPublicationError(error);
      if (apiError.status === 401) {
        this.items.set([]);
        this.accessState.set('unauthenticated');
      } else if (apiError.status === 403) {
        this.items.set([]);
        this.accessState.set(
          apiError.code === 'POLICY_ACCEPTANCE_REQUIRED' ? 'policy-required' : 'owner-required',
        );
      } else if (apiError.status === 404) {
        this.items.set([]);
        this.accessState.set('owner-required');
      } else if (apiError.status === 400 && cursor) {
        this.items.set([]);
        this.nextCursor.set(null);
        this.errorMessage.set('Trang đã hết hiệu lực. Tải lại danh sách từ đầu.');
      } else {
        this.errorMessage.set('Không thể tải các bản dịch đã duyệt. Thử tải lại.');
      }
      this.announcement.set(this.errorMessage() ?? 'Không thể mở danh sách bản dịch.');
    } finally {
      if (token === this.requestToken) {
        this.loading.set(false);
        this.loadingMore.set(false);
      }
    }
  }

  private async loadHistory(reset: boolean): Promise<void> {
    if (!this.workId || !this.featureAvailable() || this.historyLoading()) return;
    const cursor = reset ? null : this.historyCursor();
    if (!reset && !cursor) return;
    if (!reset && this.consumedHistoryCursors.has(cursor!)) {
      this.rollbackError.set('Danh sách lịch sử không thể tiếp tục. Tải lại lịch sử từ đầu.');
      this.historyCursor.set(null);
      return;
    }
    const token = ++this.historyRequestToken;
    if (reset) {
      this.historyItems.set([]);
      this.historyCursor.set(null);
      this.rollbackError.set(null);
    }
    this.historyLoading.set(true);
    try {
      const page = await this.api.getHistory(this.workId, cursor, 20);
      if (token !== this.historyRequestToken) return;
      const known = new Set(this.historyItems().map(({ eventId }) => eventId));
      if (page.items.some(({ eventId }) => known.has(eventId))) {
        this.historyCursor.set(null);
        this.rollbackError.set('Lịch sử có mục trùng. Tải lại trước khi khôi phục phiên bản.');
        return;
      }
      if (cursor) this.consumedHistoryCursors.add(cursor);
      this.historyItems.update((items) => [...items, ...page.items]);
      this.historyCursor.set(page.nextCursor);
    } catch (error) {
      const apiError = asPublicationError(error);
      if (apiError.status === 401) this.accessState.set('unauthenticated');
      else if (apiError.status === 403)
        this.accessState.set(
          apiError.code === 'POLICY_ACCEPTANCE_REQUIRED' ? 'policy-required' : 'owner-required',
        );
      else this.rollbackError.set('Không thể tải lịch sử bản dịch. Hãy thử tải lại.');
    } finally {
      if (token === this.historyRequestToken) this.historyLoading.set(false);
    }
  }

  private async initializeFeature(): Promise<void> {
    const currentStatus = await this.status.waitForStatus();
    this.featureAvailable.set(currentStatus?.features.translationPublicationEnabled === true);
    if (this.policy.status() === 'accepted' && this.featureAvailable()) {
      await Promise.all([this.loadPage(true), this.loadHistory(true)]);
    }
  }
}

function asPublicationError(error: unknown): TranslationPublicationApiError {
  return error instanceof TranslationPublicationApiError
    ? error
    : new TranslationPublicationApiError(0, null);
}
