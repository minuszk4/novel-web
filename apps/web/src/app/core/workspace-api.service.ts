import { HttpClient, HttpErrorResponse, HttpHeaders, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom, timeout } from 'rxjs';

export const workspaceRoles = ['owner', 'admin', 'editor', 'reader'] as const;
export type WorkspaceRole = (typeof workspaceRoles)[number];
export type WorkspaceMemberRole = Exclude<WorkspaceRole, 'owner'>;
export type WorkspaceMemberStatus = 'active' | 'revoked';

export interface WorkspaceSummary {
  id: string;
  name: string;
  role: WorkspaceRole;
  scopeVersion: number;
  createdAt: string;
  joinedAt: string;
}

export interface WorkspaceDetail {
  id: string;
  name: string;
  scopeVersion: number;
  createdAt: string;
}

export interface WorkspaceMember {
  userId: string;
  role: WorkspaceRole;
  status: WorkspaceMemberStatus;
  joinedAt: string;
  revokedAt: string | null;
}

export interface WorkspaceLibrary {
  id: string;
  name: string;
  visibility: 'private';
  isDefault: false;
  createdAt: string;
}

export interface WorkspaceLibraryPage {
  items: WorkspaceLibrary[];
  nextCursor: string | null;
}

export class WorkspaceApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code: string | null,
  ) {
    super(message);
    this.name = 'WorkspaceApiError';
  }
}

const workspacesUrl = '/api/v1/me/workspaces';
const workspaceUrl = (workspaceId: string) =>
  `/api/v1/workspaces/${encodeURIComponent(workspaceId)}`;
const membersUrl = (workspaceId: string) => `${workspaceUrl(workspaceId)}/members`;
const memberUrl = (workspaceId: string, userId: string) =>
  `${membersUrl(workspaceId)}/${encodeURIComponent(userId)}`;
const librariesUrl = (workspaceId: string) => `${workspaceUrl(workspaceId)}/libraries`;
const csrfUrl = '/api/v1/auth/csrf';
const timeoutMs = 5_000;
const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const tokenPattern = /^[A-Za-z0-9_-]{43}$/;

@Injectable({ providedIn: 'root' })
export class WorkspaceApiService {
  private readonly http = inject(HttpClient);

  async listWorkspaces(): Promise<WorkspaceSummary[]> {
    try {
      const response: unknown = await firstValueFrom(
        this.http.get<unknown>(workspacesUrl).pipe(timeout(timeoutMs)),
      );
      if (
        !isObject(response) ||
        !hasExactKeys(response, ['items']) ||
        !Array.isArray(response['items'])
      ) {
        throw invalidResponse();
      }
      const items = response['items'].map(parseWorkspaceSummary);
      if (new Set(items.map(({ id }) => id)).size !== items.length) throw invalidResponse();
      return items;
    } catch (error) {
      throw toApiError(error);
    }
  }

  async createWorkspace(workspaceId: string, requestedName: string): Promise<WorkspaceDetail> {
    const name = requestedName.trim();
    if (!isUuid(workspaceId) || !name || [...name].length > 120) {
      throw new WorkspaceApiError('INVALID_WORKSPACE_REQUEST', 400, 'INVALID_WORKSPACE');
    }
    try {
      const response: unknown = await firstValueFrom(
        this.http
          .put<unknown>(
            workspaceUrlForCreate(workspaceId),
            { name },
            {
              headers: await this.csrfHeaders(),
            },
          )
          .pipe(timeout(timeoutMs)),
      );
      const workspace = parseWorkspaceDetail(response);
      if (workspace.id !== workspaceId || workspace.name !== name) throw invalidResponse();
      return workspace;
    } catch (error) {
      throw toApiError(error);
    }
  }

  async listMembers(workspaceId: string): Promise<WorkspaceMember[]> {
    if (!isUuid(workspaceId)) throw new WorkspaceApiError('INVALID_WORKSPACE_ID', 400, null);
    try {
      const response: unknown = await firstValueFrom(
        this.http.get<unknown>(membersUrl(workspaceId)).pipe(timeout(timeoutMs)),
      );
      if (
        !isObject(response) ||
        !hasExactKeys(response, ['items']) ||
        !Array.isArray(response['items'])
      ) {
        throw invalidResponse();
      }
      const items = response['items'].map(parseWorkspaceMember);
      if (new Set(items.map(({ userId }) => userId)).size !== items.length) throw invalidResponse();
      return items;
    } catch (error) {
      throw toApiError(error);
    }
  }

