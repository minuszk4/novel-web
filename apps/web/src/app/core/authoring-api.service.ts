import { HttpClient, HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom, timeout } from 'rxjs';

export interface CreatePrivateManuscriptInput {
  title: string;
  author: string;
  language: string;
}

export interface CreatedPrivateManuscript {
  id: string;
  title: string;
  author: string;
  language: string;
  publicationState: 'draft';
  visibility: 'private';
  createdAt: string;
}

export type AuthoringChapterStatus = 'draft' | 'ready' | 'unavailable';

export interface PrivateChapterOutlineItem {
  id: string;
  ordinal: number;
  title: string;
  language: string;
  status: AuthoringChapterStatus;
  contentAvailable: boolean;
  createdAt: string;
}

export interface PrivateChapterOutlinePage {
  items: PrivateChapterOutlineItem[];
  nextCursor: string | null;
}

export interface CreatedPrivateChapter {
  id: string;
  workId: string;
  ordinal: number;
  title: string;
  language: string;
  status: 'draft';
  createdAt: string;
}

export type PrivateDraftSourceFormat = 'plain_text' | 'markdown' | 'xhtml' | 'epub_xhtml';

export interface PrivateDraftSegment {
  ordinal: number;
  type: 'paragraph' | 'heading' | 'quote' | 'image_ref';
  sourceText: string;
  stableAnchor: string;
}

export interface PrivateAuthoredChapterDraft {
  id: string;
  workId: string;
  title: string;
  status: 'draft';
  revisionNo: number;
  sourceFormat: PrivateDraftSourceFormat | null;
  normalizedContent: string;
  segments: PrivateDraftSegment[];
  updatedAt: string;
  etag: string;
}

export interface SavePrivateDraftRevisionInput {
  sourceFormat: PrivateDraftSourceFormat;
  normalizedContent: string;
  segments: PrivateDraftSegment[];
}

export interface CreatedPrivateDraftRevision {
  id: string;
  chapterId: string;
  revisionNo: number;
  sourceFormat: PrivateDraftSourceFormat;
  createdAt: string;
  etag: string;
}

const manuscriptsUrl = '/api/v1/authoring/manuscripts';
const csrfUrl = '/api/v1/auth/csrf';
const requestTimeoutMs = 5_000;
const defaultOutlineLimit = 25;
const maxOutlineLimit = 100;
const csrfTokenPattern = /^[A-Za-z0-9_-]{43}$/;
const idempotencyKeyPattern = /^[A-Za-z0-9._~-]{16,128}$/;
const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const cursorPattern = /^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/;
const draftEtagPattern = /^"draft-(0|[1-9][0-9]*)"$/;
const draftAnchorPattern = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,159}$/;
const draftFormats = new Set<PrivateDraftSourceFormat>([
  'plain_text',
  'markdown',
  'xhtml',
  'epub_xhtml',
]);
const draftSegmentTypes = new Set<PrivateDraftSegment['type']>([
  'paragraph',
  'heading',
  'quote',
  'image_ref',
]);
const maxDraftBytes = 10_485_760;
const maxDraftSegmentBytes = 1_048_576;
const responseFields = new Set([
  'id',
  'title',
  'author',
  'language',
  'publicationState',
  'visibility',
  'createdAt',
]);

@Injectable({ providedIn: 'root' })
export class AuthoringApiService {
  private readonly http = inject(HttpClient);

  async createPrivateManuscript(
    input: CreatePrivateManuscriptInput,
    idempotencyKey: string,
  ): Promise<CreatedPrivateManuscript> {
    const title = input.title.trim();
    const author = input.author.trim();
    const language = input.language.trim();
    if (
      !isBoundedString(title, 1, 300) ||
      !isBoundedString(author, 0, 200) ||
      !isBoundedString(language, 2, 35) ||
      !idempotencyKeyPattern.test(idempotencyKey)
    ) {
      throw new AuthoringApiError(400, 'INVALID_MANUSCRIPT');
    }

    try {
      const headers = (await this.csrfHeaders())
        .set('Content-Type', 'application/json')
        .set('Idempotency-Key', idempotencyKey);
      const response = await firstValueFrom(
        this.http
          .post<unknown>(
            manuscriptsUrl,
            { title, author, language },
            { headers, observe: 'response' },
          )
          .pipe(timeout(requestTimeoutMs)),
      );
      if (response.status !== 201) throw invalidResponse();
      return parseCreatedManuscript(response.body);
    } catch (error) {
      throw toAuthoringApiError(error);
    }
  }

