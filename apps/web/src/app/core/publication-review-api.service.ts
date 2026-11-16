import {
  HttpClient,
  HttpErrorResponse,
  HttpHeaders,
  HttpParams,
  HttpResponse,
} from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom, timeout } from 'rxjs';

export type PublicationReviewScope = 'PUBLIC_METADATA' | 'CHAPTER_TEXT' | 'COMMERCIAL_SALE';
export type PublicationRightsBasis =
  'ORIGINAL_WORK' | 'LICENSE_OR_PERMISSION' | 'PUBLIC_DOMAIN' | 'NEEDS_REVIEW';
export type PublicationReviewState =
  | 'PENDING_REVIEW'
  | 'NEEDS_INFORMATION'
  | 'APPROVED_UNPUBLISHED'
  | 'REJECTED'
  | 'SUPERSEDED'
  | 'WITHDRAWN';
export type PublicationReviewAction = 'NEEDS_INFORMATION' | 'APPROVED' | 'REJECTED';
export type PublicationReviewReason =
  | 'INCOMPLETE_CLAIM'
  | 'MORE_INFORMATION'
  | 'SCOPE_UNCLEAR'
  | 'RIGHTS_REVIEWED'
  | 'SCOPE_CONFIRMED'
  | 'RIGHTS_NOT_VERIFIED'
  | 'OUT_OF_SCOPE'
  | 'POLICY_MISMATCH';

export interface PublicationReviewQueueItem {
  requestId: string;
  requestVersion: number;
  submittedAt: string;
  policyVersion: string;
  snapshotSha256: string;
  title: string;
  author: string;
  sourceLanguage: string;
  scopeCodes: PublicationReviewScope[];
}

export interface PublicationReviewQueuePage {
  items: PublicationReviewQueueItem[];
  nextCursor: string | null;
}

export interface PublicationReviewClaim {
  scopeCode: PublicationReviewScope;
  rightsBasisCode: PublicationRightsBasis;
}

export interface PublicationReviewWorkPreview {
  title: string;
  author: string;
  sourceLanguage: string;
}

export interface PublicationReviewChapterPreview {
  ordinal: number;
  title: string;
  sourceLanguage: string;
  contentHash: string | null;
  segmentCount: number | null;
}

export interface PublicationReviewScopePreview {
  scopeCode: PublicationReviewScope;
  snapshotSha256: string;
  work: PublicationReviewWorkPreview;
  chapters: PublicationReviewChapterPreview[];
}

export interface PublicationReviewEvent {
  sequence: number;
  actorRoleCode: 'author' | 'publication_reviewer';
  actionCode:
    'SUBMITTED' | 'NEEDS_INFORMATION' | 'APPROVED' | 'REJECTED' | 'SUPERSEDED' | 'WITHDRAWN';
  fromState: PublicationReviewState | null;
  toState: PublicationReviewState;
  reasonCode: PublicationReviewReason | null;
  createdAt: string;
}

export interface PublicationReviewDetail {
  requestId: string;
  requestVersion: number;
  submittedAt: string;
  policyVersion: string;
  state: PublicationReviewState;
  snapshotSha256: string;
  scopes: PublicationReviewScopePreview[];
  claims: PublicationReviewClaim[];
  events: PublicationReviewEvent[];
}

export interface PublicationReviewDecisionReceipt {
  eventSequence: number;
  actorRoleCode: 'publication_reviewer';
  actionCode: PublicationReviewAction;
  fromState: 'PENDING_REVIEW';
  toState: 'NEEDS_INFORMATION' | 'APPROVED_UNPUBLISHED' | 'REJECTED';
  reasonCode: PublicationReviewReason;
  decidedAt: string;
}

export class PublicationReviewApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code: string | null,
    readonly retryAfterSeconds: number | null = null,
  ) {
    super(message);
    this.name = 'PublicationReviewApiError';
  }
}

const baseUrl = '/api/v1/review/publications';
const csrfUrl = '/api/v1/auth/csrf';
const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const sha256Pattern = /^[a-f0-9]{64}$/;
const etagPattern = /^"publication-review-[a-f0-9]{64}"$/;
const csrfTokenPattern = /^[A-Za-z0-9_-]{43}$/;
const idempotencyKeyPattern = /^[A-Za-z0-9._~-]{16,128}$/;
const cursorPattern = /^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/;
const readTimeoutMs = 5_000;
const writeTimeoutMs = 12_000;
const maxQueuePageSize = 50;
const maxCursorLength = 2_048;
const maxChapterPreviews = 50_000;
const maxReviewEvents = 10_000;

