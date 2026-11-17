import {
  HttpClient,
  HttpErrorResponse,
  HttpHeaders,
  HttpParams,
  HttpResponse,
} from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom, timeout } from 'rxjs';

export const libraryReadingStatuses = [
  'reading',
  'want-to-read',
  'paused',
  'completed',
  'dropped',
] as const;

export type AccountReadingStatus = (typeof libraryReadingStatuses)[number];

export interface AccountLibraryCollection {
  id: string;
  name: string;
  workCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface AccountLibrarySummary {
  id: string;
  name: string;
  visibility: 'private';
  isDefault: boolean;
  createdAt: string;
}

export interface AccountWorkSummary {
  id: string;
  libraryId: string;
  title: string;
  author: string;
  language: string;
  visibility: 'private' | 'public';
  chapterCount: number;
  createdAt: string;
}

export interface AccountListPage<T> {
  items: T[];
  nextCursor: string | null;
}

export interface AccountWorkFilters {
  q?: string;
  tag?: string;
  status?: 'active' | 'archived';
  cursor?: string;
  limit?: number;
}

export interface AccountWorkState {
  workId: string;
  readingStatus: AccountReadingStatus;
  collectionIds: string[];
  version: number;
  updatedAt: string | null;
  etag: string;
}

export interface WorkStateChanges {
  readingStatus: AccountReadingStatus;
  collectionIds: string[];
}

export class LibraryApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code: string | null,
    readonly latestState: AccountWorkState | null = null,
  ) {
    super(message);
    this.name = 'LibraryApiError';
  }
}

const collectionsUrl = '/api/v1/me/library/collections';
const librariesUrl = '/api/v1/libraries';
const worksUrl = '/api/v1/works';
const csrfUrl = '/api/v1/auth/csrf';
const requestTimeoutMs = 5_000;

@Injectable({ providedIn: 'root' })
export class LibraryApiService {
  private readonly http = inject(HttpClient);

  async listLibraries(
    cursor?: string,
    limit = 100,
  ): Promise<AccountListPage<AccountLibrarySummary>> {
    validatePageRequest(cursor, limit);
    try {
      let params = new HttpParams().set('limit', limit);
      if (cursor) params = params.set('cursor', cursor);
      const response: unknown = await firstValueFrom(
        this.http.get<unknown>(librariesUrl, { params }).pipe(timeout(requestTimeoutMs)),
      );
      return parseListPage(response, limit, parseLibrarySummary);
    } catch (error) {
      throw toLibraryApiError(error);
    }
  }

  async listWorks(
    libraryId: string,
    filters: AccountWorkFilters = {},
  ): Promise<AccountListPage<AccountWorkSummary>> {
    if (!isUuid(libraryId))
      throw new LibraryApiError('INVALID_LIBRARY_ID', 400, 'INVALID_LIBRARY_ID');
    const { cursor, limit = 25, q, tag, status } = filters;
    validatePageRequest(cursor, limit);
    if (
      (q && [...q].length > 160) ||
      (tag && [...tag].length > 80) ||
      (status !== undefined && status !== 'active' && status !== 'archived')
    ) {
      throw new LibraryApiError('INVALID_LIBRARY_QUERY', 400, 'INVALID_LIBRARY_QUERY');
    }

    try {
      let params = new HttpParams().set('libraryId', libraryId).set('limit', limit);
      if (cursor) params = params.set('cursor', cursor);
      if (q) params = params.set('q', q);
      if (tag) params = params.set('tag', tag);
      if (status) params = params.set('status', status);
      const response: unknown = await firstValueFrom(
        this.http.get<unknown>(worksUrl, { params }).pipe(timeout(requestTimeoutMs)),
      );
      return parseListPage(response, limit, (value) => parseWorkSummary(value, libraryId));
    } catch (error) {
      throw toLibraryApiError(error);
    }
  }

  async listCollections(): Promise<AccountLibraryCollection[]> {
    try {
      const response: unknown = await firstValueFrom(
        this.http.get<unknown>(collectionsUrl).pipe(timeout(requestTimeoutMs)),
      );
      if (!Array.isArray(response) || response.length > 12) throw invalidResponse();
      const collections = response.map(parseCollection);
      if (new Set(collections.map(({ id }) => id)).size !== collections.length)
        throw invalidResponse();
      return collections;
    } catch (error) {
      throw toLibraryApiError(error);
    }
  }

  async createCollection(requestedName: string): Promise<AccountLibraryCollection> {
    const name = requestedName.trim();
    if (!name || [...name].length > 40)
      throw new LibraryApiError('INVALID_COLLECTION_NAME', 400, 'INVALID_COLLECTION_NAME');

    try {
      const headers = await this.csrfHeaders();
      const response: unknown = await firstValueFrom(
        this.http
          .post<unknown>(collectionsUrl, { name }, { headers })
          .pipe(timeout(requestTimeoutMs)),
      );
      return parseCollection(response);
    } catch (error) {
      throw toLibraryApiError(error);
    }
  }