  async listPrivateChapters(
    workId: string,
    cursor?: string,
    limit = defaultOutlineLimit,
  ): Promise<PrivateChapterOutlinePage> {
    if (
      !isUuid(workId) ||
      !Number.isInteger(limit) ||
      limit < 1 ||
      limit > maxOutlineLimit ||
      (cursor !== undefined && !isCursor(cursor))
    ) {
      throw new AuthoringApiError(400, 'INVALID_AUTHORING_QUERY');
    }

    try {
      const params = new URLSearchParams({ limit: String(limit) });
      if (cursor) params.set('cursor', cursor);
      const response = await firstValueFrom(
        this.http
          .get<unknown>(`${manuscriptsUrl}/${workId}/chapters?${params.toString()}`, {
            observe: 'response',
          })
          .pipe(timeout(requestTimeoutMs)),
      );
      if (response.status !== 200) throw invalidOutlineResponse();
      return parseOutlinePage(response.body, limit, cursor ?? null);
    } catch (error) {
      throw toAuthoringApiError(error);
    }
  }

  async createPrivateChapter(
    workId: string,
    requestedTitle: string,
    idempotencyKey: string,
  ): Promise<CreatedPrivateChapter> {
    const title = requestedTitle.trim();
    if (
      !isUuid(workId) ||
      !isBoundedString(title, 1, 300) ||
      !idempotencyKeyPattern.test(idempotencyKey)
    ) {
      throw new AuthoringApiError(400, 'INVALID_CHAPTER');
    }

    try {
      const headers = (await this.csrfHeaders())
        .set('Content-Type', 'application/json')
        .set('Idempotency-Key', idempotencyKey);
      const response = await firstValueFrom(
        this.http
          .post<unknown>(
            `${manuscriptsUrl}/${workId}/chapters`,
            { title },
            { headers, observe: 'response' },
          )
          .pipe(timeout(requestTimeoutMs)),
      );
      if (response.status !== 201) throw invalidChapterResponse();
      return parseCreatedChapter(response.body, workId, title);
    } catch (error) {
      throw toAuthoringApiError(error);
    }
  }

  async getPrivateChapterDraft(
    workId: string,
    chapterId: string,
  ): Promise<PrivateAuthoredChapterDraft> {
    if (!isUuid(workId) || !isUuid(chapterId)) {
      throw new AuthoringApiError(400, 'INVALID_DRAFT_QUERY');
    }
    try {
      const response = await firstValueFrom(
        this.http
          .get<unknown>(`${manuscriptsUrl}/${workId}/chapters/${chapterId}/draft`, {
            observe: 'response',
          })
          .pipe(timeout(requestTimeoutMs)),
      );
      if (response.status !== 200 || response.headers.get('Cache-Control') !== 'no-store') {
        throw invalidDraftResponse();
      }
      return parsePrivateDraft(response.body, workId, chapterId, response.headers.get('ETag'));
    } catch (error) {
      throw toAuthoringApiError(error);
    }
  }

  async savePrivateChapterDraftRevision(
    workId: string,
    chapterId: string,
    ifMatch: string,
    input: SavePrivateDraftRevisionInput,
    idempotencyKey: string,
  ): Promise<CreatedPrivateDraftRevision> {
    if (
      !isUuid(workId) ||
      !isUuid(chapterId) ||
      !isDraftEtag(ifMatch) ||
      !isValidDraftInput(input) ||
      !idempotencyKeyPattern.test(idempotencyKey)
    ) {
      throw new AuthoringApiError(400, 'INVALID_DRAFT_REVISION');
    }

    try {
      const headers = (await this.csrfHeaders())
        .set('Content-Type', 'application/json')
        .set('If-Match', ifMatch)
        .set('Idempotency-Key', idempotencyKey);
      const response = await firstValueFrom(
        this.http
          .post<unknown>(`${manuscriptsUrl}/${workId}/chapters/${chapterId}/revisions`, input, {
            headers,
            observe: 'response',
          })
          .pipe(timeout(requestTimeoutMs)),
      );
      if (response.status !== 201 || response.headers.get('Cache-Control') !== 'no-store') {
        throw invalidDraftRevisionResponse();
      }
      return parseCreatedDraftRevision(
        response.body,
        chapterId,
        input.sourceFormat,
        response.headers.get('ETag'),
      );
    } catch (error) {
      throw toAuthoringApiError(error);
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
      throw new AuthoringApiError(503, 'CSRF_UNAVAILABLE');
    }
    return new HttpHeaders({ 'X-CSRF-Token': response['token'] });
  }
}

