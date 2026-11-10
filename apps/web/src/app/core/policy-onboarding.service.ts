import { HttpClient, HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom, timeout } from 'rxjs';

export type PolicyOnboardingStatus =
  | 'idle'
  | 'loading'
  | 'demo'
  | 'pending'
  | 'stale'
  | 'accepted'
  | 'unconfigured'
  | 'error';

export interface PolicyDocumentMetadata {
  version: string;
  effectiveAt: string;
  documentUrl: string;
}

export interface CurrentPolicies {
  terms: PolicyDocumentMetadata;
  privacyNotice: PolicyDocumentMetadata;
}

export interface AccountIdentitySummary {
  displayName: string | null;
  contactEmail: string | null;
}

interface CurrentUserResponse {
  user: { id: string; displayName: string | null; contactEmail: string | null };
  policy: {
    termsAcceptedVersion: string | null;
    privacyNoticeAcknowledgedVersion: string | null;
    requiredAcceptanceComplete: boolean;
  };
}

interface ApiProblem {
  code?: string;
}

interface PolicyAcceptanceResponse {
  termsAcceptedVersion: string;
  termsAcceptedAt: string;
  privacyNoticeAcknowledgedVersion: string;
  privacyNoticeAcknowledgedAt: string;
}

@Injectable({ providedIn: 'root' })
export class PolicyOnboardingService {
  private readonly http = inject(HttpClient);
  private loadPromise?: Promise<void>;

  readonly status = signal<PolicyOnboardingStatus>('idle');
  readonly accountIdentity = signal<AccountIdentitySummary | null>(null);
  readonly currentPolicies = signal<CurrentPolicies | null>(null);
  readonly termsDocument = signal<string | null>(null);
  readonly privacyDocument = signal<string | null>(null);
  readonly termsAcceptedVersion = signal<string | null>(null);
  readonly privacyAcknowledgedVersion = signal<string | null>(null);
  readonly errorMessage = signal<string | null>(null);
  readonly demoNotice = signal<string | null>(null);

  async load(force = false): Promise<void> {
    if (!force && this.loadPromise) return this.loadPromise;
    if (!force && this.status() !== 'idle') return;

    this.status.set('loading');
    this.errorMessage.set(null);
    this.loadPromise = this.resolveServerState();
    try {
      await this.loadPromise;
    } finally {
      this.loadPromise = undefined;
    }
  }

  async acceptCurrentPolicies(): Promise<void> {
    const current = this.currentPolicies();
    if (!current || (this.status() !== 'pending' && this.status() !== 'stale')) return;

    try {
      const csrf = await firstValueFrom(
        this.http.get<{ token: string }>('/api/v1/auth/csrf').pipe(timeout(5_000)),
      );
      const acceptance = await firstValueFrom(
        this.http
          .post<PolicyAcceptanceResponse>(
            '/api/v1/auth/policies/accept',
            {
              termsVersion: current.terms.version,
              privacyNoticeVersion: current.privacyNotice.version,
              termsAccepted: true,
              privacyNoticeAcknowledged: true,
            },
            { headers: new HttpHeaders({ 'X-CSRF-Token': csrf.token }) },
          )
          .pipe(timeout(5_000)),
      );
      if (!isAcceptanceForCurrentVersions(acceptance, current)) {
        throw new Error('The server did not confirm the current policy versions.');
      }

      this.termsAcceptedVersion.set(current.terms.version);
      this.privacyAcknowledgedVersion.set(current.privacyNotice.version);
      this.status.set('accepted');
      this.errorMessage.set(null);
    } catch (error) {
      if (isProblem(error, 'POLICY_VERSION_STALE')) {
        await this.load(true);
        if (this.status() === 'pending') this.status.set('stale');
        this.errorMessage.set('Có phiên bản chính sách mới. Hãy đọc lại và xác nhận phiên bản hiện hành.');
        return;
      }
      if (isProblem(error, 'POLICY_NOT_CONFIGURED')) {
        this.status.set('unconfigured');
        this.errorMessage.set('Chính sách hiện hành chưa được cấu hình. Bạn chưa thể tiếp tục bằng tài khoản server.');
        return;
      }
      if (isProblem(error, 'UNAUTHENTICATED') || statusOf(error) === 401) {
        this.status.set('error');
        this.errorMessage.set('Phiên đăng nhập đã hết hạn. Hãy đăng nhập lại để tiếp tục.');
        return;
      }
      this.status.set('error');
      this.errorMessage.set('Chưa ghi nhận được lựa chọn. Kiểm tra kết nối rồi thử lại; không có chấp thuận nào được giả định.');
    }
  }