  async deleteCollection(collectionId: string): Promise<void> {
    if (!isUuid(collectionId))
      throw new LibraryApiError('INVALID_COLLECTION_ID', 400, 'INVALID_COLLECTION_ID');

    try {
      const headers = await this.csrfHeaders();
      await firstValueFrom(
        this.http
          .delete<void>(`${collectionsUrl}/${collectionId}`, { headers })
          .pipe(timeout(requestTimeoutMs)),
      );
    } catch (error) {
      throw toLibraryApiError(error);
    }
  }

  async getWorkState(workId: string): Promise<AccountWorkState> {
    if (!isUuid(workId)) throw new LibraryApiError('INVALID_WORK_ID', 400, 'INVALID_WORK_ID');

    try {
      const response = await firstValueFrom(
        this.http
          .get<unknown>(this.workStateUrl(workId), { observe: 'response' })
          .pipe(timeout(requestTimeoutMs)),
      );
      return parseWorkStateResponse(response, workId);
    } catch (error) {
      throw toLibraryApiError(error);
    }
  }

  async replaceWorkState(
    workId: string,
    currentEtag: string,
    changes: WorkStateChanges,
  ): Promise<AccountWorkState> {
    if (!isUuid(workId) || !isEtag(currentEtag)) {
      throw new LibraryApiError('INVALID_WORK_STATE_VERSION', 400, 'INVALID_WORK_STATE_VERSION');
    }

    try {
      const csrf = await this.csrfHeaders();
      const headers = csrf.set('If-Match', currentEtag);
      const response = await firstValueFrom(
        this.http
          .put<unknown>(
            this.workStateUrl(workId),
            {
              readingStatus: changes.readingStatus,
              collectionIds: [...new Set(changes.collectionIds)],
            },
            { headers, observe: 'response' },
          )
          .pipe(timeout(requestTimeoutMs)),
      );
      return parseWorkStateResponse(response, workId);
    } catch (error) {
      throw toLibraryApiError(error);
    }
  }

  private async csrfHeaders(): Promise<HttpHeaders> {
    const response: unknown = await firstValueFrom(
      this.http.get<unknown>(csrfUrl).pipe(timeout(requestTimeoutMs)),
    );
    if (!isRecord(response) || typeof response['token'] !== 'string' || !response['token'].trim()) {
      throw invalidResponse();
    }
    return new HttpHeaders({ 'X-CSRF-Token': response['token'] });
  }

  private workStateUrl(workId: string): string {
    return `/api/v1/me/works/${workId}/library-state`;
  }
}

function parseCollection(value: unknown): AccountLibraryCollection {
  if (
    !isRecord(value) ||
    typeof value['id'] !== 'string' ||
    !isUuid(value['id']) ||
    typeof value['name'] !== 'string' ||
    !value['name'].trim() ||
    [...value['name']].length > 40 ||
    !Number.isInteger(value['workCount']) ||
    (value['workCount'] as number) < 0 ||
    !isTimestamp(value['createdAt']) ||
    !isTimestamp(value['updatedAt'])
  ) {
    throw invalidResponse();
  }

  return {
    id: value['id'],
    name: value['name'],
    workCount: value['workCount'] as number,
    createdAt: value['createdAt'],
    updatedAt: value['updatedAt'],
  };
}

function parseListPage<T>(
  value: unknown,
  limit: number,
  parseItem: (value: unknown) => T,
): AccountListPage<T> {
  if (
    !isRecord(value) ||
    !Array.isArray(value['items']) ||
    value['items'].length > limit ||
    (value['nextCursor'] !== null &&
      (typeof value['nextCursor'] !== 'string' ||
        !value['nextCursor'] ||
        value['nextCursor'].length > 2048))
  ) {
    throw invalidResponse();
  }
  const items = value['items'].map(parseItem);
  if (new Set(items.map((item) => (item as { id: string }).id)).size !== items.length)
    throw invalidResponse();
  return { items, nextCursor: value['nextCursor'] as string | null };
}

function parseLibrarySummary(value: unknown): AccountLibrarySummary {
  if (
    !isRecord(value) ||
    typeof value['id'] !== 'string' ||
    !isUuid(value['id']) ||
    typeof value['name'] !== 'string' ||
    !value['name'].trim() ||
    [...value['name']].length > 120 ||
    value['visibility'] !== 'private' ||
    typeof value['isDefault'] !== 'boolean' ||
    !isTimestamp(value['createdAt'])
  ) {
    throw invalidResponse();
  }
  return {
    id: value['id'],
    name: value['name'],
    visibility: 'private',
    isDefault: value['isDefault'],
    createdAt: value['createdAt'],
  };
}