export class AuthoringApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string | null,
    readonly currentEtag: string | null = null,
  ) {
    super(code ?? 'AUTHORING_UNAVAILABLE');
    this.name = 'AuthoringApiError';
  }
}

export function newAuthoringIdempotencyKey(): string {
  return globalThis.crypto.randomUUID();
}

function parseCreatedManuscript(value: unknown): CreatedPrivateManuscript {
  if (
    !isRecord(value) ||
    Object.keys(value).some((key) => !responseFields.has(key)) ||
    Object.keys(value).length !== responseFields.size ||
    !isUuid(value['id']) ||
    !isBoundedString(value['title'], 1, 300) ||
    !isBoundedString(value['author'], 0, 200) ||
    !isBoundedString(value['language'], 2, 35) ||
    value['publicationState'] !== 'draft' ||
    value['visibility'] !== 'private' ||
    !isInstant(value['createdAt'])
  ) {
    throw invalidResponse();
  }
  return value as unknown as CreatedPrivateManuscript;
}

function parseOutlinePage(
  value: unknown,
  limit: number,
  requestedCursor: string | null,
): PrivateChapterOutlinePage {
  if (
    !isRecord(value) ||
    hasUnexpectedFields(value, ['items', 'nextCursor']) ||
    !Array.isArray(value['items']) ||
    value['items'].length > limit ||
    !isNullableCursor(value['nextCursor']) ||
    (value['nextCursor'] !== null && value['nextCursor'] === requestedCursor)
  ) {
    throw invalidOutlineResponse();
  }

  const items = value['items'].map(parseOutlineItem);
  if (
    new Set(items.map(({ id }) => id)).size !== items.length ||
    new Set(items.map(({ ordinal }) => ordinal)).size !== items.length ||
    items.some((item, index) => index > 0 && item.ordinal <= items[index - 1].ordinal)
  ) {
    throw invalidOutlineResponse();
  }
  return { items, nextCursor: value['nextCursor'] as string | null };
}

function parseOutlineItem(value: unknown): PrivateChapterOutlineItem {
  if (
    !isRecord(value) ||
    hasUnexpectedFields(value, [
      'id',
      'ordinal',
      'title',
      'language',
      'status',
      'contentAvailable',
      'createdAt',
    ]) ||
    !isUuid(value['id']) ||
    !Number.isSafeInteger(value['ordinal']) ||
    (value['ordinal'] as number) < 1 ||
    !isBoundedString(value['title'], 1, 300) ||
    !isBoundedString(value['language'], 2, 35) ||
    !isAuthoringChapterStatus(value['status']) ||
    typeof value['contentAvailable'] !== 'boolean' ||
    (value['contentAvailable'] && value['status'] !== 'ready') ||
    !isInstant(value['createdAt'])
  ) {
    throw invalidOutlineResponse();
  }
  return value as unknown as PrivateChapterOutlineItem;
}

function parseCreatedChapter(
  value: unknown,
  expectedWorkId: string,
  expectedTitle: string,
): CreatedPrivateChapter {
  if (
    !isRecord(value) ||
    hasUnexpectedFields(value, [
      'id',
      'workId',
      'ordinal',
      'title',
      'language',
      'status',
      'createdAt',
    ]) ||
    !isUuid(value['id']) ||
    value['workId'] !== expectedWorkId ||
    !Number.isSafeInteger(value['ordinal']) ||
    (value['ordinal'] as number) < 1 ||
    value['title'] !== expectedTitle ||
    !isBoundedString(value['language'], 2, 35) ||
    value['status'] !== 'draft' ||
    !isInstant(value['createdAt'])
  ) {
    throw invalidChapterResponse();
  }
  return value as unknown as CreatedPrivateChapter;
}

