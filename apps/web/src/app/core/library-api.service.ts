import { HttpClient, HttpErrorResponse, HttpHeaders, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom, timeout } from 'rxjs';

export const libraryReadingStatuses = [
  'reading',
  'want-to-read',
  'paused',
  'completed',
  'dropped',
] as const;

export type AccountReadingStatus = typeof libraryReadingStatuses[number];

export interface AccountLibraryCollection {
  id: string;
  name: string;
  workCount: number;
  createdAt: string;
  updatedAt: string;
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
const csrfUrl = '/api/v1/auth/csrf';
const requestTimeoutMs = 5_000;

@Injectable({ providedIn: 'root' })
export class LibraryApiService {
  private readonly http = inject(HttpClient);

  async listCollections(): Promise<AccountLibraryCollection[]> {
    try {
      const response: unknown = await firstValueFrom(
        this.http.get<unknown>(collectionsUrl).pipe(timeout(requestTimeoutMs)),
      );
      if (!Array.isArray(response) || response.length > 12) throw invalidResponse();
      const collections = response.map(parseCollection);
      if (new Set(collections.map(({ id }) => id)).size !== collections.length) throw invalidResponse();
      return collections;
    } catch (error) {
      throw toLibraryApiError(error);
    }
  }

  async createCollection(requestedName: string): Promise<AccountLibraryCollection> {
    const name = requestedName.trim();
    if (!name || [...name].length > 40) throw new LibraryApiError('INVALID_COLLECTION_NAME', 400, 'INVALID_COLLECTION_NAME');

    try {
      const headers = await this.csrfHeaders();
      const response: unknown = await firstValueFrom(
        this.http.post<unknown>(collectionsUrl, { name }, { headers }).pipe(timeout(requestTimeoutMs)),
      );
      return parseCollection(response);
    } catch (error) {
      throw toLibraryApiError(error);
    }
  }

  async deleteCollection(collectionId: string): Promise<void> {
    if (!isUuid(collectionId)) throw new LibraryApiError('INVALID_COLLECTION_ID', 400, 'INVALID_COLLECTION_ID');

    try {
      const headers = await this.csrfHeaders();
      await firstValueFrom(
        this.http.delete<void>(`${collectionsUrl}/${collectionId}`, { headers }).pipe(timeout(requestTimeoutMs)),
      );
    } catch (error) {
      throw toLibraryApiError(error);
    }
  }

  async getWorkState(workId: string): Promise<AccountWorkState> {
    if (!isUuid(workId)) throw new LibraryApiError('INVALID_WORK_ID', 400, 'INVALID_WORK_ID');

    try {
      const response = await firstValueFrom(
        this.http.get<unknown>(this.workStateUrl(workId), { observe: 'response' }).pipe(timeout(requestTimeoutMs)),
      );
      return parseWorkStateResponse(response, workId);
    } catch (error) {
      throw toLibraryApiError(error);
    }
  }

  async replaceWorkState(workId: string, currentEtag: string, changes: WorkStateChanges): Promise<AccountWorkState> {
    if (!isUuid(workId) || !isEtag(currentEtag)) {
      throw new LibraryApiError('INVALID_WORK_STATE_VERSION', 400, 'INVALID_WORK_STATE_VERSION');
    }

    try {
      const csrf = await this.csrfHeaders();
      const headers = csrf.set('If-Match', currentEtag);
      const response = await firstValueFrom(
        this.http.put<unknown>(this.workStateUrl(workId), {
          readingStatus: changes.readingStatus,
          collectionIds: [...new Set(changes.collectionIds)],
        }, { headers, observe: 'response' }).pipe(timeout(requestTimeoutMs)),
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
  if (!isRecord(value)
      || typeof value['id'] !== 'string'
      || !isUuid(value['id'])
      || typeof value['name'] !== 'string'
      || !value['name'].trim()
      || [...value['name']].length > 40
      || !Number.isInteger(value['workCount'])
      || (value['workCount'] as number) < 0
      || !isTimestamp(value['createdAt'])
      || !isTimestamp(value['updatedAt'])) {
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

function parseWorkStateResponse(response: HttpResponse<unknown>, expectedWorkId: string): AccountWorkState {
  const state = parseWorkState(response.body, response.headers.get('ETag'));
  if (state.workId !== expectedWorkId) throw invalidResponse();
  return state;
}

function parseWorkState(value: unknown, etag: string | null): AccountWorkState {
  if (!isRecord(value)
      || typeof value['workId'] !== 'string'
      || !isUuid(value['workId'])
      || typeof value['readingStatus'] !== 'string'
      || !isReadingStatus(value['readingStatus'])
      || !Array.isArray(value['collectionIds'])
      || value['collectionIds'].length > 12
      || !value['collectionIds'].every((id) => typeof id === 'string' && isUuid(id))
      || new Set(value['collectionIds']).size !== value['collectionIds'].length
      || !Number.isSafeInteger(value['version'])
      || (value['version'] as number) < 0
      || (value['updatedAt'] !== null && !isTimestamp(value['updatedAt']))
      || ((value['version'] as number) === 0 && value['updatedAt'] !== null)
      || ((value['version'] as number) > 0 && value['updatedAt'] === null)
      || !etag
      || !isEtag(etag)
      || Number(etag.slice(2, -1)) !== value['version']) {
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
  const latestState = error.status === 409 && body && isRecord(body['latest'])
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
