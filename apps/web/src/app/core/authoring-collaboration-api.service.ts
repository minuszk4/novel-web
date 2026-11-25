import { HttpClient, HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom, timeout } from 'rxjs';

export const manuscriptPermissions = ['VIEW', 'EDIT'] as const;
export type ManuscriptPermission = (typeof manuscriptPermissions)[number];

export interface ManuscriptCollaborator {
  id: string;
  permissions: ManuscriptPermission[];
  version: number;
  grantedAt: string;
  etag: string;
}

export interface ManuscriptInvitationSummary {
  id: string;
  permissions: ManuscriptPermission[];
  status: 'pending';
  createdAt: string;
  expiresAt: string;
}

export interface ManuscriptCollaborationManagement {
  collaborators: ManuscriptCollaborator[];
  invitations: ManuscriptInvitationSummary[];
}

export interface SharedManuscript {
  workId: string;
  title: string;
  author: string;
  permissions: ManuscriptPermission[];
  grantedAt: string;
}

export interface ManuscriptInvitationPreview {
  id: string;
  title: string;
  author: string;
  permissions: ManuscriptPermission[];
  expiresAt: string;
}

export interface ManuscriptInvitationQueued {
  id: string;
  permissions: ManuscriptPermission[];
  status: 'pending' | 'accepted' | 'rejected' | 'revoked' | 'expired';
  expiresAt: string;
  message: string;
}

export class AuthoringCollaborationApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string | null,
  ) {
    super(code ?? `HTTP_${status}`);
    this.name = 'AuthoringCollaborationApiError';
  }
}

const root = '/api/v1';
const timeoutMs = 5_000;
const tokenPattern = /^[A-Za-z0-9_-]{43}$/;
const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const grantEtagPattern = /^"manuscript-grant-[1-9][0-9]*"$/;

@Injectable({ providedIn: 'root' })
export class AuthoringCollaborationApiService {
  private readonly http = inject(HttpClient);

  async listManagement(workId: string): Promise<ManuscriptCollaborationManagement> {
    requireUuid(workId);
    try {
      const response = await firstValueFrom(
        this.http
          .get<unknown>(`${root}/authoring/manuscripts/${workId}/contributors`, {
            observe: 'response',
          })
          .pipe(timeout(timeoutMs)),
      );
      requireNoStore(response.status, response.headers);
      const body = requireObject(response.body, ['collaborators', 'invitations']);
      if (
        !Array.isArray(body['collaborators']) ||
        body['collaborators'].length > 100 ||
        !Array.isArray(body['invitations']) ||
        body['invitations'].length > 100
      ) {
        throw invalidResponse();
      }
      return {
        collaborators: body['collaborators'].map(parseCollaborator),
        invitations: body['invitations'].map(parseInvitationSummary),
      };
    } catch (error) {
      throw toApiError(error);
    }
  }

  async createInvitation(
    workId: string,
    invitationId: string,
    email: string,
    permissions: ManuscriptPermission[],
  ): Promise<ManuscriptInvitationQueued> {
    validateInviteInput(workId, invitationId, email, permissions);
    try {
      const response = await firstValueFrom(
        this.http
          .put<unknown>(
            `${root}/authoring/manuscripts/${workId}/invitations/${invitationId}`,
            { email: email.trim().toLowerCase(), permissions, confirmedPermissionToEmail: true },
            { headers: await this.csrfHeaders(), observe: 'response' },
          )
          .pipe(timeout(timeoutMs)),
      );
      if (response.status !== 202) throw invalidResponse();
      requireNoStore(response.status, response.headers);
      const queued = parseQueued(response.body);
      if (queued.id !== invitationId) throw invalidResponse();
      return queued;
    } catch (error) {
      throw toApiError(error);
    }
  }

