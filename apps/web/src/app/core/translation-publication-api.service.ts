import {
  HttpClient,
  HttpErrorResponse,
  HttpHeaders,
  HttpParams,
  HttpResponse,
} from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom, timeout } from 'rxjs';

export interface TranslationAcceptedItem {
  submissionId: string;
  chapterId: string;
  workTitle: string;
  chapterTitle: string;
  targetLanguage: string;
  snapshotDigest: string;
  segmentCount: number;
  submittedAt: string;
  rightsCurrent: boolean;
  currentVersionId: string | null;
  currentVersionNo: number | null;
}

export interface TranslationAcceptedPage {
  items: TranslationAcceptedItem[];
  nextCursor: string | null;
  hasMore: boolean;
}

export interface TranslationReleaseReceipt {
  versionId: string;
  versionNo: number;
  snapshotDigest: string;
  releasedAt: string;
  replayed: boolean;
}

export interface TranslationVersionHistoryItem {
  eventId: string;
  versionId: string;
  chapterId: string;
  chapterTitle: string;
  targetLanguage: string;
  versionNo: number;
  actionCode: 'PUBLISH' | 'ROLLBACK';
  rollbackOfVersionId: string | null;
  releasedAt: string;
  current: boolean;
  currentVersionId: string | null;
  currentVersionNo: number | null;
  currentVersionEtag: string | null;
  rollbackAllowed: boolean;
}

export interface TranslationVersionHistoryPage {
  items: TranslationVersionHistoryItem[];
  nextCursor: string | null;
  hasMore: boolean;
}

export class TranslationPublicationApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string | null,
    readonly currentEtag: string | null = null,
  ) {
    super(code ?? 'TRANSLATION_PUBLICATION_UNAVAILABLE');
    this.name = 'TranslationPublicationApiError';
  }
}

const writerBase = '/api/v1/authoring';
const csrfUrl = '/api/v1/auth/csrf';
const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const digestPattern = /^[a-f0-9]{64}$/;
const cursorPattern = /^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/;
const etagPattern = /^"translation-review-[a-f0-9]{64}"$/;
const versionEtagPattern = /^"translation-version-[0-9a-fA-F-]{36}-[1-9][0-9]*"$/;
const keyPattern = /^[A-Za-z0-9._~-]{16,128}$/;
const csrfPattern = /^[A-Za-z0-9_-]{43}$/;
const readTimeoutMs = 6_000;
const writeTimeoutMs = 12_000;
const maxCursorLength = 2_048;

@Injectable({ providedIn: 'root' })
export class TranslationPublicationApiService {
  private readonly http = inject(HttpClient);

  async getAccepted(
    workId: string,
    cursor: string | null = null,
    limit = 20,
  ): Promise<TranslationAcceptedPage> {
    if (
      !isUuid(workId) ||
      !Number.isInteger(limit) ||
      limit < 1 ||
      limit > 50 ||
      (cursor !== null && (cursor.length > maxCursorLength || !cursorPattern.test(cursor)))
    ) {
      throw invalidRequest('INVALID_TRANSLATION_PUBLICATION_QUERY');
    }
    let params = new HttpParams().set('limit', String(limit));
    if (cursor !== null) params = params.set('cursor', cursor);
    try {
      const response = await firstValueFrom(
        this.http
          .get<unknown>(
            `${writerBase}/manuscripts/${encodeURIComponent(workId)}/translations/accepted`,
            { params, observe: 'response' },
          )
          .pipe(timeout(readTimeoutMs)),
      );
      requireNoStore(response);
      return parseAcceptedPage(response.body, limit);
    } catch (error) {
      throw toPublicationError(error);
    }
  }