  private async resolveServerState(): Promise<void> {
    let account: CurrentUserResponse;
    try {
      account = await firstValueFrom(
        this.http.get<CurrentUserResponse>('/api/v1/auth/me').pipe(timeout(5_000)),
      );
    } catch (error) {
      const httpStatus = statusOf(error);
      if (httpStatus === 401 || httpStatus === 404 || httpStatus === 502 || httpStatus === 0) {
        this.enterDemoMode(httpStatus);
        return;
      }
      this.fail('Không thể xác minh phiên đăng nhập. Hãy thử tải lại trước khi tiếp tục.');
      return;
    }

    if (!isCurrentUser(account)) {
      this.fail('Máy chủ trả về trạng thái tài khoản không hợp lệ. Hãy thử lại sau.');
      return;
    }

    this.accountIdentity.set({
      displayName: account.user.displayName,
      contactEmail: account.user.contactEmail,
    });

    this.termsAcceptedVersion.set(account.policy.termsAcceptedVersion);
    this.privacyAcknowledgedVersion.set(account.policy.privacyNoticeAcknowledgedVersion);

    let policies: CurrentPolicies;
    try {
      policies = await firstValueFrom(
        this.http.get<CurrentPolicies>('/api/v1/auth/policies/current').pipe(timeout(5_000)),
      );
      if (!isCurrentPolicies(policies)) throw new Error('Invalid policy catalog');
    } catch (error) {
      if (isProblem(error, 'POLICY_NOT_CONFIGURED')) {
        this.status.set('unconfigured');
        this.errorMessage.set('Terms và Thông báo quyền riêng tư đã duyệt chưa được cấu hình. Tài khoản server vẫn bị khóa.');
      } else {
        this.fail('Không tải được chính sách hiện hành. Hãy thử lại; các chức năng tài khoản vẫn bị khóa.');
      }
      return;
    }

    try {
      const [terms, privacyNotice] = await Promise.all([
        this.fetchDocument(policies.terms.documentUrl),
        this.fetchDocument(policies.privacyNotice.documentUrl),
      ]);
      this.currentPolicies.set(policies);
      this.termsDocument.set(terms);
      this.privacyDocument.set(privacyNotice);
    } catch {
      this.currentPolicies.set(policies);
      this.fail('Không tải đủ nội dung chính sách. Không thể xác nhận khi chưa đọc được cả hai tài liệu.');
      return;
    }

    const hasPreviousDecision = !!account.policy.termsAcceptedVersion
      || !!account.policy.privacyNoticeAcknowledgedVersion;
    const matchesCurrentVersions = account.policy.termsAcceptedVersion === policies.terms.version
      && account.policy.privacyNoticeAcknowledgedVersion === policies.privacyNotice.version;

    if (account.policy.requiredAcceptanceComplete && matchesCurrentVersions) {
      this.status.set('accepted');
    } else {
      this.status.set(hasPreviousDecision ? 'stale' : 'pending');
    }
  }

  private async fetchDocument(url: string): Promise<string> {
    const safePath = safePolicyDocumentPath(url);
    return firstValueFrom(this.http.get(safePath, { responseType: 'text' }).pipe(timeout(5_000)));
  }

  private enterDemoMode(httpStatus: number): void {
    this.accountIdentity.set(null);
    this.status.set('demo');
    if (httpStatus === 401) {
      this.demoNotice.set('Chưa có phiên đăng nhập server. Lựa chọn dưới đây chỉ dùng trong demo và sẽ mất khi tải lại trang.');
    } else {
      this.demoNotice.set('API local chưa phản hồi hoặc chưa bật session. Chỉ có chức năng demo cục bộ; lựa chọn không được gửi lên máy chủ.');
    }
  }

  private fail(message: string): void {
    this.status.set('error');
    this.errorMessage.set(message);
  }
}

function statusOf(error: unknown): number {
  return error instanceof HttpErrorResponse ? error.status : -1;
}

function isProblem(error: unknown, code: string): boolean {
  return error instanceof HttpErrorResponse
    && typeof error.error === 'object'
    && error.error !== null
    && (error.error as ApiProblem).code === code;
}

function isCurrentUser(value: unknown): value is CurrentUserResponse {
  if (typeof value !== 'object' || value === null) return false;
  const candidate = value as Partial<CurrentUserResponse>;
  return typeof candidate.user?.id === 'string'
    && typeof candidate.policy?.requiredAcceptanceComplete === 'boolean'
    && (candidate.policy.termsAcceptedVersion === null || typeof candidate.policy.termsAcceptedVersion === 'string')
    && (candidate.policy.privacyNoticeAcknowledgedVersion === null
      || typeof candidate.policy.privacyNoticeAcknowledgedVersion === 'string');
}

function isCurrentPolicies(value: unknown): value is CurrentPolicies {
  if (typeof value !== 'object' || value === null) return false;
  const candidate = value as Partial<CurrentPolicies>;
  return isPolicyMetadata(candidate.terms) && isPolicyMetadata(candidate.privacyNotice);
}

function isPolicyMetadata(value: unknown): value is PolicyDocumentMetadata {
  if (typeof value !== 'object' || value === null) return false;
  const candidate = value as Partial<PolicyDocumentMetadata>;
  return typeof candidate.version === 'string'
    && typeof candidate.effectiveAt === 'string'
    && typeof candidate.documentUrl === 'string';
}

function isAcceptanceForCurrentVersions(
  value: unknown,
  current: CurrentPolicies,
): value is PolicyAcceptanceResponse {
  if (typeof value !== 'object' || value === null) return false;
  const acceptance = value as Partial<PolicyAcceptanceResponse>;
  return acceptance.termsAcceptedVersion === current.terms.version
    && acceptance.privacyNoticeAcknowledgedVersion === current.privacyNotice.version
    && typeof acceptance.termsAcceptedAt === 'string'
    && Number.isFinite(Date.parse(acceptance.termsAcceptedAt))
    && typeof acceptance.privacyNoticeAcknowledgedAt === 'string'
    && Number.isFinite(Date.parse(acceptance.privacyNoticeAcknowledgedAt));
}

function safePolicyDocumentPath(url: string): string {
  const parsed = new URL(url, window.location.origin);
  if (parsed.origin !== window.location.origin || !parsed.pathname.startsWith('/api/v1/auth/policies/')) {
    throw new Error('Policy document URL must stay on the same origin.');
  }
  return `${parsed.pathname}${parsed.search}`;
}
