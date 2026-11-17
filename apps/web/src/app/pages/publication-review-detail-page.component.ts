import { Component, ElementRef, OnInit, ViewChild, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import {
  PublicationReviewAction,
  PublicationReviewApiError,
  PublicationReviewApiService,
  PublicationReviewClaim,
  PublicationReviewDecisionReceipt,
  PublicationReviewDetail,
  PublicationReviewEvent,
  PublicationReviewReason,
  PublicationReviewScope,
  PublicationReviewState,
  PublicationRightsBasis,
} from '../core/publication-review-api.service';

type DetailAccessState = 'unauthenticated' | 'reviewer-required' | 'policy-required' | 'not-found';
type DecisionAttempt = {
  actionCode: PublicationReviewAction;
  reasonCode: PublicationReviewReason;
  idempotencyKey: string;
};

@Component({
  selector: 'app-publication-review-detail-page',
  imports: [RouterLink],
  templateUrl: './publication-review-detail-page.component.html',
  styleUrl: './publication-review-detail-page.component.css',
})
export class PublicationReviewDetailPageComponent implements OnInit {
  private readonly api = inject(PublicationReviewApiService);
  private readonly route = inject(ActivatedRoute);

  protected readonly detail = signal<PublicationReviewDetail | null>(null);
  protected readonly etag = signal<string | null>(null);
  protected readonly loading = signal(false);
  protected readonly actionBusy = signal(false);
  protected readonly errorMessage = signal<string | null>(null);
  protected readonly accessState = signal<DetailAccessState | null>(null);
  protected readonly detailRefreshRequired = signal(false);
  protected readonly detailRefreshError = signal(false);
  protected readonly decisionAction = signal<PublicationReviewAction | null>(null);
  protected readonly selectedReason = signal<PublicationReviewReason | null>(null);
  protected readonly pendingAttempt = signal<DecisionAttempt | null>(null);
  protected readonly savedReceipt = signal<PublicationReviewDecisionReceipt | null>(null);
  protected readonly resultMessage = signal('');
  protected readonly announcement = signal('');
  protected readonly hasUnresolvedClaim = computed(
    () => this.detail()?.claims.some((claim) => claim.rightsBasisCode === 'NEEDS_REVIEW') ?? false,
  );
  protected readonly canDecide = computed(
    () =>
      this.detail()?.state === 'PENDING_REVIEW' &&
      !this.loading() &&
      !this.actionBusy() &&
      !this.detailRefreshRequired() &&
      !this.savedReceipt() &&
      !this.pendingAttempt(),
  );
  protected readonly canApprove = computed(() => this.canDecide() && !this.hasUnresolvedClaim());

  @ViewChild('decisionDialog') private decisionDialog?: ElementRef<HTMLDialogElement>;
  @ViewChild('pageTitle') private pageTitle?: ElementRef<HTMLHeadingElement>;
  @ViewChild('resultStatus') private resultStatus?: ElementRef<HTMLElement>;
  @ViewChild('errorSummary') private errorSummary?: ElementRef<HTMLElement>;
  private dialogReturnFocus?: HTMLElement;
  private readonly requestId: string | null;

  constructor() {
    this.requestId = this.route.snapshot.paramMap.get('requestId');
  }

  ngOnInit(): void {
    void this.loadDetail(false);
  }

  protected async refreshDetail(): Promise<void> {
    await this.loadDetail(true);
  }

  protected openDecision(action: PublicationReviewAction, event: Event): void {
    if (!this.canDecide() || (action === 'APPROVED' && !this.canApprove())) return;
    const dialog = this.decisionDialog?.nativeElement;
    if (!dialog) return;
    this.dialogReturnFocus = event.currentTarget as HTMLElement;
    this.decisionAction.set(action);
    this.selectedReason.set(null);
    this.errorMessage.set(null);
    dialog.showModal();
    queueMicrotask(() => dialog.querySelector<HTMLInputElement>('input[type="radio"]')?.focus());
  }

  protected onReasonChange(event: Event, reason: PublicationReviewReason): void {
    if (this.pendingAttempt()) return;
    if ((event.target as HTMLInputElement).checked) this.selectedReason.set(reason);
  }

  protected async confirmDecision(): Promise<void> {
    const action = this.decisionAction();
    const reason = this.selectedReason();
    if (!action || !reason || this.actionBusy() || this.pendingAttempt()) return;
    if (!isReasonForAction(action, reason)) return;
    await this.submitAttempt({
      actionCode: action,
      reasonCode: reason,
      idempotencyKey: createIdempotencyKey(),
    });
  }

  protected async retryExactDecision(): Promise<void> {
    const attempt = this.pendingAttempt();
    if (!attempt || this.actionBusy() || this.detailRefreshRequired()) return;
    await this.submitAttempt(attempt);
  }

  protected cancelDialog(): void {
    if (this.actionBusy()) return;
    this.decisionDialog?.nativeElement.close();
  }

  protected onDialogCancel(event: Event): void {
    if (this.actionBusy()) event.preventDefault();
  }

  protected onDialogClosed(): void {
    if (this.actionBusy()) return;
    this.dialogReturnFocus?.focus();
    this.dialogReturnFocus = undefined;
  }

  protected reasonsForAction(action: PublicationReviewAction | null): PublicationReviewReason[] {
    switch (action) {
      case 'NEEDS_INFORMATION':
        return ['INCOMPLETE_CLAIM', 'MORE_INFORMATION', 'SCOPE_UNCLEAR'];
      case 'APPROVED':
        return ['RIGHTS_REVIEWED', 'SCOPE_CONFIRMED'];
      case 'REJECTED':
        return ['RIGHTS_NOT_VERIFIED', 'OUT_OF_SCOPE', 'POLICY_MISMATCH'];
      default:
        return [];
    }
  }

  protected reasonLabel(reason: PublicationReviewReason): string {
    return reasonLabel(reason);
  }

  protected scopeLabel(scope: PublicationReviewScope): string {
    switch (scope) {
      case 'PUBLIC_METADATA':
        return 'Thông tin công khai';
      case 'CHAPTER_TEXT':
        return 'Văn bản chương';
      case 'COMMERCIAL_SALE':
        return 'Quyền thương mại';
      case 'TRANSLATION_ADAPTATION':
        return 'Quyền chuyển ngữ/thích nghi';
    }
  }

  protected basisLabel(basis: PublicationRightsBasis): string {
    switch (basis) {
      case 'ORIGINAL_WORK':
        return 'Tác phẩm do tác giả tự sáng tác';
      case 'LICENSE_OR_PERMISSION':
        return 'Có giấy phép hoặc sự cho phép';
      case 'PUBLIC_DOMAIN':
        return 'Tác phẩm thuộc phạm vi công cộng';
      case 'NEEDS_REVIEW':
        return 'Cần xác minh thêm';
    }
  }

  protected stateLabel(state: PublicationReviewState): string {
    switch (state) {
      case 'PENDING_REVIEW':
        return 'Đang chờ xem xét';
      case 'NEEDS_INFORMATION':
        return 'Đã yêu cầu bổ sung';
      case 'APPROVED_UNPUBLISHED':
        return 'Được duyệt · Chưa công khai';
      case 'REJECTED':
        return 'Đã từ chối';
      case 'SUPERSEDED':
        return 'Đã được thay bằng yêu cầu mới';
      case 'WITHDRAWN':
        return 'Đã rút yêu cầu';
    }
  }

  protected eventActionLabel(action: PublicationReviewEvent['actionCode']): string {
    switch (action) {
      case 'SUBMITTED':
        return 'Gửi yêu cầu';
      case 'NEEDS_INFORMATION':
        return 'Yêu cầu bổ sung';
      case 'APPROVED':
        return 'Chấp thuận quyền';
      case 'REJECTED':
        return 'Từ chối yêu cầu';
      case 'SUPERSEDED':
        return 'Thay bằng yêu cầu mới';
      case 'WITHDRAWN':
        return 'Rút yêu cầu';
    }
  }

  protected actorLabel(role: PublicationReviewEvent['actorRoleCode']): string {
    return role === 'author' ? 'Tác giả' : 'Người duyệt';
  }

  protected claimFor(scope: PublicationReviewScope): PublicationReviewClaim | undefined {
    return this.detail()?.claims.find((claim) => claim.scopeCode === scope);
  }

  protected formatDate(value: string): string {
    return new Intl.DateTimeFormat('vi-VN', { dateStyle: 'medium', timeStyle: 'short' }).format(
      new Date(value),
    );
  }

  protected receiptStateLabel(receipt: PublicationReviewDecisionReceipt): string {
    return this.stateLabel(receipt.toState);
  }

  private async loadDetail(manualRefresh: boolean): Promise<boolean> {
    if (!this.requestId || this.loading()) return false;
    this.loading.set(true);
    this.errorMessage.set(null);
    this.detailRefreshError.set(false);
    if (!this.detail()) this.accessState.set(null);
    try {
      const response = await this.api.getDetail(this.requestId);
      this.detail.set(response.detail);
      this.etag.set(response.etag);
      this.accessState.set(null);
      this.detailRefreshRequired.set(false);
      this.pendingAttempt.set(null);
      this.savedReceipt.set(null);
      this.errorMessage.set(null);
      if (manualRefresh) {
        this.announcement.set('Đã tải lại chi tiết và trạng thái hiện hành.');
      }
      this.pageTitle?.nativeElement.focus();
      return true;
    } catch (error) {
      const status = statusOf(error);
      const code = codeOf(error);
      if (status === 401) {
        this.detail.set(null);
        this.etag.set(null);
        this.pendingAttempt.set(null);
        this.accessState.set('unauthenticated');
      } else if (status === 403) {
        this.detail.set(null);
        this.etag.set(null);
        this.pendingAttempt.set(null);
        this.accessState.set(
          code === 'POLICY_ACCEPTANCE_REQUIRED' ? 'policy-required' : 'reviewer-required',
        );
      } else if (status === 404) {
        this.detail.set(null);
        this.etag.set(null);
        this.pendingAttempt.set(null);
        this.accessState.set('not-found');
      } else {
        this.detailRefreshError.set(!!this.detail());
        this.errorMessage.set(detailLoadError(error));
      }
      return false;
    } finally {
      this.loading.set(false);
      if (this.detailRefreshRequired() && !this.detail()) this.focusErrorSummary();
    }
  }

  private async submitAttempt(attempt: DecisionAttempt): Promise<void> {
    const currentEtag = this.etag();
    if (!this.requestId || !currentEtag || this.actionBusy()) return;
    this.actionBusy.set(true);
    this.errorMessage.set(null);
    try {
      const receipt = await this.api.decide(
        this.requestId,
        currentEtag,
        attempt.idempotencyKey,
        attempt.actionCode,
        attempt.reasonCode,
      );
      this.pendingAttempt.set(null);
      this.savedReceipt.set(receipt);
      this.resultMessage.set(`Đã ghi nhận: ${this.receiptStateLabel(receipt)}.`);
      this.announcement.set(this.resultMessage());
      this.decisionDialog?.nativeElement.close();
      await this.loadDetailAfterDecision();
    } catch (error) {
      const status = statusOf(error);
      const code = codeOf(error);
      if (status === 401) {
        this.detail.set(null);
        this.etag.set(null);
        this.pendingAttempt.set(null);
        this.accessState.set('unauthenticated');
        this.decisionDialog?.nativeElement.close();
      } else if (status === 403) {
        this.detail.set(null);
        this.etag.set(null);
        this.pendingAttempt.set(null);
        this.accessState.set(
          code === 'POLICY_ACCEPTANCE_REQUIRED' ? 'policy-required' : 'reviewer-required',
        );
        this.decisionDialog?.nativeElement.close();
      } else if (status === 404) {
        this.detail.set(null);
        this.etag.set(null);
        this.pendingAttempt.set(null);
        this.accessState.set('not-found');
        this.decisionDialog?.nativeElement.close();
      } else if (status === 409 || status === 412) {
        this.pendingAttempt.set(null);
        this.detailRefreshRequired.set(true);
        this.errorMessage.set(decisionConflictMessage(code));
        this.announcement.set(this.errorMessage() ?? 'Yêu cầu đã thay đổi.');
        this.decisionDialog?.nativeElement.close();
        this.focusErrorSummary();
      } else if (status === 0 || status >= 500) {
        this.pendingAttempt.set(attempt);
        if (!this.decisionDialog?.nativeElement.open) this.openRetryDialog();
      } else {
        this.pendingAttempt.set(null);
        this.errorMessage.set(
          decisionErrorMessage(
            status,
            code,
            error instanceof PublicationReviewApiError ? error.retryAfterSeconds : null,
          ),
        );
        this.announcement.set(this.errorMessage() ?? 'Không thể ghi nhận quyết định.');
        this.decisionDialog?.nativeElement.close();
        this.focusErrorSummary();
      }
    } finally {
      this.actionBusy.set(false);
    }
  }

  private async loadDetailAfterDecision(): Promise<void> {
    const refreshed = await this.loadDetail(false);
    if (refreshed) {
      this.resultStatus?.nativeElement.focus();
    } else if (this.savedReceipt()) {
      this.detailRefreshRequired.set(true);
      this.detailRefreshError.set(true);
      this.errorMessage.set(
        'Quyết định đã được ghi nhận. Chưa tải được trạng thái mới; hãy tải lại trước khi tiếp tục.',
      );
      this.focusErrorSummary();
    }
  }

  private openRetryDialog(): void {
    const dialog = this.decisionDialog?.nativeElement;
    if (!dialog || dialog.open) return;
    this.decisionAction.set(this.pendingAttempt()?.actionCode ?? null);
    this.selectedReason.set(this.pendingAttempt()?.reasonCode ?? null);
    dialog.showModal();
  }

  private focusErrorSummary(): void {
    queueMicrotask(() => this.errorSummary?.nativeElement.focus());
  }
}

function statusOf(error: unknown): number {
  return error instanceof PublicationReviewApiError ? error.status : -1;
}

function codeOf(error: unknown): string | null {
  return error instanceof PublicationReviewApiError ? error.code : null;
}

function detailLoadError(error: unknown): string {
  if (statusOf(error) === 503) {
    return 'Chi tiết hiện chưa sẵn sàng. Hãy thử lại; không thực hiện quyết định nào.';
  }
  if (statusOf(error) === 0) {
    return 'Không thể xác minh dữ liệu chi tiết. Kiểm tra kết nối rồi thử lại.';
  }
  return 'Không thể tải yêu cầu này. Hãy thử lại sau.';
}

function decisionConflictMessage(code: string | null): string {
  switch (code) {
    case 'PUBLICATION_REVIEW_ETAG_STALE':
    case 'PUBLICATION_REVIEW_SNAPSHOT_STALE':
      return 'Bản gửi duyệt đã thay đổi. Tải lại chi tiết và xem lại snapshot trước khi chọn quyết định mới.';
    case 'PUBLICATION_RIGHTS_POLICY_STALE':
      return 'Yêu cầu dùng phiên bản chính sách cũ. Tải lại chi tiết; không thể chấp thuận theo phiên bản này.';
    case 'PUBLICATION_REVIEW_CLAIM_UNRESOLVED':
      return 'Có phạm vi quyền cần xác minh thêm. Tải lại chi tiết; không thể chấp thuận yêu cầu này.';
    case 'PUBLICATION_REVIEW_STATE_CONFLICT':
      return 'Yêu cầu đã có trạng thái mới. Tải lại để xem lịch sử quyết định.';
    case 'IDEMPOTENCY_KEY_REUSED':
      return 'Mã yêu cầu này đã gắn với quyết định khác. Tải lại trước khi chọn lại hành động.';
    default:
      return 'Yêu cầu đã thay đổi. Tải lại chi tiết trước khi tiếp tục.';
  }
}

function decisionErrorMessage(
  status: number,
  code: string | null,
  retryAfterSeconds: number | null,
): string {
  if (status === 429) {
    return retryAfterSeconds
      ? `Bạn thao tác quá nhanh. Hãy đợi khoảng ${retryAfterSeconds} giây rồi tải lại trước khi chọn quyết định.`
      : 'Bạn thao tác quá nhanh. Hãy chờ rồi tải lại trước khi chọn quyết định.';
  }
  if (status === 428 || code === 'IF_MATCH_REQUIRED') {
    return 'Thiếu bộ kiểm tra phiên bản. Tải lại chi tiết trước khi quyết định.';
  }
  if (status === 413 || status === 415 || status === 400) {
    return 'Yêu cầu không đúng định dạng. Hãy tải lại chi tiết rồi chọn lại lý do.';
  }
  return 'Không thể ghi nhận quyết định. Hãy tải lại trạng thái trước khi thử tiếp.';
}

function isReasonForAction(
  action: PublicationReviewAction,
  reason: PublicationReviewReason,
): boolean {
  if (action === 'NEEDS_INFORMATION') {
    return ['INCOMPLETE_CLAIM', 'MORE_INFORMATION', 'SCOPE_UNCLEAR'].includes(reason);
  }
  if (action === 'APPROVED') return ['RIGHTS_REVIEWED', 'SCOPE_CONFIRMED'].includes(reason);
  return ['RIGHTS_NOT_VERIFIED', 'OUT_OF_SCOPE', 'POLICY_MISMATCH'].includes(reason);
}

function createIdempotencyKey(): string {
  return `review-decision-${crypto.randomUUID()}`;
}

function reasonLabel(reason: PublicationReviewReason): string {
  switch (reason) {
    case 'INCOMPLETE_CLAIM':
      return 'Khai báo quyền chưa đầy đủ';
    case 'MORE_INFORMATION':
      return 'Cần thêm thông tin';
    case 'SCOPE_UNCLEAR':
      return 'Phạm vi quyền chưa rõ';
    case 'RIGHTS_REVIEWED':
      return 'Đã xem xét khai báo quyền';
    case 'SCOPE_CONFIRMED':
      return 'Phạm vi khai báo đã rõ';
    case 'RIGHTS_NOT_VERIFIED':
      return 'Chưa đủ căn cứ xác minh quyền';
    case 'OUT_OF_SCOPE':
      return 'Khai báo vượt phạm vi';
    case 'POLICY_MISMATCH':
      return 'Không khớp chính sách hiện hành';
  }
}