@Injectable({ providedIn: 'root' })
export class PublicationReviewApiService {
  private readonly http = inject(HttpClient);

  async getQueue(cursor: string | null = null, limit = 20): Promise<PublicationReviewQueuePage> {
    if (!Number.isInteger(limit) || limit < 1 || limit > maxQueuePageSize) {
      throw invalidResponse('INVALID_REVIEW_QUEUE_LIMIT');
    }
    if (cursor !== null && (cursor.length > maxCursorLength || !cursorPattern.test(cursor))) {
      throw invalidResponse('INVALID_REVIEW_QUEUE_CURSOR');
    }
    let params = new HttpParams().set('limit', String(limit));
    if (cursor !== null) params = params.set('cursor', cursor);
    try {
      const response: unknown = await firstValueFrom(
        this.http.get<unknown>(baseUrl, { params }).pipe(timeout(readTimeoutMs)),
      );
      return parseQueuePage(response);
    } catch (error) {
      throw toPublicationReviewError(error);
    }
  }

  async getDetail(requestId: string): Promise<{ detail: PublicationReviewDetail; etag: string }> {
    if (!isUuid(requestId)) throw invalidResponse('INVALID_REVIEW_REQUEST_ID');
    try {
      const response: HttpResponse<unknown> = await firstValueFrom(
        this.http
          .get<unknown>(`${baseUrl}/${encodeURIComponent(requestId)}`, { observe: 'response' })
          .pipe(timeout(readTimeoutMs)),
      );
      const etag = response.headers.get('ETag');
      if (!etag || !etagPattern.test(etag)) throw invalidResponse('INVALID_REVIEW_ETAG');
      return { detail: parseDetail(response.body), etag };
    } catch (error) {
      throw toPublicationReviewError(error);
    }
  }

  async decide(
    requestId: string,
    etag: string,
    idempotencyKey: string,
    actionCode: PublicationReviewAction,
    reasonCode: PublicationReviewReason,
  ): Promise<PublicationReviewDecisionReceipt> {
    if (
      !isUuid(requestId) ||
      !etagPattern.test(etag) ||
      !idempotencyKeyPattern.test(idempotencyKey) ||
      !isReasonForAction(actionCode, reasonCode)
    ) {
      throw invalidResponse('INVALID_REVIEW_DECISION');
    }
    try {
      const tokenResponse: unknown = await firstValueFrom(
        this.http.get<unknown>(csrfUrl).pipe(timeout(readTimeoutMs)),
      );
      if (!isRecord(tokenResponse) || !isCsrfToken(tokenResponse['token'])) {
        throw invalidResponse('INVALID_CSRF_RESPONSE');
      }
      const headers = new HttpHeaders({
        'X-CSRF-Token': tokenResponse['token'],
        'If-Match': etag,
        'Idempotency-Key': idempotencyKey,
      });
      const response: unknown = await firstValueFrom(
        this.http
          .post<unknown>(
            `${baseUrl}/${encodeURIComponent(requestId)}/decisions`,
            { actionCode, reasonCode },
            { headers, observe: 'response' },
          )
          .pipe(timeout(writeTimeoutMs)),
      );
      if (!(response instanceof HttpResponse) || response.status !== 201) {
        throw invalidResponse('INVALID_REVIEW_DECISION_RESPONSE');
      }
      return parseDecisionReceipt(response.body, actionCode, reasonCode);
    } catch (error) {
      throw toPublicationReviewError(error);
    }
  }
}

function parseQueuePage(value: unknown): PublicationReviewQueuePage {
  if (
    !hasExactKeys(value, 'items,nextCursor') ||
    !Array.isArray(value['items']) ||
    value['items'].length > maxQueuePageSize ||
    !(value['nextCursor'] === null || isCursor(value['nextCursor']))
  ) {
    throw invalidResponse();
  }
  const items = value['items'].map(parseQueueItem);
  if (new Set(items.map(({ requestId }) => requestId)).size !== items.length) {
    throw invalidResponse();
  }
  return { items, nextCursor: value['nextCursor'] };
}