  async listLibraries(
    workspaceId: string,
    cursor?: string,
    limit = 25,
  ): Promise<WorkspaceLibraryPage> {
    if (
      !isUuid(workspaceId) ||
      !Number.isInteger(limit) ||
      limit < 1 ||
      limit > 100 ||
      (cursor !== undefined && (!cursor.length || cursor.length > 2048))
    ) {
      throw new WorkspaceApiError('INVALID_WORKSPACE_LIBRARY_QUERY', 400, null);
    }
    try {
      let params = new HttpParams().set('limit', limit);
      if (cursor) params = params.set('cursor', cursor);
      const response: unknown = await firstValueFrom(
        this.http.get<unknown>(librariesUrl(workspaceId), { params }).pipe(timeout(timeoutMs)),
      );
      if (
        !isObject(response) ||
        !hasExactKeys(response, ['items', 'nextCursor']) ||
        !Array.isArray(response['items']) ||
        !(response['nextCursor'] === null || isBoundedCursor(response['nextCursor'])) ||
        response['items'].length > limit
      ) {
        throw invalidResponse();
      }
      const items = response['items'].map(parseWorkspaceLibrary);
      if (new Set(items.map(({ id }) => id)).size !== items.length) throw invalidResponse();
      return { items, nextCursor: response['nextCursor'] as string | null };
    } catch (error) {
      throw toApiError(error);
    }
  }

  async createLibrary(
    workspaceId: string,
    libraryId: string,
    requestedName: string,
  ): Promise<WorkspaceLibrary> {
    const name = requestedName.trim();
    if (!isUuid(workspaceId) || !isUuid(libraryId) || !name || [...name].length > 120) {
      throw new WorkspaceApiError('INVALID_WORKSPACE_LIBRARY', 400, null);
    }
    try {
      const response: unknown = await firstValueFrom(
        this.http
          .put<unknown>(
            `${librariesUrl(workspaceId)}/${encodeURIComponent(libraryId)}`,
            { name },
            { headers: await this.csrfHeaders() },
          )
          .pipe(timeout(timeoutMs)),
      );
      const library = parseWorkspaceLibrary(response);
      if (library.id !== libraryId || library.name !== name) throw invalidResponse();
      return library;
    } catch (error) {
      throw toApiError(error);
    }
  }

  async setMemberRole(
    workspaceId: string,
    userId: string,
    role: WorkspaceMemberRole,
  ): Promise<WorkspaceMember> {
    this.requireMemberIds(workspaceId, userId);
    if (!isMemberRole(role)) throw new WorkspaceApiError('INVALID_MEMBER_ROLE', 400, null);
    try {
      const response: unknown = await firstValueFrom(
        this.http
          .put<unknown>(
            memberUrl(workspaceId, userId),
            { role },
            {
              headers: await this.csrfHeaders(),
            },
          )
          .pipe(timeout(timeoutMs)),
      );
      const member = parseWorkspaceMember(response);
      if (member.userId !== userId || member.role !== role || member.status !== 'active') {
        throw invalidResponse();
      }
      return member;
    } catch (error) {
      throw toApiError(error);
    }
  }

  async revokeMember(workspaceId: string, userId: string): Promise<WorkspaceMember> {
    this.requireMemberIds(workspaceId, userId);
    try {
      const response: unknown = await firstValueFrom(
        this.http
          .delete<unknown>(memberUrl(workspaceId, userId), { headers: await this.csrfHeaders() })
          .pipe(timeout(timeoutMs)),
      );
      const member = parseWorkspaceMember(response);
      if (member.userId !== userId || member.status !== 'revoked' || member.revokedAt === null) {
        throw invalidResponse();
      }
      return member;
    } catch (error) {
      throw toApiError(error);
    }
  }

  async transferOwnership(workspaceId: string, newOwnerUserId: string): Promise<WorkspaceDetail> {
    if (!isUuid(workspaceId) || !isUuid(newOwnerUserId)) {
      throw new WorkspaceApiError('INVALID_WORKSPACE_OWNER', 400, null);
    }
    try {
      const response: unknown = await firstValueFrom(
        this.http
          .put<unknown>(
            `${workspaceUrl(workspaceId)}/owner`,
            { newOwnerUserId },
            {
              headers: await this.csrfHeaders(),
            },
          )
          .pipe(timeout(timeoutMs)),
      );
      const workspace = parseWorkspaceDetail(response);
      if (workspace.id !== workspaceId) throw invalidResponse();
      return workspace;
    } catch (error) {
      throw toApiError(error);
    }
  }

  private async csrfHeaders(): Promise<HttpHeaders> {
    const response: unknown = await firstValueFrom(
      this.http.get<unknown>(csrfUrl).pipe(timeout(timeoutMs)),
    );
    if (
      !isObject(response) ||
      !hasExactKeys(response, ['token']) ||
      typeof response['token'] !== 'string' ||
      !tokenPattern.test(response['token'])
    ) {
      throw invalidResponse();
    }
    return new HttpHeaders({ 'X-CSRF-Token': response['token'] });
  }

  private requireMemberIds(workspaceId: string, userId: string): void {
    if (!isUuid(workspaceId) || !isUuid(userId)) {
      throw new WorkspaceApiError('INVALID_WORKSPACE_MEMBER_ID', 400, null);
    }
  }
}

function workspaceUrlForCreate(workspaceId: string): string {
  return `${workspacesUrl}/${encodeURIComponent(workspaceId)}`;
}

