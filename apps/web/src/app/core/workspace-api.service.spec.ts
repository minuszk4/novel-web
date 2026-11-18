import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import {
  WorkspaceApiError,
  WorkspaceApiService,
  WorkspaceDetail,
  WorkspaceLibrary,
  WorkspaceMember,
  WorkspaceSummary,
} from './workspace-api.service';

const workspaceId = '11111111-1111-4111-8111-111111111111';
const memberId = '33333333-3333-4333-8333-333333333333';
const csrf = 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA';
const workspace: WorkspaceSummary = {
  id: workspaceId,
  name: 'Nhóm đọc',
  role: 'owner',
  scopeVersion: 2,
  createdAt: '2026-11-18T01:00:00Z',
  joinedAt: '2026-11-18T01:00:00Z',
};
const detail: WorkspaceDetail = {
  id: workspaceId,
  name: workspace.name,
  scopeVersion: 2,
  createdAt: workspace.createdAt,
};
const member: WorkspaceMember = {
  userId: memberId,
  role: 'reader',
  status: 'active',
  joinedAt: '2026-11-18T01:01:00Z',
  revokedAt: null,
};
const library: WorkspaceLibrary = {
  id: '44444444-4444-4444-8444-444444444444',
  name: 'Truyện đang dịch',
  visibility: 'private',
  isDefault: false,
  createdAt: '2026-11-18T02:00:00Z',
};
const invitationId = '55555555-5555-4555-8555-555555555555';
const invitation = {
  id: invitationId,
  role: 'reader' as const,
  status: 'pending' as const,
  createdAt: '2026-11-18T02:00:00Z',
  expiresAt: '2026-11-25T02:00:00Z',
};
const invitationToken = 'A'.repeat(43);

