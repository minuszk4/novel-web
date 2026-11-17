import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom, timeout } from 'rxjs';

const requestTimeoutMs = 10_000;
const languageTagPattern = /^[a-z]{2,3}(?:-[a-z0-9]{2,8})*$/i;
const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

export interface PublicCatalogWork {
  id: string;
  title: string;
  author: string;
  sourceLanguage: string;
  readyChapterCount: number;
  isOwner?: boolean;
}

export interface PublicCatalogPage {
  items: PublicCatalogWork[];
  nextCursor: string | null;
}

export interface PublicWorkDetail extends PublicCatalogWork {
  isOwner: boolean;
}

export interface PublicWorkChapter {
  ordinal: number;
  title: string;
}

export interface PublicWorkChapterPage {
  workId: string;
  items: PublicWorkChapter[];
  nextAfterOrdinal: number | null;
}

export interface PublicCatalogRequest {
  query?: string;
  language?: string;
  cursor?: string;
  limit?: number;
}

export class PublicCatalogApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    readonly retryAfterSeconds?: number,
  ) {
    super(code);
    this.name = 'PublicCatalogApiError';
  }
}

@Injectable({ providedIn: 'root' })
export class PublicCatalogApiService {
  private readonly http = inject(HttpClient);

  async getWork(workId: string): Promise<PublicWorkDetail> {
    if (!uuidPattern.test(workId)) throw new PublicCatalogApiError(400, 'INVALID_CATALOG_QUERY');
    try {
      const response: unknown = await firstValueFrom(
        this.http
          .get<unknown>(`/api/v1/catalog/works/${encodeURIComponent(workId)}`)
          .pipe(timeout(requestTimeoutMs)),
      );
      return parseCatalogWork(response, true) as PublicWorkDetail;
    } catch (error) {
      throw toCatalogApiError(error);
    }
  }

  async listChapters(workId: string, afterOrdinal = 0, limit = 20): Promise<PublicWorkChapterPage> {
    if (
      !uuidPattern.test(workId) ||
      !Number.isSafeInteger(afterOrdinal) ||
      afterOrdinal < 0 ||
      afterOrdinal > 2_000_000_000 ||
      !Number.isSafeInteger(limit) ||
      limit < 1 ||
      limit > 50
    ) {
      throw new PublicCatalogApiError(400, 'INVALID_CATALOG_QUERY');
    }
    const params = new HttpParams().set('afterOrdinal', afterOrdinal).set('limit', limit);
    try {
      const response: unknown = await firstValueFrom(
        this.http
          .get<unknown>(`/api/v1/catalog/works/${encodeURIComponent(workId)}/chapters`, { params })
          .pipe(timeout(requestTimeoutMs)),
      );
      return parseChapterPage(response, workId, afterOrdinal, limit);
    } catch (error) {
      throw toCatalogApiError(error);
    }
  }

  async listWorks(request: PublicCatalogRequest = {}): Promise<PublicCatalogPage> {
    const query = normalizeQuery(request.query);
    const language = normalizeLanguage(request.language);
    const cursor = request.cursor?.trim() || undefined;
    const limit = request.limit ?? 20;

    if (
      query.codePointLength > 120 ||
      [...query.value].some((character) => {
        const point = character.codePointAt(0);
        return point !== undefined && isControlCodePoint(point);
      }) ||
      (language !== '' && !languageTagPattern.test(language)) ||
      language.length > 35 ||
      !Number.isSafeInteger(limit) ||
      limit < 1 ||
      limit > 50 ||
      (cursor !== undefined && cursor.length > 2048)
    ) {
      throw new PublicCatalogApiError(400, 'INVALID_CATALOG_QUERY');
    }

    let params = new HttpParams().set('limit', limit);
    if (query.value) params = params.set('q', query.value);
    if (language) params = params.set('language', language);
    if (cursor) params = params.set('cursor', cursor);

    try {
      const response: unknown = await firstValueFrom(
        this.http.get<unknown>('/api/v1/catalog/works', { params }).pipe(timeout(requestTimeoutMs)),
      );
      return parseCatalogPage(response, limit);
    } catch (error) {
      throw toCatalogApiError(error);
    }
  }
}

function normalizeQuery(value: string | undefined): { value: string; codePointLength: number } {
  if (value === undefined) return { value: '', codePointLength: 0 };
  if (typeof value !== 'string') throw new PublicCatalogApiError(400, 'INVALID_CATALOG_QUERY');
  const normalized = value.trim().normalize('NFKC').toLowerCase();
  return { value: normalized, codePointLength: [...normalized].length };
}

function normalizeLanguage(value: string | undefined): string {
  if (value === undefined) return '';
  if (typeof value !== 'string') throw new PublicCatalogApiError(400, 'INVALID_CATALOG_QUERY');
  return value.trim().toLowerCase();
}

function isControlCodePoint(point: number): boolean {
  return point <= 0x1f || (point >= 0x7f && point <= 0x9f);
}

