import {
  HttpClient,
  HttpErrorResponse,
  HttpHeaders,
  HttpParams,
  HttpResponse,
} from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom, timeout } from 'rxjs';

export type TranslationReviewAction = 'ACCEPT' | 'REJECT';
export type TranslationReviewReason =
  | 'ACCURATE'
  | 'MINOR_TERMINOLOGY_ISSUE'
  | 'MISSING_CONTENT'
  | 'MEANING_ERROR'
  | 'TERMINOLOGY_ERROR'
  | 'FORMATTING_ERROR'
  | 'OTHER';

export interface TranslationReviewQueueItem {
  submissionId: string;
  workTitle: string;
  chapterTitle: string;
  sourceLanguage: string;
  targetLanguage: string;
  segmentCount: number;
  submitterDisplayName: string;
  submittedAt: string;
  sourceCurrent: boolean;
}

export interface TranslationReviewQueuePage {
  items: TranslationReviewQueueItem[];
  nextCursor: string | null;
  hasMore: boolean;
}

export interface TranslationReviewSegment {
  ordinal: number;
  editRevision: number;
  translatedSha256: string;
  sourceText: string;
  submittedText: string;
}

export interface TranslationReviewDetail {
  submissionId: string;
  status: 'pending_review' | 'accepted' | 'rejected';
  workTitle: string;
  chapterTitle: string;
  submitterDisplayName: string;
  sourceLanguage: string;
  targetLanguage: string;
  snapshotDigest: string;
  segmentCount: number;
  submittedAt: string;
  canDecide: boolean;
  segments: TranslationReviewSegment[];
}

export interface TranslationReviewReadiness {
  jobId: string;
  sourceLanguage: string;
  targetLanguage: string;
  editedSegments: number;
  totalSegments: number;
  missingSegmentOrdinals: number[];
  pendingReview: boolean;
  snapshotDigest: string | null;
  readyForSubmission: boolean;
  blockers: string[];
}

export interface TranslationReviewSubmissionReceipt {
  submissionId: string;
  status: 'pending_review';
  snapshotDigest: string;
  segmentCount: number;
  submittedAt: string;
  replayed: boolean;
}

export interface TranslationReviewDecisionReceipt {
  submissionId: string;
  actionCode: TranslationReviewAction;
  reasonCode: TranslationReviewReason | null;
  snapshotDigest: string;
  decidedAt: string;
  replayed: boolean;
}

export class TranslationReviewApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string | null,
    readonly currentEtag: string | null = null,
  ) {
    super(code ?? 'TRANSLATION_REVIEW_UNAVAILABLE');
    this.name = 'TranslationReviewApiError';
  }
}

const writerBase = '/api/v1/translation/jobs';
const reviewerBase = '/api/v1/review/translations';
const csrfUrl = '/api/v1/auth/csrf';
const digestPattern = /^[a-f0-9]{64}$/;
const reviewEtagPattern = /^"translation-review-[a-f0-9]{64}"$/;
const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const cursorPattern = /^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/;
const idempotencyKeyPattern = /^[A-Za-z0-9._~-]{16,128}$/;
const csrfTokenPattern = /^[A-Za-z0-9_-]{43}$/;
const readTimeoutMs = 6_000;
const writeTimeoutMs = 12_000;
const maxPageSize = 50;
const maxCursorLength = 2_048;
const maxReviewSegments = 100_000;

@Injectable({ providedIn: 'root' })
export class TranslationReviewApiService {
  private readonly http = inject(HttpClient);

  async getReadiness(
    jobId: string,
  ): Promise<{ readiness: TranslationReviewReadiness; etag: string | null }> {
    if (!isUuid(jobId)) throw invalidRequest('INVALID_TRANSLATION_REVIEW_JOB_ID');
    try {
      const response = await firstValueFrom(
        this.http
          .get<unknown>(`${writerBase}/${encodeURIComponent(jobId)}/candidate/review-readiness`, {
            observe: 'response',
          })
          .pipe(timeout(readTimeoutMs)),
      );
      requireNoStore(response);
      const readiness = parseReadiness(response.body);
      const etag = response.headers.get('ETag');
      if (etag !== null && !reviewEtagPattern.test(etag))
        throw invalidRequest('INVALID_REVIEW_ETAG');
      if ((readiness.snapshotDigest !== null) !== (etag !== null)) throw invalidRequest();
      if (
        readiness.readyForSubmission !==
        (readiness.snapshotDigest !== null && readiness.blockers.length === 0)
      )
        throw invalidRequest();
      return { readiness, etag };
    } catch (error) {
      throw toReviewError(error);
    }
  }