  async release(
    submissionId: string,
    etag: string,
    idempotencyKey: string,
  ): Promise<TranslationReleaseReceipt> {
    if (!isUuid(submissionId) || !etagPattern.test(etag) || !keyPattern.test(idempotencyKey)) {
      throw invalidRequest('INVALID_TRANSLATION_RELEASE_REQUEST');
    }
    try {
      const headers = (await this.csrfHeaders())
        .set('If-Match', etag)
        .set('Idempotency-Key', idempotencyKey);
      const response = await firstValueFrom(
        this.http
          .post<unknown>(
            `${writerBase}/translations/review-submissions/${encodeURIComponent(submissionId)}/release`,
            null,
            { headers, observe: 'response' },
          )
          .pipe(timeout(writeTimeoutMs)),
      );
      requireNoStore(response);
      const receipt = parseReleaseReceipt(response.body);
      if (
        response.status !== (receipt.replayed ? 200 : 201) ||
        response.headers.get('ETag') !== `"translation-review-${receipt.snapshotDigest}"`
      ) {
        throw invalidRequest('INVALID_TRANSLATION_RELEASE_RESPONSE');
      }
      return receipt;
    } catch (error) {
      throw toPublicationError(error);
    }
  }

  async getHistory(
    workId: string,
    cursor: string | null = null,
    limit = 20,
  ): Promise<TranslationVersionHistoryPage> {
    if (
      !isUuid(workId) ||
      !Number.isInteger(limit) ||
      limit < 1 ||
      limit > 50 ||
      (cursor !== null && (cursor.length > maxCursorLength || !cursorPattern.test(cursor)))
    ) {
      throw invalidRequest('INVALID_TRANSLATION_HISTORY_QUERY');
    }
    let params = new HttpParams().set('limit', String(limit));
    if (cursor !== null) params = params.set('cursor', cursor);
    try {
      const response = await firstValueFrom(
        this.http
          .get<unknown>(
            `${writerBase}/manuscripts/${encodeURIComponent(workId)}/translations/history`,
            { params, observe: 'response' },
          )
          .pipe(timeout(readTimeoutMs)),
      );
      requireNoStore(response);
      return parseHistoryPage(response.body, limit);
    } catch (error) {
      throw toPublicationError(error);
    }
  }

  async rollback(
    versionId: string,
    currentVersionEtag: string,
    idempotencyKey: string,
  ): Promise<TranslationReleaseReceipt> {
    if (
      !isUuid(versionId) ||
      !versionEtagPattern.test(currentVersionEtag) ||
      !keyPattern.test(idempotencyKey)
    ) {
      throw invalidRequest('INVALID_TRANSLATION_ROLLBACK_REQUEST');
    }
    try {
      const headers = (await this.csrfHeaders())
        .set('If-Match', currentVersionEtag)
        .set('Idempotency-Key', idempotencyKey);
      const response = await firstValueFrom(
        this.http
          .post<unknown>(
            `${writerBase}/translations/versions/${encodeURIComponent(versionId)}/rollback`,
            null,
            { headers, observe: 'response' },
          )
          .pipe(timeout(writeTimeoutMs)),
      );
      requireNoStore(response);
      const receipt = parseReleaseReceipt(response.body);
      if (
        response.status !== (receipt.replayed ? 200 : 201) ||
        response.headers.get('ETag') !== `"translation-review-${receipt.snapshotDigest}"`
      ) {
        throw invalidRequest('INVALID_TRANSLATION_ROLLBACK_RESPONSE');
      }
      return receipt;
    } catch (error) {
      throw toPublicationError(error);
    }
  }

  private async csrfHeaders(): Promise<HttpHeaders> {
    const response: unknown = await firstValueFrom(
      this.http.get<unknown>(csrfUrl).pipe(timeout(readTimeoutMs)),
    );
    if (
      !isRecord(response) ||
      typeof response['token'] !== 'string' ||
      !csrfPattern.test(response['token'])
    ) {
      throw invalidRequest('INVALID_CSRF_RESPONSE');
    }
    return new HttpHeaders({ 'X-CSRF-Token': response['token'] });
  }
}

function parseAcceptedPage(value: unknown, limit: number): TranslationAcceptedPage {
  if (
    !hasExactKeys(value, 'hasMore,items,nextCursor') ||
    !Array.isArray(value['items']) ||
    value['items'].length > limit ||
    typeof value['hasMore'] !== 'boolean' ||
    !(value['nextCursor'] === null || isCursor(value['nextCursor'])) ||
    value['hasMore'] !== (value['nextCursor'] !== null)
  ) {
    throw invalidRequest('INVALID_TRANSLATION_PUBLICATION_PAGE');
  }
  const items = value['items'].map(parseAcceptedItem);
  if (new Set(items.map(({ submissionId }) => submissionId)).size !== items.length) {
    throw invalidRequest('INVALID_TRANSLATION_PUBLICATION_PAGE');
  }
  return { items, nextCursor: value['nextCursor'], hasMore: value['hasMore'] };
}