function parseCatalogPage(value: unknown, limit: number): PublicCatalogPage {
  if (!isRecord(value) || !hasOnlyKeys(value, ['items', 'nextCursor'])) {
    throw invalidCatalogResponse();
  }
  if (
    !Array.isArray(value['items']) ||
    value['items'].length > limit ||
    !(
      value['nextCursor'] === null ||
      (typeof value['nextCursor'] === 'string' &&
        value['nextCursor'].length > 0 &&
        value['nextCursor'].length <= 2048)
    )
  ) {
    throw invalidCatalogResponse();
  }

  return {
    items: value['items'].map((item) => parseCatalogWork(item)),
    nextCursor: value['nextCursor'],
  };
}

function parseCatalogWork(value: unknown, includeOwner = false): PublicCatalogWork {
  const allowedKeys = ['id', 'title', 'author', 'sourceLanguage', 'readyChapterCount'];
  if (includeOwner) allowedKeys.push('isOwner');
  if (!isRecord(value) || !hasOnlyKeys(value, allowedKeys)) {
    throw invalidCatalogResponse();
  }
  const { id, title, author, sourceLanguage, readyChapterCount } = value;
  if (
    typeof id !== 'string' ||
    !uuidPattern.test(id) ||
    typeof title !== 'string' ||
    [...title].length < 1 ||
    [...title].length > 300 ||
    typeof author !== 'string' ||
    [...author].length > 200 ||
    typeof sourceLanguage !== 'string' ||
    sourceLanguage.length < 2 ||
    sourceLanguage.length > 35 ||
    !languageTagPattern.test(sourceLanguage) ||
    !Number.isSafeInteger(readyChapterCount) ||
    (readyChapterCount as number) < 1 ||
    (includeOwner && typeof value['isOwner'] !== 'boolean')
  ) {
    throw invalidCatalogResponse();
  }

  return {
    id,
    title,
    author,
    sourceLanguage,
    readyChapterCount: readyChapterCount as number,
    ...(includeOwner ? { isOwner: value['isOwner'] as boolean } : {}),
  };
}

function parseChapterPage(
  value: unknown,
  requestedWorkId: string,
  afterOrdinal: number,
  limit: number,
): PublicWorkChapterPage {
  if (
    !isRecord(value) ||
    !hasOnlyKeys(value, ['workId', 'items', 'nextAfterOrdinal']) ||
    value['workId'] !== requestedWorkId ||
    !Array.isArray(value['items']) ||
    value['items'].length > limit ||
    !(value['nextAfterOrdinal'] === null || Number.isSafeInteger(value['nextAfterOrdinal']))
  ) {
    throw invalidCatalogResponse();
  }
  const items = value['items'].map((item): PublicWorkChapter => {
    if (
      !isRecord(item) ||
      !hasOnlyKeys(item, ['ordinal', 'title']) ||
      !Number.isSafeInteger(item['ordinal']) ||
      (item['ordinal'] as number) < 1 ||
      (item['ordinal'] as number) > 2_000_000_000 ||
      (item['ordinal'] as number) <= afterOrdinal ||
      typeof item['title'] !== 'string' ||
      [...item['title']].length < 1 ||
      [...item['title']].length > 300
    ) {
      throw invalidCatalogResponse();
    }
    return { ordinal: item['ordinal'] as number, title: item['title'] };
  });
  if (items.some((item, index) => index > 0 && item.ordinal <= items[index - 1].ordinal)) {
    throw invalidCatalogResponse();
  }
  const nextAfterOrdinal = value['nextAfterOrdinal'] as number | null;
  if (
    (nextAfterOrdinal !== null &&
      (items.length !== limit ||
        items.length === 0 ||
        nextAfterOrdinal !== items.at(-1)?.ordinal)) ||
    (nextAfterOrdinal === null && items.length > 0 && items.at(-1)!.ordinal <= afterOrdinal)
  ) {
    throw invalidCatalogResponse();
  }
  return { workId: requestedWorkId, items, nextAfterOrdinal };
}

function toCatalogApiError(error: unknown): PublicCatalogApiError {
  if (error instanceof PublicCatalogApiError) return error;
  if (error instanceof HttpErrorResponse) {
    const code =
      isRecord(error.error) && typeof error.error['code'] === 'string'
        ? error.error['code']
        : error.status === 0
          ? 'NETWORK_ERROR'
          : 'CATALOG_REQUEST_FAILED';
    const retryAfter = error.headers.get('Retry-After');
    const retryAfterSeconds =
      retryAfter && /^\d{1,9}$/.test(retryAfter) && Number.isSafeInteger(Number(retryAfter))
        ? Number(retryAfter)
        : undefined;
    return new PublicCatalogApiError(error.status, code, retryAfterSeconds);
  }
  return new PublicCatalogApiError(0, 'NETWORK_ERROR');
}

function invalidCatalogResponse(): PublicCatalogApiError {
  return new PublicCatalogApiError(502, 'INVALID_CATALOG_RESPONSE');
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function hasOnlyKeys(value: Record<string, unknown>, allowed: string[]): boolean {
  const keys = Object.keys(value);
  return keys.length === allowed.length && keys.every((key) => allowed.includes(key));
}