  async changePermissions(
    workId: string,
    grant: ManuscriptCollaborator,
    invitationId: string,
    permissions: ManuscriptPermission[],
  ): Promise<ManuscriptInvitationQueued> {
    validateInviteInput(workId, invitationId, 'valid@example.test', permissions);
    if (!uuidPattern.test(grant.id) || !grantEtagPattern.test(grant.etag)) throw invalidRequest();
    try {
      const response = await firstValueFrom(
        this.http
          .put<unknown>(
            `${root}/authoring/manuscripts/${workId}/contributors/${grant.id}`,
            { invitationId, permissions, confirmedPermissionToEmail: true },
            {
              headers: (await this.csrfHeaders()).set('If-Match', grant.etag),
              observe: 'response',
            },
          )
          .pipe(timeout(timeoutMs)),
      );
      if (response.status !== 202) throw invalidResponse();
      requireNoStore(response.status, response.headers);
      const queued = parseQueued(response.body);
      if (queued.id !== invitationId) throw invalidResponse();
      return queued;
    } catch (error) {
      throw toApiError(error);
    }
  }

  async revokeInvitation(workId: string, invitationId: string): Promise<void> {
    requireUuid(workId);
    requireUuid(invitationId);
    try {
      const response = await firstValueFrom(
        this.http
          .delete<void>(`${root}/authoring/manuscripts/${workId}/invitations/${invitationId}`, {
            headers: await this.csrfHeaders(),
            observe: 'response',
          })
          .pipe(timeout(timeoutMs)),
      );
      if (response.status !== 204) throw invalidResponse();
      requireNoStore(response.status, response.headers);
    } catch (error) {
      throw toApiError(error);
    }
  }

  async revokeCollaborator(workId: string, grant: ManuscriptCollaborator): Promise<void> {
    requireUuid(workId);
    if (!uuidPattern.test(grant.id) || !grantEtagPattern.test(grant.etag)) throw invalidRequest();
    try {
      const response = await firstValueFrom(
        this.http
          .delete<void>(`${root}/authoring/manuscripts/${workId}/contributors/${grant.id}`, {
            headers: (await this.csrfHeaders()).set('If-Match', grant.etag),
            observe: 'response',
          })
          .pipe(timeout(timeoutMs)),
      );
      if (response.status !== 204) throw invalidResponse();
      requireNoStore(response.status, response.headers);
    } catch (error) {
      throw toApiError(error);
    }
  }

  async listSharedWorks(): Promise<SharedManuscript[]> {
    try {
      const response = await firstValueFrom(
        this.http
          .get<unknown>(`${root}/authoring/collaborations`, { observe: 'response' })
          .pipe(timeout(timeoutMs)),
      );
      requireNoStore(response.status, response.headers);
      const body = requireObject(response.body, ['items']);
      if (!Array.isArray(body['items']) || body['items'].length > 100) throw invalidResponse();
      const items = body['items'].map(parseSharedManuscript);
      if (new Set(items.map(({ workId }) => workId)).size !== items.length) throw invalidResponse();
      return items;
    } catch (error) {
      throw toApiError(error);
    }
  }

  async permissionsForWork(workId: string): Promise<ManuscriptPermission[] | null> {
    requireUuid(workId);
    try {
      const shared = await this.listSharedWorks();
      return shared.find((item) => item.workId === workId)?.permissions ?? null;
    } catch (error) {
      const mapped = toApiError(error);
      // When collaboration is disabled only owners can reach an authored manuscript route.
      if (mapped.status === 503 && mapped.code === 'MANUSCRIPT_COLLABORATION_UNAVAILABLE')
        return null;
      throw mapped;
    }
  }

  async resolveInvitation(
    invitationId: string,
    token: string,
  ): Promise<ManuscriptInvitationPreview> {
    validateInvitationCredentials(invitationId, token);
    return this.postInvitationAction(invitationId, token, 'resolve').then((response) => {
      const invitation = parseInvitationPreview(response);
      if (invitation.id !== invitationId) throw invalidResponse();
      return invitation;
    });
  }

