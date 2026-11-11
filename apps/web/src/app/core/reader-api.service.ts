import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom, timeout } from 'rxjs';

export type ReaderChapterAccess = 'readable' | 'unavailable';
export type ReaderSegmentType = 'paragraph' | 'heading' | 'quote';

export interface ReaderWork {
  id: string;
  libraryId: string;
  title: string;
  author: string;
  language: string;
  visibility: 'private';
  chapterCount: number;
  createdAt: string;
}

export interface ReaderChapter {
  id: string;
  ordinal: number;
  title: string;
  accessState: ReaderChapterAccess;
}

export interface ReaderManifestPage {
  work: ReaderWork;
  sourceLanguage: string;
  chapters: ReaderChapter[];
  nextCursor: string | null;
}

export interface ReaderSourceSegment {
  anchor: string | null;
  type: ReaderSegmentType;
  sourceText: string;
}

export interface ReaderContentPage {
  chapterId: string;
  workId: string;
  sourceRevisionId: string;
  sourceLanguage: string;
  segments: ReaderSourceSegment[];
  nextSegmentCursor: string | null;
}

export interface ReaderProgress {
  workId: string;
  chapterId: string | null;
  segmentAnchor: string | null;
  scrollRatio: number;
  version: number;
  updatedAt: string | null;
}

export interface SaveReaderProgressRequest {
  expectedVersion: number;
  chapterId: string | null;
  segmentAnchor: string | null;
  scrollRatio: number;
}

export interface ReaderPreferences {
  schemaVersion: 1;
  theme: 'light' | 'sepia' | 'dark';
  fontFamily: 'serif' | 'sans';
  fontSize: number;
  lineHeight: number;
  contentWidth: number;
}

export interface ReaderBookmark {
  id: string;
  workId: string;
  chapterId: string;
  segmentAnchor: string | null;
  label: string;
  createdAt: string;
}

export interface ReaderBookmarkPage {
  items: ReaderBookmark[];
  nextCursor: string | null;
}

export interface CreateReaderBookmarkRequest {
  chapterId: string;
  segmentAnchor: string | null;
  label: string;
}

export class ReaderApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code: string | null,
    readonly latestProgress: ReaderProgress | null = null,
  ) {
    super(message);
    this.name = 'ReaderApiError';
  }
}

const manifestUrl = (workId: string) => `/api/v1/works/${workId}/reader-manifest`;
const contentUrl = (chapterId: string) => `/api/v1/chapters/${chapterId}/content`;
const progressUrl = (workId: string) => `/api/v1/works/${workId}/progress`;
const readerPreferencesUrl = '/api/v1/me/reader-preferences';
const bookmarksUrl = (workId: string) => `/api/v1/works/${workId}/bookmarks`;
const bookmarkUrl = (bookmarkId: string) => `/api/v1/bookmarks/${bookmarkId}`;
const requestTimeoutMs = 10_000;

@Injectable({ providedIn: 'root' })
export class ReaderApiService {
  private readonly http = inject(HttpClient);

  async getReaderPreferences(): Promise<ReaderPreferences> {
    try {
      const response: unknown = await firstValueFrom(
        this.http.get<unknown>(readerPreferencesUrl).pipe(timeout(requestTimeoutMs)),
      );
      return parseReaderPreferences(response);
    } catch (error) {
      throw toReaderApiError(error);
    }
  }

  async saveReaderPreferences(preferences: ReaderPreferences): Promise<ReaderPreferences> {
    const validated = parseReaderPreferences(preferences);
    try {
      const response: unknown = await firstValueFrom(
        this.http.put<unknown>(readerPreferencesUrl, validated).pipe(timeout(requestTimeoutMs)),
      );
      return parseReaderPreferences(response);
    } catch (error) {
      throw toReaderApiError(error);
    }
  }

  async getBookmarks(workId: string, cursor?: string, limit = 50): Promise<ReaderBookmarkPage> {
    if (!isUuid(workId) || !isValidPageRequest(cursor, limit, 100)) {
      throw invalidResponse('INVALID_READER_QUERY');
    }
    try {
      let params = new HttpParams().set('limit', limit);
      if (cursor) params = params.set('cursor', cursor);
      const response: unknown = await firstValueFrom(
        this.http.get<unknown>(bookmarksUrl(workId), { params }).pipe(timeout(requestTimeoutMs)),
      );
      return parseBookmarkPage(response, workId, limit);
    } catch (error) {
      throw toReaderApiError(error);
    }
  }

