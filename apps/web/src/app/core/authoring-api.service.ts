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

export interface PrivateManuscriptListItem {
  id: string;
  title: string;
  author: string;
  language: string;
  visibility: 'private';
  publicationState: 'draft';
  chapterCount: number;
  createdAt: string;
}

export interface PrivateManuscriptDetails {
  id: string;
  title: string;
  author: string;
  language: string;
  visibility: 'private' | 'public';
  publicationState: 'draft' | 'published';
  chapterCount: number;
  createdAt: string;
  metadataVersion: number;
  etag: string;
}

export interface UpdatePrivateManuscriptInput {
  title: string;
  author: string;
  language: string;
}

export interface PublicationRightsPolicy {
  version: string;
  effectiveAt: string;
  displayText: string;
  policySha256: string;
  acceptedAt: string | null;
}

export interface PublicationRightsPolicyAcceptance {
  id: string;
  policyVersion: string;
  policySha256: string;
  acceptedAt: string;
}

export type PublicationRequestState =
  | 'PENDING_REVIEW'
  | 'NEEDS_INFORMATION'
  | 'APPROVED_UNPUBLISHED'
  | 'REJECTED'
  | 'SUPERSEDED'
  | 'WITHDRAWN';

export type PublicationScopeCode =
  'PUBLIC_METADATA' | 'CHAPTER_TEXT' | 'COMMERCIAL_SALE' | 'TRANSLATION_ADAPTATION';

export interface PublicationScopeReadiness {
  scopeCode: PublicationScopeCode;
  ready: boolean;
  snapshotEtag: string;
}

export type PublicationReadinessBlocker =
  | 'PUBLICATION_RIGHTS_POLICY_NOT_CONFIGURED'
  | 'PUBLICATION_RIGHTS_POLICY_NOT_ACCEPTED'
  | 'READY_CHAPTER_REQUIRED'
  | 'PUBLICATION_REQUEST_ALREADY_PENDING';

export interface PublicationReadiness {
  workId: string;
  canSubmit: boolean;
  publicationRightsPolicyAvailable: boolean;
  publicationRightsPolicyVersion: string | null;
  publicationRightsPolicyEffectiveAt: string | null;
  publicationRightsPolicyAcceptedAt: string | null;
  publicationContentVersion: number;
  readyChapterCount: number;
  currentRequestState: PublicationRequestState | null;
  snapshotEtag: string;
  scopes: PublicationScopeReadiness[];
  blockerCodes: PublicationReadinessBlocker[];
}

export interface PublicationReleaseReceipt {
  eventId: string;
  workId: string;
  requestId: string;
  state: 'PUBLISHED';
  publishedAt: string;
  publicationContentVersion: number;
}

export interface ChapterPublicationReleaseReceipt {
  eventId: string;
  workId: string;
  chapterId: string;
  requestId: string;
  state: 'PUBLISHED';
  publishedAt: string;
  publicationContentVersion: number;
}

export type PublicationRightsBasisCode =
  'ORIGINAL_WORK' | 'LICENSE_OR_PERMISSION' | 'PUBLIC_DOMAIN' | 'NEEDS_REVIEW';

export interface PublicationRequestScopeClaim {
  scopeCode: PublicationScopeCode;
  rightsBasisCode: PublicationRightsBasisCode;
}

export interface PublicationRequestSubmission {
  policyVersion: string;
  scopeClaims: PublicationRequestScopeClaim[];
}

export interface PublicationRequestReceipt {
  requestId: string;
  requestVersion: number;
  policyVersion: string;
  snapshotSha256: string;
  scopeCodes: PublicationScopeCode[];
  submittedAt: string;
  state: 'PENDING_REVIEW';
}

export interface PublicationRequestWithdrawalReceipt {
  eventId: string;
  requestId: string;
  requestVersion: number;
  state: 'WITHDRAWN';
  withdrawnAt: string;
}

export type PublicationRequestHistoryAction =
  'SUBMITTED' | 'NEEDS_INFORMATION' | 'APPROVED' | 'REJECTED' | 'SUPERSEDED' | 'WITHDRAWN';

export type PublicationRequestHistoryReason =
  | 'INCOMPLETE_CLAIM'
  | 'MORE_INFORMATION'
  | 'SCOPE_UNCLEAR'
  | 'RIGHTS_REVIEWED'
  | 'SCOPE_CONFIRMED'
  | 'RIGHTS_NOT_VERIFIED'
  | 'OUT_OF_SCOPE'
  | 'POLICY_MISMATCH';

export interface PublicationRequestHistoryEvent {
  sequence: number;
  actorType: 'OWNER' | 'REVIEWER';
  actionCode: PublicationRequestHistoryAction;
  fromState: PublicationRequestState | null;
  toState: PublicationRequestState;
  reasonCode: PublicationRequestHistoryReason | null;
  createdAt: string;
}

export interface PublicationRequestHistoryItem {
  requestVersion: number;
  state: PublicationRequestState;
  submittedAt: string;
  events: PublicationRequestHistoryEvent[];
}

export interface PublicationRequestHistoryPage {
  items: PublicationRequestHistoryItem[];
  nextCursor: string | null;
}

export interface PrivateManuscriptListPage {
  items: PrivateManuscriptListItem[];
  nextCursor: string | null;
}

export type AuthoringChapterStatus = 'draft' | 'ready' | 'unavailable';

export interface PrivateChapterOutlineItem {
  id: string;
  ordinal: number;
  title: string;
  language: string;
  status: AuthoringChapterStatus;
  contentAvailable: boolean;
  hasSavedDraftContent: boolean;
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
const publicationReadinessEtagPattern = /^"publication-snapshot-[a-f0-9]{64}"$/;
const publicationScopeEtagPattern = /^"publication-scope-[a-f0-9]{64}"$/;
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

