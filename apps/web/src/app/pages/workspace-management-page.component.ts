import { Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { PolicyOnboardingService } from '../core/policy-onboarding.service';
import {
  WorkspaceApiError,
  WorkspaceApiService,
  WorkspaceLibrary,
  WorkspaceInvitationSummary,
  WorkspaceMember,
  WorkspaceMemberRole,
  WorkspaceRole,
  WorkspaceSummary,
} from '../core/workspace-api.service';

type LoadPhase = 'loading' | 'ready' | 'error';
type MutationPhase = 'idle' | 'saving';

interface PendingWorkspaceCreate {
  id: string;
  name: string;
}

interface PendingLibraryCreate {
  workspaceId: string;
  id: string;
  name: string;
}

interface PendingInvitationCreate {
  workspaceId: string;
  id: string;
  email: string;
  role: WorkspaceMemberRole;
}

const pendingCreateStorageKey = 'novel.workspace-create-attempt.v1';
const pendingLibraryCreateStorageKey = 'novel.workspace-library-create-attempt.v1';
const pendingInvitationCreateStorageKey = 'novel.workspace-invitation-create-attempt.v1';
const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const memberRoles: readonly WorkspaceMemberRole[] = ['admin', 'editor', 'reader'];

@Component({
  selector: 'app-workspace-management-page',
  imports: [RouterLink],
  templateUrl: './workspace-management-page.component.html',
  styleUrl: './workspace-management-page.component.css',
})
export class WorkspaceManagementPageComponent {
  protected readonly policy = inject(PolicyOnboardingService);
  private readonly api = inject(WorkspaceApiService);

  protected readonly accountId = computed(() => this.policy.accountIdentity()?.userId ?? null);
  protected readonly workspaces = signal<WorkspaceSummary[]>([]);
  protected readonly selectedWorkspaceId = signal<string | null>(null);
  protected readonly members = signal<WorkspaceMember[]>([]);
  protected readonly libraries = signal<WorkspaceLibrary[]>([]);
  protected readonly invitations = signal<WorkspaceInvitationSummary[]>([]);
  protected readonly invitationPhase = signal<LoadPhase>('loading');
  protected readonly invitationMutation = signal<MutationPhase>('idle');
  protected readonly invitationEmail = signal('');
  protected readonly invitationRole = signal<WorkspaceMemberRole>('reader');
  protected readonly invitationConsent = signal(false);
  protected readonly invitationError = signal<string | null>(null);
  protected readonly invitationMessage = signal<string | null>(null);
  protected readonly pendingRevokeInvitationId = signal<string | null>(null);
  protected readonly nextLibraryCursor = signal<string | null>(null);
  protected readonly libraryPhase = signal<LoadPhase>('loading');
  protected readonly libraryMutation = signal<MutationPhase>('idle');
  protected readonly libraryName = signal('');
  protected readonly libraryError = signal<string | null>(null);
  protected readonly libraryMessage = signal<string | null>(null);
  protected readonly workspacePhase = signal<LoadPhase>('loading');
  protected readonly memberPhase = signal<LoadPhase>('loading');
  protected readonly workspaceMutation = signal<MutationPhase>('idle');
  protected readonly memberMutation = signal<MutationPhase>('idle');
  protected readonly workspaceName = signal('');
  protected readonly workspaceError = signal<string | null>(null);
  protected readonly workspaceMessage = signal<string | null>(null);
  protected readonly memberUserId = signal('');
  protected readonly memberRole = signal<WorkspaceMemberRole>('reader');
  protected readonly memberError = signal<string | null>(null);
  protected readonly memberMessage = signal<string | null>(null);
  protected readonly memberRoleDrafts = signal<Record<string, WorkspaceMemberRole>>({});
  protected readonly pendingRevokeUserId = signal<string | null>(null);
  protected readonly transferTargetUserId = signal('');
  protected readonly transferConfirmationOpen = signal(false);
  protected readonly copyMessage = signal<string | null>(null);
  protected readonly selectedWorkspace = computed(
    () => this.workspaces().find(({ id }) => id === this.selectedWorkspaceId()) ?? null,
  );
  protected readonly canManageMembers = computed(() => {
    const role = this.selectedWorkspace()?.role;
    return role === 'owner' || role === 'admin';
  });
  protected readonly canCreateLibraries = this.canManageMembers;
  protected readonly canTransferOwnership = computed(
    () => this.selectedWorkspace()?.role === 'owner',
  );
  protected readonly transferableMembers = computed(() =>
    this.members().filter(
      ({ role, status, userId }) =>
        status === 'active' && role !== 'owner' && userId !== this.accountId(),
    ),
  );

  private pendingCreate = this.readPendingCreate();
  private pendingLibraryCreate = this.readPendingLibraryCreate();
  private pendingInvitationCreate = this.readPendingInvitationCreate();

  constructor() {
    void this.loadWorkspaces();
  }

  protected updateWorkspaceName(event: Event): void {
    const value = (event.target as HTMLInputElement).value;
    this.workspaceName.set(value);
    this.workspaceError.set(null);
    if (this.pendingCreate && value.trim() !== this.pendingCreate.name) this.clearPendingCreate();
  }

  protected async createWorkspace(): Promise<void> {
    const name = this.workspaceName().trim();
    if (!name || [...name].length > 120) {
      this.workspaceError.set('Tên không gian cần có từ 1 đến 120 ký tự.');
      return;
    }
    if (!this.accountId()) {
      this.workspaceError.set(
        'Không xác minh được tài khoản. Hãy tải lại trang sau khi đăng nhập.',
      );
      return;
    }

    if (!this.pendingCreate || this.pendingCreate.name !== name) {
      this.pendingCreate = { id: crypto.randomUUID(), name };
      this.writePendingCreate(this.pendingCreate);
    }
    this.workspaceError.set(null);
    this.workspaceMessage.set(null);
    this.workspaceMutation.set('saving');
    try {
      const created = await this.api.createWorkspace(this.pendingCreate.id, name);
      this.clearPendingCreate();
      this.workspaceName.set('');
      this.selectedWorkspaceId.set(created.id);
      this.workspaceMessage.set(`Đã tạo không gian “${created.name}”.`);
      await this.loadWorkspaces();
    } catch (error) {
      this.workspaceError.set(
        workspaceErrorMessage(error, 'Chưa xác nhận được kết quả. Thử lại sẽ dùng cùng yêu cầu.'),
      );
    } finally {
      this.workspaceMutation.set('idle');
    }
  }

  protected async loadWorkspaces(): Promise<void> {
    this.workspacePhase.set('loading');
    this.workspaceError.set(null);
    try {
      const items = await this.api.listWorkspaces();
      this.workspaces.set(items);
      this.workspacePhase.set('ready');
      const selected = this.selectedWorkspaceId();
      const nextSelected =
        (selected && items.some(({ id }) => id === selected) ? selected : items[0]?.id) ?? null;
      if (nextSelected !== selected) {
        this.members.set([]);
        this.libraries.set([]);
        this.nextLibraryCursor.set(null);
      }
      this.selectedWorkspaceId.set(nextSelected);
      if (this.pendingLibraryCreate?.workspaceId === nextSelected) {
        this.libraryName.set(this.pendingLibraryCreate.name);
      }
      if (nextSelected)
        await Promise.all([
          this.loadMembers(nextSelected),
          this.loadLibraries(nextSelected),
          this.loadInvitations(nextSelected),
        ]);
      else {
        this.members.set([]);
        this.memberPhase.set('ready');
        this.libraries.set([]);
        this.nextLibraryCursor.set(null);
        this.libraryPhase.set('ready');
        this.invitations.set([]);
        this.invitationPhase.set('ready');
      }
    } catch (error) {
      this.workspacePhase.set('error');
      this.workspaceError.set(workspaceErrorMessage(error, 'Không tải được danh sách không gian.'));
    }
  }

  protected async selectWorkspace(event: Event): Promise<void> {
    const id = (event.target as HTMLSelectElement).value;
    if (!this.workspaces().some((workspace) => workspace.id === id)) return;
    this.selectedWorkspaceId.set(id);
    this.libraries.set([]);
    this.nextLibraryCursor.set(null);
    this.libraryPhase.set('loading');
    this.pendingRevokeUserId.set(null);
    this.pendingRevokeInvitationId.set(null);
    this.transferConfirmationOpen.set(false);
    this.memberError.set(null);
    this.memberMessage.set(null);
    this.libraryError.set(null);
    this.libraryMessage.set(null);
    await Promise.all([this.loadMembers(id), this.loadLibraries(id), this.loadInvitations(id)]);
  }

  protected updateLibraryName(event: Event): void {
    const value = (event.target as HTMLInputElement).value;
    this.libraryName.set(value);
    this.libraryError.set(null);
    if (this.pendingLibraryCreate && value.trim() !== this.pendingLibraryCreate.name) {
      this.clearPendingLibraryCreate();
    }
  }

  protected async createLibrary(): Promise<void> {
    const workspaceId = this.selectedWorkspaceId();
    const name = this.libraryName().trim();
    if (!workspaceId || !this.canCreateLibraries()) return;
    if (!name || [...name].length > 120) {
      this.libraryError.set('Tên thư viện cần có từ 1 đến 120 ký tự.');
      return;
    }
    if (
      !this.pendingLibraryCreate ||
      this.pendingLibraryCreate.workspaceId !== workspaceId ||
      this.pendingLibraryCreate.name !== name
    ) {
      this.pendingLibraryCreate = { workspaceId, id: crypto.randomUUID(), name };
      this.writePendingLibraryCreate(this.pendingLibraryCreate);
    }
    this.libraryMutation.set('saving');
    this.libraryError.set(null);
    this.libraryMessage.set(null);
    try {
      const library = await this.api.createLibrary(workspaceId, this.pendingLibraryCreate.id, name);
      this.clearPendingLibraryCreate();
      this.libraryName.set('');
      this.libraryMessage.set(`Đã tạo thư viện “${library.name}”.`);
      await this.loadLibraries(workspaceId);
    } catch (error) {
      this.libraryError.set(
        workspaceErrorMessage(error, 'Chưa xác nhận được kết quả. Thử lại sẽ dùng cùng yêu cầu.'),
      );
    } finally {
      this.libraryMutation.set('idle');
    }
  }

  protected async loadMoreLibraries(): Promise<void> {
    const workspaceId = this.selectedWorkspaceId();
    const cursor = this.nextLibraryCursor();
    if (!workspaceId || !cursor || this.libraryPhase() === 'loading') return;
    this.libraryPhase.set('loading');
    this.libraryError.set(null);
    try {
      const page = await this.api.listLibraries(workspaceId, cursor, 25);
      if (this.selectedWorkspaceId() !== workspaceId) return;
      const seen = new Set(this.libraries().map(({ id }) => id));
      this.libraries.update((items) => [...items, ...page.items.filter(({ id }) => !seen.has(id))]);
      this.nextLibraryCursor.set(page.nextCursor);
      this.libraryPhase.set('ready');
    } catch (error) {
      if (this.selectedWorkspaceId() === workspaceId) {
        this.libraryPhase.set('error');
        this.libraryError.set(workspaceErrorMessage(error, 'Không tải được thêm thư viện.'));
      }
    }
  }

  protected async retryLibraries(): Promise<void> {
    const workspaceId = this.selectedWorkspaceId();
    if (workspaceId) await this.loadLibraries(workspaceId);
  }

  protected updateInvitationEmail(event: Event): void {
    const value = (event.target as HTMLInputElement).value;
    this.invitationEmail.set(value);
    this.invitationError.set(null);
    if (
      this.pendingInvitationCreate &&
      value.trim().toLowerCase() !== this.pendingInvitationCreate.email
    ) {
      this.clearPendingInvitationCreate();
    }
  }

  protected updateInvitationRole(event: Event): void {
    const role = (event.target as HTMLSelectElement).value;
    if (!isMemberRole(role)) return;
    this.invitationRole.set(role);
    this.invitationError.set(null);
    if (this.pendingInvitationCreate && role !== this.pendingInvitationCreate.role) {
      this.clearPendingInvitationCreate();
    }
  }

  protected updateInvitationConsent(event: Event): void {
    this.invitationConsent.set((event.target as HTMLInputElement).checked);
  }

  protected async inviteByEmail(): Promise<void> {
    const workspaceId = this.selectedWorkspaceId();
    const email = this.invitationEmail().trim().toLowerCase();
    const role = this.invitationRole();
    if (!workspaceId || !this.canManageMembers()) return;
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email) || [...email].length > 320) {
      this.invitationError.set('Nhập địa chỉ email hợp lệ, tối đa 320 ký tự.');
      return;
    }
    if (!this.invitationConsent()) {
      this.invitationError.set('Hãy xác nhận bạn có quyền gửi lời mời đến địa chỉ này.');
      return;
    }
    if (
      !this.pendingInvitationCreate ||
      this.pendingInvitationCreate.workspaceId !== workspaceId ||
      this.pendingInvitationCreate.email !== email ||
      this.pendingInvitationCreate.role !== role
    ) {
      this.pendingInvitationCreate = {
        workspaceId,
        id: crypto.randomUUID(),
        email,
        role,
      };
      this.writePendingInvitationCreate(this.pendingInvitationCreate);
    }
    this.invitationMutation.set('saving');
    this.invitationError.set(null);
    this.invitationMessage.set(null);
    try {
      const result = await this.api.createInvitation(
        workspaceId,
        this.pendingInvitationCreate.id,
        email,
        role,
      );
      this.clearPendingInvitationCreate();
      this.invitationEmail.set('');
      this.invitationConsent.set(false);
      this.invitationMessage.set(
        result.status === 'pending'
          ? 'Đã xếp lời mời vào hàng gửi. Người nhận cần đăng nhập Google bằng email đã xác minh.'
          : 'Yêu cầu trước đã được xử lý; hãy tải lại danh sách lời mời.',
      );
      await this.loadInvitations(workspaceId);
    } catch (error) {
      this.invitationError.set(
        workspaceInvitationErrorMessage(
          error,
          'Chưa xác nhận được lời mời. Bạn có thể thử lại an toàn.',
        ),
      );
    } finally {
      this.invitationMutation.set('idle');
    }
  }

  protected requestRevokeInvitation(invitation: WorkspaceInvitationSummary): void {
    if (!this.canManageMembers() || invitation.status !== 'pending') return;
    this.pendingRevokeInvitationId.set(invitation.id);
    this.invitationError.set(null);
  }

  protected cancelRevokeInvitation(): void {
    this.pendingRevokeInvitationId.set(null);
  }

  protected async confirmRevokeInvitation(invitation: WorkspaceInvitationSummary): Promise<void> {
    const workspaceId = this.selectedWorkspaceId();
    if (!workspaceId || !this.canManageMembers()) return;
    this.invitationMutation.set('saving');
    this.invitationError.set(null);
    this.invitationMessage.set(null);
    try {
      await this.api.revokeInvitation(workspaceId, invitation.id);
      this.pendingRevokeInvitationId.set(null);
      this.invitationMessage.set(
        'Đã thu hồi lời mời. Liên kết trong email không còn sử dụng được.',
      );
      await this.loadInvitations(workspaceId);
    } catch (error) {
      this.invitationError.set(
        workspaceInvitationErrorMessage(
          error,
          'Không thu hồi được lời mời. Hãy tải lại rồi thử lại.',
        ),
      );
    } finally {
      this.invitationMutation.set('idle');
    }
  }

  protected async retryInvitations(): Promise<void> {
    const workspaceId = this.selectedWorkspaceId();
    if (workspaceId) await this.loadInvitations(workspaceId);
  }

  protected workspaceRoleLabel(role: WorkspaceMemberRole): string {
    return role === 'admin' ? 'Quản trị viên' : role === 'editor' ? 'Biên tập viên' : 'Người đọc';
  }

  protected formatInvitationExpiry(value: string): string {
    return new Intl.DateTimeFormat('vi-VN', { dateStyle: 'medium', timeStyle: 'short' }).format(
      new Date(value),
    );
  }

  protected async loadMembers(workspaceId = this.selectedWorkspaceId()): Promise<void> {
    if (!workspaceId) {
      this.members.set([]);
      this.memberPhase.set('ready');
      return;
    }
    this.memberPhase.set('loading');
    this.memberError.set(null);
    try {
      const members = await this.api.listMembers(workspaceId);
      this.members.set(members);
      this.memberRoleDrafts.set(
        Object.fromEntries(
          members
            .filter(({ role }) => role !== 'owner')
            .map(({ userId, role }) => [userId, role as WorkspaceMemberRole]),
        ),
      );
      this.memberPhase.set('ready');
    } catch (error) {
      this.memberPhase.set('error');
      this.memberError.set(workspaceErrorMessage(error, 'Không tải được danh sách thành viên.'));
    }
  }

  protected updateMemberUserId(event: Event): void {
    this.memberUserId.set((event.target as HTMLInputElement).value.trim());
    this.memberError.set(null);
  }

  protected updateMemberRole(event: Event): void {
    const role = (event.target as HTMLSelectElement).value;
    if (isMemberRole(role)) this.memberRole.set(role);
  }

  protected async saveMember(): Promise<void> {
    const workspaceId = this.selectedWorkspaceId();
    const userId = this.memberUserId().trim();
    if (!workspaceId || !this.canManageMembers()) return;
    if (!uuidPattern.test(userId)) {
      this.memberError.set('Nhập UUID tài khoản hợp lệ do thành viên cung cấp.');
      return;
    }
    if (userId === this.accountId()) {
      this.memberError.set('Bạn đã là thành viên của không gian này.');
      return;
    }

    this.memberMutation.set('saving');
    this.memberError.set(null);
    this.memberMessage.set(null);
    try {
      const member = await this.api.setMemberRole(workspaceId, userId, this.memberRole());
      this.memberUserId.set('');
      this.memberMessage.set(
        member.status === 'active'
          ? 'Đã cấp hoặc cập nhật quyền thành viên.'
          : 'Đã cập nhật thành viên.',
      );
      await this.refreshWorkspaceState(workspaceId);
    } catch (error) {
      this.memberError.set(workspaceErrorMessage(error, 'Không cập nhật được thành viên.'));
    } finally {
      this.memberMutation.set('idle');
    }
  }

  protected setRoleDraft(userId: string, event: Event): void {
    const role = (event.target as HTMLSelectElement).value;
    if (!isMemberRole(role)) return;
    this.memberRoleDrafts.update((drafts) => ({ ...drafts, [userId]: role }));
  }

  protected roleDraft(userId: string): WorkspaceMemberRole {
    return this.memberRoleDrafts()[userId] ?? 'reader';
  }

  protected async saveRole(member: WorkspaceMember): Promise<void> {
    const workspaceId = this.selectedWorkspaceId();
    const role = this.roleDraft(member.userId);
    if (!workspaceId || !this.canManageMembers() || member.role === 'owner') return;
    if (role === member.role) return;
    this.memberMutation.set('saving');
    this.memberError.set(null);
    this.memberMessage.set(null);
    try {
      await this.api.setMemberRole(workspaceId, member.userId, role);
      this.memberMessage.set('Đã cập nhật vai trò. Quyền trong workspace đã đổi ngay lập tức.');
      await this.refreshWorkspaceState(workspaceId);
    } catch (error) {
      this.memberError.set(workspaceErrorMessage(error, 'Không lưu được vai trò thành viên.'));
    } finally {
      this.memberMutation.set('idle');
    }
  }

  protected requestRevoke(member: WorkspaceMember): void {
    if (!this.canManageMembers() || member.role === 'owner' || member.status !== 'active') return;
    this.pendingRevokeUserId.set(member.userId);
    this.memberError.set(null);
  }

  protected cancelRevoke(): void {
    this.pendingRevokeUserId.set(null);
  }

  protected async confirmRevoke(member: WorkspaceMember): Promise<void> {
    const workspaceId = this.selectedWorkspaceId();
    if (
      !workspaceId ||
      this.pendingRevokeUserId() !== member.userId ||
      !this.canManageMembers() ||
      member.role === 'owner'
    ) {
      return;
    }
    this.memberMutation.set('saving');
    this.memberError.set(null);
    this.memberMessage.set(null);
    try {
      await this.api.revokeMember(workspaceId, member.userId);
      this.pendingRevokeUserId.set(null);
      this.memberMessage.set('Đã thu hồi quyền. Thành viên không thể mở nội dung workspace nữa.');
      await this.refreshWorkspaceState(workspaceId);
    } catch (error) {
      this.memberError.set(workspaceErrorMessage(error, 'Không thu hồi được quyền thành viên.'));
    } finally {
      this.memberMutation.set('idle');
    }
  }

  protected updateTransferTarget(event: Event): void {
    this.transferTargetUserId.set((event.target as HTMLSelectElement).value);
    this.transferConfirmationOpen.set(false);
  }

  protected requestOwnershipTransfer(): void {
    if (
      !this.canTransferOwnership() ||
      !this.transferableMembers().some(({ userId }) => userId === this.transferTargetUserId())
    ) {
      this.memberError.set('Chọn thành viên đang hoạt động trước khi chuyển quyền sở hữu.');
      return;
    }
    this.transferConfirmationOpen.set(true);
    this.memberError.set(null);
  }

  protected cancelOwnershipTransfer(): void {
    this.transferConfirmationOpen.set(false);
  }

  protected async confirmOwnershipTransfer(): Promise<void> {
    const workspaceId = this.selectedWorkspaceId();
    const target = this.transferTargetUserId();
    if (
      !workspaceId ||
      !this.transferConfirmationOpen() ||
      !this.canTransferOwnership() ||
      !this.transferableMembers().some(({ userId }) => userId === target)
    ) {
      return;
    }
    this.memberMutation.set('saving');
    this.memberError.set(null);
    this.memberMessage.set(null);
    try {
      await this.api.transferOwnership(workspaceId, target);
      this.transferConfirmationOpen.set(false);
      this.transferTargetUserId.set('');
      this.memberMessage.set('Đã chuyển quyền sở hữu. Vai trò của bạn hiện là quản trị viên.');
      await this.refreshWorkspaceState(workspaceId);
    } catch (error) {
      this.memberError.set(workspaceErrorMessage(error, 'Không chuyển được quyền sở hữu.'));
    } finally {
      this.memberMutation.set('idle');
    }
  }

  protected async copyAccountId(): Promise<void> {
    const userId = this.accountId();
    if (!userId || !navigator.clipboard) {
      this.copyMessage.set('Không thể sao chép tự động. Hãy chọn và sao chép UUID bên trên.');
      return;
    }
    try {
      await navigator.clipboard.writeText(userId);
      this.copyMessage.set('Đã sao chép UUID tài khoản.');
    } catch {
      this.copyMessage.set('Không thể sao chép tự động. Hãy chọn và sao chép UUID bên trên.');
    }
  }

  protected roleLabel(role: WorkspaceRole): string {
    return {
      owner: 'Chủ sở hữu',
      admin: 'Quản trị viên',
      editor: 'Biên tập viên',
      reader: 'Người đọc',
    }[role];
  }

  protected memberStatusLabel(status: WorkspaceMember['status']): string {
    return status === 'active' ? 'Đang hoạt động' : 'Đã thu hồi';
  }

  protected async retry(): Promise<void> {
    await this.loadWorkspaces();
  }

  private async loadInvitations(workspaceId: string): Promise<void> {
    if (!this.canManageMembers()) {
      this.invitations.set([]);
      this.invitationPhase.set('ready');
      return;
    }
    this.invitationPhase.set('loading');
    this.invitationError.set(null);
    try {
      const invitations = await this.api.listInvitations(workspaceId);
      if (this.selectedWorkspaceId() !== workspaceId) return;
      this.invitations.set(invitations);
      this.invitationPhase.set('ready');
    } catch (error) {
      if (this.selectedWorkspaceId() !== workspaceId) return;
      this.invitationPhase.set('error');
      this.invitationError.set(
        workspaceInvitationErrorMessage(error, 'Không tải được các lời mời đang chờ.'),
      );
    }
  }

  private async refreshWorkspaceState(workspaceId: string): Promise<void> {
    this.selectedWorkspaceId.set(workspaceId);
    await this.loadWorkspaces();
  }

  private async loadLibraries(workspaceId = this.selectedWorkspaceId()): Promise<void> {
    if (!workspaceId) {
      this.libraries.set([]);
      this.nextLibraryCursor.set(null);
      this.libraryPhase.set('ready');
      return;
    }
    this.libraryPhase.set('loading');
    this.libraryError.set(null);
    this.nextLibraryCursor.set(null);
    try {
      const page = await this.api.listLibraries(workspaceId, undefined, 25);
      if (this.selectedWorkspaceId() !== workspaceId) return;
      this.libraries.set(page.items);
      this.nextLibraryCursor.set(page.nextCursor);
      this.libraryPhase.set('ready');
    } catch (error) {
      if (this.selectedWorkspaceId() !== workspaceId) return;
      this.libraryPhase.set('error');
      this.libraryError.set(workspaceErrorMessage(error, 'Không tải được thư viện workspace.'));
    }
  }

  private readPendingCreate(): PendingWorkspaceCreate | null {
    if (typeof sessionStorage === 'undefined') return null;
    try {
      const value: unknown = JSON.parse(sessionStorage.getItem(pendingCreateStorageKey) ?? 'null');
      if (
        typeof value === 'object' &&
        value !== null &&
        'id' in value &&
        'name' in value &&
        typeof value.id === 'string' &&
        uuidPattern.test(value.id) &&
        typeof value.name === 'string' &&
        value.name.trim().length > 0 &&
        [...value.name].length <= 120
      ) {
        this.workspaceName.set(value.name);
        return { id: value.id, name: value.name };
      }
    } catch {
      // An invalid or unavailable attempt record is ignored; no work content is stored here.
    }
    return null;
  }

  private writePendingCreate(attempt: PendingWorkspaceCreate): void {
    try {
      sessionStorage.setItem(pendingCreateStorageKey, JSON.stringify(attempt));
    } catch {
      // The in-memory UUID remains stable until the current page is closed.
    }
  }

  private clearPendingCreate(): void {
    this.pendingCreate = null;
    try {
      sessionStorage.removeItem(pendingCreateStorageKey);
    } catch {
      // The current page can still safely retry from its in-memory state.
    }
  }

  private readPendingLibraryCreate(): PendingLibraryCreate | null {
    if (typeof sessionStorage === 'undefined') return null;
    try {
      const value: unknown = JSON.parse(
        sessionStorage.getItem(pendingLibraryCreateStorageKey) ?? 'null',
      );
      if (
        typeof value === 'object' &&
        value !== null &&
        'workspaceId' in value &&
        'id' in value &&
        'name' in value &&
        typeof value.workspaceId === 'string' &&
        uuidPattern.test(value.workspaceId) &&
        typeof value.id === 'string' &&
        uuidPattern.test(value.id) &&
        typeof value.name === 'string' &&
        value.name.trim().length > 0 &&
        [...value.name].length <= 120
      ) {
        if (this.selectedWorkspaceId() === value.workspaceId) this.libraryName.set(value.name);
        return { workspaceId: value.workspaceId, id: value.id, name: value.name };
      }
    } catch {
      // An invalid attempt record is ignored; no work content is stored here.
    }
    return null;
  }

  private writePendingLibraryCreate(attempt: PendingLibraryCreate): void {
    try {
      sessionStorage.setItem(pendingLibraryCreateStorageKey, JSON.stringify(attempt));
    } catch {
      // The in-memory UUID remains stable until the current page is closed.
    }
  }

  private clearPendingLibraryCreate(): void {
    this.pendingLibraryCreate = null;
    try {
      sessionStorage.removeItem(pendingLibraryCreateStorageKey);
    } catch {
      // The current page can still safely retry from its in-memory state.
    }
  }

  private readPendingInvitationCreate(): PendingInvitationCreate | null {
    if (typeof sessionStorage === 'undefined') return null;
    try {
      const value: unknown = JSON.parse(
        sessionStorage.getItem(pendingInvitationCreateStorageKey) ?? 'null',
      );
      if (
        typeof value === 'object' &&
        value !== null &&
        'workspaceId' in value &&
        'id' in value &&
        'email' in value &&
        'role' in value &&
        typeof value.workspaceId === 'string' &&
        uuidPattern.test(value.workspaceId) &&
        typeof value.id === 'string' &&
        uuidPattern.test(value.id) &&
        typeof value.email === 'string' &&
        /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value.email) &&
        typeof value.role === 'string' &&
        isMemberRole(value.role)
      ) {
        this.invitationEmail.set(value.email);
        this.invitationRole.set(value.role);
        return {
          workspaceId: value.workspaceId,
          id: value.id,
          email: value.email,
          role: value.role,
        };
      }
    } catch {
      // Invalid attempt state is ignored; no invitation token is stored here.
    }
    return null;
  }

  private writePendingInvitationCreate(attempt: PendingInvitationCreate): void {
    try {
      sessionStorage.setItem(pendingInvitationCreateStorageKey, JSON.stringify(attempt));
    } catch {
      // Keep the stable request ID in memory if session storage is unavailable.
    }
  }

  private clearPendingInvitationCreate(): void {
    this.pendingInvitationCreate = null;
    try {
      sessionStorage.removeItem(pendingInvitationCreateStorageKey);
    } catch {
      // The current page can still safely complete this attempt in memory.
    }
  }
}