function parseWorkSummary(value: unknown, expectedLibraryId: string): AccountWorkSummary {
  if (
    !isRecord(value) ||
    typeof value['id'] !== 'string' ||
    !isUuid(value['id']) ||
    value['libraryId'] !== expectedLibraryId ||
    typeof value['title'] !== 'string' ||
    !value['title'].trim() ||
    [...value['title']].length > 300 ||
    typeof value['author'] !== 'string' ||
    [...value['author']].length > 200 ||
    typeof value['language'] !== 'string' ||
    [...value['language']].length < 2 ||
    [...value['language']].length > 35 ||
    (value['visibility'] !== 'private' && value['visibility'] !== 'public') ||
    !Number.isInteger(value['chapterCount']) ||
    (value['chapterCount'] as number) < 0 ||
    !isTimestamp(value['createdAt'])
  ) {
    throw invalidResponse();
  }
  return {
    id: value['id'],
    libraryId: value['libraryId'],
    title: value['title'],
    author: value['author'],
    language: value['language'],
    visibility: value['visibility'],
    chapterCount: value['chapterCount'] as number,
    createdAt: value['createdAt'],
  };
}

function validatePageRequest(cursor: string | undefined, limit: number): void {
  if (
    !Number.isInteger(limit) ||
    limit < 1 ||
    limit > 100 ||
    (cursor !== undefined && (!cursor || cursor.length > 2048))
  ) {
    throw new LibraryApiError('INVALID_LIBRARY_QUERY', 400, 'INVALID_LIBRARY_QUERY');
  }
}

function parseWorkStateResponse(
  response: HttpResponse<unknown>,
  expectedWorkId: string,
): AccountWorkState {
  const state = parseWorkState(response.body, response.headers.get('ETag'));
  if (state.workId !== expectedWorkId) throw invalidResponse();
  return state;
}

function parseWorkState(value: unknown, etag: string | null): AccountWorkState {
  if (
    !isRecord(value) ||
    typeof value['workId'] !== 'string' ||
    !isUuid(value['workId']) ||
    typeof value['readingStatus'] !== 'string' ||
    !isReadingStatus(value['readingStatus']) ||
    !Array.isArray(value['collectionIds']) ||
    value['collectionIds'].length > 12 ||
    !value['collectionIds'].every((id) => typeof id === 'string' && isUuid(id)) ||
    new Set(value['collectionIds']).size !== value['collectionIds'].length ||
    !Number.isSafeInteger(value['version']) ||
    (value['version'] as number) < 0 ||
    (value['updatedAt'] !== null && !isTimestamp(value['updatedAt'])) ||
    ((value['version'] as number) === 0 && value['updatedAt'] !== null) ||
    ((value['version'] as number) > 0 && value['updatedAt'] === null) ||
    !etag ||
    !isEtag(etag) ||
    Number(etag.slice(2, -1)) !== value['version']
  ) {
    throw invalidResponse();
  }

  return {
    workId: value['workId'],
    readingStatus: value['readingStatus'],
    collectionIds: [...value['collectionIds']] as string[],
    version: value['version'] as number,
    updatedAt: value['updatedAt'] as string | null,
    etag,
  };
}

function toLibraryApiError(error: unknown): LibraryApiError {
  if (error instanceof LibraryApiError) return error;
  if (!(error instanceof HttpErrorResponse)) {
    return new LibraryApiError('NETWORK_OR_INVALID_RESPONSE', 0, null);
  }

  const body = isRecord(error.error) ? error.error : null;
  const code = body && typeof body['code'] === 'string' ? body['code'] : null;
  const latestState =
    error.status === 409 && body && isRecord(body['latest'])
      ? tryParseWorkState(body['latest'], error.headers.get('ETag'))
      : null;
  return new LibraryApiError(error.message, error.status, code, latestState);
}

function tryParseWorkState(value: unknown, etag: string | null): AccountWorkState | null {
  try {
    return parseWorkState(value, etag);
  } catch {
    return null;
  }
}

function invalidResponse(): LibraryApiError {
  return new LibraryApiError('INVALID_API_RESPONSE', 502, 'INVALID_API_RESPONSE');
}

function isReadingStatus(value: string): value is AccountReadingStatus {
  return libraryReadingStatuses.some((status) => status === value);
}

function isTimestamp(value: unknown): value is string {
  return typeof value === 'string' && Number.isFinite(Date.parse(value));
}

function isUuid(value: string): boolean {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value);
}

function isEtag(value: string): boolean {
  return /^"v(?:0|[1-9][0-9]{0,18})"$/.test(value);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