function parseQueueItem(value: unknown): PublicationReviewQueueItem {
  if (
    !hasExactKeys(
      value,
      'author,policyVersion,requestId,requestVersion,scopeCodes,snapshotSha256,sourceLanguage,submittedAt,title',
    ) ||
    !isUuid(value['requestId']) ||
    !isPositiveInteger(value['requestVersion']) ||
    !isDateTime(value['submittedAt']) ||
    !isText(value['policyVersion'], 64) ||
    !isSha256(value['snapshotSha256']) ||
    !isText(value['title'], 500) ||
    !isText(value['author'], 200) ||
    !isText(value['sourceLanguage'], 35) ||
    !isScopeList(value['scopeCodes'])
  ) {
    throw invalidResponse();
  }
  return {
    requestId: value['requestId'],
    requestVersion: value['requestVersion'],
    submittedAt: value['submittedAt'],
    policyVersion: value['policyVersion'],
    snapshotSha256: value['snapshotSha256'],
    title: value['title'],
    author: value['author'],
    sourceLanguage: value['sourceLanguage'],
    scopeCodes: value['scopeCodes'],
  };
}

function parseDetail(value: unknown): PublicationReviewDetail {
  if (
    !hasExactKeys(
      value,
      'claims,events,policyVersion,requestId,requestVersion,scopes,snapshotSha256,state,submittedAt',
    ) ||
    !isUuid(value['requestId']) ||
    !isPositiveInteger(value['requestVersion']) ||
    !isDateTime(value['submittedAt']) ||
    !isText(value['policyVersion'], 64) ||
    !isReviewState(value['state']) ||
    !isSha256(value['snapshotSha256']) ||
    !Array.isArray(value['scopes']) ||
    value['scopes'].length < 2 ||
    value['scopes'].length > 3 ||
    !Array.isArray(value['claims']) ||
    value['claims'].length < 2 ||
    value['claims'].length > 3 ||
    !Array.isArray(value['events']) ||
    value['events'].length < 1 ||
    value['events'].length > maxReviewEvents
  ) {
    throw invalidResponse();
  }
  const scopes = value['scopes'].map(parseScopePreview);
  const claims = value['claims'].map(parseClaim);
  const events = value['events'].map(parseReviewEvent);
  if (
    new Set(scopes.map(({ scopeCode }) => scopeCode)).size !== scopes.length ||
    new Set(claims.map(({ scopeCode }) => scopeCode)).size !== claims.length ||
    scopes.some((scope) => !claims.some((claim) => claim.scopeCode === scope.scopeCode)) ||
    new Set(events.map(({ sequence }) => sequence)).size !== events.length
  ) {
    throw invalidResponse();
  }
  return {
    requestId: value['requestId'],
    requestVersion: value['requestVersion'],
    submittedAt: value['submittedAt'],
    policyVersion: value['policyVersion'],
    state: value['state'],
    snapshotSha256: value['snapshotSha256'],
    scopes,
    claims,
    events,
  };
}

function parseScopePreview(value: unknown): PublicationReviewScopePreview {
  if (
    !hasExactKeys(value, 'chapters,scopeCode,snapshotSha256,work') ||
    !isScope(value['scopeCode']) ||
    !isSha256(value['snapshotSha256']) ||
    !hasExactKeys(value['work'], 'author,sourceLanguage,title') ||
    !isText(value['work']['title'], 500) ||
    !isText(value['work']['author'], 200) ||
    !isText(value['work']['sourceLanguage'], 35) ||
    !Array.isArray(value['chapters']) ||
    value['chapters'].length > maxChapterPreviews
  ) {
    throw invalidResponse();
  }
  return {
    scopeCode: value['scopeCode'],
    snapshotSha256: value['snapshotSha256'],
    work: {
      title: value['work']['title'],
      author: value['work']['author'],
      sourceLanguage: value['work']['sourceLanguage'],
    },
    chapters: value['chapters'].map(parseChapterPreview),
  };
}