function parseAcceptedItem(value: unknown): TranslationAcceptedItem {
  if (
    !hasExactKeys(
      value,
      'chapterId,chapterTitle,currentVersionId,currentVersionNo,rightsCurrent,segmentCount,snapshotDigest,submissionId,submittedAt,targetLanguage,workTitle',
    ) ||
    !isUuid(value['submissionId']) ||
    !isUuid(value['chapterId']) ||
    !isText(value['workTitle'], 300) ||
    !isText(value['chapterTitle'], 300) ||
    !isText(value['targetLanguage'], 35) ||
    !isDigest(value['snapshotDigest']) ||
    !isPositiveInteger(value['segmentCount']) ||
    !isDateTime(value['submittedAt']) ||
    typeof value['rightsCurrent'] !== 'boolean' ||
    !(value['currentVersionId'] === null || isUuid(value['currentVersionId'])) ||
    !(value['currentVersionNo'] === null || isPositiveInteger(value['currentVersionNo'])) ||
    (value['currentVersionId'] === null) !== (value['currentVersionNo'] === null)
  ) {
    throw invalidRequest('INVALID_TRANSLATION_PUBLICATION_ITEM');
  }
  return {
    submissionId: value['submissionId'],
    chapterId: value['chapterId'],
    workTitle: value['workTitle'],
    chapterTitle: value['chapterTitle'],
    targetLanguage: value['targetLanguage'],
    snapshotDigest: value['snapshotDigest'],
    segmentCount: value['segmentCount'],
    submittedAt: value['submittedAt'],
    rightsCurrent: value['rightsCurrent'],
    currentVersionId: value['currentVersionId'],
    currentVersionNo: value['currentVersionNo'],
  };
}

function parseHistoryPage(value: unknown, limit: number): TranslationVersionHistoryPage {
  if (
    !hasExactKeys(value, 'hasMore,items,nextCursor') ||
    !Array.isArray(value['items']) ||
    value['items'].length > limit ||
    typeof value['hasMore'] !== 'boolean' ||
    !(value['nextCursor'] === null || isCursor(value['nextCursor'])) ||
    value['hasMore'] !== (value['nextCursor'] !== null)
  ) {
    throw invalidRequest('INVALID_TRANSLATION_HISTORY_PAGE');
  }
  const items = value['items'].map(parseHistoryItem);
  if (
    new Set(items.map(({ eventId }) => eventId)).size !== items.length ||
    new Set(items.map(({ versionId }) => versionId)).size !== items.length
  ) {
    throw invalidRequest('INVALID_TRANSLATION_HISTORY_PAGE');
  }
  return { items, nextCursor: value['nextCursor'], hasMore: value['hasMore'] };
}

function parseHistoryItem(value: unknown): TranslationVersionHistoryItem {
  if (
    !hasExactKeys(
      value,
      'actionCode,chapterId,chapterTitle,current,currentVersionEtag,currentVersionId,currentVersionNo,eventId,releasedAt,rollbackAllowed,rollbackOfVersionId,targetLanguage,versionId,versionNo',
    )
  ) {
    throw invalidRequest('INVALID_TRANSLATION_HISTORY_ITEM');
  }
  const entry = value as Record<string, unknown>;
  if (
    !isUuid(entry['eventId']) ||
    !isUuid(entry['versionId']) ||
    !isUuid(entry['chapterId']) ||
    !isText(entry['chapterTitle'], 300) ||
    !isText(entry['targetLanguage'], 35) ||
    !isPositiveInteger(entry['versionNo']) ||
    (entry['actionCode'] !== 'PUBLISH' && entry['actionCode'] !== 'ROLLBACK') ||
    !(entry['rollbackOfVersionId'] === null || isUuid(entry['rollbackOfVersionId'])) ||
    !isDateTime(entry['releasedAt']) ||
    typeof entry['current'] !== 'boolean' ||
    !(entry['currentVersionId'] === null || isUuid(entry['currentVersionId'])) ||
    !(entry['currentVersionNo'] === null || isPositiveInteger(entry['currentVersionNo'])) ||
    !(
      entry['currentVersionEtag'] === null ||
      versionEtagPattern.test(String(entry['currentVersionEtag']))
    ) ||
    typeof entry['rollbackAllowed'] !== 'boolean' ||
    (entry['currentVersionId'] === null) !== (entry['currentVersionNo'] === null) ||
    (entry['currentVersionId'] === null) !== (entry['currentVersionEtag'] === null) ||
    entry['current'] !== (entry['versionId'] === entry['currentVersionId']) ||
    (entry['actionCode'] === 'ROLLBACK') !== (entry['rollbackOfVersionId'] !== null)
  ) {
    throw invalidRequest('INVALID_TRANSLATION_HISTORY_ITEM');
  }
  return {
    eventId: entry['eventId'],
    versionId: entry['versionId'],
    chapterId: entry['chapterId'],
    chapterTitle: entry['chapterTitle'],
    targetLanguage: entry['targetLanguage'],
    versionNo: entry['versionNo'] as number,
    actionCode: entry['actionCode'],
    rollbackOfVersionId: entry['rollbackOfVersionId'] as string | null,
    releasedAt: entry['releasedAt'],
    current: entry['current'],
    currentVersionId: entry['currentVersionId'] as string | null,
    currentVersionNo: entry['currentVersionNo'] as number | null,
    currentVersionEtag: entry['currentVersionEtag'] as string | null,
    rollbackAllowed: entry['rollbackAllowed'],
  };
}

