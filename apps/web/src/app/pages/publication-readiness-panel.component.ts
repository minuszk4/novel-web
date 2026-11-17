import {
  afterNextRender,
  Component,
  ElementRef,
  EventEmitter,
  Input,
  Injector,
  OnChanges,
  Output,
  SimpleChanges,
  ViewChild,
  inject,
  signal,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import {
  AuthoringApiError,
  AuthoringApiService,
  PublicationReadiness,
  PublicationReleaseReceipt,
  PublicationRequestReceipt,
  PublicationRequestHistoryAction,
  PublicationRequestHistoryEvent,
  PublicationRequestHistoryPage,
  PublicationRequestHistoryReason,
  PublicationScopeCode,
  newAuthoringIdempotencyKey,
} from '../core/authoring-api.service';
import { SystemStatusService } from '../core/system-status.service';
import { PublicationRequestFormComponent } from './publication-request-form.component';

type PanelState = 'idle' | 'loading' | 'ready' | 'error' | 'publishing' | 'uncertain' | 'published';
type WithdrawalState = 'idle' | 'confirming' | 'submitting' | 'uncertain';
type RequestHistoryState = 'idle' | 'loading' | 'ready' | 'error';

interface ReleaseAttempt {
  workId: string;
  readinessEtag: string;
  idempotencyKey: string;
}

interface WithdrawalAttempt {
  workId: string;
  readinessEtag: string;
  idempotencyKey: string;
}

@Component({
  selector: 'app-publication-readiness-panel',
  imports: [RouterLink, PublicationRequestFormComponent],
  templateUrl: './publication-readiness-panel.component.html',
  styleUrl: './publication-readiness-panel.component.css',
})
export class PublicationReadinessPanelComponent implements OnChanges {
  @Input({ required: true })
  workId = '';

  @Input()
  refreshRequest = 0;

  @Output()
  requestPolicyReview = new EventEmitter<void>();

  protected readonly state = signal<PanelState>('idle');
  protected readonly readiness = signal<PublicationReadiness | null>(null);
  protected readonly error = signal<AuthoringApiError | null>(null);
  protected readonly announcement = signal('');
  protected readonly confirmationOpen = signal(false);
  protected readonly receipt = signal<PublicationReleaseReceipt | null>(null);
  protected readonly requestFlowActive = signal(false);
  protected readonly withdrawalState = signal<WithdrawalState>('idle');
  protected readonly withdrawalError = signal<AuthoringApiError | null>(null);
  protected readonly requestHistoryState = signal<RequestHistoryState>('idle');
  protected readonly requestHistory = signal<PublicationRequestHistoryPage | null>(null);
  protected readonly requestHistoryError = signal<AuthoringApiError | null>(null);
  protected readonly requestHistoryAnnouncement = signal('');

  private readonly api = inject(AuthoringApiService);
  private readonly systemStatus = inject(SystemStatusService);
  private readonly injector = inject(Injector);
  private requestToken = 0;
  private requestHistoryToken = 0;
  private failedHistoryCursor: string | null = null;
  private pendingRelease: ReleaseAttempt | null = null;
  private pendingWithdrawal: WithdrawalAttempt | null = null;

  @ViewChild('confirmationHeading')
  private confirmationHeading?: ElementRef<HTMLHeadingElement>;

  @ViewChild('publishTrigger')
  private publishTrigger?: ElementRef<HTMLButtonElement>;

  @ViewChild('withdrawalHeading')
  private withdrawalHeading?: ElementRef<HTMLHeadingElement>;

  @ViewChild('withdrawTrigger')
  private withdrawTrigger?: ElementRef<HTMLButtonElement>;

  @ViewChild('currentRequestState')
  private currentRequestStateElement?: ElementRef<HTMLElement>;

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['workId']) this.reset();
    if (
      changes['refreshRequest'] &&
      !changes['refreshRequest'].firstChange &&
      this.state() !== 'idle'
    ) {
      void this.loadReadiness(
        'Chính sách hoặc nội dung đã được cập nhật. Checklist đã được tải lại.',
      );
    }
  }

  protected get isBusy(): boolean {
    return (
      this.state() === 'loading' ||
      this.state() === 'publishing' ||
      this.withdrawalState() === 'submitting'
    );
  }

  protected get canWithdrawCurrentRequest(): boolean {
    return (
      this.state() === 'ready' &&
      !this.requestFlowActive() &&
      this.readiness()?.currentRequestState === 'PENDING_REVIEW' &&
      this.withdrawalState() !== 'uncertain' &&
      this.withdrawalState() !== 'submitting'
    );
  }

  protected get canPublishNow(): boolean {
    const readiness = this.readiness();
    return (
      this.state() === 'ready' &&
      !this.requestFlowActive() &&
      !this.error() &&
      this.systemStatus.status()?.features.publicationReleaseEnabled === true &&
      readiness?.canSubmit === true &&
      readiness.publicationRightsPolicyAvailable &&
      readiness.publicationRightsPolicyAcceptedAt !== null &&
      readiness.currentRequestState === 'APPROVED_UNPUBLISHED'
    );
  }

  protected get recoveryUrl(): string | null {
    if (this.error()?.status === 401) return '/sign-in';
    if (this.error()?.status === 403) return '/app/setup';
    if (this.error()?.status === 404) return '/app/library';
    return null;
  }

  protected get recoveryQueryParams(): Record<string, string> {
    if (this.recoveryUrl === '/sign-in') {
      return { returnTo: `/app/authoring/${this.workId}/chapters` };
    }
    if (this.recoveryUrl === '/app/setup') {
      return { returnUrl: `/app/authoring/${this.workId}/chapters` };
    }
    return {};
  }

  protected get showPolicyAction(): boolean {
    return (
      this.readiness()?.blockerCodes.includes('PUBLICATION_RIGHTS_POLICY_NOT_ACCEPTED') === true
    );
  }

  protected get releaseFeatureEnabled(): boolean {
    return this.systemStatus.status()?.features.publicationReleaseEnabled === true;
  }

  protected async loadReadiness(afterLoadAnnouncement = ''): Promise<void> {
    if (!this.workId || this.isBusy) return;
    const token = ++this.requestToken;
    this.requestFlowActive.set(false);
    this.state.set('loading');
    this.readiness.set(null);
    this.receipt.set(null);
    this.error.set(null);
    this.announcement.set('');
    this.confirmationOpen.set(false);
    this.pendingRelease = null;
    this.withdrawalState.set('idle');
    this.withdrawalError.set(null);
    this.pendingWithdrawal = null;
    this.resetRequestHistory();

    try {
      const readiness = await this.api.getPublicationReadiness(this.workId);
      if (token !== this.requestToken) return;
      this.readiness.set(readiness);
      this.state.set('ready');
      this.announcement.set(
        afterLoadAnnouncement ||
          'Đã tải checklist điều kiện hiện tại. Checklist không xác minh quyền pháp lý.',
      );
    } catch (error) {
      if (token !== this.requestToken) return;
      this.error.set(toAuthoringError(error));
      this.state.set('error');
    }
  }

  protected openReleaseConfirmation(): void {
    if (!this.canPublishNow) return;
    this.confirmationOpen.set(true);
    afterNextRender(() => this.confirmationHeading?.nativeElement.focus({ preventScroll: true }), {
      injector: this.injector,
    });
  }

  protected cancelReleaseConfirmation(): void {
    if (this.state() === 'publishing' || this.state() === 'uncertain') return;
    this.confirmationOpen.set(false);
    this.pendingRelease = null;
    afterNextRender(() => this.publishTrigger?.nativeElement.focus({ preventScroll: true }), {
      injector: this.injector,
    });
  }

  protected async confirmRelease(): Promise<void> {
    const readiness = this.readiness();
    if (!readiness || !this.canPublishNow || this.state() === 'publishing') return;

    let attempt = this.pendingRelease;
    if (
      !attempt ||
      attempt.workId !== this.workId ||
      attempt.readinessEtag !== readiness.snapshotEtag
    ) {
      attempt = {
        workId: this.workId,
        readinessEtag: readiness.snapshotEtag,
        idempotencyKey: newAuthoringIdempotencyKey(),
      };
      this.pendingRelease = attempt;
    }

    const token = this.requestToken;
    this.state.set('publishing');
    this.error.set(null);
    this.announcement.set('Đang xác nhận xuất bản…');
    try {
      const receipt = await this.api.publishManuscriptNow(
        attempt.workId,
        attempt.readinessEtag,
        attempt.idempotencyKey,
      );
      if (token !== this.requestToken) return;
      this.receipt.set(receipt);
      this.pendingRelease = null;
      this.confirmationOpen.set(false);
      this.state.set('published');
      this.announcement.set(
        `Máy chủ đã xác nhận tác phẩm được công khai lúc ${this.publishedDate(receipt.publishedAt)}.`,
      );
    } catch (error) {
      if (token !== this.requestToken) return;
      const mapped = toAuthoringError(error);
      if (mapped.status === 412 || mapped.status === 409) {
        this.pendingRelease = null;
        this.confirmationOpen.set(false);
        this.state.set('ready');
        this.error.set(null);
        await this.loadReadiness(
          'Trạng thái bản thảo đã thay đổi. Hãy xem lại checklist trước khi xác nhận lại.',
        );
        return;
      }
      if (mapped.status === 0 || mapped.status >= 500) {
        this.error.set(mapped);
        this.confirmationOpen.set(false);
        this.state.set('uncertain');
        this.announcement.set('Chưa xác định được kết quả xuất bản.');
        return;
      }

      this.pendingRelease = null;
      this.error.set(mapped);
      this.confirmationOpen.set(false);
      this.state.set('ready');
    }
  }

  protected async retryUncertainRelease(): Promise<void> {
    if (this.state() !== 'uncertain' || !this.pendingRelease) return;
    this.confirmationOpen.set(true);
    this.error.set(null);
    this.state.set('ready');
    await this.confirmRelease();
  }

  protected openWithdrawalConfirmation(): void {
    if (!this.canWithdrawCurrentRequest) return;
    this.withdrawalState.set('confirming');
    this.withdrawalError.set(null);
    afterNextRender(() => this.withdrawalHeading?.nativeElement.focus({ preventScroll: true }), {
      injector: this.injector,
    });
  }

  protected cancelWithdrawalConfirmation(): void {
    if (this.withdrawalState() === 'submitting' || this.withdrawalState() === 'uncertain') return;
    this.withdrawalState.set('idle');
    this.withdrawalError.set(null);
    this.pendingWithdrawal = null;
    afterNextRender(() => this.withdrawTrigger?.nativeElement.focus({ preventScroll: true }), {
      injector: this.injector,
    });
  }

  protected async confirmWithdrawal(): Promise<void> {
    const readiness = this.readiness();
    if (!readiness || !this.canWithdrawCurrentRequest) return;

    let attempt = this.pendingWithdrawal;
    if (
      !attempt ||
      attempt.workId !== this.workId ||
      attempt.readinessEtag !== readiness.snapshotEtag
    ) {
      attempt = {
        workId: this.workId,
        readinessEtag: readiness.snapshotEtag,
        idempotencyKey: newAuthoringIdempotencyKey(),
      };
      this.pendingWithdrawal = attempt;
    }

    const token = this.requestToken;
    this.withdrawalState.set('submitting');
    this.withdrawalError.set(null);
    this.announcement.set('Đang rút yêu cầu duyệt…');
    try {
      const receipt = await this.api.withdrawPublicationRequest(
        attempt.workId,
        attempt.readinessEtag,
        attempt.idempotencyKey,
      );
      if (token !== this.requestToken) return;
      this.pendingWithdrawal = null;
      this.withdrawalState.set('idle');
      this.announcement.set(`Đã rút yêu cầu duyệt phiên bản ${receipt.requestVersion}.`);
      await this.loadReadiness(
        `Đã rút yêu cầu duyệt phiên bản ${receipt.requestVersion}. Bản thảo vẫn riêng tư.`,
      );
      afterNextRender(
        () => this.currentRequestStateElement?.nativeElement.focus({ preventScroll: true }),
        { injector: this.injector },
      );
    } catch (error) {
      if (token !== this.requestToken) return;
      const mapped = toAuthoringError(error);
      if (mapped.status === 412 || mapped.status === 409) {
        this.pendingWithdrawal = null;
        this.withdrawalState.set('idle');
        this.withdrawalError.set(null);
        await this.loadReadiness(
          'Trạng thái yêu cầu đã thay đổi. Checklist mới đã được tải; hãy rà soát lại trước khi tiếp tục.',
        );
        return;
      }
      if (mapped.status === 0 || mapped.status >= 500) {
        this.withdrawalError.set(mapped);
        this.withdrawalState.set('uncertain');
        this.announcement.set('Chưa xác định được kết quả rút yêu cầu.');
        return;
      }
      this.pendingWithdrawal = null;
      this.withdrawalError.set(mapped);
      this.withdrawalState.set('confirming');
    }
  }

  protected async retryUncertainWithdrawal(): Promise<void> {
    if (this.withdrawalState() !== 'uncertain' || !this.pendingWithdrawal) return;
    this.withdrawalState.set('confirming');
    await this.confirmWithdrawal();
  }

  protected retryReadiness(): Promise<void> {
    if (this.recoveryUrl) return Promise.resolve();
    return this.loadReadiness();
  }

  protected openRightsPolicy(): void {
    this.requestPolicyReview.emit();
  }

  protected onRequestSubmitted(receipt: PublicationRequestReceipt): void {
    this.requestFlowActive.set(false);
    void this.loadReadiness(
      `Đã gửi yêu cầu duyệt phiên bản ${receipt.requestVersion}. Bản thảo vẫn riêng tư.`,
    );
  }

  protected onRequestReadinessUpdated(readiness: PublicationReadiness): void {
    if (readiness.workId !== this.workId) return;
    this.readiness.set(readiness);
    this.state.set('ready');
    this.error.set(null);
    this.announcement.set('Snapshot mới đã tải. Hãy rà soát lại trước khi gửi.');
  }

  protected onRequestActivityChange(active: boolean): void {
    this.requestFlowActive.set(active);
  }

  protected async loadRequestHistory(cursor: string | null = null): Promise<void> {
    if (!this.workId || this.requestHistoryState() === 'loading') return;
    const token = ++this.requestHistoryToken;
    this.failedHistoryCursor = cursor;
    this.requestHistoryState.set('loading');
    this.requestHistoryError.set(null);
    this.requestHistoryAnnouncement.set('');
    try {
      const page = await this.api.getPublicationRequestHistory(this.workId, cursor);
      if (token !== this.requestHistoryToken) return;
      const current = this.requestHistory();
      const items = cursor === null || !current ? page.items : [...current.items, ...page.items];
      const byVersion = new Map(items.map((item) => [item.requestVersion, item]));
      const merged = [...byVersion.values()].sort(
        (left, right) => right.requestVersion - left.requestVersion,
      );
      this.requestHistory.set({ items: merged, nextCursor: page.nextCursor });
      this.requestHistoryState.set('ready');
      this.failedHistoryCursor = null;
      this.requestHistoryAnnouncement.set(
        page.items.length === 0 && merged.length === 0
          ? 'Chưa có hoạt động xét duyệt nào.'
          : cursor === null
            ? `Đã tải ${merged.length} yêu cầu trong lịch sử.`
            : `Đã tải thêm ${page.items.length} yêu cầu cũ.`,
      );
    } catch (error) {
      if (token !== this.requestHistoryToken) return;
      this.requestHistoryError.set(toAuthoringError(error));
      this.requestHistoryState.set('error');
      this.requestHistoryAnnouncement.set('Chưa tải được lịch sử xét duyệt.');
    }
  }

  protected retryRequestHistory(): void {
    if (this.requestHistoryState() !== 'error') return;
    void this.loadRequestHistory(this.failedHistoryCursor);
  }

  protected loadOlderRequestHistory(): void {
    const cursor = this.requestHistory()?.nextCursor;
    if (!cursor || this.requestHistoryState() === 'loading') return;
    void this.loadRequestHistory(cursor);
  }

  protected blockerLabel(code: string): string {
    switch (code) {
      case 'PUBLICATION_RIGHTS_POLICY_NOT_CONFIGURED':
        return 'Chưa có chính sách quyền xuất bản hiện hành được cấu hình.';
      case 'PUBLICATION_RIGHTS_POLICY_NOT_ACCEPTED':
        return 'Bạn cần xem và xác nhận chính sách quyền xuất bản cho bản thảo này.';
      case 'READY_CHAPTER_REQUIRED':
        return 'Cần ít nhất một chương có nội dung đã lưu sẵn sàng.';
      case 'PUBLICATION_REQUEST_ALREADY_PENDING':
        return 'Đã có yêu cầu đang chờ người duyệt.';
      default:
        return 'Cần kiểm tra thêm một điều kiện trước khi tiếp tục.';
    }
  }

  protected scopeLabel(scopeCode: PublicationScopeCode): string {
    switch (scopeCode) {
      case 'PUBLIC_METADATA':
        return 'Thông tin tác phẩm';
      case 'CHAPTER_TEXT':
        return 'Nội dung chương';
      case 'COMMERCIAL_SALE':
        return 'Quyền thương mại';
      case 'TRANSLATION_ADAPTATION':
        return 'Quyền chuyển ngữ/thích nghi';
    }
  }

  protected requestStateLabel(state: PublicationReadiness['currentRequestState']): string {
    switch (state) {
      case 'PENDING_REVIEW':
        return 'Đang chờ người duyệt';
      case 'NEEDS_INFORMATION':
        return 'Người duyệt yêu cầu bổ sung thông tin';
      case 'APPROVED_UNPUBLISHED':
        return 'Đã duyệt · Chưa công khai';
      case 'REJECTED':
        return 'Yêu cầu đã bị từ chối';
      case 'SUPERSEDED':
        return 'Yêu cầu cũ đã được thay thế';
      case 'WITHDRAWN':
        return 'Yêu cầu đã được rút';
      default:
        return 'Chưa có yêu cầu duyệt hiện hành';
    }
  }

  protected historyActionLabel(action: PublicationRequestHistoryAction): string {
    switch (action) {
      case 'SUBMITTED':
        return 'Đã gửi yêu cầu duyệt';
      case 'NEEDS_INFORMATION':
        return 'Người duyệt yêu cầu bổ sung thông tin';
      case 'APPROVED':
        return 'Yêu cầu đã được duyệt, bản thảo vẫn riêng tư';
      case 'REJECTED':
        return 'Yêu cầu đã bị từ chối';
      case 'SUPERSEDED':
        return 'Yêu cầu được thay thế bởi phiên bản mới';
      case 'WITHDRAWN':
        return 'Yêu cầu đã được rút';
    }
  }

  protected historyReasonLabel(reason: PublicationRequestHistoryReason): string {
    switch (reason) {
      case 'INCOMPLETE_CLAIM':
        return 'Khai báo quyền còn thiếu';
      case 'MORE_INFORMATION':
        return 'Cần bổ sung thông tin';
      case 'SCOPE_UNCLEAR':
        return 'Phạm vi quyền chưa rõ';
      case 'RIGHTS_REVIEWED':
        return 'Đã xem xét khai báo quyền';
      case 'SCOPE_CONFIRMED':
        return 'Phạm vi quyền đã được xác nhận';
      case 'RIGHTS_NOT_VERIFIED':
        return 'Chưa xác minh được quyền';
      case 'OUT_OF_SCOPE':
        return 'Nội dung nằm ngoài phạm vi xem xét';
      case 'POLICY_MISMATCH':
        return 'Khai báo chưa phù hợp với chính sách';
    }
  }

  protected historyActorLabel(actorType: PublicationRequestHistoryEvent['actorType']): string {
    return actorType === 'OWNER' ? 'Bạn' : 'Nhóm duyệt';
  }

  protected historyTimestamp(value: string): string {
    return new Intl.DateTimeFormat('vi-VN', {
      dateStyle: 'medium',
      timeStyle: 'short',
      timeZone: 'Asia/Ho_Chi_Minh',
    }).format(new Date(value));
  }

  protected requestHistoryErrorMessage(): string {
    const error = this.requestHistoryError();
    if (error?.status === 401) return 'Phiên đăng nhập đã hết hạn. Hãy đăng nhập lại.';
    if (error?.status === 404) return 'Không tìm thấy bản thảo của bạn.';
    if (error?.status === 503)
      return 'Lịch sử chưa tải được. Dữ liệu bản thảo vẫn được bảo vệ; hãy thử lại.';
    if (error?.status === 502) return 'Máy chủ trả về lịch sử không hợp lệ. Hãy thử tải lại.';
    return 'Chưa tải được lịch sử xét duyệt. Kiểm tra kết nối rồi thử lại.';
  }

  protected errorMessage(): string {
    const error = this.error();
    if (!error) return '';
    if (error.status === 401) return 'Phiên đăng nhập đã hết hạn. Hãy đăng nhập lại.';
    if (error.status === 403 && error.code === 'POLICY_ACCEPTANCE_REQUIRED') {
      return 'Cần xác nhận Điều khoản và Chính sách quyền riêng tư trước khi tiếp tục.';
    }
    if (error.status === 403) return 'Tài khoản chưa thể thực hiện thao tác này.';
    if (error.status === 404) return 'Không tìm thấy bản thảo riêng tư này.';
    if (error.status === 429) {
      return 'Máy chủ đang giới hạn thao tác. Hãy tải lại trạng thái trước khi thử lại.';
    }
    if (this.state() === 'uncertain') {
      return 'Có thể thao tác đã được ghi nhận. Thử lại sẽ gửi đúng yêu cầu với cùng mã an toàn.';
    }
    if (error.status === 503) return 'Dịch vụ xuất bản chưa sẵn sàng. Bản thảo vẫn riêng tư.';
    if (error.status === 502)
      return 'Máy chủ trả về dữ liệu không hợp lệ. Hãy kiểm tra lại trạng thái.';
    return 'Chưa tải được trạng thái xuất bản. Kiểm tra kết nối rồi thử lại.';
  }

  protected withdrawalErrorMessage(): string {
    const error = this.withdrawalError();
    if (!error) return '';
    if (this.withdrawalState() === 'uncertain') {
      return 'Có thể yêu cầu đã được rút. Thử lại sẽ gửi đúng thao tác với cùng mã an toàn.';
    }
    if (error.status === 401) return 'Phiên đăng nhập đã hết hạn. Hãy đăng nhập lại.';
    if (error.status === 403 && error.code === 'CSRF_FAILED') {
      return 'Phiên xác nhận đã hết hạn. Tải lại trạng thái rồi thử lại.';
    }
    if (error.status === 404) return 'Không tìm thấy bản thảo riêng tư này.';
    if (error.status === 503) return 'Dịch vụ chưa sẵn sàng. Bản thảo vẫn riêng tư.';
    if (error.status === 502)
      return 'Máy chủ trả về biên nhận không hợp lệ. Hãy kiểm tra lại trạng thái.';
    return 'Chưa rút được yêu cầu. Hãy kiểm tra trạng thái rồi thử lại.';
  }

  protected publishedDate(value: string): string {
    return new Intl.DateTimeFormat('vi-VN', {
      dateStyle: 'medium',
      timeStyle: 'short',
      timeZone: 'UTC',
    }).format(new Date(value));
  }

  private reset(): void {
    this.requestToken += 1;
    this.state.set('idle');
    this.readiness.set(null);
    this.error.set(null);
    this.announcement.set('');
    this.confirmationOpen.set(false);
    this.receipt.set(null);
    this.requestFlowActive.set(false);
    this.pendingRelease = null;
    this.withdrawalState.set('idle');
    this.withdrawalError.set(null);
    this.pendingWithdrawal = null;
    this.resetRequestHistory();
  }

  private resetRequestHistory(): void {
    this.requestHistoryToken += 1;
    this.requestHistoryState.set('idle');
    this.requestHistory.set(null);
    this.requestHistoryError.set(null);
    this.requestHistoryAnnouncement.set('');
    this.failedHistoryCursor = null;
  }
}

function toAuthoringError(error: unknown): AuthoringApiError {
  return error instanceof AuthoringApiError
    ? error
    : new AuthoringApiError(0, 'AUTHORING_UNAVAILABLE');
}