function parsePrivateDraft(
  value: unknown,
  expectedWorkId: string,
  expectedChapterId: string,
  etag: string | null,
): PrivateAuthoredChapterDraft {
  if (
    !isRecord(value) ||
    hasUnexpectedFields(value, [
      'id',
      'workId',
      'title',
      'status',
      'revisionNo',
      'sourceFormat',
      'normalizedContent',
      'segments',
      'updatedAt',
    ]) ||
    value['id'] !== expectedChapterId ||
    value['workId'] !== expectedWorkId ||
    !isBoundedString(value['title'], 1, 300) ||
    value['status'] !== 'draft' ||
    !Number.isSafeInteger(value['revisionNo']) ||
    (value['revisionNo'] as number) < 0 ||
    !isNullableDraftFormat(value['sourceFormat']) ||
    typeof value['normalizedContent'] !== 'string' ||
    utf8Bytes(value['normalizedContent']) > maxDraftBytes ||
    !Array.isArray(value['segments']) ||
    value['segments'].length > 10_000 ||
    !isInstant(value['updatedAt']) ||
    !isDraftEtag(etag) ||
    draftEtagRevision(etag) !== value['revisionNo']
  ) {
    throw invalidDraftResponse();
  }

  const parsedSegments = value['segments'].map(parsePrivateDraftSegment);
  const segments = parsedSegments.filter((segment): segment is PrivateDraftSegment => !!segment);
  if (
    segments.length !== parsedSegments.length ||
    !areDraftSegmentsOrderedAndBounded(segments) ||
    (value['revisionNo'] === 0 &&
      (value['sourceFormat'] !== null ||
        value['normalizedContent'] !== '' ||
        segments.length > 0)) ||
    (value['revisionNo'] > 0 &&
      (!isDraftFormat(value['sourceFormat']) || value['normalizedContent'].length === 0))
  ) {
    throw invalidDraftResponse();
  }
  return { ...(value as unknown as Omit<PrivateAuthoredChapterDraft, 'etag'>), segments, etag };
}

function parsePrivateDraftSegment(value: unknown): PrivateDraftSegment | null {
  if (
    !isRecord(value) ||
    hasUnexpectedFields(value, ['ordinal', 'type', 'sourceText', 'stableAnchor']) ||
    !Number.isSafeInteger(value['ordinal']) ||
    (value['ordinal'] as number) < 1 ||
    typeof value['type'] !== 'string' ||
    !draftSegmentTypes.has(value['type'] as PrivateDraftSegment['type']) ||
    typeof value['sourceText'] !== 'string' ||
    utf8Bytes(value['sourceText']) > maxDraftSegmentBytes ||
    typeof value['stableAnchor'] !== 'string' ||
    !draftAnchorPattern.test(value['stableAnchor'])
  ) {
    return null;
  }
  return value as unknown as PrivateDraftSegment;
}

function parseCreatedDraftRevision(
  value: unknown,
  expectedChapterId: string,
  expectedFormat: PrivateDraftSourceFormat,
  etag: string | null,
): CreatedPrivateDraftRevision {
  if (
    !isRecord(value) ||
    hasUnexpectedFields(value, ['id', 'chapterId', 'revisionNo', 'sourceFormat', 'createdAt']) ||
    !isUuid(value['id']) ||
    value['chapterId'] !== expectedChapterId ||
    !Number.isSafeInteger(value['revisionNo']) ||
    (value['revisionNo'] as number) < 1 ||
    value['sourceFormat'] !== expectedFormat ||
    !isInstant(value['createdAt']) ||
    !isDraftEtag(etag) ||
    draftEtagRevision(etag) !== value['revisionNo']
  ) {
    throw invalidDraftRevisionResponse();
  }
  return { ...(value as unknown as Omit<CreatedPrivateDraftRevision, 'etag'>), etag };
}