function parseChapterPreview(value: unknown): PublicationReviewChapterPreview {
  if (
    !hasExactKeys(value, 'contentHash,ordinal,segmentCount,sourceLanguage,title') ||
    !isPositiveInteger(value['ordinal']) ||
    !isText(value['title'], 500) ||
    !isText(value['sourceLanguage'], 35) ||
    !(value['contentHash'] === null || isSha256(value['contentHash'])) ||
    !(value['segmentCount'] === null || isNonNegativeInteger(value['segmentCount']))
  ) {
    throw invalidResponse();
  }
  return {
    ordinal: value['ordinal'],
    title: value['title'],
    sourceLanguage: value['sourceLanguage'],
    contentHash: value['contentHash'],
    segmentCount: value['segmentCount'],
  };
}

function parseClaim(value: unknown): PublicationReviewClaim {
  if (
    !hasExactKeys(value, 'rightsBasisCode,scopeCode') ||
    !isScope(value['scopeCode']) ||
    !isRightsBasis(value['rightsBasisCode'])
  ) {
    throw invalidResponse();
  }
  return { scopeCode: value['scopeCode'], rightsBasisCode: value['rightsBasisCode'] };
}

function parseReviewEvent(value: unknown): PublicationReviewEvent {
  if (
    !hasExactKeys(
      value,
      'actionCode,actorRoleCode,createdAt,fromState,reasonCode,sequence,toState',
    ) ||
    !isPositiveInteger(value['sequence']) ||
    !['author', 'publication_reviewer'].includes(String(value['actorRoleCode'])) ||
    !isEventAction(value['actionCode']) ||
    !(value['fromState'] === null || isReviewState(value['fromState'])) ||
    !isReviewState(value['toState']) ||
    !(value['reasonCode'] === null || isReviewReason(value['reasonCode'])) ||
    !isDateTime(value['createdAt'])
  ) {
    throw invalidResponse();
  }
  return {
    sequence: value['sequence'],
    actorRoleCode: value['actorRoleCode'],
    actionCode: value['actionCode'],
    fromState: value['fromState'],
    toState: value['toState'],
    reasonCode: value['reasonCode'],
    createdAt: value['createdAt'],
  };
}

function parseDecisionReceipt(
  value: unknown,
  actionCode: PublicationReviewAction,
  reasonCode: PublicationReviewReason,
): PublicationReviewDecisionReceipt {
  if (
    !hasExactKeys(
      value,
      'actionCode,actorRoleCode,decidedAt,eventSequence,fromState,reasonCode,toState',
    ) ||
    !isPositiveInteger(value['eventSequence']) ||
    value['actorRoleCode'] !== 'publication_reviewer' ||
    value['actionCode'] !== actionCode ||
    value['fromState'] !== 'PENDING_REVIEW' ||
    !isDecisionToState(value['toState']) ||
    value['toState'] !== toStateForAction(actionCode) ||
    value['reasonCode'] !== reasonCode ||
    !isDateTime(value['decidedAt'])
  ) {
    throw invalidResponse('INVALID_REVIEW_DECISION_RECEIPT');
  }
  return {
    eventSequence: value['eventSequence'],
    actorRoleCode: 'publication_reviewer',
    actionCode,
    fromState: 'PENDING_REVIEW',
    toState: value['toState'],
    reasonCode,
    decidedAt: value['decidedAt'],
  };
}

function toPublicationReviewError(error: unknown): PublicationReviewApiError {
  if (error instanceof PublicationReviewApiError) return error;
  if (error instanceof HttpErrorResponse) {
    const body = isRecord(error.error) ? error.error : null;
    return new PublicationReviewApiError(
      'PUBLICATION_REVIEW_REQUEST_FAILED',
      error.status,
      body && typeof body['code'] === 'string' ? body['code'] : null,
      parseRetryAfter(error.headers.get('Retry-After')),
    );
  }
  return new PublicationReviewApiError('PUBLICATION_REVIEW_REQUEST_FAILED', 0, null);
}

function hasExactKeys(value: unknown, expected: string): value is Record<string, any> {
  if (!isRecord(value)) return false;
  return Object.keys(value).sort().join(',') === expected.split(',').sort().join(',');
}

function isRecord(value: unknown): value is Record<string, any> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isUuid(value: unknown): value is string {
  return typeof value === 'string' && uuidPattern.test(value);
}