  async createBookmark(
    workId: string,
    request: CreateReaderBookmarkRequest,
    idempotencyKey: string,
  ): Promise<ReaderBookmark> {
    if (
      !isUuid(workId) ||
      !isUuid(request.chapterId) ||
      !(request.segmentAnchor === null || isStableAnchor(request.segmentAnchor)) ||
      typeof request.label !== 'string' ||
      !request.label.trim() ||
      [...request.label.trim()].length > 160 ||
      !/^[A-Za-z0-9._~-]{16,128}$/.test(idempotencyKey)
    ) {
      throw invalidResponse('INVALID_READER_QUERY');
    }
    try {
      const response: unknown = await firstValueFrom(
        this.http
          .post<unknown>(bookmarksUrl(workId), request, {
            headers: { 'Idempotency-Key': idempotencyKey },
          })
          .pipe(timeout(requestTimeoutMs)),
      );
      return parseBookmark(response, workId);
    } catch (error) {
      throw toReaderApiError(error);
    }
  }

  async deleteBookmark(bookmarkId: string): Promise<void> {
    if (!isUuid(bookmarkId)) throw invalidResponse('INVALID_READER_QUERY');
    try {
      await firstValueFrom(
        this.http.delete<void>(bookmarkUrl(bookmarkId)).pipe(timeout(requestTimeoutMs)),
      );
    } catch (error) {
      throw toReaderApiError(error);
    }
  }

  async getManifestPage(workId: string, cursor?: string, limit = 100): Promise<ReaderManifestPage> {
    if (!isUuid(workId) || !isValidPageRequest(cursor, limit, 100)) {
      throw invalidResponse('INVALID_READER_QUERY');
    }
    try {
      let params = new HttpParams().set('limit', limit);
      if (cursor) params = params.set('cursor', cursor);
      const response: unknown = await firstValueFrom(
        this.http.get<unknown>(manifestUrl(workId), { params }).pipe(timeout(requestTimeoutMs)),
      );
      return parseManifestPage(response, workId, limit);
    } catch (error) {
      throw toReaderApiError(error);
    }
  }

  async getContentPage(
    chapterId: string,
    revisionId?: string,
    cursor?: string,
    limit = 1000,
  ): Promise<ReaderContentPage> {
    if (
      !isUuid(chapterId) ||
      (revisionId !== undefined && !isUuid(revisionId)) ||
      !isValidPageRequest(cursor, limit, 1000)
    ) {
      throw invalidResponse('INVALID_READER_QUERY');
    }
    try {
      let params = new HttpParams().set('limit', limit);
      if (revisionId) params = params.set('revision', revisionId);
      if (cursor) params = params.set('cursor', cursor);
      const response: unknown = await firstValueFrom(
        this.http.get<unknown>(contentUrl(chapterId), { params }).pipe(timeout(requestTimeoutMs)),
      );
      return parseContentPage(response, chapterId, limit);
    } catch (error) {
      throw toReaderApiError(error);
    }
  }

  async getProgress(workId: string): Promise<ReaderProgress> {
    if (!isUuid(workId)) throw invalidResponse('INVALID_READER_QUERY');
    try {
      const response: unknown = await firstValueFrom(
        this.http.get<unknown>(progressUrl(workId)).pipe(timeout(requestTimeoutMs)),
      );
      return parseProgress(response, workId);
    } catch (error) {
      throw toReaderApiError(error, workId);
    }
  }

  async saveProgress(workId: string, request: SaveReaderProgressRequest): Promise<ReaderProgress> {
    if (
      !isUuid(workId) ||
      !Number.isSafeInteger(request.expectedVersion) ||
      request.expectedVersion < 0 ||
      (request.chapterId !== null && !isUuid(request.chapterId)) ||
      (request.segmentAnchor !== null && !isStableAnchor(request.segmentAnchor)) ||
      !Number.isFinite(request.scrollRatio) ||
      request.scrollRatio < 0 ||
      request.scrollRatio > 1 ||
      (request.chapterId === null && (request.segmentAnchor !== null || request.scrollRatio !== 0))
    ) {
      throw invalidResponse('INVALID_READER_QUERY');
    }
    try {
      const response: unknown = await firstValueFrom(
        this.http.put<unknown>(progressUrl(workId), request).pipe(timeout(requestTimeoutMs)),
      );
      return parseProgress(response, workId);
    } catch (error) {
      throw toReaderApiError(error, workId);
    }
  }
}