  async submit(
    jobId: string,
    etag: string,
    idempotencyKey: string,
  ): Promise<TranslationReviewSubmissionReceipt> {
    if (!isUuid(jobId) || !reviewEtagPattern.test(etag) || !isIdempotencyKey(idempotencyKey)) {
      throw invalidRequest('INVALID_TRANSLATION_REVIEW_SUBMISSION');
    }
    try {
      const response = await firstValueFrom(
        this.http
          .post<unknown>(
            `${writerBase}/${encodeURIComponent(jobId)}/candidate/review-submissions`,
            null,
            {
              headers: (await this.csrfHeaders())
                .set('If-Match', etag)
                .set('Idempotency-Key', idempotencyKey),
              observe: 'response',
            },
          )
          .pipe(timeout(writeTimeoutMs)),
      );
      requireNoStore(response);
      const receipt = parseSubmissionReceipt(response.body);
      if (
        response.status !== (receipt.replayed ? 200 : 201) ||
        response.headers.get('ETag') !== `"translation-review-${receipt.snapshotDigest}"`
      ) {
        throw invalidRequest('INVALID_TRANSLATION_REVIEW_SUBMISSION_RESPONSE');
      }
      return receipt;
    } catch (error) {
      throw toReviewError(error);
    }
  }

  async getQueue(cursor: string | null = null, limit = 20): Promise<TranslationReviewQueuePage> {
    if (
      !Number.isInteger(limit) ||
      limit < 1 ||
      limit > maxPageSize ||
      (cursor !== null && (cursor.length > maxCursorLength || !cursorPattern.test(cursor)))
    ) {
      throw invalidRequest('INVALID_TRANSLATION_REVIEW_QUEUE_REQUEST');
    }
    let params = new HttpParams().set('limit', String(limit));
    if (cursor !== null) params = params.set('cursor', cursor);
    try {
      const response = await firstValueFrom(
        this.http
          .get<unknown>(reviewerBase, { params, observe: 'response' })
          .pipe(timeout(readTimeoutMs)),
      );
      requireNoStore(response);
      return parseQueuePage(response.body, limit);
    } catch (error) {
      throw toReviewError(error);
    }
  }

  async getDetail(
    submissionId: string,
  ): Promise<{ detail: TranslationReviewDetail; etag: string }> {
    if (!isUuid(submissionId)) throw invalidRequest('INVALID_TRANSLATION_REVIEW_SUBMISSION_ID');
    try {
      const response = await firstValueFrom(
        this.http
          .get<unknown>(`${reviewerBase}/${encodeURIComponent(submissionId)}`, {
            observe: 'response',
          })
          .pipe(timeout(readTimeoutMs)),
      );
      requireNoStore(response);
      const etag = response.headers.get('ETag');
      if (!etag || !reviewEtagPattern.test(etag))
        throw invalidRequest('INVALID_TRANSLATION_REVIEW_ETAG');
      return { detail: parseDetail(response.body), etag };
    } catch (error) {
      throw toReviewError(error);
    }
  }

  async decide(
    submissionId: string,
    etag: string,
    idempotencyKey: string,
    actionCode: TranslationReviewAction,
    reasonCode: TranslationReviewReason | null,
  ): Promise<TranslationReviewDecisionReceipt> {
    if (
      !isUuid(submissionId) ||
      !reviewEtagPattern.test(etag) ||
      !isIdempotencyKey(idempotencyKey) ||
      !isValidDecision(actionCode, reasonCode)
    ) {
      throw invalidRequest('INVALID_TRANSLATION_REVIEW_DECISION');
    }
    try {
      const headers = (await this.csrfHeaders())
        .set('If-Match', etag)
        .set('Idempotency-Key', idempotencyKey);
      const body = reasonCode === null ? { actionCode } : { actionCode, reasonCode };
      const response = await firstValueFrom(
        this.http
          .post<unknown>(`${reviewerBase}/${encodeURIComponent(submissionId)}/decisions`, body, {
            headers,
            observe: 'response',
          })
          .pipe(timeout(writeTimeoutMs)),
      );
      requireNoStore(response);
      const receipt = parseDecisionReceipt(response.body, actionCode, reasonCode);
      if (
        response.status !== (receipt.replayed ? 200 : 201) ||
        response.headers.get('ETag') !== `"translation-review-${receipt.snapshotDigest}"`
      ) {
        throw invalidRequest('INVALID_TRANSLATION_REVIEW_DECISION_RESPONSE');
      }
      return receipt;
    } catch (error) {
      throw toReviewError(error);
    }
  }