function parseReleaseReceipt(value: unknown): TranslationReleaseReceipt {
  if (
    !hasExactKeys(value, 'replayed,releasedAt,snapshotDigest,versionId,versionNo') ||
    !isUuid(value['versionId']) ||
    !isPositiveInteger(value['versionNo']) ||
    !isDigest(value['snapshotDigest']) ||
    !isDateTime(value['releasedAt']) ||
    typeof value['replayed'] !== 'boolean'
  ) {
    throw invalidRequest('INVALID_TRANSLATION_RELEASE_RESPONSE');
  }
  return {
    versionId: value['versionId'],
    versionNo: value['versionNo'],
    snapshotDigest: value['snapshotDigest'],
    releasedAt: value['releasedAt'],
    replayed: value['replayed'],
  };
}

function requireNoStore(response: HttpResponse<unknown>): void {
  if (
    response.status < 200 ||
    response.status >= 300 ||
    response.headers.get('Cache-Control') !== 'no-store' ||
    response.headers.get('Pragma') !== 'no-cache'
  ) {
    throw invalidRequest('INVALID_TRANSLATION_PUBLICATION_RESPONSE');
  }
}

function toPublicationError(error: unknown): TranslationPublicationApiError {
  if (error instanceof TranslationPublicationApiError) return error;
  if (!(error instanceof HttpErrorResponse)) {
    return new TranslationPublicationApiError(0, 'TRANSLATION_PUBLICATION_UNAVAILABLE');
  }
  const body = isRecord(error.error) ? error.error : null;
  const code = body && typeof body['code'] === 'string' ? body['code'] : null;
  const etag = error.headers.get('ETag');
  return new TranslationPublicationApiError(
    error.status,
    code,
    etag && (etagPattern.test(etag) || versionEtagPattern.test(etag)) ? etag : null,
  );
}

function invalidRequest(code: string): TranslationPublicationApiError {
  return new TranslationPublicationApiError(502, code);
}

function hasExactKeys(value: unknown, expected: string): value is Record<string, unknown> {
  if (!isRecord(value)) return false;
  return Object.keys(value).sort().join(',') === expected.split(',').sort().join(',');
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isUuid(value: unknown): value is string {
  return typeof value === 'string' && uuidPattern.test(value);
}

function isCursor(value: unknown): value is string {
  return typeof value === 'string' && value.length <= maxCursorLength && cursorPattern.test(value);
}

function isDigest(value: unknown): value is string {
  return typeof value === 'string' && digestPattern.test(value);
}

function isPositiveInteger(value: unknown): value is number {
  return Number.isSafeInteger(value) && (value as number) > 0;
}

function isText(value: unknown, maximum: number): value is string {
  return typeof value === 'string' && value.trim().length > 0 && value.length <= maximum;
}

function isDateTime(value: unknown): value is string {
  return typeof value === 'string' && Number.isFinite(Date.parse(value));
}