describe('WorkspaceApiService', () => {
  let api: WorkspaceApiService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    api = TestBed.inject(WorkspaceApiService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    try {
      http.verify();
    } finally {
      TestBed.resetTestingModule();
    }
  });

  it('loads a strict workspace list without creator or owner identifiers', async () => {
    const result = api.listWorkspaces();
    http.expectOne({ method: 'GET', url: '/api/v1/me/workspaces' }).flush({ items: [workspace] });
    await expect(result).resolves.toEqual([workspace]);
  });

  it('creates at a stable caller-chosen UUID with the session CSRF token', async () => {
    const result = api.createWorkspace(workspaceId, '  Nhóm đọc  ');
    http.expectOne({ method: 'GET', url: '/api/v1/auth/csrf' }).flush({ token: csrf });
    await new Promise((resolve) => setTimeout(resolve, 0));
    const request = http.expectOne({
      method: 'PUT',
      url: `/api/v1/me/workspaces/${workspaceId}`,
    });
    expect(request.request.headers.get('X-CSRF-Token')).toBe(csrf);
    expect(request.request.body).toEqual({ name: 'Nhóm đọc' });
    request.flush(detail);
    await expect(result).resolves.toEqual(detail);
  });

  it('loads the roster and validates active and revoked member records', async () => {
    const result = api.listMembers(workspaceId);
    http.expectOne({ method: 'GET', url: `/api/v1/workspaces/${workspaceId}/members` }).flush({
      items: [member],
    });
    await expect(result).resolves.toEqual([member]);

    const invalid = api.listMembers(workspaceId);
    http
      .expectOne({ method: 'GET', url: `/api/v1/workspaces/${workspaceId}/members` })
      .flush({ items: [{ ...member, contactEmail: 'private@example.test' }] });
    await expect(invalid).rejects.toMatchObject({ status: 502, code: null });
  });

  it('lists workspace libraries with a validated cursor page and rejects unsafe DTO fields', async () => {
    const result = api.listLibraries(workspaceId, 'cursor-1', 10);
    http
      .expectOne(
        (request) =>
          request.method === 'GET' &&
          request.url === `/api/v1/workspaces/${workspaceId}/libraries` &&
          request.params.get('limit') === '10' &&
          request.params.get('cursor') === 'cursor-1',
      )
      .flush({ items: [library], nextCursor: null });
    await expect(result).resolves.toEqual({ items: [library], nextCursor: null });

    const invalid = api.listLibraries(workspaceId);
    http
      .expectOne((request) => request.url.endsWith(`/workspaces/${workspaceId}/libraries`))
      .flush({ items: [{ ...library, ownerUserId: memberId }], nextCursor: null });
    await expect(invalid).rejects.toMatchObject({ status: 502, code: null });
    await expect(api.listLibraries(workspaceId, 'x'.repeat(2049))).rejects.toMatchObject({
      status: 400,
    });
  });

  it('creates a shared library with a stable client UUID and session CSRF', async () => {
    const result = api.createLibrary(workspaceId, library.id, '  Truyện đang dịch  ');
    http.expectOne({ method: 'GET', url: '/api/v1/auth/csrf' }).flush({ token: csrf });
    await new Promise((resolve) => setTimeout(resolve, 0));
    const request = http.expectOne({
      method: 'PUT',
      url: `/api/v1/workspaces/${workspaceId}/libraries/${library.id}`,
    });
    expect(request.request.headers.get('X-CSRF-Token')).toBe(csrf);
    expect(request.request.body).toEqual({ name: library.name });
    request.flush(library);
    await expect(result).resolves.toEqual(library);
  });

  it('lists invitation summaries while rejecting recipient-address leakage', async () => {
    const result = api.listInvitations(workspaceId);
    http
      .expectOne({ method: 'GET', url: `/api/v1/workspaces/${workspaceId}/invitations` })
      .flush({ items: [invitation] });
    await expect(result).resolves.toEqual([invitation]);

    const invalid = api.listInvitations(workspaceId);
    http
      .expectOne({ method: 'GET', url: `/api/v1/workspaces/${workspaceId}/invitations` })
      .flush({ items: [{ ...invitation, invitedEmail: 'private@example.test' }] });
    await expect(invalid).rejects.toMatchObject({ status: 502, code: null });
  });

  it('creates an email invitation through CSRF with strict consent and stable request ID', async () => {
    const result = api.createInvitation(
      workspaceId,
      invitationId,
      '  Reader@Example.Test ',
      'reader',
    );
    http.expectOne({ method: 'GET', url: '/api/v1/auth/csrf' }).flush({ token: csrf });
    await new Promise((resolve) => setTimeout(resolve, 0));
    const request = http.expectOne({
      method: 'PUT',
      url: `/api/v1/workspaces/${workspaceId}/invitations/${invitationId}`,
    });
    expect(request.request.headers.get('X-CSRF-Token')).toBe(csrf);
    expect(request.request.body).toEqual({
      email: 'reader@example.test',
      role: 'reader',
      confirmedPermissionToEmail: true,
    });
    const queued = {
      id: invitationId,
      status: 'pending',
      expiresAt: invitation.expiresAt,
      message: 'Queued.',
    };
    request.flush(queued);
    await expect(result).resolves.toEqual(queued);
    await expect(
      api.createInvitation(workspaceId, invitationId, 'bad', 'reader'),
    ).rejects.toMatchObject({
      status: 400,
    });
  });

  it('resolves and accepts a verified-email invitation using CSRF and a body token', async () => {
    const resolved = api.resolveInvitation(invitationId, invitationToken);
    http.expectOne({ method: 'GET', url: '/api/v1/auth/csrf' }).flush({ token: csrf });
    await new Promise((resolve) => setTimeout(resolve, 0));
    const resolveRequest = http.expectOne({
      method: 'POST',
      url: `/api/v1/me/workspace-invitations/${invitationId}/resolve`,
    });
    expect(resolveRequest.request.headers.get('X-CSRF-Token')).toBe(csrf);
    expect(resolveRequest.request.body).toEqual({ token: invitationToken });
    const recipient = {
      id: invitationId,
      workspaceId,
      workspaceName: 'Nhóm đọc',
      role: 'reader',
      expiresAt: invitation.expiresAt,
    };
    resolveRequest.flush(recipient);
    await expect(resolved).resolves.toEqual(recipient);

    const accepted = api.decideInvitation(invitationId, invitationToken, 'accept');
    http.expectOne({ method: 'GET', url: '/api/v1/auth/csrf' }).flush({ token: csrf });
    await new Promise((resolve) => setTimeout(resolve, 0));
    const acceptRequest = http.expectOne({
      method: 'POST',
      url: `/api/v1/me/workspace-invitations/${invitationId}/accept`,
    });
    expect(acceptRequest.request.headers.get('X-CSRF-Token')).toBe(csrf);
    expect(acceptRequest.request.body).toEqual({ token: invitationToken });
    acceptRequest.flush({ id: invitationId, status: 'accepted' });
    await expect(accepted).resolves.toBe('accepted');
  });

  it('revokes a pending invitation with session CSRF and validates recipient decisions', async () => {
    const revoking = api.revokeInvitation(workspaceId, invitationId);
    http.expectOne({ method: 'GET', url: '/api/v1/auth/csrf' }).flush({ token: csrf });
    await new Promise((resolve) => setTimeout(resolve, 0));
    const revokeRequest = http.expectOne({
      method: 'DELETE',
      url: `/api/v1/workspaces/${workspaceId}/invitations/${invitationId}`,
    });
    expect(revokeRequest.request.headers.get('X-CSRF-Token')).toBe(csrf);
    revokeRequest.flush(null, { status: 204, statusText: 'No Content' });
    await expect(revoking).resolves.toBeUndefined();

    await expect(api.resolveInvitation(invitationId, 'invalid')).rejects.toMatchObject({
      status: 400,
    });
    const rejected = api.decideInvitation(invitationId, invitationToken, 'reject');
    http.expectOne({ method: 'GET', url: '/api/v1/auth/csrf' }).flush({ token: csrf });
    await new Promise((resolve) => setTimeout(resolve, 0));
    http
      .expectOne({
        method: 'POST',
        url: `/api/v1/me/workspace-invitations/${invitationId}/reject`,
      })
      .flush({ id: invitationId, status: 'rejected' });
    await expect(rejected).resolves.toBe('rejected');
  });

  it('sets a role idempotently through PUT and validates the exact resulting member', async () => {
    const result = api.setMemberRole(workspaceId, memberId, 'editor');
    http.expectOne({ method: 'GET', url: '/api/v1/auth/csrf' }).flush({ token: csrf });
    await new Promise((resolve) => setTimeout(resolve, 0));
    const request = http.expectOne({
      method: 'PUT',
      url: `/api/v1/workspaces/${workspaceId}/members/${memberId}`,
    });
    expect(request.request.headers.get('X-CSRF-Token')).toBe(csrf);
    expect(request.request.body).toEqual({ role: 'editor' });
    request.flush({ ...member, role: 'editor' });
    await expect(result).resolves.toMatchObject({ role: 'editor', status: 'active' });
  });

  it('revokes a member and transfers ownership only using CSRF-protected mutations', async () => {
    const revoking = api.revokeMember(workspaceId, memberId);
    http.expectOne({ method: 'GET', url: '/api/v1/auth/csrf' }).flush({ token: csrf });
    await new Promise((resolve) => setTimeout(resolve, 0));
    const revokeRequest = http.expectOne({
      method: 'DELETE',
      url: `/api/v1/workspaces/${workspaceId}/members/${memberId}`,
    });
    expect(revokeRequest.request.headers.get('X-CSRF-Token')).toBe(csrf);
    revokeRequest.flush({ ...member, status: 'revoked', revokedAt: '2026-11-18T02:00:00Z' });
    await expect(revoking).resolves.toMatchObject({ status: 'revoked' });

    const transferring = api.transferOwnership(workspaceId, memberId);
    http.expectOne({ method: 'GET', url: '/api/v1/auth/csrf' }).flush({ token: csrf });
    await new Promise((resolve) => setTimeout(resolve, 0));
    const transferRequest = http.expectOne({
      method: 'PUT',
      url: `/api/v1/workspaces/${workspaceId}/owner`,
    });
    expect(transferRequest.request.headers.get('X-CSRF-Token')).toBe(csrf);
    expect(transferRequest.request.body).toEqual({ newOwnerUserId: memberId });
    transferRequest.flush({ ...detail, scopeVersion: 3 });
    await expect(transferring).resolves.toMatchObject({ scopeVersion: 3 });
  });

  it('rejects invalid ids, roles, response fields and malformed CSRF tokens', async () => {
    await expect(api.createWorkspace('bad', 'Group')).rejects.toMatchObject({ status: 400 });
    await expect(api.setMemberRole(workspaceId, memberId, 'owner' as never)).rejects.toMatchObject({
      status: 400,
    });

    const invalidWorkspace = api.createWorkspace(workspaceId, 'Group');
    http.expectOne({ method: 'GET', url: '/api/v1/auth/csrf' }).flush({ token: ` ${csrf}` });
    await expect(invalidWorkspace).rejects.toBeInstanceOf(WorkspaceApiError);
  });

  it('preserves stable typed API errors for role denial and owner protection', async () => {
    const result = api.setMemberRole(workspaceId, memberId, 'reader');
    http.expectOne({ method: 'GET', url: '/api/v1/auth/csrf' }).flush({ token: csrf });
    await new Promise((resolve) => setTimeout(resolve, 0));
    http
      .expectOne({
        method: 'PUT',
        url: `/api/v1/workspaces/${workspaceId}/members/${memberId}`,
      })
      .flush({ code: 'WORKSPACE_OWNER_PROTECTED' }, { status: 409, statusText: 'Conflict' });
    await expect(result).rejects.toMatchObject({ status: 409, code: 'WORKSPACE_OWNER_PROTECTED' });
  });
});