  private async csrfHeaders(): Promise<HttpHeaders> {
    const response: unknown = await firstValueFrom(
      this.http.get<unknown>(csrfUrl).pipe(timeout(readTimeoutMs)),
    );
    if (
      !isRecord(response) ||
      typeof response['token'] !== 'string' ||
      !csrfTokenPattern.test(response['token'])
    ) {
      throw invalidRequest('INVALID_CSRF_RESPONSE');
    }
    return new HttpHeaders({ 'X-CSRF-Token': response['token'] });
  }
}

function parseReadiness(value: unknown): TranslationReviewReadiness {
  if (
    !hasExactKeys(
      value,
      'blockers,candidateId,editedSegments,jobId,missingSegmentOrdinals,pendingReview,readyForSubmission,snapshotDigest,sourceLanguage,sourceRevisionCurrent,sourceRevisionId,targetLanguage,totalSegments',
    ) ||
    !isUuid(value['jobId']) ||
    !isUuid(value['candidateId']) ||
    !isUuid(value['sourceRevisionId']) ||
    value['sourceRevisionCurrent'] !== true ||
    !isText(value['sourceLanguage'], 35) ||
    !isText(value['targetLanguage'], 35) ||
    !isNonNegativeInteger(value['editedSegments']) ||
    !isPositiveInteger(value['totalSegments']) ||
    value['editedSegments'] > value['totalSegments'] ||
    !Array.isArray(value['missingSegmentOrdinals']) ||
    value['missingSegmentOrdinals'].some(
      (item) => !isPositiveInteger(item) || item > (value['totalSegments'] as number),
    ) ||
    typeof value['pendingReview'] !== 'boolean' ||
    typeof value['readyForSubmission'] !== 'boolean' ||
    !(value['snapshotDigest'] === null || isDigest(value['snapshotDigest'])) ||
    !Array.isArray(value['blockers']) ||
    value['blockers'].some((item) => !['INCOMPLETE_MANUAL_EDITS', 'PENDING_REVIEW'].includes(item))
  )
    throw invalidRequest('INVALID_TRANSLATION_REVIEW_READINESS_RESPONSE');
  return {
    jobId: value['jobId'],
    sourceLanguage: value['sourceLanguage'],
    targetLanguage: value['targetLanguage'],
    editedSegments: value['editedSegments'],
    totalSegments: value['totalSegments'],
    missingSegmentOrdinals: value['missingSegmentOrdinals'],
    pendingReview: value['pendingReview'],
    snapshotDigest: value['snapshotDigest'],
    readyForSubmission: value['readyForSubmission'],
    blockers: value['blockers'],
  };
}

function parseSubmissionReceipt(value: unknown): TranslationReviewSubmissionReceipt {
  if (
    !hasExactKeys(
      value,
      'candidateId,replayed,segmentCount,snapshotDigest,status,submissionId,submittedAt',
    ) ||
    !isUuid(value['submissionId']) ||
    !isUuid(value['candidateId']) ||
    value['status'] !== 'pending_review' ||
    !isDigest(value['snapshotDigest']) ||
    !isPositiveInteger(value['segmentCount']) ||
    !isDateTime(value['submittedAt']) ||
    typeof value['replayed'] !== 'boolean'
  )
    throw invalidRequest('INVALID_TRANSLATION_REVIEW_SUBMISSION_RESPONSE');
  return {
    submissionId: value['submissionId'],
    status: 'pending_review',
    snapshotDigest: value['snapshotDigest'],
    segmentCount: value['segmentCount'],
    submittedAt: value['submittedAt'],
    replayed: value['replayed'],
  };
}

