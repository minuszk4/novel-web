import {
  afterNextRender,
  Component,
  ElementRef,
  EventEmitter,
  Injector,
  Input,
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
  PublicationRightsPolicy,
  newAuthoringIdempotencyKey,
} from '../core/authoring-api.service';

type PolicyPanelState =
  | 'closed'
  | 'loading'
  | 'ready'
  | 'submitting'
  | 'uncertain'
  | 'accepted'
  | 'unavailable'
  | 'error';

interface AcceptanceAttempt {
  policyVersion: string;
  policySha256: string;
  idempotencyKey: string;
}

@Component({
  selector: 'app-publication-rights-policy-panel',
  imports: [RouterLink],
  templateUrl: './publication-rights-policy-panel.component.html',
  styleUrl: './publication-rights-policy-panel.component.css',
})
export class PublicationRightsPolicyPanelComponent implements OnChanges {
  @Input({ required: true })
  workId = '';

  @Input()
  workTitle = '';

  @Input()
  openRequest = 0;

  @Output()
  policyAccepted = new EventEmitter<void>();

  protected readonly state = signal<PolicyPanelState>('closed');
  protected readonly policy = signal<PublicationRightsPolicy | null>(null);
  protected readonly confirmed = signal(false);
  protected readonly announcement = signal('');
  protected readonly errorMessage = signal('');

  private readonly api = inject(AuthoringApiService);
  private readonly injector = inject(Injector);
  private requestToken = 0;
  private pendingAttempt: AcceptanceAttempt | null = null;