function parseWorkspaceSummary(value: unknown): WorkspaceSummary {
  if (!isObject(value)) throw invalidResponse();
  const id = value['id'];
  const name = value['name'];
  const role = value['role'];
  const scopeVersion = value['scopeVersion'];
  const createdAt = value['createdAt'];
  const joinedAt = value['joinedAt'];
  if (
    !hasExactKeys(value, ['id', 'name', 'role', 'scopeVersion', 'createdAt', 'joinedAt']) ||
    !isUuid(id) ||
    !isWorkspaceName(name) ||
    !isWorkspaceRole(role) ||
    !isPositiveInteger(scopeVersion) ||
    !isTimestamp(createdAt) ||
    !isTimestamp(joinedAt)
  ) {
    throw invalidResponse();
  }
  return {
    id,
    name,
    role,
    scopeVersion,
    createdAt,
    joinedAt,
  };
}

function parseWorkspaceDetail(value: unknown): WorkspaceDetail {
  if (!isObject(value)) throw invalidResponse();
  const id = value['id'];
  const name = value['name'];
  const scopeVersion = value['scopeVersion'];
  const createdAt = value['createdAt'];
  if (
    !hasExactKeys(value, ['id', 'name', 'scopeVersion', 'createdAt']) ||
    !isUuid(id) ||
    !isWorkspaceName(name) ||
    !isPositiveInteger(scopeVersion) ||
    !isTimestamp(createdAt)
  ) {
    throw invalidResponse();
  }
  return {
    id,
    name,
    scopeVersion,
    createdAt,
  };
}

function parseWorkspaceMember(value: unknown): WorkspaceMember {
  if (!isObject(value)) throw invalidResponse();
  const userId = value['userId'];
  const role = value['role'];
  const status = value['status'];
  const joinedAt = value['joinedAt'];
  const revokedAt = value['revokedAt'];
  if (
    !hasExactKeys(value, ['userId', 'role', 'status', 'joinedAt', 'revokedAt']) ||
    !isUuid(userId) ||
    !isWorkspaceRole(role) ||
    !isWorkspaceMemberStatus(status) ||
    !isTimestamp(joinedAt) ||
    !(revokedAt === null || isTimestamp(revokedAt)) ||
    (status === 'active' && revokedAt !== null) ||
    (status === 'revoked' && revokedAt === null)
  ) {
    throw invalidResponse();
  }
  return {
    userId,
    role,
    status,
    joinedAt,
    revokedAt,
  };
}

function parseWorkspaceLibrary(value: unknown): WorkspaceLibrary {
  if (
    !isObject(value) ||
    !hasExactKeys(value, ['id', 'name', 'visibility', 'isDefault', 'createdAt']) ||
    !isUuid(value['id']) ||
    !isWorkspaceName(value['name']) ||
    value['visibility'] !== 'private' ||
    value['isDefault'] !== false ||
    !isTimestamp(value['createdAt'])
  ) {
    throw invalidResponse();
  }
  return {
    id: value['id'],
    name: value['name'],
    visibility: 'private',
    isDefault: false,
    createdAt: value['createdAt'],
  };
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function hasExactKeys(value: Record<string, unknown>, expected: string[]): boolean {
  return Object.keys(value).length === expected.length && expected.every((key) => key in value);
}

function isWorkspaceName(value: unknown): value is string {
  return typeof value === 'string' && value.trim().length > 0 && [...value].length <= 120;
}

function isTimestamp(value: unknown): value is string {
  return typeof value === 'string' && Number.isFinite(Date.parse(value));
}

function isBoundedCursor(value: unknown): value is string {
  return typeof value === 'string' && value.length > 0 && value.length <= 2048;
}

function isPositiveInteger(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value > 0;
}

function isUuid(value: unknown): value is string {
  return typeof value === 'string' && uuidPattern.test(value);
}

function isWorkspaceRole(value: unknown): value is WorkspaceRole {
  return typeof value === 'string' && (workspaceRoles as readonly string[]).includes(value);
}

function isMemberRole(value: unknown): value is WorkspaceMemberRole {
  return value === 'admin' || value === 'editor' || value === 'reader';
}

function isWorkspaceMemberStatus(value: unknown): value is WorkspaceMemberStatus {
  return value === 'active' || value === 'revoked';
}

function invalidResponse(): WorkspaceApiError {
  return new WorkspaceApiError('INVALID_WORKSPACE_RESPONSE', 502, null);
}

function toApiError(error: unknown): WorkspaceApiError {
  if (error instanceof WorkspaceApiError) return error;
  if (error instanceof HttpErrorResponse) {
    const body: unknown = error.error;
    const code = isObject(body) && typeof body['code'] === 'string' ? body['code'] : null;
    return new WorkspaceApiError(`WORKSPACE_HTTP_${error.status}`, error.status, code);
  }
  return new WorkspaceApiError('WORKSPACE_REQUEST_FAILED', 0, null);
}