function parseQueuePage(value: unknown, requestedLimit: number): TranslationReviewQueuePage {
  if (
    !hasExactKeys(value, 'hasMore,items,nextCursor') ||
    !Array.isArray(value['items']) ||
    value['items'].length > requestedLimit ||
    typeof value['hasMore'] !== 'boolean' ||
    !(value['nextCursor'] === null || isCursor(value['nextCursor'])) ||
    value['hasMore'] !== (value['nextCursor'] !== null)
  )
    throw invalidRequest('INVALID_TRANSLATION_REVIEW_QUEUE_RESPONSE');
  const items = value['items'].map(parseQueueItem);
  if (new Set(items.map((item) => item.submissionId)).size !== items.length) throw invalidRequest();
  return { items, nextCursor: value['nextCursor'], hasMore: value['hasMore'] };
}

function parseQueueItem(value: unknown): TranslationReviewQueueItem {
  if (
    !hasExactKeys(
      value,
      'chapterTitle,segmentCount,sourceCurrent,sourceLanguage,submissionId,submittedAt,submitterDisplayName,targetLanguage,workTitle',
    ) ||
    !isUuid(value['submissionId']) ||
    !isText(value['workTitle'], 500) ||
    !isText(value['chapterTitle'], 500) ||
    !isText(value['sourceLanguage'], 35) ||
    !isText(value['targetLanguage'], 35) ||
    !isPositiveInteger(value['segmentCount']) ||
    !isText(value['submitterDisplayName'], 200) ||
    !isDateTime(value['submittedAt']) ||
    typeof value['sourceCurrent'] !== 'boolean'
  )
    throw invalidRequest('INVALID_TRANSLATION_REVIEW_QUEUE_ITEM');
  return {
    submissionId: value['submissionId'],
    workTitle: value['workTitle'],
    chapterTitle: value['chapterTitle'],
    sourceLanguage: value['sourceLanguage'],
    targetLanguage: value['targetLanguage'],
    segmentCount: value['segmentCount'],
    submitterDisplayName: value['submitterDisplayName'],
    submittedAt: value['submittedAt'],
    sourceCurrent: value['sourceCurrent'],
  };
}

function parseDetail(value: unknown): TranslationReviewDetail {
  if (
    !hasExactKeys(
      value,
      'canDecide,chapterTitle,segmentCount,segments,snapshotDigest,sourceLanguage,status,submittedAt,submitterDisplayName,submissionId,targetLanguage,workTitle',
    ) ||
    !isUuid(value['submissionId']) ||
    !isReviewStatus(value['status']) ||
    !isText(value['workTitle'], 500) ||
    !isText(value['chapterTitle'], 500) ||
    !isText(value['submitterDisplayName'], 200) ||
    !isText(value['sourceLanguage'], 35) ||
    !isText(value['targetLanguage'], 35) ||
    !isDigest(value['snapshotDigest']) ||
    !isPositiveInteger(value['segmentCount']) ||
    value['segmentCount'] > maxReviewSegments ||
    !isDateTime(value['submittedAt']) ||
    typeof value['canDecide'] !== 'boolean' ||
    !Array.isArray(value['segments']) ||
    value['segments'].length !== value['segmentCount']
  )
    throw invalidRequest('INVALID_TRANSLATION_REVIEW_DETAIL_RESPONSE');
  const segments = value['segments'].map(parseReviewSegment);
  if (segments.some((segment, index) => segment.ordinal !== index + 1)) throw invalidRequest();
  return {
    submissionId: value['submissionId'],
    status: value['status'],
    workTitle: value['workTitle'],
    chapterTitle: value['chapterTitle'],
    submitterDisplayName: value['submitterDisplayName'],
    sourceLanguage: value['sourceLanguage'],
    targetLanguage: value['targetLanguage'],
    snapshotDigest: value['snapshotDigest'],
    segmentCount: value['segmentCount'],
    submittedAt: value['submittedAt'],
    canDecide: value['canDecide'],
    segments,
  };
}

function parseReviewSegment(value: unknown): TranslationReviewSegment {
  if (
    !hasExactKeys(value, 'editRevision,ordinal,sourceText,submittedText,translatedSha256') ||
    !isPositiveInteger(value['ordinal']) ||
    !isPositiveInteger(value['editRevision']) ||
    !isDigest(value['translatedSha256']) ||
    typeof value['sourceText'] !== 'string' ||
    typeof value['submittedText'] !== 'string'
  )
    throw invalidRequest('INVALID_TRANSLATION_REVIEW_SEGMENT');
  return {
    ordinal: value['ordinal'],
    editRevision: value['editRevision'],
    translatedSha256: value['translatedSha256'],
    sourceText: value['sourceText'],
    submittedText: value['submittedText'],
  };
}