  async decideInvitation(
    invitationId: string,
    token: string,
    decision: 'accept' | 'reject',
  ): Promise<'accepted' | 'rejected'> {
    validateInvitationCredentials(invitationId, token);
    const response = requireObject(await this.postInvitationAction(invitationId, token, decision), [
      'id',
      'status',
    ]);
    const status = decision === 'accept' ? 'accepted' : 'rejected';
    if (response['id'] !== invitationId || response['status'] !== status) throw invalidResponse();
    return status;
  }

  private async postInvitationAction(
    invitationId: string,
    token: string,
    action: 'resolve' | 'accept' | 'reject',
  ): Promise<unknown> {
    try {
      const response = await firstValueFrom(
        this.http
          .post<unknown>(
            `${root}/me/authoring-invitations/${invitationId}/${action}`,
            { token },
            { headers: await this.csrfHeaders(), observe: 'response' },
          )
          .pipe(timeout(timeoutMs)),
      );
      if (response.status !== 200) throw invalidResponse();
      requireNoStore(response.status, response.headers);
      return response.body;
    } catch (error) {
      throw toApiError(error);
    }
  }

  private async csrfHeaders(): Promise<HttpHeaders> {
    const response = await firstValueFrom(
      this.http.get<unknown>(`${root}/auth/csrf`).pipe(timeout(timeoutMs)),
    );
    const body = requireObject(response, ['token']);
    if (typeof body['token'] !== 'string' || !tokenPattern.test(body['token'])) {
      throw invalidResponse();
    }
    return new HttpHeaders({ 'X-CSRF-Token': body['token'] });
  }
}

function parseCollaborator(value: unknown): ManuscriptCollaborator {
  const row = requireObject(value, ['id', 'permissions', 'version', 'grantedAt', 'etag']);
  const permissions = parsePermissions(row['permissions']);
  if (
    typeof row['id'] !== 'string' ||
    !uuidPattern.test(row['id']) ||
    !Number.isSafeInteger(row['version']) ||
    Number(row['version']) < 1 ||
    !isDateTime(row['grantedAt']) ||
    typeof row['etag'] !== 'string' ||
    !grantEtagPattern.test(row['etag']) ||
    row['etag'] !== `"manuscript-grant-${row['version']}"`
  ) {
    throw invalidResponse();
  }
  return {
    id: row['id'],
    permissions,
    version: Number(row['version']),
    grantedAt: row['grantedAt'],
    etag: row['etag'],
  };
}

function parseInvitationSummary(value: unknown): ManuscriptInvitationSummary {
  const row = requireObject(value, ['id', 'permissions', 'status', 'createdAt', 'expiresAt']);
  if (
    typeof row['id'] !== 'string' ||
    !uuidPattern.test(row['id']) ||
    row['status'] !== 'pending' ||
    !isDateTime(row['createdAt']) ||
    !isDateTime(row['expiresAt'])
  ) {
    throw invalidResponse();
  }
  return {
    id: row['id'],
    permissions: parsePermissions(row['permissions']),
    status: 'pending',
    createdAt: row['createdAt'],
    expiresAt: row['expiresAt'],
  };
}

function parseSharedManuscript(value: unknown): SharedManuscript {
  const row = requireObject(value, ['workId', 'title', 'author', 'permissions', 'grantedAt']);
  if (
    typeof row['workId'] !== 'string' ||
    !uuidPattern.test(row['workId']) ||
    !isBoundedString(row['title'], 1, 300) ||
    !isBoundedString(row['author'], 0, 300) ||
    !isDateTime(row['grantedAt'])
  ) {
    throw invalidResponse();
  }
  return {
    workId: row['workId'],
    title: row['title'],
    author: row['author'],
    permissions: parsePermissions(row['permissions']),
    grantedAt: row['grantedAt'],
  };
}

function parseInvitationPreview(value: unknown): ManuscriptInvitationPreview {
  const row = requireObject(value, ['id', 'title', 'author', 'permissions', 'expiresAt']);
  if (
    typeof row['id'] !== 'string' ||
    !uuidPattern.test(row['id']) ||
    !isBoundedString(row['title'], 1, 300) ||
    !isBoundedString(row['author'], 0, 300) ||
    !isDateTime(row['expiresAt'])
  ) {
    throw invalidResponse();
  }
  return {
    id: row['id'],
    title: row['title'],
    author: row['author'],
    permissions: parsePermissions(row['permissions']),
    expiresAt: row['expiresAt'],
  };
}