function isSha256(value: unknown): value is string {
  return typeof value === 'string' && sha256Pattern.test(value);
}

function isScope(value: unknown): value is PublicationReviewScope {
  return value === 'PUBLIC_METADATA' || value === 'CHAPTER_TEXT' || value === 'COMMERCIAL_SALE';
}

function isScopeList(value: unknown): value is PublicationReviewScope[] {
  return (
    Array.isArray(value) &&
    value.length >= 2 &&
    value.length <= 3 &&
    value.every(isScope) &&
    new Set(value).size === value.length
  );
}

function isRightsBasis(value: unknown): value is PublicationRightsBasis {
  return (
    value === 'ORIGINAL_WORK' ||
    value === 'LICENSE_OR_PERMISSION' ||
    value === 'PUBLIC_DOMAIN' ||
    value === 'NEEDS_REVIEW'
  );
}

function isReviewState(value: unknown): value is PublicationReviewState {
  return (
    value === 'PENDING_REVIEW' ||
    value === 'NEEDS_INFORMATION' ||
    value === 'APPROVED_UNPUBLISHED' ||
    value === 'REJECTED' ||
    value === 'SUPERSEDED' ||
    value === 'WITHDRAWN'
  );
}

function isEventAction(value: unknown): value is PublicationReviewEvent['actionCode'] {
  return (
    value === 'SUBMITTED' ||
    value === 'NEEDS_INFORMATION' ||
    value === 'APPROVED' ||
    value === 'REJECTED' ||
    value === 'SUPERSEDED' ||
    value === 'WITHDRAWN'
  );
}

function isReviewReason(value: unknown): value is PublicationReviewReason {
  return (
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

function isDecisionToState(value: unknown): value is PublicationReviewDecisionReceipt['toState'] {
  return value === 'NEEDS_INFORMATION' || value === 'APPROVED_UNPUBLISHED' || value === 'REJECTED';
}

function isReasonForAction(
  action: PublicationReviewAction,
  reason: PublicationReviewReason,
): boolean {
  if (action === 'NEEDS_INFORMATION') {
    return ['INCOMPLETE_CLAIM', 'MORE_INFORMATION', 'SCOPE_UNCLEAR'].includes(reason);
  }
  if (action === 'APPROVED') return ['RIGHTS_REVIEWED', 'SCOPE_CONFIRMED'].includes(reason);
  return ['RIGHTS_NOT_VERIFIED', 'OUT_OF_SCOPE', 'POLICY_MISMATCH'].includes(reason);
}

function toStateForAction(
  action: PublicationReviewAction,
): PublicationReviewDecisionReceipt['toState'] {
  switch (action) {
    case 'NEEDS_INFORMATION':
      return 'NEEDS_INFORMATION';
    case 'APPROVED':
      return 'APPROVED_UNPUBLISHED';
    case 'REJECTED':
      return 'REJECTED';
  }
}

function isCursor(value: unknown): value is string {
  return typeof value === 'string' && value.length <= maxCursorLength && cursorPattern.test(value);
}

function isCsrfToken(value: unknown): value is string {
  return typeof value === 'string' && csrfTokenPattern.test(value);
}

function isText(value: unknown, maxLength: number): value is string {
  return typeof value === 'string' && value.trim().length > 0 && value.length <= maxLength;
}

function isDateTime(value: unknown): value is string {
  return typeof value === 'string' && Number.isFinite(Date.parse(value));
}

function isPositiveInteger(value: unknown): value is number {
  return Number.isSafeInteger(value) && Number(value) > 0;
}

function isNonNegativeInteger(value: unknown): value is number {
  return Number.isSafeInteger(value) && Number(value) >= 0;
}

function parseRetryAfter(value: string | null): number | null {
  if (!value) return null;
  const seconds = /^\d{1,6}$/.test(value)
    ? Number(value)
    : Math.ceil((Date.parse(value) - Date.now()) / 1000);
  return Number.isFinite(seconds) && seconds > 0 ? Math.min(seconds, 86_400) : null;
}

function invalidResponse(code = 'INVALID_PUBLICATION_REVIEW_RESPONSE'): PublicationReviewApiError {
  return new PublicationReviewApiError(code, 0, code);
}