function parseProgress(value: unknown, expectedWorkId: string): ReaderProgress {
  if (
    !isRecord(value) ||
    value['workId'] !== expectedWorkId ||
    !(value['chapterId'] === null || isUuid(value['chapterId'])) ||
    !(value['segmentAnchor'] === null || isStableAnchor(value['segmentAnchor'])) ||
    typeof value['scrollRatio'] !== 'number' ||
    !Number.isFinite(value['scrollRatio']) ||
    value['scrollRatio'] < 0 ||
    value['scrollRatio'] > 1 ||
    !Number.isSafeInteger(value['version']) ||
    (value['version'] as number) < 0 ||
    !(value['updatedAt'] === null || isTimestamp(value['updatedAt'])) ||
    (value['chapterId'] === null && (value['segmentAnchor'] !== null || value['scrollRatio'] !== 0))
  ) {
    throw invalidResponse();
  }
  return {
    workId: value['workId'],
    chapterId: value['chapterId'] as string | null,
    segmentAnchor: value['segmentAnchor'] as string | null,
    scrollRatio: value['scrollRatio'],
    version: value['version'] as number,
    updatedAt: value['updatedAt'] as string | null,
  };
}

function parseReaderPreferences(value: unknown): ReaderPreferences {
  if (
    !isRecord(value) ||
    Object.keys(value).sort().join(',') !==
      'contentWidth,fontFamily,fontSize,lineHeight,schemaVersion,theme' ||
    value['schemaVersion'] !== 1 ||
    (value['theme'] !== 'light' && value['theme'] !== 'sepia' && value['theme'] !== 'dark') ||
    (value['fontFamily'] !== 'serif' && value['fontFamily'] !== 'sans') ||
    !Number.isSafeInteger(value['fontSize']) ||
    (value['fontSize'] as number) < 16 ||
    (value['fontSize'] as number) > 28 ||
    typeof value['lineHeight'] !== 'number' ||
    !Number.isFinite(value['lineHeight']) ||
    value['lineHeight'] < 1.2 ||
    value['lineHeight'] > 2.2 ||
    !Number.isSafeInteger(value['contentWidth']) ||
    (value['contentWidth'] as number) < 560 ||
    (value['contentWidth'] as number) > 900
  ) {
    throw invalidResponse('INVALID_READER_PREFERENCES');
  }
  return {
    schemaVersion: 1,
    theme: value['theme'],
    fontFamily: value['fontFamily'],
    fontSize: value['fontSize'] as number,
    lineHeight: value['lineHeight'],
    contentWidth: value['contentWidth'] as number,
  };
}

function parseBookmarkPage(
  value: unknown,
  expectedWorkId: string,
  limit: number,
): ReaderBookmarkPage {
  if (
    !isRecord(value) ||
    Object.keys(value).sort().join(',') !== 'items,nextCursor' ||
    !Array.isArray(value['items']) ||
    value['items'].length > limit ||
    !isCursor(value['nextCursor'])
  ) {
    throw invalidResponse();
  }
  const items = value['items'].map((item) => parseBookmark(item, expectedWorkId));
  if (new Set(items.map((item) => item.id)).size !== items.length) throw invalidResponse();
  return { items, nextCursor: value['nextCursor'] };
}

function parseBookmark(value: unknown, expectedWorkId: string): ReaderBookmark {
  if (
    !isRecord(value) ||
    Object.keys(value).sort().join(',') !== 'chapterId,createdAt,id,label,segmentAnchor,workId' ||
    !isUuid(value['id']) ||
    value['workId'] !== expectedWorkId ||
    !isUuid(value['chapterId']) ||
    !(value['segmentAnchor'] === null || isStableAnchor(value['segmentAnchor'])) ||
    typeof value['label'] !== 'string' ||
    !value['label'].trim() ||
    [...value['label']].length > 160 ||
    !isTimestamp(value['createdAt'])
  ) {
    throw invalidResponse();
  }
  return {
    id: value['id'],
    workId: value['workId'],
    chapterId: value['chapterId'],
    segmentAnchor: value['segmentAnchor'] as string | null,
    label: value['label'],
    createdAt: value['createdAt'],
  };
}

function parseManifestPage(
  value: unknown,
  expectedWorkId: string,
  limit: number,
): ReaderManifestPage {
  if (
    !isRecord(value) ||
    !isRecord(value['work']) ||
    !Array.isArray(value['chapters']) ||
    value['chapters'].length > limit ||
    !isLanguage(value['sourceLanguage']) ||
    !isCursor(value['nextCursor'])
  ) {
    throw invalidResponse();
  }
  const work = parseWork(value['work']);
  if (work.id !== expectedWorkId || value['sourceLanguage'] !== work.language) {
    throw invalidResponse();
  }
  const chapters = value['chapters'].map(parseChapter);
  if (
    new Set(chapters.map(({ id }) => id)).size !== chapters.length ||
    new Set(chapters.map(({ ordinal }) => ordinal)).size !== chapters.length
  ) {
    throw invalidResponse();
  }
  return {
    work,
    sourceLanguage: value['sourceLanguage'],
    chapters,
    nextCursor: value['nextCursor'],
  };
}