function parseQueued(value: unknown): ManuscriptInvitationQueued {
  const row = requireObject(value, ['id', 'permissions', 'status', 'expiresAt', 'message']);
  if (
    typeof row['id'] !== 'string' ||
    !uuidPattern.test(row['id']) ||
    !['pending', 'accepted', 'rejected', 'revoked', 'expired'].includes(String(row['status'])) ||
    !isDateTime(row['expiresAt']) ||
    typeof row['message'] !== 'string'
  ) {
    throw invalidResponse();
  }
  return {
    id: row['id'],
    permissions: parsePermissions(row['permissions']),
    status: row['status'] as ManuscriptInvitationQueued['status'],
    expiresAt: row['expiresAt'],
    message: row['message'],
  };
}

function parsePermissions(value: unknown): ManuscriptPermission[] {
  if (
    !Array.isArray(value) ||
    value.length < 1 ||
    value.length > 2 ||
    !value.every((permission) =>
      manuscriptPermissions.includes(permission as ManuscriptPermission),
    ) ||
    !value.includes('VIEW') ||
    new Set(value).size !== value.length ||
    [...value].sort().join('|') !== value.join('|')
  ) {
    throw invalidResponse();
  }
  return value as ManuscriptPermission[];
}

function validateInviteInput(
  workId: string,
  invitationId: string,
  email: string,
  permissions: ManuscriptPermission[],
): void {
  const normalized = email.trim().toLowerCase();
  if (
    !uuidPattern.test(workId) ||
    !uuidPattern.test(invitationId) ||
    normalized.length < 3 ||
    normalized.length > 320 ||
    !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(normalized) ||
    permissions.length < 1 ||
    permissions.length > 2 ||
    !permissions.includes('VIEW') ||
    permissions.some((permission) => !manuscriptPermissions.includes(permission))
  ) {
    throw invalidRequest();
  }
}

function validateInvitationCredentials(invitationId: string, token: string): void {
  if (!uuidPattern.test(invitationId) || !tokenPattern.test(token)) throw invalidRequest();
}

function requireObject(value: unknown, keys: string[]): Record<string, unknown> {
  if (
    value === null ||
    typeof value !== 'object' ||
    Array.isArray(value) ||
    Object.keys(value).sort().join('|') !== [...keys].sort().join('|')
  ) {
    throw invalidResponse();
  }
  return value as Record<string, unknown>;
}

function requireNoStore(status: number, headers: HttpHeaders): void {
  if (status < 200 || status > 299 || !headers.get('Cache-Control')?.includes('no-store')) {
    throw invalidResponse();
  }
}

function isBoundedString(value: unknown, min: number, max: number): value is string {
  return typeof value === 'string' && [...value].length >= min && [...value].length <= max;
}

function isDateTime(value: unknown): value is string {
  return typeof value === 'string' && Number.isFinite(Date.parse(value));
}

function requireUuid(value: string): void {
  if (!uuidPattern.test(value)) throw invalidRequest();
}

function invalidRequest(): AuthoringCollaborationApiError {
  return new AuthoringCollaborationApiError(400, 'INVALID_COLLABORATION_REQUEST');
}

function invalidResponse(): AuthoringCollaborationApiError {
  return new AuthoringCollaborationApiError(502, 'INVALID_COLLABORATION_RESPONSE');
}

function toApiError(error: unknown): AuthoringCollaborationApiError {
  if (error instanceof AuthoringCollaborationApiError) return error;
  if (error instanceof HttpErrorResponse) {
    const body = error.error;
    const code =
      body !== null && typeof body === 'object' && typeof body['code'] === 'string'
        ? body['code']
        : null;
    return new AuthoringCollaborationApiError(error.status, code);
  }
  return new AuthoringCollaborationApiError(0, null);
}
