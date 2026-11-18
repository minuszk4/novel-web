import { provideHttpClient } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { PolicyOnboardingService } from '../core/policy-onboarding.service';
import {
  WorkspaceApiService,
  WorkspaceApiError,
  WorkspaceDetail,
  WorkspaceLibrary,
  WorkspaceMember,
  WorkspaceSummary,
} from '../core/workspace-api.service';
import { WorkspaceManagementPageComponent } from './workspace-management-page.component';

const workspaceId = '11111111-1111-4111-8111-111111111111';
const ownerId = '22222222-2222-4222-8222-222222222222';
const memberId = '33333333-3333-4333-8333-333333333333';
const workspace: WorkspaceSummary = {
  id: workspaceId,
  name: 'Nhóm đọc',
  role: 'owner',
  scopeVersion: 3,
  createdAt: '2026-11-18T01:00:00Z',
  joinedAt: '2026-11-18T01:00:00Z',
};
const owner: WorkspaceMember = {
  userId: ownerId,
  role: 'owner',
  status: 'active',
  joinedAt: '2026-11-18T01:00:00Z',
  revokedAt: null,
};
const reader: WorkspaceMember = {
  userId: memberId,
  role: 'reader',
  status: 'active',
  joinedAt: '2026-11-18T01:01:00Z',
  revokedAt: null,
};
const sharedLibrary: WorkspaceLibrary = {
  id: '44444444-4444-4444-8444-444444444444',
  name: 'Truyện đang dịch',
  visibility: 'private',
  isDefault: false,
  createdAt: '2026-11-18T02:00:00Z',
};

