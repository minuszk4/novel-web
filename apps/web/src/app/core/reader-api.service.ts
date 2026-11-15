import { HttpClient, HttpErrorResponse, HttpHeaders, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom, timeout } from 'rxjs';

export type ReaderChapterAccess = 'readable' | 'preview' | 'locked' | 'owned' | 'unavailable';
export type ReaderSegmentType = 'paragraph' | 'heading' | 'quote';

export interface ReaderWork {
  id: string;
  libraryId: string;
  title: string;
  author: string;
  language: string;
  visibility: 'private' | 'public';
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

export type ReaderAnnotationColor = 'amber' | 'mint' | 'sky' | 'rose';
export type ReaderAnnotationAnchorState = 'CURRENT' | 'STALE';

export interface ReaderAnnotation {
  id: string;
  workId: string;
  chapterId: string;
  chapterRevisionId: string | null;
  startAnchor: string;
  startOffset: number;
  endAnchor: string;
  endOffset: number;
  quoteSnapshot: string;
  note: string;
  color: ReaderAnnotationColor;
  version: number;
  createdAt: string;
  updatedAt: string;
  chapterOrdinal: number;
  chapterTitle: string;
  anchorState: ReaderAnnotationAnchorState;
}

export interface ReaderAnnotationPage {
  items: ReaderAnnotation[];
  nextCursor: string | null;
}

export interface CreateReaderAnnotationRequest {
  chapterId: string;
  sourceRevisionId: string;
  startAnchor: string;
  startOffset: number;
  endAnchor: string;
  endOffset: number;
  quoteSnapshot: string;
  note: string;
  color: ReaderAnnotationColor;
}

export interface ReaderHistoryChapter {
  id: string;
  ordinal: number;
  title: string;
}

export interface ReaderHistoryWork extends ReaderWork {
  lastReadAt: string;
}

export interface ReaderHistoryItem {
  work: ReaderHistoryWork;
  chapter: ReaderHistoryChapter;
  progress: ReaderProgress;
}

export interface ReaderHistoryPage {
  items: ReaderHistoryItem[];
  nextCursor: string | null;
}

export interface CreateReaderBookmarkRequest {
  chapterId: string;
  segmentAnchor: string | null;
  label: string;
}

export interface CultivationReadingSessionStart {
  sessionId: string;
  startedAt: string;
  expiresAt: string;
  lastSequence: number;
  totalActiveSeconds: number;
  heartbeatIntervalSeconds: number;
  idleTimeoutSeconds: number;
}

export interface CultivationReadingSessionHeartbeat {
  sequence: number;
  creditedSeconds: number;
  totalActiveSeconds: number;
  awardedXp: number;
  receivedAt: string;
  expiresAt: string;
}

export interface CultivationReadingSessionClose {
  state: 'closed' | 'expired' | 'revoked';
  startedAt: string;
  closedAt: string;
  totalActiveSeconds: number;
  lastSequence: number;
  awardedXp: number;
}

export class ReaderApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code: string | null,
    readonly latestProgress: ReaderProgress | null = null,
    readonly latestAnnotation: ReaderAnnotation | null = null,
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
const annotationsUrl = (workId: string) => `/api/v1/works/${workId}/annotations`;
const annotationUrl = (annotationId: string) => `/api/v1/annotations/${annotationId}`;
const readingHistoryUrl = '/api/v1/me/reading-history';
const readingHistoryWorkUrl = (workId: string) => `${readingHistoryUrl}/${workId}`;
const cultivationReadingSessionsUrl = '/api/v1/me/reading-sessions';
const cultivationReadingSessionUrl = (sessionId: string) =>
  `${cultivationReadingSessionsUrl}/${sessionId}`;
const csrfUrl = '/api/v1/auth/csrf';
const requestTimeoutMs = 10_000;
const csrfTokenPattern = /^[A-Za-z0-9_-]{43}$/;
const idempotencyKeyPattern = /^[A-Za-z0-9._~-]{16,128}$/;

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
        this.http
          .put<unknown>(readerPreferencesUrl, validated, { headers: await this.csrfHeaders() })
          .pipe(timeout(requestTimeoutMs)),
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
            headers: (await this.csrfHeaders()).set('Idempotency-Key', idempotencyKey),
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
        this.http
          .delete<void>(bookmarkUrl(bookmarkId), { headers: await this.csrfHeaders() })
          .pipe(timeout(requestTimeoutMs)),
      );
    } catch (error) {
      throw toReaderApiError(error);
    }
  }

  async getAnnotations(
    workId: string,
    chapterId?: string,
    cursor?: string,
    limit = 100,
  ): Promise<ReaderAnnotationPage> {
    if (
      !isUuid(workId) ||
      (chapterId !== undefined && !isUuid(chapterId)) ||
      !isValidPageRequest(cursor, limit, 100)
    ) {
      throw invalidResponse('INVALID_READER_QUERY');
    }
    try {
      let params = new HttpParams().set('limit', limit);
      if (chapterId) params = params.set('chapterId', chapterId);
      if (cursor) params = params.set('cursor', cursor);
      const response: unknown = await firstValueFrom(
        this.http.get<unknown>(annotationsUrl(workId), { params }).pipe(timeout(requestTimeoutMs)),
      );
      return parseAnnotationPage(response, workId, chapterId, limit);
    } catch (error) {
      throw toReaderApiError(error);
    }
  }

  async createAnnotation(
    workId: string,
    request: CreateReaderAnnotationRequest,
  ): Promise<ReaderAnnotation> {
    if (
      !isUuid(workId) ||
      !isUuid(request.chapterId) ||
      !isUuid(request.sourceRevisionId) ||
      !isStableAnchor(request.startAnchor) ||
      !isStableAnchor(request.endAnchor) ||
      !Number.isSafeInteger(request.startOffset) ||
      request.startOffset < 0 ||
      !Number.isSafeInteger(request.endOffset) ||
      request.endOffset < 0 ||
      typeof request.quoteSnapshot !== 'string' ||
      [...request.quoteSnapshot].length < 1 ||
      [...request.quoteSnapshot].length > 2000 ||
      typeof request.note !== 'string' ||
      [...request.note].length > 2000 ||
      !isAnnotationColor(request.color)
    ) {
      throw invalidResponse('INVALID_READER_QUERY');
    }
    try {
      const response: unknown = await firstValueFrom(
        this.http
          .post<unknown>(annotationsUrl(workId), request, { headers: await this.csrfHeaders() })
          .pipe(timeout(requestTimeoutMs)),
      );
      return parseReaderAnnotation(response, workId);
    } catch (error) {
      throw toReaderApiError(error, undefined, workId);
    }
  }

  async updateAnnotation(
    annotationId: string,
    version: number,
    note: string,
    color: ReaderAnnotationColor,
  ): Promise<ReaderAnnotation> {
    if (
      !isUuid(annotationId) ||
      !Number.isSafeInteger(version) ||
      version < 1 ||
      typeof note !== 'string' ||
      [...note].length > 2000 ||
      !isAnnotationColor(color)
    ) {
      throw invalidResponse('INVALID_READER_QUERY');
    }
    try {
      const response: unknown = await firstValueFrom(
        this.http
          .patch<unknown>(
            annotationUrl(annotationId),
            { note, color },
            {
              headers: (await this.csrfHeaders()).set('If-Match', `"annotation-${version}"`),
            },
          )
          .pipe(timeout(requestTimeoutMs)),
      );
      return parseReaderAnnotation(response);
    } catch (error) {
      throw toReaderApiError(error);
    }
  }

  async deleteAnnotation(annotationId: string): Promise<void> {
    if (!isUuid(annotationId)) throw invalidResponse('INVALID_READER_QUERY');
    try {
      await firstValueFrom(
        this.http
          .delete<void>(annotationUrl(annotationId), { headers: await this.csrfHeaders() })
          .pipe(timeout(requestTimeoutMs)),
      );
    } catch (error) {
      throw toReaderApiError(error);
    }
  }

  async clearAnnotations(workId: string): Promise<void> {
    if (!isUuid(workId)) throw invalidResponse('INVALID_READER_QUERY');
    try {
      await firstValueFrom(
        this.http
          .delete<void>(annotationsUrl(workId), { headers: await this.csrfHeaders() })
          .pipe(timeout(requestTimeoutMs)),
      );
    } catch (error) {
      throw toReaderApiError(error);
    }
  }

  async getReadingHistory(cursor?: string, limit = 25): Promise<ReaderHistoryPage> {
    if (!isValidPageRequest(cursor, limit, 100)) throw invalidResponse('INVALID_READER_QUERY');
    try {
      let params = new HttpParams().set('limit', limit);
      if (cursor) params = params.set('cursor', cursor);
      const response: unknown = await firstValueFrom(
        this.http.get<unknown>(readingHistoryUrl, { params }).pipe(timeout(requestTimeoutMs)),
      );
      return parseHistoryPage(response, limit);
    } catch (error) {
      throw toReaderApiError(error);
    }
  }

  async deleteReadingHistoryWork(workId: string): Promise<void> {
    if (!isUuid(workId)) throw invalidResponse('INVALID_READER_QUERY');
    try {
      await firstValueFrom(
        this.http
          .delete<void>(readingHistoryWorkUrl(workId), { headers: await this.csrfHeaders() })
          .pipe(timeout(requestTimeoutMs)),
      );
    } catch (error) {
      throw toReaderApiError(error);
    }
  }

  async clearReadingHistory(): Promise<void> {
    try {
      await firstValueFrom(
        this.http
          .delete<void>(readingHistoryUrl, { headers: await this.csrfHeaders() })
          .pipe(timeout(requestTimeoutMs)),
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

  async startCultivationReadingSession(
    workId: string,
    chapterId: string,
    idempotencyKey: string,
  ): Promise<CultivationReadingSessionStart> {
    if (!isUuid(workId) || !isUuid(chapterId) || !idempotencyKeyPattern.test(idempotencyKey)) {
      throw invalidResponse('INVALID_READER_QUERY');
    }
    try {
      const response: unknown = await firstValueFrom(
        this.http
          .post<unknown>(
            cultivationReadingSessionsUrl,
            { workId, chapterId, active: true },
            {
              headers: (await this.csrfHeaders()).set('Idempotency-Key', idempotencyKey),
            },
          )
          .pipe(timeout(requestTimeoutMs)),
      );
      return parseCultivationReadingSessionStart(response);
    } catch (error) {
      throw toReaderApiError(error);
    }
  }

  async heartbeatCultivationReadingSession(
    sessionId: string,
    sequence: number,
    active: boolean,
    idempotencyKey: string,
  ): Promise<CultivationReadingSessionHeartbeat> {
    if (
      !isUuid(sessionId) ||
      !Number.isSafeInteger(sequence) ||
      sequence < 1 ||
      typeof active !== 'boolean' ||
      !idempotencyKeyPattern.test(idempotencyKey)
    ) {
      throw invalidResponse('INVALID_READER_QUERY');
    }
    try {
      const response: unknown = await firstValueFrom(
        this.http
          .post<unknown>(
            `${cultivationReadingSessionUrl(sessionId)}:heartbeat`,
            { sequence, active },
            {
              headers: (await this.csrfHeaders()).set('Idempotency-Key', idempotencyKey),
            },
          )
          .pipe(timeout(requestTimeoutMs)),
      );
      return parseCultivationReadingSessionHeartbeat(response, sessionId, sequence);
    } catch (error) {
      throw toReaderApiError(error);
    }
  }

  async closeCultivationReadingSession(sessionId: string): Promise<CultivationReadingSessionClose> {
    if (!isUuid(sessionId)) throw invalidResponse('INVALID_READER_QUERY');
    try {
      const response: unknown = await firstValueFrom(
        this.http
          .post<unknown>(`${cultivationReadingSessionUrl(sessionId)}:close`, null, {
            headers: await this.csrfHeaders(),
          })
          .pipe(timeout(requestTimeoutMs)),
      );
      return parseCultivationReadingSessionClose(response, sessionId);
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
        this.http
          .put<unknown>(progressUrl(workId), request, { headers: await this.csrfHeaders() })
          .pipe(timeout(requestTimeoutMs)),
      );
      return parseProgress(response, workId);
    } catch (error) {
      throw toReaderApiError(error, workId);
    }
  }

  private async csrfHeaders(): Promise<HttpHeaders> {
    const response: unknown = await firstValueFrom(
      this.http.get<unknown>(csrfUrl).pipe(timeout(requestTimeoutMs)),
    );
    if (
      !isRecord(response) ||
      typeof response['token'] !== 'string' ||
      !csrfTokenPattern.test(response['token'])
    ) {
      throw invalidResponse('INVALID_CSRF_RESPONSE');
    }
    return new HttpHeaders({ 'X-CSRF-Token': response['token'] });
  }
}

function parseCultivationReadingSessionStart(value: unknown): CultivationReadingSessionStart {
  if (
    !isRecord(value) ||
    Object.keys(value).sort().join(',') !==
      'expiresAt,heartbeatIntervalSeconds,idleTimeoutSeconds,lastSequence,sessionId,startedAt,state,totalActiveSeconds' ||
    !isUuid(value['sessionId']) ||
    value['state'] !== 'active' ||
    !isTimestamp(value['startedAt']) ||
    !isTimestamp(value['expiresAt']) ||
    value['expiresAt'] <= value['startedAt'] ||
    value['lastSequence'] !== 0 ||
    value['totalActiveSeconds'] !== 0 ||
    !Number.isSafeInteger(value['heartbeatIntervalSeconds']) ||
    (value['heartbeatIntervalSeconds'] as number) < 1 ||
    (value['heartbeatIntervalSeconds'] as number) > 60 ||
    !Number.isSafeInteger(value['idleTimeoutSeconds']) ||
    (value['idleTimeoutSeconds'] as number) <= (value['heartbeatIntervalSeconds'] as number) ||
    (value['idleTimeoutSeconds'] as number) > 300
  ) {
    throw invalidResponse('INVALID_READING_SESSION_RESPONSE');
  }
  return {
    sessionId: value['sessionId'],
    startedAt: value['startedAt'],
    expiresAt: value['expiresAt'],
    lastSequence: 0,
    totalActiveSeconds: 0,
    heartbeatIntervalSeconds: value['heartbeatIntervalSeconds'] as number,
    idleTimeoutSeconds: value['idleTimeoutSeconds'] as number,
  };
}

function parseCultivationReadingSessionHeartbeat(
  value: unknown,
  sessionId: string,
  expectedSequence: number,
): CultivationReadingSessionHeartbeat {
  if (
    !isRecord(value) ||
    Object.keys(value).sort().join(',') !==
      'awardedXp,creditedSeconds,expiresAt,receivedAt,sequence,sessionId,state,totalActiveSeconds' ||
    value['sessionId'] !== sessionId ||
    value['state'] !== 'active' ||
    value['sequence'] !== expectedSequence ||
    !Number.isSafeInteger(value['creditedSeconds']) ||
    (value['creditedSeconds'] as number) < 0 ||
    (value['creditedSeconds'] as number) > 60 ||
    !Number.isSafeInteger(value['totalActiveSeconds']) ||
    (value['totalActiveSeconds'] as number) < 0 ||
    !Number.isSafeInteger(value['awardedXp']) ||
    (value['awardedXp'] as number) < 0 ||
    (value['awardedXp'] as number) > 120 ||
    !isTimestamp(value['receivedAt']) ||
    !isTimestamp(value['expiresAt']) ||
    value['expiresAt'] <= value['receivedAt']
  ) {
    throw invalidResponse('INVALID_READING_SESSION_RESPONSE');
  }
  return {
    sequence: expectedSequence,
    creditedSeconds: value['creditedSeconds'] as number,
    totalActiveSeconds: value['totalActiveSeconds'] as number,
    awardedXp: value['awardedXp'] as number,
    receivedAt: value['receivedAt'],
    expiresAt: value['expiresAt'],
  };
}

function parseCultivationReadingSessionClose(
  value: unknown,
  sessionId: string,
): CultivationReadingSessionClose {
  if (
    !isRecord(value) ||
    Object.keys(value).sort().join(',') !==
      'awardedXp,closedAt,lastSequence,sessionId,startedAt,state,totalActiveSeconds' ||
    value['sessionId'] !== sessionId ||
    (value['state'] !== 'closed' && value['state'] !== 'expired' && value['state'] !== 'revoked') ||
    !isTimestamp(value['startedAt']) ||
    !isTimestamp(value['closedAt']) ||
    value['closedAt'] < value['startedAt'] ||
    !Number.isSafeInteger(value['totalActiveSeconds']) ||
    (value['totalActiveSeconds'] as number) < 0 ||
    !Number.isSafeInteger(value['lastSequence']) ||
    (value['lastSequence'] as number) < 0 ||
    !Number.isSafeInteger(value['awardedXp']) ||
    (value['awardedXp'] as number) < 0 ||
    (value['awardedXp'] as number) > 120
  ) {
    throw invalidResponse('INVALID_READING_SESSION_RESPONSE');
  }
  return {
    state: value['state'],
    startedAt: value['startedAt'],
    closedAt: value['closedAt'],
    totalActiveSeconds: value['totalActiveSeconds'] as number,
    lastSequence: value['lastSequence'] as number,
    awardedXp: value['awardedXp'] as number,
  };
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

function parseHistoryPage(value: unknown, limit: number): ReaderHistoryPage {
  if (
    !isRecord(value) ||
    Object.keys(value).sort().join(',') !== 'items,nextCursor' ||
    !Array.isArray(value['items']) ||
    value['items'].length > limit ||
    !isCursor(value['nextCursor'])
  ) {
    throw invalidResponse();
  }
  const items = value['items'].map(parseHistoryItem);
  if (new Set(items.map(({ work }) => work.id)).size !== items.length) throw invalidResponse();
  return { items, nextCursor: value['nextCursor'] };
}

function parseHistoryItem(value: unknown): ReaderHistoryItem {
  if (
    !isRecord(value) ||
    Object.keys(value).sort().join(',') !== 'chapter,progress,work' ||
    !isRecord(value['work']) ||
    !isRecord(value['chapter']) ||
    !isRecord(value['progress'])
  ) {
    throw invalidResponse();
  }
  const work = value['work'];
  const chapter = value['chapter'];
  if (
    Object.keys(work).sort().join(',') !==
      'author,chapterCount,createdAt,id,language,lastReadAt,libraryId,title,visibility' ||
    !isUuid(work['id']) ||
    !isUuid(work['libraryId']) ||
    typeof work['title'] !== 'string' ||
    !work['title'].trim() ||
    [...work['title']].length > 300 ||
    typeof work['author'] !== 'string' ||
    [...work['author']].length > 200 ||
    !isLanguage(work['language']) ||
    work['visibility'] !== 'private' ||
    !Number.isSafeInteger(work['chapterCount']) ||
    (work['chapterCount'] as number) < 0 ||
    !isTimestamp(work['createdAt']) ||
    !isTimestamp(work['lastReadAt']) ||
    Object.keys(chapter).sort().join(',') !== 'id,ordinal,title' ||
    !isUuid(chapter['id']) ||
    !Number.isSafeInteger(chapter['ordinal']) ||
    (chapter['ordinal'] as number) < 1 ||
    typeof chapter['title'] !== 'string' ||
    [...chapter['title']].length > 300
  ) {
    throw invalidResponse();
  }
  const progress = parseProgress(value['progress'], work['id']);
  if (progress.chapterId !== chapter['id'] || progress.updatedAt !== work['lastReadAt']) {
    throw invalidResponse();
  }
  return {
    work: {
      id: work['id'],
      libraryId: work['libraryId'],
      title: work['title'],
      author: work['author'],
      language: work['language'],
      visibility: 'private',
      chapterCount: work['chapterCount'] as number,
      createdAt: work['createdAt'],
      lastReadAt: work['lastReadAt'],
    },
    chapter: {
      id: chapter['id'],
      ordinal: chapter['ordinal'] as number,
      title: chapter['title'],
    },
    progress,
  };
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

function parseAnnotationPage(
  value: unknown,
  expectedWorkId: string,
  expectedChapterId: string | undefined,
  limit: number,
): ReaderAnnotationPage {
  if (
    !isRecord(value) ||
    Object.keys(value).sort().join(',') !== 'items,nextCursor' ||
    !Array.isArray(value['items']) ||
    value['items'].length > limit ||
    !isCursor(value['nextCursor'])
  ) {
    throw invalidResponse();
  }
  const items = value['items'].map((item) => parseReaderAnnotation(item, expectedWorkId));
  if (
    new Set(items.map((item) => item.id)).size !== items.length ||
    (expectedChapterId !== undefined && items.some((item) => item.chapterId !== expectedChapterId))
  ) {
    throw invalidResponse();
  }
  return { items, nextCursor: value['nextCursor'] };
}

function parseReaderAnnotation(value: unknown, expectedWorkId?: string): ReaderAnnotation {
  if (
    !isRecord(value) ||
    Object.keys(value).sort().join(',') !==
      'anchorState,chapterId,chapterOrdinal,chapterRevisionId,chapterTitle,color,createdAt,endAnchor,endOffset,id,note,quoteSnapshot,startAnchor,startOffset,updatedAt,version,workId'
  ) {
    throw invalidResponse();
  }
  if (
    !isUuid(value['id']) ||
    !isUuid(value['workId']) ||
    (expectedWorkId !== undefined && value['workId'] !== expectedWorkId) ||
    !isUuid(value['chapterId']) ||
    !(value['chapterRevisionId'] === null || isUuid(value['chapterRevisionId'])) ||
    !isStableAnchor(value['startAnchor']) ||
    !Number.isSafeInteger(value['startOffset']) ||
    (value['startOffset'] as number) < 0 ||
    !isStableAnchor(value['endAnchor']) ||
    !Number.isSafeInteger(value['endOffset']) ||
    (value['endOffset'] as number) < 0 ||
    typeof value['quoteSnapshot'] !== 'string' ||
    !value['quoteSnapshot'].trim() ||
    [...value['quoteSnapshot']].length > 2000 ||
    typeof value['note'] !== 'string' ||
    [...value['note']].length > 2000 ||
    !isAnnotationColor(value['color']) ||
    !Number.isSafeInteger(value['version']) ||
    (value['version'] as number) < 1 ||
    !isTimestamp(value['createdAt']) ||
    !isTimestamp(value['updatedAt']) ||
    !Number.isSafeInteger(value['chapterOrdinal']) ||
    (value['chapterOrdinal'] as number) < 1 ||
    typeof value['chapterTitle'] !== 'string' ||
    [...value['chapterTitle']].length > 300 ||
    (value['anchorState'] !== 'CURRENT' && value['anchorState'] !== 'STALE')
  ) {
    throw invalidResponse();
  }
  return {
    id: value['id'],
    workId: value['workId'],
    chapterId: value['chapterId'],
    chapterRevisionId: value['chapterRevisionId'] as string | null,
    startAnchor: value['startAnchor'],
    startOffset: value['startOffset'] as number,
    endAnchor: value['endAnchor'],
    endOffset: value['endOffset'] as number,
    quoteSnapshot: value['quoteSnapshot'],
    note: value['note'],
    color: value['color'],
    version: value['version'] as number,
    createdAt: value['createdAt'],
    updatedAt: value['updatedAt'],
    chapterOrdinal: value['chapterOrdinal'] as number,
    chapterTitle: value['chapterTitle'],
    anchorState: value['anchorState'],
  };
}

function isAnnotationColor(value: unknown): value is ReaderAnnotationColor {
  return value === 'amber' || value === 'mint' || value === 'sky' || value === 'rose';
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
    (value['visibility'] !== 'private' && value['visibility'] !== 'public') ||
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
    visibility: value['visibility'],
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
    !['readable', 'preview', 'locked', 'owned', 'unavailable'].includes(
      String(value['accessState']),
    )
  ) {
    throw invalidResponse();
  }
  return {
    id: value['id'],
    ordinal: value['ordinal'] as number,
    title: value['title'],
    accessState: value['accessState'] as ReaderChapterAccess,
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

function toReaderApiError(
  error: unknown,
  expectedWorkId?: string,
  expectedAnnotationWorkId?: string,
): ReaderApiError {
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
  let latestAnnotation: ReaderAnnotation | null = null;
  if (body && body['latestAnnotation'] !== undefined) {
    try {
      latestAnnotation = parseReaderAnnotation(body['latestAnnotation'], expectedAnnotationWorkId);
    } catch {
      latestAnnotation = null;
    }
  }
  return new ReaderApiError(error.message, error.status, code, latestProgress, latestAnnotation);
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
