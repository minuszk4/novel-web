import { Component, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import {
  RecipientWorkspaceInvitation,
  WorkspaceApiError,
  WorkspaceApiService,
  WorkspaceMemberRole,
} from '../core/workspace-api.service';

type InvitationPageState = 'loading' | 'ready' | 'saving' | 'accepted' | 'rejected' | 'error';

const tokenPattern = /^[A-Za-z0-9_-]{43}$/;

@Component({
  selector: 'app-workspace-invitation-page',
  imports: [RouterLink],
  templateUrl: './workspace-invitation-page.component.html',
  styleUrl: './workspace-invitation-page.component.css',
})
export class WorkspaceInvitationPageComponent {
  private readonly route = inject(ActivatedRoute);
  private readonly api = inject(WorkspaceApiService);
  private token: string | null = null;

  protected readonly invitationId = signal('');
  protected readonly invitation = signal<RecipientWorkspaceInvitation | null>(null);
  protected readonly state = signal<InvitationPageState>('loading');
  protected readonly message = signal('Đang kiểm tra liên kết lời mời.');

  constructor() {
    const invitationId = this.route.snapshot.paramMap.get('invitationId') ?? '';
    this.invitationId.set(invitationId);
    this.token = this.captureToken(invitationId);
    if (!isUuid(invitationId) || !this.token) {
      this.showUnavailable();
      return;
    }
    void this.loadInvitation();
  }

  protected get signInReturnTo(): string {
    return `/workspace-invitations/${encodeURIComponent(this.invitationId())}`;
  }

  protected roleLabel(role: WorkspaceMemberRole): string {
    return role === 'admin' ? 'quản trị viên' : role === 'editor' ? 'biên tập viên' : 'người đọc';
  }

  protected formatExpiry(value: string): string {
    return new Intl.DateTimeFormat('vi-VN', { dateStyle: 'long', timeStyle: 'short' }).format(
      new Date(value),
    );
  }

  protected async retry(): Promise<void> {
    if (!this.token) {
      this.showUnavailable();
      return;
    }
    await this.loadInvitation();
  }

  protected async decide(decision: 'accept' | 'reject'): Promise<void> {
    const token = this.token;
    const invitation = this.invitation();
    if (!token || !invitation || this.state() !== 'ready') return;
    this.state.set('saving');
    this.message.set(
      decision === 'accept' ? 'Đang thêm bạn vào workspace…' : 'Đang ghi nhận từ chối…',
    );
    try {
      const status = await this.api.decideInvitation(this.invitationId(), token, decision);
      this.clearToken();
      this.token = null;
      this.state.set(status);
      this.message.set(
        status === 'accepted'
          ? 'Bạn đã tham gia workspace. Quyền truy cập đã được cập nhật.'
          : 'Bạn đã từ chối lời mời này. Liên kết không thể dùng lại.',
      );
    } catch (error) {
      this.showError(error);
    }
  }

  private async loadInvitation(): Promise<void> {
    const token = this.token;
    if (!token) {
      this.showUnavailable();
      return;
    }
    this.state.set('loading');
    this.message.set('Đang kiểm tra tài khoản và quyền truy cập lời mời.');
    try {
      const invitation = await this.api.resolveInvitation(this.invitationId(), token);
      this.invitation.set(invitation);
      this.state.set('ready');
      this.message.set('Kiểm tra thông tin bên dưới trước khi quyết định.');
    } catch (error) {
      this.showError(error);
    }
  }

  private captureToken(invitationId: string): string | null {
    const key = tokenStorageKey(invitationId);
    let token: string | null = null;
    try {
      const fromFragment = new URLSearchParams(window.location.hash.slice(1)).get('token');
      if (fromFragment && tokenPattern.test(fromFragment)) {
        sessionStorage.setItem(key, fromFragment);
        token = fromFragment;
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
      sessionStorage.removeItem(tokenStorageKey(this.invitationId()));
    } catch {
      // The consumed server token is already invalid even if local cleanup is unavailable.
    }
  }

  private showError(error: unknown): void {
    if (error instanceof WorkspaceApiError && error.status === 401) {
      this.state.set('error');
      this.message.set('Hãy đăng nhập Google để xác minh bạn là người nhận lời mời.');
      return;
    }
    if (
      error instanceof WorkspaceApiError &&
      error.status === 403 &&
      error.code === 'POLICY_ACCEPTANCE_REQUIRED'
    ) {
      this.state.set('error');
      this.message.set('Hãy xem và xác nhận chính sách hiện hành để tiếp tục xử lý lời mời.');
      return;
    }
    if (error instanceof WorkspaceApiError && error.status === 503) {
      this.state.set('error');
      this.message.set('Dịch vụ lời mời đang tạm thời không khả dụng. Hãy thử lại sau.');
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
      'Liên kết không hợp lệ, đã hết hạn hoặc đã được sử dụng. Hãy nhờ quản trị viên gửi lời mời mới.',
    );
  }
}

function tokenStorageKey(invitationId: string): string {
  return `novel.workspace-invitation-token.v1.${invitationId}`;
}

function isUuid(value: string): boolean {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value);
}