  @ViewChild('policyHeading')
  private policyHeading?: ElementRef<HTMLHeadingElement>;

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['workId']) this.reset();
    if (changes['openRequest'] && !changes['openRequest'].firstChange && this.openRequest > 0) {
      void this.loadPolicy();
    }
  }

  protected get isBusy(): boolean {
    return this.state() === 'loading' || this.state() === 'submitting';
  }

  protected get isOpen(): boolean {
    return this.state() !== 'closed';
  }

  protected get recoveryUrl(): string | null {
    if (this.state() !== 'error') return null;
    if (this.errorMessage().startsWith('Phiên đăng nhập')) return '/sign-in';
    if (this.errorMessage().startsWith('Cần xác nhận Điều khoản')) return '/app/setup';
    if (this.errorMessage().startsWith('Không tìm thấy')) return '/app/library';
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

  protected get recoveryLabel(): string {
    if (this.recoveryUrl === '/sign-in') return 'Đăng nhập lại';
    if (this.recoveryUrl === '/app/setup') return 'Xem chính sách tài khoản';
    return 'Về thư viện';
  }

  protected formatEffectiveDate(value: string): string {
    return new Intl.DateTimeFormat('vi-VN', {
      dateStyle: 'long',
      timeStyle: 'short',
      timeZone: 'UTC',
    }).format(new Date(value));
  }

  protected async togglePolicy(): Promise<void> {
    if (this.isBusy || this.state() === 'uncertain') return;
    if (this.state() === 'closed') {
      await this.loadPolicy();
      return;
    }
    this.reset();
  }

  protected retryPolicyRead(): Promise<void> {
    if (this.isBusy) return Promise.resolve();
    return this.loadPolicy();
  }

  protected updateConfirmation(event: Event): void {
    if (this.state() !== 'ready') return;
    const checkbox = event.target as HTMLInputElement;
    this.confirmed.set(checkbox.checked);
    this.announcement.set('');
    this.errorMessage.set('');
    this.pendingAttempt = null;
  }

  protected async confirmPolicy(): Promise<void> {
    const current = this.policy();
    if (
      !current ||
      !this.confirmed() ||
      (this.state() !== 'ready' && this.state() !== 'uncertain')
    ) {
      return;
    }

    const token = this.requestToken;
    const attempt = this.pendingAttempt ?? {
      policyVersion: current.version,
      policySha256: current.policySha256,
      idempotencyKey: newAuthoringIdempotencyKey(),
    };
    this.pendingAttempt = attempt;
    this.state.set('submitting');
    this.errorMessage.set('');
    this.announcement.set('');

    try {
      const receipt = await this.api.acceptCurrentPublicationRightsPolicy(
        this.workId,
        attempt.policyVersion,
        attempt.policySha256,
        attempt.idempotencyKey,
      );
      if (token !== this.requestToken) return;
      this.pendingAttempt = null;
      this.policy.set({ ...current, acceptedAt: receipt.acceptedAt });
      this.confirmed.set(false);
      this.state.set('accepted');
      this.policyAccepted.emit();
      this.announcement.set(
        `Đã ghi nhận xác nhận policy phiên bản ${receipt.policyVersion} cho bản thảo này lúc ${this.formatEffectiveDate(receipt.acceptedAt)}. Xác nhận này không xác minh quyền tác giả hoặc giấy phép.`,
      );
      this.focusPolicyHeading();
    } catch (error) {
      if (token !== this.requestToken) return;
      const mapped = toAuthoringError(error);
      if (mapped.status === 409 && mapped.code === 'PUBLICATION_RIGHTS_POLICY_STALE') {
        this.pendingAttempt = null;
        this.confirmed.set(false);
        this.policy.set(null);
        this.announcement.set('Chính sách đã đổi. Hãy đọc và xác nhận phiên bản hiện hành.');
        await this.loadPolicy();
        this.announcement.set('Chính sách đã đổi. Hãy đọc và xác nhận phiên bản hiện hành.');
        return;
      }
      if (mapped.status === 0 || mapped.status >= 500) {
        this.state.set('uncertain');
        this.errorMessage.set(
          'Chưa xác định được kết quả. Có thể thử lại đúng xác nhận này bằng cùng mã an toàn.',
        );
        return;
      }

      this.pendingAttempt = null;
      this.errorMessage.set(this.acceptanceErrorMessage(mapped));
      this.state.set(
        mapped.status === 401 || mapped.status === 403 || mapped.status === 404 ? 'error' : 'ready',
      );
    }
  }

  protected retryUncertainAcceptance(): Promise<void> {
    if (this.state() !== 'uncertain' || !this.pendingAttempt) return Promise.resolve();
    this.state.set('uncertain');
    return this.confirmPolicy();
  }

  private async loadPolicy(): Promise<void> {
    const token = ++this.requestToken;
    this.state.set('loading');
    this.policy.set(null);
    this.confirmed.set(false);
    this.pendingAttempt = null;
    this.errorMessage.set('');
    this.announcement.set('');

    try {
      const policy = await this.api.getCurrentPublicationRightsPolicy(this.workId);
      if (token !== this.requestToken) return;
      this.policy.set(policy);
      this.state.set(policy.acceptedAt ? 'accepted' : 'ready');
      if (policy.acceptedAt) {
        this.announcement.set(
          `Đã ghi nhận xác nhận policy phiên bản ${policy.version} cho bản thảo này lúc ${this.formatEffectiveDate(policy.acceptedAt)}. Xác nhận này không xác minh quyền tác giả hoặc giấy phép.`,
        );
      }
      this.focusPolicyHeading();
    } catch (error) {
      if (token !== this.requestToken) return;
      const mapped = toAuthoringError(error);
      this.errorMessage.set(this.readErrorMessage(mapped));
      this.state.set(mapped.status === 503 ? 'unavailable' : 'error');
      this.focusPolicyHeading();
    }
  }

  private acceptanceErrorMessage(error: AuthoringApiError): string {
    if (error.status === 401) return 'Phiên đăng nhập đã hết hạn. Hãy đăng nhập lại.';
    if (error.status === 403 && error.code === 'POLICY_ACCEPTANCE_REQUIRED') {
      return 'Cần xác nhận Điều khoản và Chính sách quyền riêng tư hiện hành trước.';
    }
    if (error.status === 404) return 'Không tìm thấy bản thảo riêng tư này.';
    if (error.status === 409 && error.code === 'IDEMPOTENCY_KEY_REUSED') {
      return 'Mã xác nhận đã được dùng cho yêu cầu khác. Hãy xác nhận lại để gửi yêu cầu mới.';
    }
    return 'Chưa ghi nhận được xác nhận. Hãy kiểm tra lại thao tác rồi thử lại.';
  }

  private readErrorMessage(error: AuthoringApiError): string {
    if (error.status === 401) return 'Phiên đăng nhập đã hết hạn. Hãy đăng nhập lại.';
    if (error.status === 403 && error.code === 'POLICY_ACCEPTANCE_REQUIRED') {
      return 'Cần xác nhận Điều khoản và Chính sách quyền riêng tư hiện hành trước.';
    }
    if (error.status === 404) return 'Không tìm thấy bản thảo riêng tư này.';
    if (error.status === 503) {
      return 'Chưa thể xác nhận chính sách quyền xuất bản. Chính sách hiện hành chưa được cấu hình hoặc chưa thể xác minh. Bản thảo vẫn riêng tư.';
    }
    return 'Chưa tải được chính sách quyền xuất bản. Kiểm tra kết nối rồi thử lại.';
  }

  private focusPolicyHeading(): void {
    afterNextRender(() => this.policyHeading?.nativeElement.focus({ preventScroll: true }), {
      injector: this.injector,
    });
  }

  private reset(): void {
    this.requestToken += 1;
    this.state.set('closed');
    this.policy.set(null);
    this.confirmed.set(false);
    this.announcement.set('');
    this.errorMessage.set('');
    this.pendingAttempt = null;
  }
}

function toAuthoringError(error: unknown): AuthoringApiError {
  return error instanceof AuthoringApiError
    ? error
    : new AuthoringApiError(0, 'AUTHORING_UNAVAILABLE');
}