function isMemberRole(value: string): value is WorkspaceMemberRole {
  return memberRoles.includes(value as WorkspaceMemberRole);
}

function workspaceErrorMessage(error: unknown, fallback: string): string {
  if (!(error instanceof WorkspaceApiError)) return fallback;
  if (error.status === 401) return 'Phiên đăng nhập đã hết hạn. Hãy đăng nhập lại.';
  if (error.status === 403 && error.code === 'POLICY_ACCEPTANCE_REQUIRED') {
    return 'Hãy xác nhận chính sách hiện hành trước khi quản lý workspace.';
  }
  if (error.status === 404)
    return 'Không gian hoặc thành viên không còn khả dụng. Danh sách đã được làm mới.';
  if (error.status === 409 && error.code === 'WORKSPACE_MEMBER_CONFLICT') {
    return 'UUID đang trùng với workspace khác hoặc yêu cầu trước đã dùng tên khác. Tải lại trước khi thử tiếp.';
  }
  if (error.status === 409 && error.code === 'WORKSPACE_OWNER_PROTECTED') {
    return 'Chủ sở hữu chỉ có thể đổi qua thao tác chuyển quyền sở hữu.';
  }
  if (error.status === 403) return 'Tài khoản chưa có quyền thực hiện thao tác này.';
  if (error.status === 0 || error.status >= 500) {
    return 'Máy chủ chưa xác nhận thay đổi. Tải lại trạng thái; thao tác tạo có thể thử lại an toàn.';
  }
  return fallback;
}

function workspaceInvitationErrorMessage(error: unknown, fallback: string): string {
  if (!(error instanceof WorkspaceApiError)) return fallback;
  if (error.status === 401) return 'Phiên đăng nhập đã hết hạn. Hãy đăng nhập lại rồi thử lại.';
  if (error.status === 403 && error.code === 'POLICY_ACCEPTANCE_REQUIRED') {
    return 'Hãy xác nhận chính sách hiện hành trước khi gửi hoặc quản lý lời mời.';
  }
  if (error.status === 404)
    return 'Workspace hoặc lời mời không còn khả dụng. Hãy tải lại danh sách.';
  if (error.status === 409) return 'Lời mời đã thay đổi hoặc đã được xử lý. Hãy tải lại danh sách.';
  if (error.status === 429) return 'Đã đạt giới hạn gửi lời mời trong 24 giờ. Hãy thử lại sau.';
  if (error.status === 503) return 'Mời qua email đang tắt hoặc chưa cấu hình máy chủ gửi thư.';
  if (error.status === 0 || error.status >= 500) {
    return 'Máy chủ chưa xác nhận kết quả. Thử lại sẽ dùng cùng yêu cầu mời.';
  }
  return fallback;
}