  async listPrivateManuscripts(
    cursor?: string,
    limit = defaultOutlineLimit,
  ): Promise<PrivateManuscriptListPage> {
    if (
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
          .get<unknown>(`${manuscriptsUrl}?${params.toString()}`, { observe: 'response' })
          .pipe(timeout(requestTimeoutMs)),
      );
      if (response.status !== 200 || response.headers.get('Cache-Control') !== 'no-store') {
        throw invalidManuscriptListResponse();
      }
      return parseManuscriptListPage(response.body, limit, cursor ?? null);
    } catch (error) {
      throw toAuthoringApiError(error);
    }
  }

  async getPrivateManuscript(workId: string): Promise<PrivateManuscriptDetails> {
    if (!isUuid(workId)) throw new AuthoringApiError(400, 'INVALID_AUTHORING_QUERY');

    try {
      return await this.readManuscriptDetails(workId, 'private');
    } catch (error) {
      const privateError = toAuthoringApiError(error);
      if (privateError.status !== 404) throw privateError;
      return this.readManuscriptDetails(workId, 'public');
    }
  }

  private async readManuscriptDetails(
    workId: string,
    visibility: 'private' | 'public',
  ): Promise<PrivateManuscriptDetails> {
    const path =
      visibility === 'private'
        ? `${manuscriptsUrl}/${workId}`
        : `${manuscriptsUrl}/${workId}/public-drafting-context`;
    try {
      const response = await firstValueFrom(
        this.http.get<unknown>(path, { observe: 'response' }).pipe(timeout(requestTimeoutMs)),
      );
      if (response.status !== 200 || response.headers.get('Cache-Control') !== 'no-store') {
        throw invalidManuscriptDetailResponse();
      }
      let details: Omit<PrivateManuscriptDetails, 'etag'>;
      try {
        details = parseManuscriptDetails(response.body, visibility);
      } catch {
        throw invalidManuscriptDetailResponse();
      }
      const etag = response.headers.get('ETag');
      if (
        details.id !== workId ||
        !isManuscriptMetadataEtag(etag) ||
        etag !== manuscriptMetadataEtag(details.metadataVersion)
      ) {
        throw invalidManuscriptDetailResponse();
      }
      return { ...details, etag };
    } catch (error) {
      throw toAuthoringApiError(error);
    }
  }

  async getCurrentPublicationRightsPolicy(workId: string): Promise<PublicationRightsPolicy> {
    if (!isUuid(workId)) throw new AuthoringApiError(400, 'INVALID_AUTHORING_QUERY');

    try {
      const response = await firstValueFrom(
        this.http
          .get<unknown>(`${manuscriptsUrl}/${workId}/publication/rights-policy`, {
            observe: 'response',
          })
          .pipe(timeout(requestTimeoutMs)),
      );
      if (response.status !== 200 || response.headers.get('Cache-Control') !== 'no-store') {
        throw invalidPublicationRightsPolicyResponse();
      }
      return parsePublicationRightsPolicy(response.body);
    } catch (error) {
      throw toAuthoringApiError(error);
    }
  }

  async acceptCurrentPublicationRightsPolicy(
    workId: string,
    policyVersion: string,
    policySha256: string,
    idempotencyKey: string,
  ): Promise<PublicationRightsPolicyAcceptance> {
    if (
      !isUuid(workId) ||
      !isPublicationRightsPolicyVersion(policyVersion) ||
      !isSha256(policySha256) ||
      !isUuid(idempotencyKey)
    ) {
      throw new AuthoringApiError(400, 'INVALID_PUBLICATION_RIGHTS_ACCEPTANCE');
    }

    try {
      const headers = (await this.csrfHeaders())
        .set('Content-Type', 'application/json')
        .set('Idempotency-Key', idempotencyKey);
      const response = await firstValueFrom(
        this.http
          .post<unknown>(
            `${manuscriptsUrl}/${workId}/publication/rights-policy/acceptance`,
            { policyVersion, confirmed: true },
            { headers, observe: 'response' },
          )
          .pipe(timeout(requestTimeoutMs)),
      );
      if (response.status !== 200 || response.headers.get('Cache-Control') !== 'no-store') {
        throw invalidPublicationRightsAcceptanceResponse();
      }
      return parsePublicationRightsPolicyAcceptance(response.body, policyVersion, policySha256);
    } catch (error) {
      throw toAuthoringApiError(error);
    }
  }

  async getPublicationReadiness(workId: string): Promise<PublicationReadiness> {
    if (!isUuid(workId)) throw new AuthoringApiError(400, 'INVALID_AUTHORING_QUERY');

    try {
      const response = await firstValueFrom(
        this.http
          .get<unknown>(`${manuscriptsUrl}/${workId}/publication/readiness`, {
            observe: 'response',
          })
          .pipe(timeout(requestTimeoutMs)),
      );
      if (
        response.status !== 200 ||
        response.headers.get('Cache-Control') !== 'no-store' ||
        response.headers.get('Pragma') !== 'no-cache'
      ) {
        throw invalidPublicationReadinessResponse();
      }
      return parsePublicationReadiness(response.body, workId, response.headers.get('ETag'));
    } catch (error) {
      throw toAuthoringApiError(error);
    }
  }

  async getChapterPublicationReadiness(
    workId: string,
    chapterId: string,
  ): Promise<PublicationReadiness> {
    if (!isUuid(workId) || !isUuid(chapterId)) {
      throw new AuthoringApiError(400, 'INVALID_PUBLICATION_READINESS_QUERY');
    }
    try {
      const response = await firstValueFrom(
        this.http
          .get<unknown>(`${manuscriptsUrl}/${workId}/publication/readiness/chapters/${chapterId}`, {
            observe: 'response',
          })
          .pipe(timeout(requestTimeoutMs)),
      );
      if (
        response.status !== 200 ||
        response.headers.get('Cache-Control') !== 'no-store' ||
        response.headers.get('Pragma') !== 'no-cache'
      ) {
        throw invalidPublicationReadinessResponse();
      }
      return parsePublicationReadiness(response.body, workId, response.headers.get('ETag'));
    } catch (error) {
      throw toAuthoringApiError(error);
    }
  }

  async getPublicationRequestHistory(
    workId: string,
    cursor: string | null = null,
    limit = 20,
  ): Promise<PublicationRequestHistoryPage> {
    if (
      !isUuid(workId) ||
      !Number.isSafeInteger(limit) ||
      limit < 1 ||
      limit > 50 ||
      (cursor !== null && (!isBoundedString(cursor, 1, 2048) || /\s/.test(cursor)))
    ) {
      throw new AuthoringApiError(400, 'INVALID_PUBLICATION_HISTORY_QUERY');
    }

    const cursorQuery = cursor === null ? '' : `&cursor=${encodeURIComponent(cursor)}`;
    try {
      const response = await firstValueFrom(
        this.http
          .get<unknown>(
            `${manuscriptsUrl}/${workId}/publication/requests/history?limit=${limit}${cursorQuery}`,
            { observe: 'response' },
          )
          .pipe(timeout(requestTimeoutMs)),
      );
      if (
        response.status !== 200 ||
        response.headers.get('Cache-Control') !== 'no-store' ||
        response.headers.get('Pragma') !== 'no-cache'
      ) {
        throw invalidPublicationRequestHistoryResponse();
      }
      return parsePublicationRequestHistoryPage(response.body, limit);
    } catch (error) {
      throw toAuthoringApiError(error);
    }
  }

  async publishManuscriptNow(
    workId: string,
    readinessEtag: string,
    idempotencyKey: string,
  ): Promise<PublicationReleaseReceipt> {
    if (
      !isUuid(workId) ||
      !isPublicationReadinessEtag(readinessEtag) ||
      !idempotencyKeyPattern.test(idempotencyKey)
    ) {
      throw new AuthoringApiError(400, 'INVALID_PUBLICATION_ACTION');
    }

    try {
      const headers = (await this.csrfHeaders())
        .set('Content-Type', 'application/json')
        .set('If-Match', readinessEtag)
        .set('Idempotency-Key', idempotencyKey);
      const response = await firstValueFrom(
        this.http
          .post<unknown>(
            `${manuscriptsUrl}/${workId}/publication-actions`,
            { actionCode: 'PUBLISH_NOW' },
            { headers, observe: 'response' },
          )
          .pipe(timeout(requestTimeoutMs)),
      );
      if (
        (response.status !== 201 && response.status !== 200) ||
        response.headers.get('Cache-Control') !== 'no-store' ||
        response.headers.get('Pragma') !== 'no-cache'
      ) {
        throw invalidPublicationReleaseResponse();
      }
      return parsePublicationReleaseReceipt(response.body, workId);
    } catch (error) {
      throw toAuthoringApiError(error);
    }
  }

  async publishChapterNow(
    workId: string,
    chapterId: string,
    readinessEtag: string,
    idempotencyKey: string,
  ): Promise<ChapterPublicationReleaseReceipt> {
    if (
      !isUuid(workId) ||
      !isUuid(chapterId) ||
      !isPublicationReadinessEtag(readinessEtag) ||
      !idempotencyKeyPattern.test(idempotencyKey)
    ) {
      throw new AuthoringApiError(400, 'INVALID_CHAPTER_PUBLICATION_ACTION');
    }
    try {
      const headers = (await this.csrfHeaders())
        .set('Content-Type', 'application/json')
        .set('If-Match', readinessEtag)
        .set('Idempotency-Key', idempotencyKey);
      const response = await firstValueFrom(
        this.http
          .post<unknown>(
            `${manuscriptsUrl}/${workId}/chapters/${chapterId}/publication-release`,
            { actionCode: 'PUBLISH_NOW' },
            { headers, observe: 'response' },
          )
          .pipe(timeout(requestTimeoutMs)),
      );
      if (
        (response.status !== 201 && response.status !== 200) ||
        response.headers.get('Cache-Control') !== 'no-store' ||
        response.headers.get('Pragma') !== 'no-cache'
      ) {
        throw invalidChapterPublicationReleaseResponse();
      }
      return parseChapterPublicationReleaseReceipt(response.body, workId, chapterId);
    } catch (error) {
      throw toAuthoringApiError(error);
    }
  }

  async submitPublicationRequest(
    workId: string,
    readinessEtag: string,
    submission: PublicationRequestSubmission,
    idempotencyKey: string,
  ): Promise<PublicationRequestReceipt> {
    if (
      !isUuid(workId) ||
      !isPublicationReadinessEtag(readinessEtag) ||
      !idempotencyKeyPattern.test(idempotencyKey) ||
      !isValidPublicationRequestSubmission(submission)
    ) {
      throw new AuthoringApiError(400, 'INVALID_PUBLICATION_REQUEST');
    }

    try {
      const headers = (await this.csrfHeaders())
        .set('Content-Type', 'application/json')
        .set('If-Match', readinessEtag)
        .set('Idempotency-Key', idempotencyKey);
      const response = await firstValueFrom(
        this.http
          .post<unknown>(`${manuscriptsUrl}/${workId}/publication/requests`, submission, {
            headers,
            observe: 'response',
          })
          .pipe(timeout(requestTimeoutMs)),
      );
      if (
        response.status !== 201 ||
        response.headers.get('Cache-Control') !== 'no-store' ||
        response.headers.get('Pragma') !== 'no-cache'
      ) {
        throw invalidPublicationRequestResponse();
      }
      return parsePublicationRequestReceipt(response.body, submission.policyVersion);
    } catch (error) {
      throw toAuthoringApiError(error);
    }
  }

  async submitChapterPublicationRequest(
    workId: string,
    chapterId: string,
    readinessEtag: string,
    submission: PublicationRequestSubmission,
    idempotencyKey: string,
  ): Promise<PublicationRequestReceipt> {
    if (
      !isUuid(workId) ||
      !isUuid(chapterId) ||
      !isPublicationReadinessEtag(readinessEtag) ||
      !idempotencyKeyPattern.test(idempotencyKey) ||
      !isValidPublicationRequestSubmission(submission)
    ) {
      throw new AuthoringApiError(400, 'INVALID_CHAPTER_PUBLICATION_REQUEST');
    }
    try {
      const headers = (await this.csrfHeaders())
        .set('Content-Type', 'application/json')
        .set('If-Match', readinessEtag)
        .set('Idempotency-Key', idempotencyKey);
      const response = await firstValueFrom(
        this.http
          .post<unknown>(
            `${manuscriptsUrl}/${workId}/publication/requests/chapters/${chapterId}`,
            submission,
            { headers, observe: 'response' },
          )
          .pipe(timeout(requestTimeoutMs)),
      );
      if (
        response.status !== 201 ||
        response.headers.get('Cache-Control') !== 'no-store' ||
        response.headers.get('Pragma') !== 'no-cache'
      ) {
        throw invalidPublicationRequestResponse();
      }
      return parsePublicationRequestReceipt(response.body, submission.policyVersion);
    } catch (error) {
      throw toAuthoringApiError(error);
    }
  }

  async withdrawPublicationRequest(
    workId: string,
    readinessEtag: string,
    idempotencyKey: string,
  ): Promise<PublicationRequestWithdrawalReceipt> {
    if (
      !isUuid(workId) ||
      !isPublicationReadinessEtag(readinessEtag) ||
      !idempotencyKeyPattern.test(idempotencyKey)
    ) {
      throw new AuthoringApiError(400, 'INVALID_PUBLICATION_REQUEST_WITHDRAWAL');
    }

    try {
      const headers = (await this.csrfHeaders())
        .set('If-Match', readinessEtag)
        .set('Idempotency-Key', idempotencyKey);
      const response = await firstValueFrom(
        this.http
          .post<unknown>(`${manuscriptsUrl}/${workId}/publication/requests/withdrawal`, null, {
            headers,
            observe: 'response',
          })
          .pipe(timeout(requestTimeoutMs)),
      );
      if (
        response.status !== 201 ||
        response.headers.get('Cache-Control') !== 'no-store' ||
        response.headers.get('Pragma') !== 'no-cache'
      ) {
        throw invalidPublicationRequestWithdrawalResponse();
      }
      return parsePublicationRequestWithdrawalReceipt(response.body);
    } catch (error) {
      throw toAuthoringApiError(error);
    }
  }

  async updatePrivateManuscript(
    workId: string,
    input: UpdatePrivateManuscriptInput,
    etag: string,
  ): Promise<PrivateManuscriptDetails> {
    if (
      !isUuid(workId) ||
      !isManuscriptMetadataEtag(etag) ||
      !isBoundedString(input.title, 1, 300) ||
      !isBoundedString(input.author, 0, 200) ||
      !isBoundedString(input.language, 2, 35)
    ) {
      throw new AuthoringApiError(400, 'INVALID_AUTHORING_MANUSCRIPT');
    }

    try {
      const headers = (await this.csrfHeaders()).set('If-Match', etag);
      const response = await firstValueFrom(
        this.http
          .put<unknown>(`${manuscriptsUrl}/${workId}`, input, {
            observe: 'response',
            headers,
          })
          .pipe(timeout(requestTimeoutMs)),
      );
      if (response.status !== 200 || response.headers.get('Cache-Control') !== 'no-store') {
        throw invalidManuscriptDetailResponse();
      }
      let details: Omit<PrivateManuscriptDetails, 'etag'>;
      try {
        details = parseManuscriptDetails(response.body, 'private');
      } catch {
        throw invalidManuscriptDetailResponse();
      }
      const responseEtag = response.headers.get('ETag');
      if (
        details.id !== workId ||
        !isManuscriptMetadataEtag(responseEtag) ||
        responseEtag !== manuscriptMetadataEtag(details.metadataVersion)
      ) {
        throw invalidManuscriptDetailResponse();
      }
      return { ...details, etag: responseEtag };
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

  async deletePrivateEmptyChapter(workId: string, chapterId: string): Promise<void> {
    if (!isUuid(workId) || !isUuid(chapterId)) {
      throw new AuthoringApiError(400, 'INVALID_CHAPTER');
    }

    try {
      const headers = await this.csrfHeaders();
      const response = await firstValueFrom(
        this.http
          .delete<unknown>(`${manuscriptsUrl}/${workId}/chapters/${chapterId}`, {
            headers,
            observe: 'response',
          })
          .pipe(timeout(requestTimeoutMs)),
      );
      if (
        response.status !== 204 ||
        response.headers.get('Cache-Control') !== 'no-store' ||
        response.headers.get('Pragma') !== 'no-cache' ||
        response.body !== null
      ) {
        throw invalidChapterDeleteResponse();
      }
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
    readonly retryAfterSeconds: number | null = null,
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

function parseManuscriptListPage(
  value: unknown,
  limit: number,
  requestedCursor: string | null,
): PrivateManuscriptListPage {
  if (
    !isRecord(value) ||
    hasUnexpectedFields(value, ['items', 'nextCursor']) ||
    !Array.isArray(value['items']) ||
    value['items'].length > limit ||
    !isNullableCursor(value['nextCursor']) ||
    (value['nextCursor'] !== null && value['nextCursor'] === requestedCursor)
  ) {
    throw invalidManuscriptListResponse();
  }

  const items = value['items'].map((item) => parseManuscriptListItem(item));
  if (
    new Set(items.map(({ id }) => id)).size !== items.length ||
    items.some(
      (item, index) =>
        index > 0 && Date.parse(item.createdAt) > Date.parse(items[index - 1].createdAt),
    )
  ) {
    throw invalidManuscriptListResponse();
  }
  return { items, nextCursor: value['nextCursor'] as string | null };
}

function parseManuscriptListItem(value: unknown): PrivateManuscriptListItem {
  if (
    !isRecord(value) ||
    hasUnexpectedFields(value, [
      'id',
      'title',
      'author',
      'language',
      'visibility',
      'publicationState',
      'chapterCount',
      'createdAt',
    ]) ||
    !isUuid(value['id']) ||
    !isBoundedString(value['title'], 1, 300) ||
    !isBoundedString(value['author'], 0, 200) ||
    !isBoundedString(value['language'], 2, 35) ||
    value['visibility'] !== 'private' ||
    value['publicationState'] !== 'draft' ||
    !Number.isSafeInteger(value['chapterCount']) ||
    (value['chapterCount'] as number) < 0 ||
    !isInstant(value['createdAt'])
  ) {
    throw invalidManuscriptListResponse();
  }
  return value as unknown as PrivateManuscriptListItem;
}

function parseManuscriptDetails(
  value: unknown,
  visibility: 'private' | 'public',
): Omit<PrivateManuscriptDetails, 'etag'> {
  if (
    !isRecord(value) ||
    hasUnexpectedFields(value, [
      'id',
      'title',
      'author',
      'language',
      'visibility',
      'publicationState',
      'chapterCount',
      'createdAt',
      'metadataVersion',
    ]) ||
    !isUuid(value['id']) ||
    !isBoundedString(value['title'], 1, 300) ||
    !isBoundedString(value['author'], 0, 200) ||
    !isBoundedString(value['language'], 2, 35) ||
    value['visibility'] !== visibility ||
    value['publicationState'] !== (visibility === 'private' ? 'draft' : 'published') ||
    !Number.isSafeInteger(value['chapterCount']) ||
    (value['chapterCount'] as number) < 0 ||
    !isInstant(value['createdAt']) ||
    !Number.isSafeInteger(value['metadataVersion']) ||
    (value['metadataVersion'] as number) < 1
  ) {
    throw invalidManuscriptDetailResponse();
  }
  return {
    id: value['id'] as string,
    title: value['title'] as string,
    author: value['author'] as string,
    language: value['language'] as string,
    visibility,
    publicationState: visibility === 'private' ? 'draft' : 'published',
    chapterCount: value['chapterCount'] as number,
    createdAt: value['createdAt'] as string,
    metadataVersion: value['metadataVersion'] as number,
  };
}

function parsePublicationRightsPolicy(value: unknown): PublicationRightsPolicy {
  if (
    !isRecord(value) ||
    hasUnexpectedFields(value, [
      'version',
      'effectiveAt',
      'displayText',
      'policySha256',
      'acceptedAt',
    ]) ||
    !isPublicationRightsPolicyVersion(value['version']) ||
    !isInstant(value['effectiveAt']) ||
    !isBoundedString(value['displayText'], 1, 32_768) ||
    new TextEncoder().encode(value['displayText']).byteLength > 32_768 ||
    !isSha256(value['policySha256']) ||
    !(value['acceptedAt'] === null || isInstant(value['acceptedAt']))
  ) {
    throw invalidPublicationRightsPolicyResponse();
  }
  return value as unknown as PublicationRightsPolicy;
}

function parsePublicationRightsPolicyAcceptance(
  value: unknown,
  expectedVersion: string,
  expectedSha256: string,
): PublicationRightsPolicyAcceptance {
  if (
    !isRecord(value) ||
    hasUnexpectedFields(value, ['id', 'policyVersion', 'policySha256', 'acceptedAt']) ||
    !isUuid(value['id']) ||
    value['policyVersion'] !== expectedVersion ||
    value['policySha256'] !== expectedSha256 ||
    !isInstant(value['acceptedAt'])
  ) {
    throw invalidPublicationRightsAcceptanceResponse();
  }
  return value as unknown as PublicationRightsPolicyAcceptance;
}

function parsePublicationReadiness(
  value: unknown,
  expectedWorkId: string,
  etag: string | null,
): PublicationReadiness {
  if (
    !isRecord(value) ||
    hasUnexpectedFields(value, [
      'workId',
      'canSubmit',
      'publicationRightsPolicyAvailable',
      'publicationRightsPolicyVersion',
      'publicationRightsPolicyEffectiveAt',
      'publicationRightsPolicyAcceptedAt',
      'publicationContentVersion',
      'readyChapterCount',
      'currentRequestState',
      'snapshotEtag',
      'scopes',
      'blockerCodes',
    ]) ||
    value['workId'] !== expectedWorkId ||
    typeof value['canSubmit'] !== 'boolean' ||
    typeof value['publicationRightsPolicyAvailable'] !== 'boolean' ||
    !isNullablePublicationRightsPolicyVersion(value['publicationRightsPolicyVersion']) ||
    !isNullableInstant(value['publicationRightsPolicyEffectiveAt']) ||
    !isNullableInstant(value['publicationRightsPolicyAcceptedAt']) ||
    !Number.isSafeInteger(value['publicationContentVersion']) ||
    (value['publicationContentVersion'] as number) < 1 ||
    !isNullablePublicationRequestState(value['currentRequestState']) ||
    !isPublicationReadinessEtag(value['snapshotEtag']) ||
    !isPublicationReadinessEtag(etag) ||
    etag !== value['snapshotEtag'] ||
    !Array.isArray(value['scopes']) ||
    value['scopes'].length !== 4 ||
    !Array.isArray(value['blockerCodes']) ||
    value['blockerCodes'].length > 4 ||
    value['blockerCodes'].some((code) => !isPublicationReadinessBlocker(code)) ||
    new Set(value['blockerCodes']).size !== value['blockerCodes'].length ||
    value['canSubmit'] !== (value['blockerCodes'].length === 0) ||
    value['publicationRightsPolicyAvailable'] !==
      (value['publicationRightsPolicyVersion'] !== null) ||
    (!value['publicationRightsPolicyAvailable'] &&
      (value['publicationRightsPolicyEffectiveAt'] !== null ||
        value['publicationRightsPolicyAcceptedAt'] !== null)) ||
    (!value['publicationRightsPolicyAvailable'] &&
      value['blockerCodes'].includes('PUBLICATION_RIGHTS_POLICY_NOT_CONFIGURED')) ||
    (value['publicationRightsPolicyAvailable'] &&
      value['blockerCodes'].includes('PUBLICATION_RIGHTS_POLICY_NOT_CONFIGURED'))
  ) {
    throw invalidPublicationReadinessResponse();
  }

  const parsedScopes = value['scopes'].map(parsePublicationScopeReadiness);
  const scopes = parsedScopes.filter((scope): scope is PublicationScopeReadiness => scope !== null);
  const requiredScopes: PublicationScopeCode[] = [
    'PUBLIC_METADATA',
    'CHAPTER_TEXT',
    'COMMERCIAL_SALE',
    'TRANSLATION_ADAPTATION',
  ];
  if (
    scopes.length !== parsedScopes.length ||
    new Set(scopes.map((scope) => scope?.scopeCode)).size !== requiredScopes.length ||
    requiredScopes.some((scopeCode) => !scopes.some((scope) => scope?.scopeCode === scopeCode))
  ) {
    throw invalidPublicationReadinessResponse();
  }

  return { ...(value as unknown as PublicationReadiness), scopes };
}

function parsePublicationScopeReadiness(value: unknown): PublicationScopeReadiness | null {
  if (
    !isRecord(value) ||
    hasUnexpectedFields(value, ['scopeCode', 'ready', 'snapshotEtag']) ||
    !isPublicationScopeCode(value['scopeCode']) ||
    typeof value['ready'] !== 'boolean' ||
    !isPublicationScopeEtag(value['snapshotEtag'])
  ) {
    return null;
  }
  return value as unknown as PublicationScopeReadiness;
}

function parsePublicationReleaseReceipt(
  value: unknown,
  expectedWorkId: string,
): PublicationReleaseReceipt {
  if (
    !isRecord(value) ||
    hasUnexpectedFields(value, [
      'eventId',
      'workId',
      'requestId',
      'state',
      'publishedAt',
      'publicationContentVersion',
    ]) ||
    !isUuid(value['eventId']) ||
    value['workId'] !== expectedWorkId ||
    !isUuid(value['requestId']) ||
    value['state'] !== 'PUBLISHED' ||
    !isInstant(value['publishedAt']) ||
    !Number.isSafeInteger(value['publicationContentVersion']) ||
    (value['publicationContentVersion'] as number) < 1
  ) {
    throw invalidPublicationReleaseResponse();
  }
  return value as unknown as PublicationReleaseReceipt;
}

function parseChapterPublicationReleaseReceipt(
  value: unknown,
  expectedWorkId: string,
  expectedChapterId: string,
): ChapterPublicationReleaseReceipt {
  if (
    !isRecord(value) ||
    hasUnexpectedFields(value, [
      'eventId',
      'workId',
      'chapterId',
      'requestId',
      'state',
      'publishedAt',
      'publicationContentVersion',
    ]) ||
    !isUuid(value['eventId']) ||
    value['workId'] !== expectedWorkId ||
    value['chapterId'] !== expectedChapterId ||
    !isUuid(value['requestId']) ||
    value['state'] !== 'PUBLISHED' ||
    !isInstant(value['publishedAt']) ||
    !Number.isSafeInteger(value['publicationContentVersion']) ||
    (value['publicationContentVersion'] as number) < 1
  ) {
    throw invalidChapterPublicationReleaseResponse();
  }
  return value as unknown as ChapterPublicationReleaseReceipt;
}

function parsePublicationRequestReceipt(
  value: unknown,
  expectedPolicyVersion: string,
): PublicationRequestReceipt {
  if (
    !isRecord(value) ||
    hasUnexpectedFields(value, [
      'requestId',
      'requestVersion',
      'policyVersion',
      'snapshotSha256',
      'scopeCodes',
      'submittedAt',
      'state',
    ]) ||
    !isUuid(value['requestId']) ||
    !Number.isSafeInteger(value['requestVersion']) ||
    (value['requestVersion'] as number) < 1 ||
    value['policyVersion'] !== expectedPolicyVersion ||
    !isSha256(value['snapshotSha256']) ||
    !Array.isArray(value['scopeCodes']) ||
    value['scopeCodes'].length < 2 ||
    value['scopeCodes'].length > 4 ||
    value['scopeCodes'].some((scope) => !isPublicationScopeCode(scope)) ||
    new Set(value['scopeCodes']).size !== value['scopeCodes'].length ||
    !value['scopeCodes'].includes('PUBLIC_METADATA') ||
    !value['scopeCodes'].includes('CHAPTER_TEXT') ||
    !isInstant(value['submittedAt']) ||
    value['state'] !== 'PENDING_REVIEW'
  ) {
    throw invalidPublicationRequestResponse();
  }
  return value as unknown as PublicationRequestReceipt;
}

function parsePublicationRequestWithdrawalReceipt(
  value: unknown,
): PublicationRequestWithdrawalReceipt {
  if (
    !isRecord(value) ||
    hasUnexpectedFields(value, [
      'eventId',
      'requestId',
      'requestVersion',
      'state',
      'withdrawnAt',
    ]) ||
    !isUuid(value['eventId']) ||
    !isUuid(value['requestId']) ||
    !Number.isSafeInteger(value['requestVersion']) ||
    (value['requestVersion'] as number) < 1 ||
    value['state'] !== 'WITHDRAWN' ||
    !isInstant(value['withdrawnAt'])
  ) {
    throw invalidPublicationRequestWithdrawalResponse();
  }
  return value as unknown as PublicationRequestWithdrawalReceipt;
}

function parsePublicationRequestHistoryPage(
  value: unknown,
  requestedLimit: number,
): PublicationRequestHistoryPage {
  if (
    !isRecord(value) ||
    hasUnexpectedFields(value, ['items', 'nextCursor']) ||
    !Array.isArray(value['items']) ||
    value['items'].length > requestedLimit ||
    !(value['nextCursor'] === null || isBoundedString(value['nextCursor'], 1, 2048)) ||
    (value['nextCursor'] !== null && value['items'].length !== requestedLimit)
  ) {
    throw invalidPublicationRequestHistoryResponse();
  }

  const parsed = value['items'].map(parsePublicationRequestHistoryItem);
  if (
    parsed.some((item) => item === null) ||
    new Set(parsed.map((item) => item?.requestVersion)).size !== parsed.length ||
    parsed.some(
      (item, index) => index > 0 && parsed[index - 1]!.requestVersion <= item!.requestVersion,
    )
  ) {
    throw invalidPublicationRequestHistoryResponse();
  }
  return {
    items: parsed as PublicationRequestHistoryItem[],
    nextCursor: value['nextCursor'] as string | null,
  };
}

function parsePublicationRequestHistoryItem(value: unknown): PublicationRequestHistoryItem | null {
  if (
    !isRecord(value) ||
    hasUnexpectedFields(value, ['requestVersion', 'state', 'submittedAt', 'events']) ||
    !Number.isSafeInteger(value['requestVersion']) ||
    (value['requestVersion'] as number) < 1 ||
    !isPublicationRequestState(value['state']) ||
    !isInstant(value['submittedAt']) ||
    !Array.isArray(value['events']) ||
    value['events'].length < 1 ||
    value['events'].length > 10
  ) {
    return null;
  }

  const parsedEvents = value['events'].map(parsePublicationRequestHistoryEvent);
  if (parsedEvents.some((event) => event === null)) return null;
  const events = parsedEvents as PublicationRequestHistoryEvent[];
  if (
    events.some((event, index) => event.sequence !== index + 1) ||
    events[0].actionCode !== 'SUBMITTED' ||
    events[0].fromState !== null ||
    events.some((event, index) => index > 0 && event.fromState !== events[index - 1].toState) ||
    events[events.length - 1].toState !== value['state']
  ) {
    return null;
  }

  return { ...(value as unknown as Omit<PublicationRequestHistoryItem, 'events'>), events };
}

function parsePublicationRequestHistoryEvent(
  value: unknown,
): PublicationRequestHistoryEvent | null {
  if (
    !isRecord(value) ||
    hasUnexpectedFields(value, [
      'sequence',
      'actorType',
      'actionCode',
      'fromState',
      'toState',
      'reasonCode',
      'createdAt',
    ]) ||
    !Number.isSafeInteger(value['sequence']) ||
    (value['sequence'] as number) < 1 ||
    (value['actorType'] !== 'OWNER' && value['actorType'] !== 'REVIEWER') ||
    !isPublicationRequestHistoryAction(value['actionCode']) ||
    !isNullablePublicationRequestState(value['fromState']) ||
    !isPublicationRequestState(value['toState']) ||
    !isNullablePublicationRequestHistoryReason(value['reasonCode']) ||
    !isInstant(value['createdAt']) ||
    !isPublicationHistoryEventShape(value as unknown as PublicationRequestHistoryEvent)
  ) {
    return null;
  }
  return value as unknown as PublicationRequestHistoryEvent;
}

function isPublicationHistoryEventShape(event: PublicationRequestHistoryEvent): boolean {
  switch (event.actionCode) {
    case 'SUBMITTED':
      return (
        event.actorType === 'OWNER' &&
        event.fromState === null &&
        event.toState === 'PENDING_REVIEW' &&
        event.reasonCode === null
      );
    case 'NEEDS_INFORMATION':
      return (
        event.actorType === 'REVIEWER' &&
        event.fromState === 'PENDING_REVIEW' &&
        event.toState === 'NEEDS_INFORMATION' &&
        (event.reasonCode === 'INCOMPLETE_CLAIM' ||
          event.reasonCode === 'MORE_INFORMATION' ||
          event.reasonCode === 'SCOPE_UNCLEAR')
      );
    case 'APPROVED':
      return (
        event.actorType === 'REVIEWER' &&
        event.fromState === 'PENDING_REVIEW' &&
        event.toState === 'APPROVED_UNPUBLISHED' &&
        (event.reasonCode === 'RIGHTS_REVIEWED' || event.reasonCode === 'SCOPE_CONFIRMED')
      );
    case 'REJECTED':
      return (
        event.actorType === 'REVIEWER' &&
        event.fromState === 'PENDING_REVIEW' &&
        event.toState === 'REJECTED' &&
        (event.reasonCode === 'RIGHTS_NOT_VERIFIED' ||
          event.reasonCode === 'OUT_OF_SCOPE' ||
          event.reasonCode === 'POLICY_MISMATCH')
      );
    case 'SUPERSEDED':
      return (
        event.actorType === 'OWNER' &&
        (event.fromState === 'NEEDS_INFORMATION' ||
          event.fromState === 'REJECTED' ||
          event.fromState === 'APPROVED_UNPUBLISHED') &&
        event.toState === 'SUPERSEDED' &&
        event.reasonCode === null
      );
    case 'WITHDRAWN':
      return (
        event.actorType === 'OWNER' &&
        (event.fromState === 'PENDING_REVIEW' || event.fromState === 'NEEDS_INFORMATION') &&
        event.toState === 'WITHDRAWN' &&
        event.reasonCode === null
      );
  }
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
      'hasSavedDraftContent',
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
    typeof value['hasSavedDraftContent'] !== 'boolean' ||
    (value['hasSavedDraftContent'] && value['status'] !== 'draft') ||
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

function isPublicationRightsPolicyVersion(value: unknown): value is string {
  return typeof value === 'string' && /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/.test(value);
}

function isNullablePublicationRightsPolicyVersion(value: unknown): value is string | null {
  return value === null || isPublicationRightsPolicyVersion(value);
}

function isNullableInstant(value: unknown): value is string | null {
  return value === null || isInstant(value);
}

function isPublicationReadinessEtag(value: unknown): value is string {
  return typeof value === 'string' && publicationReadinessEtagPattern.test(value);
}

function isPublicationScopeEtag(value: unknown): value is string {
  return typeof value === 'string' && publicationScopeEtagPattern.test(value);
}

function isNullablePublicationRequestState(
  value: unknown,
): value is PublicationRequestState | null {
  return (
    value === null ||
    value === 'PENDING_REVIEW' ||
    value === 'NEEDS_INFORMATION' ||
    value === 'APPROVED_UNPUBLISHED' ||
    value === 'REJECTED' ||
    value === 'SUPERSEDED' ||
    value === 'WITHDRAWN'
  );
}

function isPublicationRequestState(value: unknown): value is PublicationRequestState {
  return value !== null && isNullablePublicationRequestState(value);
}

function isPublicationRequestHistoryAction(
  value: unknown,
): value is PublicationRequestHistoryAction {
  return (
    value === 'SUBMITTED' ||
    value === 'NEEDS_INFORMATION' ||
    value === 'APPROVED' ||
    value === 'REJECTED' ||
    value === 'SUPERSEDED' ||
    value === 'WITHDRAWN'
  );
}

function isNullablePublicationRequestHistoryReason(
  value: unknown,
): value is PublicationRequestHistoryReason | null {
  return (
    value === null ||
    value === 'INCOMPLETE_CLAIM' ||
    value === 'MORE_INFORMATION' ||
    value === 'SCOPE_UNCLEAR' ||
    value === 'RIGHTS_REVIEWED' ||
    value === 'SCOPE_CONFIRMED' ||
    value === 'RIGHTS_NOT_VERIFIED' ||
    value === 'OUT_OF_SCOPE' ||
    value === 'POLICY_MISMATCH'
  );
}

function isPublicationScopeCode(value: unknown): value is PublicationScopeCode {
  return (
    value === 'PUBLIC_METADATA' ||
    value === 'CHAPTER_TEXT' ||
    value === 'COMMERCIAL_SALE' ||
    value === 'TRANSLATION_ADAPTATION'
  );
}

function isPublicationRightsBasisCode(value: unknown): value is PublicationRightsBasisCode {
  return (
    value === 'ORIGINAL_WORK' ||
    value === 'LICENSE_OR_PERMISSION' ||
    value === 'PUBLIC_DOMAIN' ||
    value === 'NEEDS_REVIEW'
  );
}

function isValidPublicationRequestSubmission(
  value: PublicationRequestSubmission,
): value is PublicationRequestSubmission {
  if (
    !isRecord(value) ||
    hasUnexpectedFields(value, ['policyVersion', 'scopeClaims']) ||
    typeof value['policyVersion'] !== 'string' ||
    !/^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/.test(value['policyVersion']) ||
    !Array.isArray(value['scopeClaims']) ||
    value['scopeClaims'].length < 2 ||
    value['scopeClaims'].length > 3
  ) {
    return false;
  }
  const claims = value['scopeClaims'];
  if (
    claims.some(
      (claim) =>
        !isRecord(claim) ||
        hasUnexpectedFields(claim, ['scopeCode', 'rightsBasisCode']) ||
        !isPublicationScopeCode(claim['scopeCode']) ||
        !isPublicationRightsBasisCode(claim['rightsBasisCode']),
    )
  ) {
    return false;
  }
  const scopeCodes = claims.map((claim) => (claim as PublicationRequestScopeClaim).scopeCode);
  return (
    new Set(scopeCodes).size === scopeCodes.length &&
    scopeCodes.includes('PUBLIC_METADATA') &&
    scopeCodes.includes('CHAPTER_TEXT')
  );
}

function isPublicationReadinessBlocker(value: unknown): value is PublicationReadinessBlocker {
  return (
    value === 'PUBLICATION_RIGHTS_POLICY_NOT_CONFIGURED' ||
    value === 'PUBLICATION_RIGHTS_POLICY_NOT_ACCEPTED' ||
    value === 'READY_CHAPTER_REQUIRED' ||
    value === 'PUBLICATION_REQUEST_ALREADY_PENDING'
  );
}

function isSha256(value: unknown): value is string {
  return typeof value === 'string' && /^[a-f0-9]{64}$/.test(value);
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
    const retryAfterValue = error.headers.get('Retry-After');
    const retryAfterSeconds =
      retryAfterValue && /^[1-9][0-9]{0,5}$/.test(retryAfterValue) ? Number(retryAfterValue) : null;
    return new AuthoringApiError(error.status, code, currentEtag, retryAfterSeconds);
  }
  return new AuthoringApiError(0, null);
}

function invalidResponse(): AuthoringApiError {
  return new AuthoringApiError(502, 'INVALID_MANUSCRIPT_RESPONSE');
}

function invalidOutlineResponse(): AuthoringApiError {
  return new AuthoringApiError(502, 'INVALID_AUTHORING_OUTLINE_RESPONSE');
}

function invalidManuscriptListResponse(): AuthoringApiError {
  return new AuthoringApiError(502, 'INVALID_AUTHORING_MANUSCRIPT_LIST_RESPONSE');
}

function invalidManuscriptDetailResponse(): AuthoringApiError {
  return new AuthoringApiError(502, 'INVALID_AUTHORING_MANUSCRIPT_RESPONSE');
}

function invalidPublicationRightsPolicyResponse(): AuthoringApiError {
  return new AuthoringApiError(502, 'INVALID_PUBLICATION_RIGHTS_POLICY_RESPONSE');
}

function invalidPublicationRightsAcceptanceResponse(): AuthoringApiError {
  return new AuthoringApiError(502, 'INVALID_PUBLICATION_RIGHTS_ACCEPTANCE_RESPONSE');
}

function invalidPublicationReadinessResponse(): AuthoringApiError {
  return new AuthoringApiError(502, 'INVALID_PUBLICATION_READINESS_RESPONSE');
}

function invalidPublicationReleaseResponse(): AuthoringApiError {
  return new AuthoringApiError(502, 'INVALID_PUBLICATION_RELEASE_RESPONSE');
}

function invalidChapterPublicationReleaseResponse(): AuthoringApiError {
  return new AuthoringApiError(502, 'INVALID_CHAPTER_PUBLICATION_RELEASE_RESPONSE');
}

function invalidPublicationRequestResponse(): AuthoringApiError {
  return new AuthoringApiError(502, 'INVALID_PUBLICATION_REQUEST_RESPONSE');
}

function invalidPublicationRequestWithdrawalResponse(): AuthoringApiError {
  return new AuthoringApiError(502, 'INVALID_PUBLICATION_REQUEST_WITHDRAWAL_RESPONSE');
}

function invalidPublicationRequestHistoryResponse(): AuthoringApiError {
  return new AuthoringApiError(502, 'INVALID_PUBLICATION_REQUEST_HISTORY_RESPONSE');
}

function invalidChapterResponse(): AuthoringApiError {
  return new AuthoringApiError(502, 'INVALID_CHAPTER_RESPONSE');
}

function invalidChapterDeleteResponse(): AuthoringApiError {
  return new AuthoringApiError(502, 'INVALID_CHAPTER_DELETE_RESPONSE');
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

function isManuscriptMetadataEtag(value: string | null): value is string {
  if (!value || !/^"manuscript-metadata-[1-9][0-9]*"$/.test(value)) return false;
  const version = Number(value.slice(21, -1));
  return Number.isSafeInteger(version) && version > 0;
}

function manuscriptMetadataEtag(version: number): string {
  return `"manuscript-metadata-${version}"`;
}

function isInstant(value: unknown): value is string {
  return typeof value === 'string' && value.length <= 64 && Number.isFinite(Date.parse(value));
}