function isValidDraftInput(value: SavePrivateDraftRevisionInput): boolean {
  if (
    !value ||
    !isDraftFormat(value.sourceFormat) ||
    typeof value.normalizedContent !== 'string' ||
    value.normalizedContent.length === 0 ||
    utf8Bytes(value.normalizedContent) > maxDraftBytes ||
    !Array.isArray(value.segments) ||
    value.segments.length > 10_000
  ) {
    return false;
  }
  return areDraftSegmentsOrderedAndBounded(value.segments);
}

function areDraftSegmentsOrderedAndBounded(segments: PrivateDraftSegment[]): boolean {
  let totalBytes = 0;
  const anchors = new Set<string>();
  return segments.every((segment, index) => {
    if (
      !segment ||
      segment.ordinal !== index + 1 ||
      !draftSegmentTypes.has(segment.type) ||
      typeof segment.sourceText !== 'string' ||
      utf8Bytes(segment.sourceText) > maxDraftSegmentBytes ||
      !draftAnchorPattern.test(segment.stableAnchor) ||
      anchors.has(segment.stableAnchor)
    ) {
      return false;
    }
    totalBytes += utf8Bytes(segment.sourceText);
    anchors.add(segment.stableAnchor);
    return totalBytes <= maxDraftBytes;
  });
}

function isDraftEtag(value: unknown): value is string {
  return typeof value === 'string' && draftEtagPattern.test(value);
}

function draftEtagRevision(value: string): number {
  return Number(value.slice(7, -1));
}

function isDraftFormat(value: unknown): value is PrivateDraftSourceFormat {
  return typeof value === 'string' && draftFormats.has(value as PrivateDraftSourceFormat);
}

function isNullableDraftFormat(value: unknown): value is PrivateDraftSourceFormat | null {
  return value === null || isDraftFormat(value);
}

function utf8Bytes(value: string): number {
  return new TextEncoder().encode(value).byteLength;
}

function hasUnexpectedFields(value: Record<string, unknown>, allowed: string[]): boolean {
  return (
    Object.keys(value).length !== allowed.length ||
    Object.keys(value).some((key) => !allowed.includes(key))
  );
}

function isAuthoringChapterStatus(value: unknown): value is AuthoringChapterStatus {
  return value === 'draft' || value === 'ready' || value === 'unavailable';
}

function isCursor(value: string): boolean {
  return value.length <= 2048 && cursorPattern.test(value);
}

function isNullableCursor(value: unknown): value is string | null {
  return value === null || (typeof value === 'string' && isCursor(value));
}

function toAuthoringApiError(error: unknown): AuthoringApiError {
  if (error instanceof AuthoringApiError) return error;
  if (error instanceof HttpErrorResponse) {
    const code =
      isRecord(error.error) && typeof error.error['code'] === 'string'
        ? error.error['code'].slice(0, 80)
        : null;
    const currentEtag =
      isRecord(error.error) && isDraftEtag(error.error['currentEtag'])
        ? error.error['currentEtag']
        : null;
    return new AuthoringApiError(error.status, code, currentEtag);
  }
  return new AuthoringApiError(0, null);
}

function invalidResponse(): AuthoringApiError {
  return new AuthoringApiError(502, 'INVALID_MANUSCRIPT_RESPONSE');
}

function invalidOutlineResponse(): AuthoringApiError {
  return new AuthoringApiError(502, 'INVALID_AUTHORING_OUTLINE_RESPONSE');
}

function invalidChapterResponse(): AuthoringApiError {
  return new AuthoringApiError(502, 'INVALID_CHAPTER_RESPONSE');
}

function invalidDraftResponse(): AuthoringApiError {
  return new AuthoringApiError(502, 'INVALID_DRAFT_RESPONSE');
}

function invalidDraftRevisionResponse(): AuthoringApiError {
  return new AuthoringApiError(502, 'INVALID_DRAFT_REVISION_RESPONSE');
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isUuid(value: unknown): value is string {
  return typeof value === 'string' && uuidPattern.test(value);
}

function isBoundedString(value: unknown, min: number, max: number): value is string {
  return typeof value === 'string' && [...value].length >= min && [...value].length <= max;
}

function isInstant(value: unknown): value is string {
  return typeof value === 'string' && value.length <= 64 && Number.isFinite(Date.parse(value));
}