describe('WorkspaceManagementPageComponent', () => {
  let api: {
    listWorkspaces: ReturnType<typeof vi.fn>;
    createWorkspace: ReturnType<typeof vi.fn>;
    listMembers: ReturnType<typeof vi.fn>;
    listLibraries: ReturnType<typeof vi.fn>;
    listInvitations: ReturnType<typeof vi.fn>;
    createInvitation: ReturnType<typeof vi.fn>;
    revokeInvitation: ReturnType<typeof vi.fn>;
    createLibrary: ReturnType<typeof vi.fn>;
    setMemberRole: ReturnType<typeof vi.fn>;
    revokeMember: ReturnType<typeof vi.fn>;
    transferOwnership: ReturnType<typeof vi.fn>;
  };

  beforeEach(() => {
    api = {
      listWorkspaces: vi.fn().mockResolvedValue([workspace]),
      createWorkspace: vi.fn(),
      listMembers: vi.fn().mockResolvedValue([owner, reader]),
      listLibraries: vi.fn().mockResolvedValue({ items: [sharedLibrary], nextCursor: null }),
      listInvitations: vi.fn().mockResolvedValue([]),
      createInvitation: vi.fn().mockResolvedValue({
        id: '55555555-5555-4555-8555-555555555555',
        status: 'pending',
        expiresAt: '2026-11-25T02:00:00Z',
        message: 'Queued.',
      }),
      revokeInvitation: vi.fn().mockResolvedValue(undefined),
      createLibrary: vi.fn().mockResolvedValue(sharedLibrary),
      setMemberRole: vi.fn().mockResolvedValue({ ...reader, role: 'editor' }),
      revokeMember: vi.fn().mockResolvedValue({
        ...reader,
        status: 'revoked',
        revokedAt: '2026-11-18T02:00:00Z',
      }),
      transferOwnership: vi.fn().mockResolvedValue({
        id: workspaceId,
        name: workspace.name,
        scopeVersion: 4,
        createdAt: workspace.createdAt,
      } satisfies WorkspaceDetail),
    };

    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideRouter([])],
    });
    TestBed.overrideProvider(WorkspaceApiService, { useValue: api });
    const policy = TestBed.inject(PolicyOnboardingService);
    policy.status.set('accepted');
    policy.accountIdentity.set({ userId: ownerId, displayName: 'Owner', contactEmail: null });
  });

  it('loads the caller workspaces and roster without displaying email addresses', async () => {
    const fixture = TestBed.createComponent(WorkspaceManagementPageComponent);
    await fixture.whenStable();
    fixture.detectChanges();

    const element: HTMLElement = fixture.nativeElement;
    expect(element.querySelector('h1')?.textContent).toContain('Không gian cộng tác');
    expect(element.textContent).toContain('Nhóm đọc');
    expect(element.textContent).toContain(ownerId);
    expect(element.textContent).toContain(memberId);
    expect(element.textContent).toContain('Chủ sở hữu');
    expect(element.textContent).toContain('Người đọc');
    expect(element.textContent).not.toContain('@');
    expect(api.listWorkspaces).toHaveBeenCalledOnce();
    expect(api.listMembers).toHaveBeenCalledWith(workspaceId);
  });

  it('lets an owner explicitly confirm a membership revocation', async () => {
    const fixture = TestBed.createComponent(WorkspaceManagementPageComponent);
    await fixture.whenStable();
    fixture.detectChanges();

    const cards = fixture.nativeElement.querySelectorAll('.workspace-member-card');
    const memberCard = cards[1] as HTMLElement;
    const revokeButton = Array.from(memberCard.querySelectorAll('button')).find((button) =>
      button.textContent?.includes('Thu hồi quyền'),
    );
    expect(revokeButton).toBeDefined();
    revokeButton?.click();
    fixture.detectChanges();
    expect(memberCard.textContent).toContain('Xác nhận thu hồi');
    expect(api.revokeMember).not.toHaveBeenCalled();

    const confirmButton = Array.from(memberCard.querySelectorAll('button')).find((button) =>
      button.textContent?.includes('Xác nhận thu hồi'),
    );
    confirmButton?.click();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(api.revokeMember).toHaveBeenCalledWith(workspaceId, memberId);
  });

  it('does not show membership mutation controls to a reader', async () => {
    api.listWorkspaces.mockResolvedValue([{ ...workspace, role: 'reader' }]);
    const fixture = TestBed.createComponent(WorkspaceManagementPageComponent);
    await fixture.whenStable();
    fixture.detectChanges();

    const element: HTMLElement = fixture.nativeElement;
    expect(element.querySelector('.workspace-add-member')).toBeNull();
    expect(element.querySelector('.workspace-transfer')).toBeNull();
    expect(element.textContent).not.toContain('Thu hồi quyền');
    expect(element.textContent).not.toContain('Lưu vai trò');
  });

  it('shows shared library deep links to members and only lets owners create libraries', async () => {
    api.listWorkspaces.mockResolvedValue([{ ...workspace, role: 'reader' }]);
    const fixture = TestBed.createComponent(WorkspaceManagementPageComponent);
    await fixture.whenStable();
    fixture.detectChanges();

    const element: HTMLElement = fixture.nativeElement;
    const libraryLink = element.querySelector<HTMLAnchorElement>('.workspace-library-card a');
    expect(element.textContent).toContain('Truyện đang dịch');
    expect(libraryLink?.getAttribute('href')).toContain(
      `/app/library?libraryId=${sharedLibrary.id}`,
    );
    expect(element.querySelector('.workspace-library-create')).toBeNull();
    expect(api.listLibraries).toHaveBeenCalledWith(workspaceId, undefined, 25);
  });

  it('creates a shared library with a stable client-selected retry ID', async () => {
    const fixture = TestBed.createComponent(WorkspaceManagementPageComponent);
    await fixture.whenStable();
    fixture.detectChanges();
    const root = fixture.nativeElement as HTMLElement;
    const input = root.querySelector<HTMLInputElement>('#workspace-library-name');
    expect(input).not.toBeNull();
    input!.value = '  Truyện mới  ';
    input!.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    const form = root.querySelector<HTMLFormElement>('.workspace-library-create');
    expect(form).not.toBeNull();
    form!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();
    fixture.detectChanges();

    expect(api.createLibrary).toHaveBeenCalledOnce();
    expect(api.createLibrary).toHaveBeenCalledWith(
      workspaceId,
      expect.stringMatching(/^[0-9a-f-]{36}$/i),
      'Truyện mới',
    );
    expect(root.textContent).toContain('Đã tạo thư viện');
  });

  it('requires explicit invitation consent and retries uncertain email sends with the same ID', async () => {
    api.createInvitation
      .mockRejectedValueOnce(new WorkspaceApiError('WORKSPACE_REQUEST_FAILED', 0, null))
      .mockResolvedValueOnce({
        id: '55555555-5555-4555-8555-555555555555',
        status: 'pending',
        expiresAt: '2026-11-25T02:00:00Z',
        message: 'Queued.',
      });
    const fixture = TestBed.createComponent(WorkspaceManagementPageComponent);
    await fixture.whenStable();
    fixture.detectChanges();
    const root = fixture.nativeElement as HTMLElement;
    const email = root.querySelector<HTMLInputElement>('#workspace-invitation-email')!;
    const role = root.querySelector<HTMLSelectElement>('#workspace-invitation-role')!;
    const consent = root.querySelector<HTMLInputElement>('#workspace-invitation-consent')!;
    const form = root.querySelector<HTMLFormElement>('.workspace-invitation-form')!;

    email.value = 'Reader@Example.Test';
    email.dispatchEvent(new Event('input'));
    role.value = 'editor';
    role.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    expect(
      root.querySelector<HTMLButtonElement>('.workspace-invitation-form button')?.disabled,
    ).toBe(true);
    consent.checked = true;
    consent.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    form.dispatchEvent(new Event('submit'));
    await fixture.whenStable();
    fixture.detectChanges();
    expect(root.textContent).toContain('Máy chủ chưa xác nhận kết quả');
    const firstId = api.createInvitation.mock.calls[0]?.[1];
    expect(firstId).toMatch(/^[0-9a-f-]{36}$/i);

    form.dispatchEvent(new Event('submit'));
    await fixture.whenStable();
    fixture.detectChanges();
    expect(api.createInvitation).toHaveBeenCalledTimes(2);
    expect(api.createInvitation.mock.calls[1]?.[1]).toBe(firstId);
    expect(api.createInvitation.mock.calls[0]).toEqual([
      workspaceId,
      firstId,
      'reader@example.test',
      'editor',
    ]);
    expect(root.textContent).toContain('Đã xếp lời mời vào hàng gửi');
    expect(email.value).toBe('');
  });
});