function parseWork(value: unknown): ReaderWork {
  if (
    !isRecord(value) ||
    !isUuid(value['id']) ||
    !isUuid(value['libraryId']) ||
    typeof value['title'] !== 'string' ||
    !value['title'].trim() ||
    [...value['title']].length > 300 ||
    typeof value['author'] !== 'string' ||
    [...value['author']].length > 200 ||
    !isLanguage(value['language']) ||
    value['visibility'] !== 'private' ||
    !Number.isSafeInteger(value['chapterCount']) ||
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
    visibility: 'private',
    chapterCount: value['chapterCount'] as number,
    createdAt: value['createdAt'],
  };
}

function parseChapter(value: unknown): ReaderChapter {
  if (
    !isRecord(value) ||
    !isUuid(value['id']) ||
    !Number.isSafeInteger(value['ordinal']) ||
    (value['ordinal'] as number) < 1 ||
    typeof value['title'] !== 'string' ||
    [...value['title']].length > 300 ||
    (value['accessState'] !== 'readable' && value['accessState'] !== 'unavailable')
  ) {
    throw invalidResponse();
  }
  return {
    id: value['id'],
    ordinal: value['ordinal'] as number,
    title: value['title'],
    accessState: value['accessState'],
  };
}

function parseContentPage(
  value: unknown,
  expectedChapterId: string,
  limit: number,
): ReaderContentPage {
  if (
    !isRecord(value) ||
    !isUuid(value['chapterId']) ||
    value['chapterId'] !== expectedChapterId ||
    !isUuid(value['workId']) ||
    !isUuid(value['sourceRevisionId']) ||
    !isLanguage(value['sourceLanguage']) ||
    !Array.isArray(value['segments']) ||
    value['segments'].length > limit ||
    !isCursor(value['nextSegmentCursor'])
  ) {
    throw invalidResponse();
  }
  const segments = value['segments'].map(parseSegment);
  if (
    new Set(segments.flatMap((segment) => (segment.anchor ? [segment.anchor] : []))).size !==
    segments.filter((segment) => segment.anchor).length
  ) {
    throw invalidResponse();
  }
  return {
    chapterId: value['chapterId'],
    workId: value['workId'],
    sourceRevisionId: value['sourceRevisionId'],
    sourceLanguage: value['sourceLanguage'],
    segments,
    nextSegmentCursor: value['nextSegmentCursor'],
  };
}

function parseSegment(value: unknown): ReaderSourceSegment {
  if (
    !isRecord(value) ||
    (value['anchor'] !== null &&
      (typeof value['anchor'] !== 'string' || value['anchor'].length > 160)) ||
    (value['type'] !== 'paragraph' && value['type'] !== 'heading' && value['type'] !== 'quote') ||
    typeof value['sourceText'] !== 'string' ||
    [...value['sourceText']].length > 4_000
  ) {
    throw invalidResponse();
  }
  return {
    anchor: value['anchor'] as string | null,
    type: value['type'],
    sourceText: value['sourceText'],
  };
}

function toReaderApiError(error: unknown, expectedWorkId?: string): ReaderApiError {
  if (error instanceof ReaderApiError) return error;
  if (!(error instanceof HttpErrorResponse)) {
    return new ReaderApiError('NETWORK_OR_INVALID_RESPONSE', 0, null);
  }
  const body = isRecord(error.error) ? error.error : null;
  const code = body && typeof body['code'] === 'string' ? body['code'] : null;
  let latestProgress: ReaderProgress | null = null;
  if (body && expectedWorkId && body['latestProgress'] !== undefined) {
    try {
      latestProgress = parseProgress(body['latestProgress'], expectedWorkId);
    } catch {
      latestProgress = null;
    }
  }
  return new ReaderApiError(error.message, error.status, code, latestProgress);
}

function invalidResponse(code = 'INVALID_READER_RESPONSE'): ReaderApiError {
  return new ReaderApiError(code, 502, code);
}

function isValidPageRequest(cursor: string | undefined, limit: number, maximum: number): boolean {
  return (
    Number.isInteger(limit) &&
    limit >= 1 &&
    limit <= maximum &&
    (cursor === undefined || (cursor.length > 0 && cursor.length <= 2048))
  );
}

function isCursor(value: unknown): value is string | null {
  return value === null || (typeof value === 'string' && !!value && value.length <= 2048);
}

function isLanguage(value: unknown): value is string {
  return typeof value === 'string' && [...value].length >= 2 && [...value].length <= 35;
}

function isTimestamp(value: unknown): value is string {
  return typeof value === 'string' && Number.isFinite(Date.parse(value));
}

function isUuid(value: unknown): value is string {
  return (
    typeof value === 'string' &&
    /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value)
  );
}

function isStableAnchor(value: unknown): value is string {
  return typeof value === 'string' && /^[A-Za-z0-9][A-Za-z0-9._:-]{0,159}$/.test(value);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
