import { DatePipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import {
  AuthoringCollaborationApiError,
  AuthoringCollaborationApiService,
  ManuscriptInvitationPreview,
  ManuscriptPermission,
} from '../core/authoring-collaboration-api.service';

type PageState = 'loading' | 'ready' | 'saving' | 'accepted' | 'rejected' | 'error';
const tokenPattern = /^[A-Za-z0-9_-]{43}$/;
const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

@Component({
  selector: 'app-authoring-invitation-page',
  imports: [DatePipe, RouterLink],
  templateUrl: './authoring-invitation-page.component.html',
  styleUrl: './authoring-invitation-page.component.css',
})
export class AuthoringInvitationPageComponent {
  private readonly route = inject(ActivatedRoute);
  private readonly api = inject(AuthoringCollaborationApiService);
  private token: string | null = null;

  protected readonly invitationId = this.route.snapshot.paramMap.get('invitationId') ?? '';
  protected readonly invitation = signal<ManuscriptInvitationPreview | null>(null);
  protected readonly state = signal<PageState>('loading');
  protected readonly message = signal('Đang kiểm tra lời mời.');

  constructor() {
    this.token = this.captureToken();
    if (!uuidPattern.test(this.invitationId) || !this.token) {
      this.showUnavailable();
      return;
    }
    void this.load();
  }

  protected get signInReturnTo(): string {
    return `/manuscript-invitations/${encodeURIComponent(this.invitationId)}`;
  }

  protected get policyReturnUrl(): string {
    return this.signInReturnTo;
  }

  protected permissionLabel(permission: ManuscriptPermission): string {
    return permission === 'VIEW'
      ? 'Xem bản thảo và nội dung đã lưu'
      : 'Chỉnh sửa thông tin và chương nháp';
  }

  protected async retry(): Promise<void> {
    await this.load();
  }

  protected async decide(decision: 'accept' | 'reject'): Promise<void> {
    const token = this.token;
    if (!token || !this.invitation() || this.state() !== 'ready') return;
    this.state.set('saving');
    this.message.set(
      decision === 'accept'
        ? 'Đang ghi nhận bạn chấp nhận lời mời…'
        : 'Đang ghi nhận bạn từ chối lời mời…',
    );
    try {
      const status = await this.api.decideInvitation(this.invitationId, token, decision);
      this.clearToken();
      this.token = null;
      this.state.set(status);
      this.message.set(
        status === 'accepted'
          ? 'Bạn đã chấp nhận lời mời. Bản thảo sẽ xuất hiện trong mục Được chia sẻ với tôi.'
          : 'Bạn đã từ chối lời mời này. Liên kết không thể dùng lại.',
      );
    } catch (error) {
      this.showError(error);
    }
  }

  private async load(): Promise<void> {
    if (!this.token) return this.showUnavailable();
    this.state.set('loading');
    this.message.set('Đang kiểm tra phiên đăng nhập, email đã xác minh và thời hạn lời mời.');
    try {
      const invitation = await this.api.resolveInvitation(this.invitationId, this.token);
      this.invitation.set(invitation);
      this.state.set('ready');
      this.message.set(
        'Kiểm tra quyền truy cập bên dưới trước khi quyết định. Chưa chấp nhận thì chưa xem được nội dung.',
      );
    } catch (error) {
      this.showError(error);
    }
  }

  private captureToken(): string | null {
    const key = tokenStorageKey(this.invitationId);
    let token: string | null = null;
    try {
      const fragmentToken = new URLSearchParams(window.location.hash.slice(1)).get('token');
      if (fragmentToken && tokenPattern.test(fragmentToken)) {
        sessionStorage.setItem(key, fragmentToken);
        token = fragmentToken;
      } else {
        const stored = sessionStorage.getItem(key);
        if (stored && tokenPattern.test(stored)) token = stored;
      }
    } catch {
      token = null;
    }
    if (window.location.hash) {
      window.history.replaceState(
        window.history.state,
        document.title,
        `${window.location.pathname}${window.location.search}`,
      );
    }
    return token;
  }

  private clearToken(): void {
    try {
      sessionStorage.removeItem(tokenStorageKey(this.invitationId));
    } catch {
      // The server has consumed the one-use token even if local cleanup is unavailable.
    }
  }

  private showError(error: unknown): void {
    if (error instanceof AuthoringCollaborationApiError && error.status === 401) {
      this.state.set('error');
      this.message.set('Đăng nhập bằng Google để kiểm tra email đã xác minh của người nhận.');
      return;
    }
    if (
      error instanceof AuthoringCollaborationApiError &&
      error.status === 403 &&
      error.code === 'POLICY_ACCEPTANCE_REQUIRED'
    ) {
      this.state.set('error');
      this.message.set('Hãy xem và xác nhận chính sách hiện hành để xử lý lời mời.');
      return;
    }
    if (error instanceof AuthoringCollaborationApiError && error.status === 503) {
      this.state.set('error');
      this.message.set('Lời mời cộng tác đang tạm thời không khả dụng. Hãy thử lại sau.');
      return;
    }
    this.clearToken();
    this.token = null;
    this.showUnavailable();
  }

  private showUnavailable(): void {
    this.invitation.set(null);
    this.state.set('error');
    this.message.set(
      'Lời mời không hợp lệ, đã hết hạn hoặc không khớp với email đã xác minh của bạn. Hãy nhờ owner gửi lời mời mới.',
    );
  }
}

function tokenStorageKey(invitationId: string): string {
  return `novel.manuscript-invitation-token.v1.${invitationId}`;
}
