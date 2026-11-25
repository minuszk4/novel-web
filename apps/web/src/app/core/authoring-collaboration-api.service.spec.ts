import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import {
  AuthoringCollaborationApiError,
  AuthoringCollaborationApiService,
  ManuscriptCollaborator,
} from './authoring-collaboration-api.service';

const workId = '11111111-1111-4111-8111-111111111111';
const grantId = '22222222-2222-4222-8222-222222222222';
const invitationId = '33333333-3333-4333-8333-333333333333';
const csrf = 'A'.repeat(43);
const grant: ManuscriptCollaborator = {
  id: grantId,
  permissions: ['VIEW'],
  version: 4,
  grantedAt: '2026-11-25T01:00:00Z',
  etag: '"manuscript-grant-4"',
};

describe('AuthoringCollaborationApiService', () => {
  let api: AuthoringCollaborationApiService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    api = TestBed.inject(AuthoringCollaborationApiService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
    TestBed.resetTestingModule();
  });

  it('sends a consented invitation by stable ID and does not expose email in the response', async () => {
    const pending = api.createInvitation(workId, invitationId, ' Writer@Example.test ', ['VIEW']);
    http.expectOne('/api/v1/auth/csrf').flush({ token: csrf });
    await settle();
    const request = http.expectOne({
      method: 'PUT',
      url: `/api/v1/authoring/manuscripts/${workId}/invitations/${invitationId}`,
    });
    expect(request.request.headers.get('X-CSRF-Token')).toBe(csrf);
    expect(request.request.body).toEqual({
      email: 'writer@example.test',
      permissions: ['VIEW'],
      confirmedPermissionToEmail: true,
    });
    request.flush(
      {
        id: invitationId,
        permissions: ['VIEW'],
        status: 'pending',
        expiresAt: '2026-12-02T01:00:00Z',
        message: 'Queued.',
      },
      { status: 202, statusText: 'Accepted', headers: { 'Cache-Control': 'no-store' } },
    );
    await expect(pending).resolves.toMatchObject({ id: invitationId, permissions: ['VIEW'] });
  });

  it('sends a permission change with the current strong grant ETag', async () => {
    const changed = api.changePermissions(workId, grant, invitationId, ['EDIT', 'VIEW']);
    http.expectOne('/api/v1/auth/csrf').flush({ token: csrf });
    await settle();
    const request = http.expectOne({
      method: 'PUT',
      url: `/api/v1/authoring/manuscripts/${workId}/contributors/${grantId}`,
    });
    expect(request.request.headers.get('X-CSRF-Token')).toBe(csrf);
    expect(request.request.headers.get('If-Match')).toBe(grant.etag);
    expect(request.request.body).toEqual({
      invitationId,
      permissions: ['EDIT', 'VIEW'],
      confirmedPermissionToEmail: true,
    });
    request.flush(
      {
        id: invitationId,
        permissions: ['EDIT', 'VIEW'],
        status: 'pending',
        expiresAt: '2026-12-02T01:00:00Z',
        message: 'Queued.',
      },
      { status: 202, statusText: 'Accepted', headers: { 'Cache-Control': 'no-store' } },
    );
    await expect(changed).resolves.toMatchObject({ permissions: ['EDIT', 'VIEW'] });
  });

  it('rejects a leaked email field in the management response', async () => {
    const result = api.listManagement(workId);
    http.expectOne(`/api/v1/authoring/manuscripts/${workId}/contributors`).flush(
      {
        collaborators: [{ ...grant, email: 'private@example.test' }],
        invitations: [],
      },
      { headers: { 'Cache-Control': 'no-store' } },
    );
    await expect(result).rejects.toMatchObject({
      status: 502,
      code: 'INVALID_COLLABORATION_RESPONSE',
    });
  });

  it('uses no-store shared-work summaries and validates exact VIEW/EDIT permission sets', async () => {
    const result = api.listSharedWorks();
    http.expectOne('/api/v1/authoring/collaborations').flush(
      {
        items: [
          {
            workId,
            title: 'Bản thảo',
            author: 'Tác giả',
            permissions: ['EDIT', 'VIEW'],
            grantedAt: '2026-11-25T01:00:00Z',
          },
        ],
      },
      { headers: { 'Cache-Control': 'no-store' } },
    );
    await expect(result).resolves.toEqual([
      {
        workId,
        title: 'Bản thảo',
        author: 'Tác giả',
        permissions: ['EDIT', 'VIEW'],
        grantedAt: '2026-11-25T01:00:00Z',
      },
    ]);

    const invalid = api.listSharedWorks();
    http.expectOne('/api/v1/authoring/collaborations').flush(
      {
        items: [
          {
            workId,
            title: 'Bản thảo',
            author: 'Tác giả',
            permissions: ['PUBLISH', 'VIEW'],
            grantedAt: '2026-11-25T01:00:00Z',
          },
        ],
      },
      { headers: { 'Cache-Control': 'no-store' } },
    );
    await expect(invalid).rejects.toBeInstanceOf(AuthoringCollaborationApiError);
  });
});

async function settle(): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, 0));
}
