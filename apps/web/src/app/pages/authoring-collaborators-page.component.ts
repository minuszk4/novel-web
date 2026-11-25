import { Component, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { ActivatedRoute, RouterLink } from '@angular/router';
import {
  AuthoringCollaborationApiError,
  AuthoringCollaborationApiService,
  ManuscriptCollaborationManagement,
  ManuscriptCollaborator,
  ManuscriptInvitationSummary,
  ManuscriptPermission,
} from '../core/authoring-collaboration-api.service';

type PageState = 'loading' | 'ready' | 'error';

interface PermissionChangeAttempt {
  invitationId: string;
  permissions: ManuscriptPermission[];
}

interface InvitationAttempt {
  invitationId: string;
  email: string;
  permissions: ManuscriptPermission[];
}

@Component({
  selector: 'app-authoring-collaborators-page',
  imports: [DatePipe, RouterLink],
  templateUrl: './authoring-collaborators-page.component.html',
  styleUrl: './authoring-collaborators-page.component.css',
})
export class AuthoringCollaboratorsPageComponent {
  private readonly route = inject(ActivatedRoute);
  private readonly api = inject(AuthoringCollaborationApiService);
  private readonly permissionAttempts = new Map<string, PermissionChangeAttempt>();

  protected readonly workId = this.route.snapshot.paramMap.get('workId') ?? '';
  protected readonly state = signal<PageState>('loading');
  protected readonly management = signal<ManuscriptCollaborationManagement | null>(null);
  protected readonly email = signal('');
  protected readonly includeEdit = signal(false);
  protected readonly consent = signal(false);
  protected readonly busy = signal(false);
  protected readonly invitationAttempt = signal<InvitationAttempt | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly editChoices = signal<Record<string, boolean>>({});
  protected readonly editConsents = signal<Record<string, boolean>>({});

  constructor() {
    this.restoreInvitationAttempt();
    void this.reload();
  }

  protected get signInReturnTo(): string {
    return `/app/authoring/${encodeURIComponent(this.workId)}/collaborators`;
  }

  protected permissionLabel(permissions: ManuscriptPermission[]): string {
    return permissions.includes('EDIT') ? 'Xem và chỉnh sửa' : 'Chỉ xem';
  }

  protected updateEmail(event: Event): void {
    if (this.invitationAttempt()) return;
    this.email.set((event.target as HTMLInputElement).value);
    this.error.set(null);
  }

  protected updateIncludeEdit(event: Event): void {
    if (this.invitationAttempt()) return;
    this.includeEdit.set((event.target as HTMLInputElement).checked);
    this.error.set(null);
  }

  protected updateConsent(event: Event): void {
    this.consent.set((event.target as HTMLInputElement).checked);
    this.error.set(null);
  }

  protected updateEditChoice(grantId: string, event: Event): void {
    this.editChoices.update((choices) => ({
      ...choices,
      [grantId]: (event.target as HTMLInputElement).checked,
    }));
  }

  protected updateEditConsent(grantId: string, event: Event): void {
    this.editConsents.update((consents) => ({
      ...consents,
      [grantId]: (event.target as HTMLInputElement).checked,
    }));
  }

  protected async sendInvitation(event: Event): Promise<void> {
    event.preventDefault();
    if (this.busy() || !this.consent()) {
      if (!this.consent()) this.error.set('Hãy xác nhận quyền liên hệ trước khi gửi lời mời.');
      return;
    }
    const normalized = this.email().trim().toLowerCase();
    if (
      !this.invitationAttempt() &&
      (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(normalized) || normalized.length > 320)
    ) {
      this.error.set('Nhập địa chỉ email hợp lệ, tối đa 320 ký tự.');
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    this.notice.set(null);
    let attempt = this.invitationAttempt();
    if (!attempt) {
      attempt = {
        invitationId: newInvitationId(),
        email: normalized,
        permissions: this.includeEdit() ? ['EDIT', 'VIEW'] : ['VIEW'],
      };
      this.invitationAttempt.set(attempt);
      this.persistInvitationAttempt(attempt);
    }
    try {
      const result = await this.api.createInvitation(
        this.workId,
        attempt.invitationId,
        attempt.email,
        attempt.permissions,
      );
      this.invitationAttempt.set(null);
      this.clearInvitationAttempt();
      this.email.set('');
      this.includeEdit.set(false);
      this.consent.set(false);
      this.notice.set(
        result.status === 'pending'
          ? 'Đã xếp hàng lời mời. Việc xếp hàng chưa xác nhận email đã được gửi.'
          : 'Lời mời này đã được phản hồi hoặc thu hồi. Danh sách đã được cập nhật.',
      );
      await this.reload();
    } catch (error) {
      this.error.set(
        this.errorMessage(error, 'Chưa xếp hàng được lời mời. Kiểm tra kết nối rồi thử lại.'),
      );
    } finally {
      this.busy.set(false);
    }
  }

  protected async changePermissions(grant: ManuscriptCollaborator): Promise<void> {
    if (this.busy()) return;
    if (!this.editConsents()[grant.id]) {
      this.error.set('Hãy xác nhận quyền liên hệ trước khi gửi yêu cầu thay đổi quyền.');
      return;
    }
    const permissions: ManuscriptPermission[] = this.editChoices()[grant.id]
      ? ['EDIT', 'VIEW']
      : ['VIEW'];
    if (permissions.join('|') === grant.permissions.join('|')) {
      this.error.set('Hãy chọn bộ quyền khác với quyền hiện tại.');
      return;
    }
    let attempt = this.permissionAttempts.get(grant.id);
    if (!attempt || attempt.permissions.join('|') !== permissions.join('|')) {
      attempt = { invitationId: newInvitationId(), permissions };
      this.permissionAttempts.set(grant.id, attempt);
    }
    this.busy.set(true);
    this.error.set(null);
    this.notice.set(null);
    try {
      await this.api.changePermissions(
        this.workId,
        grant,
        attempt.invitationId,
        attempt.permissions,
      );
      this.permissionAttempts.delete(grant.id);
      this.editConsents.update((consents) => ({ ...consents, [grant.id]: false }));
      this.notice.set(
        'Đã xếp hàng lời mời cập nhật quyền. Quyền hiện tại có hiệu lực đến khi người nhận chấp nhận.',
      );
      await this.reload();
    } catch (error) {
      this.error.set(
        this.errorMessage(
          error,
          'Chưa gửi được yêu cầu cập nhật quyền. Hãy tải lại quyền hiện tại rồi thử lại.',
        ),
      );
      if (error instanceof AuthoringCollaborationApiError && error.status === 412) {
        await this.reload();
      }
    } finally {
      this.busy.set(false);
    }
  }

  protected async revokeInvitation(invitation: ManuscriptInvitationSummary): Promise<void> {
    if (
      this.busy() ||
      !window.confirm(`Thu hồi lời mời có quyền “${this.permissionLabel(invitation.permissions)}”?`)
    )
      return;
    this.busy.set(true);
    this.error.set(null);
    try {
      await this.api.revokeInvitation(this.workId, invitation.id);
      this.notice.set('Đã thu hồi lời mời đang chờ.');
      await this.reload();
    } catch (error) {
      this.error.set(this.errorMessage(error, 'Chưa thu hồi được lời mời. Hãy tải lại danh sách.'));
    } finally {
      this.busy.set(false);
    }
  }

  protected async revokeCollaborator(grant: ManuscriptCollaborator): Promise<void> {
    if (
      this.busy() ||
      !window.confirm(
        `Thu hồi quyền “${this.permissionLabel(grant.permissions)}” của cộng tác viên này?`,
      )
    )
      return;
    this.busy.set(true);
    this.error.set(null);
    try {
      await this.api.revokeCollaborator(this.workId, grant);
      this.notice.set(
        'Đã thu hồi quyền cộng tác. Quyền bị thu hồi có hiệu lực sau các yêu cầu đang xử lý.',
      );
      await this.reload();
    } catch (error) {
      this.error.set(
        this.errorMessage(error, 'Quyền có thể đã thay đổi ở phiên khác. Hãy tải lại danh sách.'),
      );
      if (error instanceof AuthoringCollaborationApiError && error.status === 412)
        await this.reload();
    } finally {
      this.busy.set(false);
    }
  }

  protected async retry(): Promise<void> {
    await this.reload();
  }

  private async reload(): Promise<void> {
    this.state.set('loading');
    this.error.set(null);
    try {
      this.management.set(await this.api.listManagement(this.workId));
      this.state.set('ready');
    } catch (error) {
      this.state.set('error');
      this.error.set(this.errorMessage(error, 'Chưa tải được quyền cộng tác.'));
    }
  }

  private restoreInvitationAttempt(): void {
    if (!this.workId) return;
    try {
      const stored = sessionStorage.getItem(invitationAttemptKey(this.workId));
      if (!stored) return;
      const value: unknown = JSON.parse(stored);
      if (
        value === null ||
        typeof value !== 'object' ||
        Array.isArray(value) ||
        Object.keys(value).sort().join('|') !== 'email|invitationId|permissions'
      ) {
        this.clearInvitationAttempt();
        return;
      }
      const record = value as Record<string, unknown>;
      const permissions = record['permissions'];
      if (
        typeof record['invitationId'] !== 'string' ||
        !/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(
          record['invitationId'],
        ) ||
        typeof record['email'] !== 'string' ||
        !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(record['email']) ||
        !Array.isArray(permissions) ||
        !permissions.every((permission) => permission === 'VIEW' || permission === 'EDIT') ||
        !permissions.includes('VIEW') ||
        new Set(permissions).size !== permissions.length
      ) {
        this.clearInvitationAttempt();
        return;
      }
      const attempt: InvitationAttempt = {
        invitationId: record['invitationId'],
        email: record['email'],
        permissions: permissions as ManuscriptPermission[],
      };
      this.invitationAttempt.set(attempt);
      this.email.set(attempt.email);
      this.includeEdit.set(attempt.permissions.includes('EDIT'));
      this.consent.set(true);
    } catch {
      this.clearInvitationAttempt();
    }
  }

  private persistInvitationAttempt(attempt: InvitationAttempt): void {
    try {
      sessionStorage.setItem(invitationAttemptKey(this.workId), JSON.stringify(attempt));
    } catch {
      // The live page still retries with the same ID even when browser storage is unavailable.
    }
  }

  private clearInvitationAttempt(): void {
    try {
      sessionStorage.removeItem(invitationAttemptKey(this.workId));
    } catch {
      // No sensitive invitation token is held in this storage record.
    }
  }

  private errorMessage(error: unknown, fallback: string): string {
    if (!(error instanceof AuthoringCollaborationApiError)) return fallback;
    if (error.status === 401) return 'Phiên đăng nhập đã hết hạn. Hãy đăng nhập để tiếp tục.';
    if (error.status === 403 && error.code === 'POLICY_ACCEPTANCE_REQUIRED') {
      return 'Hãy xác nhận chính sách hiện hành trước khi quản lý cộng tác.';
    }
    if (error.status === 404) return 'Bản thảo hoặc quyền quản lý không khả dụng.';
    if (error.status === 409)
      return 'Lời mời đang xung đột với một yêu cầu khác. Tải lại danh sách để kiểm tra.';
    if (error.status === 412)
      return 'Quyền cộng tác đã thay đổi. Danh sách mới nhất đã được tải lại.';
    if (error.status === 429) return 'Hiện chưa thể gửi thêm lời mời. Hãy thử lại sau.';
    if (error.status === 503) return 'Tính năng cộng tác chưa được bật hoặc chưa cấu hình xong.';
    return fallback;
  }
}

function newInvitationId(): string {
  return crypto.randomUUID();
}

function invitationAttemptKey(workId: string): string {
  return `novel.authoring-invitation-create.v1.${workId}`;
}
