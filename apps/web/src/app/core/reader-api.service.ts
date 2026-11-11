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

export class ReaderApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code: string | null,
  ) {
    super(message);
    this.name = 'ReaderApiError';
  }
}

const manifestUrl = (workId: string) => `/api/v1/works/${workId}/reader-manifest`;
const contentUrl = (chapterId: string) => `/api/v1/chapters/${chapterId}/content`;
const requestTimeoutMs = 10_000;

@Injectable({ providedIn: 'root' })
export class ReaderApiService {
  private readonly http = inject(HttpClient);

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
      (typeof value['anchor'] !== 'string' || value['anchor'].length > 120)) ||
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

function toReaderApiError(error: unknown): ReaderApiError {
  if (error instanceof ReaderApiError) return error;
  if (!(error instanceof HttpErrorResponse)) {
    return new ReaderApiError('NETWORK_OR_INVALID_RESPONSE', 0, null);
  }
  const body = isRecord(error.error) ? error.error : null;
  const code = body && typeof body['code'] === 'string' ? body['code'] : null;
  return new ReaderApiError(error.message, error.status, code);
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

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
