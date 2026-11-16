import { Component, ElementRef, OnInit, ViewChild, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { PolicyOnboardingService } from '../core/policy-onboarding.service';
import {
  ReviewerAccountCandidate,
  ReviewerAdminApiError,
  ReviewerAdminApiService,
  ReviewerAssignment,
  ReviewerAssignmentStatus,
  ReviewerGrantReason,
  ReviewerRevokeReason,
} from '../core/reviewer-admin-api.service';

type AssignmentTab = 'active' | 'revoked';
type ConfirmationAction =
  | { kind: 'grant'; candidate: ReviewerAccountCandidate }
  | { kind: 'revoke'; assignment: ReviewerAssignment };
type PendingCommand =
  | { kind: 'grant'; userId: string; reasonCode: ReviewerGrantReason; idempotencyKey: string }
  | { kind: 'revoke'; userId: string; reasonCode: ReviewerRevokeReason; idempotencyKey: string };

@Component({
  selector: 'app-reviewer-admin-page',
  imports: [RouterLink],
  templateUrl: './reviewer-admin-page.component.html',
  styleUrl: './reviewer-admin-page.component.css',
})
export class ReviewerAdminPageComponent implements OnInit {
  protected readonly policy = inject(PolicyOnboardingService);
  private readonly api = inject(ReviewerAdminApiService);

  protected readonly assignments = signal<ReviewerAssignment[]>([]);
  protected readonly activeReviewerIds = signal<ReadonlySet<string>>(new Set());
  protected readonly selectedStatus = signal<AssignmentTab>('active');
  protected readonly listLoading = signal(true);
  protected readonly listError = signal<string | null>(null);
  protected readonly accessDenied = signal<'unauthenticated' | 'forbidden' | null>(null);
  protected readonly searchQuery = signal('');
  protected readonly searchLoading = signal(false);
  protected readonly searchError = signal<string | null>(null);
  protected readonly searchCompleted = signal(false);
  protected readonly candidates = signal<ReviewerAccountCandidate[]>([]);
  protected readonly selectedCandidate = signal<ReviewerAccountCandidate | null>(null);
  protected readonly confirmation = signal<ConfirmationAction | null>(null);
  protected readonly grantReason = signal<ReviewerGrantReason>('REVIEW_CAPACITY');
  protected readonly revokeReason = signal<ReviewerRevokeReason>('ACCESS_ENDED');
  protected readonly actionBusy = signal(false);
  protected readonly actionError = signal<string | null>(null);
  protected readonly actionSuccess = signal<string | null>(null);
  protected readonly pendingCommand = signal<PendingCommand | null>(null);

  @ViewChild('confirmDialog') private confirmDialog?: ElementRef<HTMLDialogElement>;
  @ViewChild('pageTitle') private pageTitle?: ElementRef<HTMLHeadingElement>;
  private dialogReturnFocus?: HTMLElement;

  ngOnInit(): void {
    void this.loadAssignments();
  }

  protected async loadAssignments(): Promise<void> {
    this.listLoading.set(true);
    this.listError.set(null);
    try {
      const items = await this.api.getAssignments(this.selectedStatus());
      this.assignments.set(items);
      if (this.selectedStatus() === 'active') {
        this.activeReviewerIds.set(new Set(items.map(({ userId }) => userId)));
      }
      this.accessDenied.set(null);
    } catch (error) {
      const status = statusOf(error);
      if (status === 401 || status === 403) {
        this.hideSensitiveState(status);
      } else {
        this.listError.set(errorMessage(error, 'list'));
      }
    } finally {
      this.listLoading.set(false);
    }
  }

  protected async selectStatus(status: AssignmentTab): Promise<void> {
    if (this.selectedStatus() === status) return;
    this.selectedStatus.set(status);
    this.actionSuccess.set(null);
    await this.loadAssignments();
  }

  protected onSearchSubmit(event: Event): void {
    event.preventDefault();
    void this.searchEligibleAccounts();
  }

  protected async searchEligibleAccounts(): Promise<void> {
    const query = this.searchQuery().trim();
    if (!query || this.searchLoading()) return;
    this.searchLoading.set(true);
    this.searchError.set(null);
    this.searchCompleted.set(false);
    this.candidates.set([]);
    this.selectedCandidate.set(null);
    try {
      this.candidates.set(await this.api.searchEligibleAccounts(query));
      this.searchCompleted.set(true);
    } catch (error) {
      if (statusOf(error) === 401 || statusOf(error) === 403) {
        this.hideSensitiveState(statusOf(error));
      } else {
        this.searchError.set(errorMessage(error, 'search'));
      }
    } finally {
      this.searchLoading.set(false);
    }
  }

  protected clearSearch(): void {
    this.searchQuery.set('');
    this.candidates.set([]);
    this.selectedCandidate.set(null);
    this.searchError.set(null);
    this.searchCompleted.set(false);
  }

  protected selectCandidate(candidate: ReviewerAccountCandidate): void {
    if (
      candidate.userId === this.policy.accountIdentity()?.userId ||
      this.activeReviewerIds().has(candidate.userId) ||
      !this.canChangeRoles()
    ) {
      return;
    }
    this.selectedCandidate.set(candidate);
    this.actionError.set(null);
    this.actionSuccess.set(null);
  }

  protected isSelf(candidate: ReviewerAccountCandidate): boolean {
    return candidate.userId === this.policy.accountIdentity()?.userId;
  }

  protected isAlreadyActiveReviewer(candidate: ReviewerAccountCandidate): boolean {
    return this.activeReviewerIds().has(candidate.userId);
  }

  protected canChangeRoles(): boolean {
    return !this.listLoading() && !this.listError() && !this.accessDenied();
  }

  protected prepareGrant(candidate: ReviewerAccountCandidate, event: Event): void {
    if (
      candidate.userId === this.policy.accountIdentity()?.userId ||
      this.activeReviewerIds().has(candidate.userId) ||
      !this.canChangeRoles()
    ) {
      return;
    }
    this.grantReason.set('REVIEW_CAPACITY');
    this.actionError.set(null);
    this.openConfirmation({ kind: 'grant', candidate }, event);
  }

  protected prepareRevoke(assignment: ReviewerAssignment, event: Event): void {
    if (!this.canChangeRoles()) return;
    this.revokeReason.set('ACCESS_ENDED');
    this.actionError.set(null);
    this.openConfirmation({ kind: 'revoke', assignment }, event);
  }

  protected onGrantReasonChange(event: Event): void {
    const value = (event.target as HTMLSelectElement).value;
    if (isGrantReason(value)) this.grantReason.set(value);
  }

  protected onRevokeReasonChange(event: Event): void {
    const value = (event.target as HTMLSelectElement).value;
    if (isRevokeReason(value)) this.revokeReason.set(value);
  }

  protected async confirmAction(): Promise<void> {
    const action = this.confirmation();
    if (!action || this.actionBusy()) return;
    const command = this.commandFor(action);
    await this.submitCommand(command);
  }

  private async submitCommand(command: PendingCommand): Promise<void> {
    if (this.actionBusy()) return;
    this.pendingCommand.set(command);
    this.actionBusy.set(true);
    this.actionError.set(null);
    try {
      if (command.kind === 'grant') {
        await this.api.grantReviewer(command.userId, command.reasonCode, command.idempotencyKey);
        this.activeReviewerIds.set(new Set([...this.activeReviewerIds(), command.userId]));
        this.selectedCandidate.set(null);
        this.actionSuccess.set('Đã cấp quyền người duyệt. Sự kiện kiểm toán đã được ghi nhận.');
        this.selectedStatus.set('active');
      } else {
        await this.api.revokeReviewer(command.userId, command.reasonCode, command.idempotencyKey);
        const nextActive = new Set(this.activeReviewerIds());
        nextActive.delete(command.userId);
        this.activeReviewerIds.set(nextActive);
        this.actionSuccess.set('Đã thu hồi quyền người duyệt. Thay đổi có hiệu lực ngay.');
        this.selectedStatus.set('revoked');
      }
      this.pendingCommand.set(null);
      this.closeDialog();
      await this.loadAssignments();
      this.focusPageTitle();
    } catch (error) {
      const status = statusOf(error);
      this.actionError.set(errorMessage(error, 'action'));
      if (status !== 0 && status !== 503) this.pendingCommand.set(null);
      if (status === 401 || status === 403) {
        this.hideSensitiveState(status);
        this.closeDialog();
      }
      if (status === 409) await this.loadAssignments();
    } finally {
      this.actionBusy.set(false);
    }
  }

  protected async checkPendingCommand(): Promise<void> {
    const pending = this.pendingCommand();
    if (!pending || this.actionBusy()) return;
    this.actionBusy.set(true);
    this.actionError.set(null);
    try {
      const [active, revoked] = await Promise.all([
        this.api.getAssignments('active'),
        this.api.getAssignments('revoked'),
      ]);
      const assignments = this.selectedStatus() === 'active' ? active : revoked;
      this.assignments.set(assignments);
      this.activeReviewerIds.set(new Set(active.map(({ userId }) => userId)));
      const completed =
        pending.kind === 'grant'
          ? active.some((assignment) => assignment.userId === pending.userId)
          : revoked.some((assignment) => assignment.userId === pending.userId);
      if (completed) {
        this.pendingCommand.set(null);
        this.actionSuccess.set(
          pending.kind === 'grant'
            ? 'Quyền người duyệt đã được cấp và xác nhận lại từ máy chủ.'
            : 'Quyền người duyệt đã được thu hồi và xác nhận lại từ máy chủ.',
        );
        this.closeDialog();
        this.focusPageTitle();
      } else {
        this.actionError.set(
          'Danh sách chưa phản ánh thay đổi. Có thể thử lại cùng yêu cầu an toàn hoặc kiểm tra lại.',
        );
      }
    } catch (error) {
      const status = statusOf(error);
      if (status === 401 || status === 403) {
        this.hideSensitiveState(status);
        this.closeDialog();
      } else {
        this.actionError.set(errorMessage(error, 'action'));
      }
    } finally {
      this.actionBusy.set(false);
    }
  }

  protected onDialogCancel(event: Event): void {
    if (this.actionBusy() || this.pendingCommand()) event.preventDefault();
  }

  protected onDialogClosed(): void {
    if (this.pendingCommand()) return;
    this.confirmation.set(null);
    const target = this.dialogReturnFocus;
    this.dialogReturnFocus = undefined;
    if (target?.isConnected) target.focus();
  }

  protected closeDialog(): void {
    const dialog = this.confirmDialog?.nativeElement;
    if (dialog?.open) dialog.close();
    this.confirmation.set(null);
  }

  protected async retryPendingCommand(): Promise<void> {
    const pending = this.pendingCommand();
    if (!pending || this.actionBusy()) return;
    await this.submitCommand(pending);
  }

  protected formatDate(value: string): string {
    return new Intl.DateTimeFormat('vi-VN', { dateStyle: 'medium', timeStyle: 'short' }).format(
      new Date(value),
    );
  }

  protected reasonLabel(reason: string | null): string {
    const labels: Record<string, string> = {
      REVIEW_CAPACITY: 'Bổ sung năng lực duyệt',
      TEMPORARY_COVERAGE: 'Thay thế tạm thời',
      ROLE_CORRECTION: 'Điều chỉnh vai trò',
      ACCESS_ENDED: 'Kết thúc quyền truy cập',
      ROLE_CHANGE: 'Thay đổi vai trò',
      SECURITY_REVIEW: 'Rà soát bảo mật',
    };
    return reason ? (labels[reason] ?? 'Lý do đã lưu') : '—';
  }

  private commandFor(action: ConfirmationAction): PendingCommand {
    const existing = this.pendingCommand();
    if (action.kind === 'grant') {
      const reasonCode = this.grantReason();
      if (
        existing?.kind === 'grant' &&
        existing.userId === action.candidate.userId &&
        existing.reasonCode === reasonCode
      ) {
        return existing;
      }
      return {
        kind: 'grant',
        userId: action.candidate.userId,
        reasonCode,
        idempotencyKey: crypto.randomUUID(),
      };
    }
    const reasonCode = this.revokeReason();
    if (
      existing?.kind === 'revoke' &&
      existing.userId === action.assignment.userId &&
      existing.reasonCode === reasonCode
    ) {
      return existing;
    }
    return {
      kind: 'revoke',
      userId: action.assignment.userId,
      reasonCode,
      idempotencyKey: crypto.randomUUID(),
    };
  }

  private openConfirmation(action: ConfirmationAction, event: Event): void {
    this.dialogReturnFocus =
      event.currentTarget instanceof HTMLElement ? event.currentTarget : undefined;
    this.confirmation.set(action);
    queueMicrotask(() => {
      const dialog = this.confirmDialog?.nativeElement;
      if (dialog && !dialog.open) dialog.showModal();
      dialog?.querySelector<HTMLElement>('[data-dialog-initial-focus]')?.focus();
    });
  }

  private focusPageTitle(): void {
    queueMicrotask(() => this.pageTitle?.nativeElement.focus());
  }

  private hideSensitiveState(status: number): void {
    this.accessDenied.set(status === 401 ? 'unauthenticated' : 'forbidden');
    this.assignments.set([]);
    this.activeReviewerIds.set(new Set());
    this.candidates.set([]);
    this.selectedCandidate.set(null);
    this.searchCompleted.set(false);
    this.pendingCommand.set(null);
  }
}

function statusOf(error: unknown): number {
  return error instanceof ReviewerAdminApiError ? error.status : -1;
}

function errorMessage(error: unknown, context: 'list' | 'search' | 'action'): string {
  const status = statusOf(error);
  if (status === 401) return 'Phiên đăng nhập đã hết hạn. Hãy đăng nhập lại để tiếp tục.';
  if (status === 403) return 'Tài khoản hiện tại không có quyền quản lý người duyệt.';
  if (status === 404) return 'Tài khoản không còn đủ điều kiện. Hãy tìm lại rồi thử lại.';
  if (status === 409) return 'Quyền đã thay đổi ở nơi khác. Tải lại danh sách trước khi tiếp tục.';
  if (status === 400 || status === 422) {
    return context === 'search'
      ? 'Nhập ít nhất 3 ký tự của email đã xác minh hoặc UUID tài khoản.'
      : 'Kiểm tra lý do và tải lại trạng thái quyền trước khi thử lại.';
  }
  if (status === 429) return `Có quá nhiều yêu cầu. Hãy chờ ${retryDelay(error)} rồi thử lại.`;
  if (status === 503) {
    return context === 'action'
      ? 'Máy chủ chưa xác nhận kết quả. Hãy kiểm tra trạng thái hoặc thử lại cùng yêu cầu.'
      : 'Máy chủ chưa thể xác nhận quyền. Thao tác đang bị khóa.';
  }
  if (status === 0) {
    return context === 'action'
      ? 'Mất kết nối trước khi xác nhận kết quả. Hãy kiểm tra trạng thái hoặc thử lại cùng yêu cầu.'
      : 'Không kết nối được máy chủ. Kiểm tra mạng rồi thử tải lại.';
  }
  return context === 'search'
    ? 'Không tìm được tài khoản lúc này. Hãy thử lại.'
    : 'Không thể tải hoặc cập nhật quyền người duyệt. Hãy thử lại.';
}

function retryDelay(error: unknown): string {
  if (!(error instanceof ReviewerAdminApiError) || error.retryAfterSeconds === null) {
    return 'theo thời gian máy chủ thông báo';
  }
  const seconds = error.retryAfterSeconds;
  if (seconds < 60) return `khoảng ${seconds} giây`;
  const minutes = Math.ceil(seconds / 60);
  if (minutes < 60) return `khoảng ${minutes} phút`;
  return `khoảng ${Math.ceil(minutes / 60)} giờ`;
}

function isGrantReason(value: string): value is ReviewerGrantReason {
  return ['REVIEW_CAPACITY', 'TEMPORARY_COVERAGE', 'ROLE_CORRECTION'].includes(value);
}

function isRevokeReason(value: string): value is ReviewerRevokeReason {
  return ['ACCESS_ENDED', 'ROLE_CHANGE', 'SECURITY_REVIEW'].includes(value);
}