function parseDecisionReceipt(
  value: unknown,
  actionCode: TranslationReviewAction,
  reasonCode: TranslationReviewReason | null,
): TranslationReviewDecisionReceipt {
  if (
    !hasExactKeys(
      value,
      'actionCode,decidedAt,decisionId,reasonCode,replayed,snapshotDigest,submissionId',
    ) ||
    !isUuid(value['decisionId']) ||
    !isUuid(value['submissionId']) ||
    value['actionCode'] !== actionCode ||
    value['reasonCode'] !== reasonCode ||
    !isDigest(value['snapshotDigest']) ||
    !isDateTime(value['decidedAt']) ||
    typeof value['replayed'] !== 'boolean'
  )
    throw invalidRequest('INVALID_TRANSLATION_REVIEW_DECISION_RESPONSE');
  return {
    submissionId: value['submissionId'],
    actionCode,
    reasonCode,
    snapshotDigest: value['snapshotDigest'],
    decidedAt: value['decidedAt'],
    replayed: value['replayed'],
  };
}

function isValidDecision(
  action: TranslationReviewAction,
  reason: TranslationReviewReason | null,
): boolean {
  return action === 'ACCEPT'
    ? reason === null || reason === 'ACCURATE' || reason === 'MINOR_TERMINOLOGY_ISSUE'
    : action === 'REJECT' &&
        reason !== null &&
        [
          'MISSING_CONTENT',
          'MEANING_ERROR',
          'TERMINOLOGY_ERROR',
          'FORMATTING_ERROR',
          'OTHER',
        ].includes(reason);
}

function isReviewStatus(value: unknown): value is TranslationReviewDetail['status'] {
  return value === 'pending_review' || value === 'accepted' || value === 'rejected';
}

function requireNoStore(response: HttpResponse<unknown>): void {
  if (!response.headers.get('Cache-Control')?.toLowerCase().includes('no-store')) {
    throw invalidRequest('INVALID_TRANSLATION_REVIEW_CACHE_POLICY');
  }
}

function toReviewError(error: unknown): TranslationReviewApiError {
  if (error instanceof TranslationReviewApiError) return error;
  if (error instanceof HttpErrorResponse) {
    const body = isRecord(error.error) ? error.error : null;
    const code = body && typeof body['code'] === 'string' ? body['code'] : null;
    const currentEtag =
      (body && typeof body['currentEtag'] === 'string' ? body['currentEtag'] : null) ??
      error.headers.get('ETag');
    return new TranslationReviewApiError(error.status, code, currentEtag);
  }
  return new TranslationReviewApiError(0, 'TRANSLATION_REVIEW_UNAVAILABLE');
}

function invalidRequest(code = 'INVALID_TRANSLATION_REVIEW_RESPONSE'): TranslationReviewApiError {
  return new TranslationReviewApiError(0, code);
}

function hasExactKeys(value: unknown, expected: string): value is Record<string, unknown> {
  if (!isRecord(value)) return false;
  const keys = Object.keys(value).sort().join(',');
  return keys === expected.split(',').sort().join(',');
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
function isUuid(value: unknown): value is string {
  return typeof value === 'string' && uuidPattern.test(value);
}
function isDigest(value: unknown): value is string {
  return typeof value === 'string' && digestPattern.test(value);
}
function isCursor(value: unknown): value is string {
  return typeof value === 'string' && value.length <= maxCursorLength && cursorPattern.test(value);
}
function isIdempotencyKey(value: string): boolean {
  return idempotencyKeyPattern.test(value);
}
function isPositiveInteger(value: unknown): value is number {
  return Number.isSafeInteger(value) && (value as number) > 0;
}
function isNonNegativeInteger(value: unknown): value is number {
  return Number.isSafeInteger(value) && (value as number) >= 0;
}
function isText(value: unknown, max: number): value is string {
  return typeof value === 'string' && value.length > 0 && value.length <= max;
}
function isDateTime(value: unknown): value is string {
  return typeof value === 'string' && Number.isFinite(Date.parse(value));
}
