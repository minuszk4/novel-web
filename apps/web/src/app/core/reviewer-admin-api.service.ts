import { HttpClient, HttpErrorResponse, HttpHeaders, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom, timeout } from 'rxjs';

export type ReviewerAssignmentStatus = 'active' | 'revoked' | 'all';
export type ReviewerGrantReason = 'REVIEW_CAPACITY' | 'TEMPORARY_COVERAGE' | 'ROLE_CORRECTION';
export type ReviewerRevokeReason = 'ACCESS_ENDED' | 'ROLE_CHANGE' | 'SECURITY_REVIEW';

export interface ReviewerAssignment {
  assignmentId: string;
  userId: string;
  displayName: string;
  maskedContactEmail: string | null;
  userStatus: 'active' | 'pending' | 'suspended' | 'deleted';
  assignedByUserId: string | null;
  assignedAt: string;
  reasonCode: ReviewerGrantReason;
  revokedAt: string | null;
  revokedByUserId: string | null;
  revocationReasonCode: ReviewerRevokeReason | null;
  lastEventId: string | null;
  lastEventActionCode: 'GRANTED' | 'REVOKED' | null;
  lastEventActorUserId: string | null;
  lastEventOccurredAt: string | null;
  lastEventReasonCode: ReviewerGrantReason | ReviewerRevokeReason | null;
}

export interface ReviewerAccountCandidate {
  userId: string;
  displayName: string;
  maskedContactEmail: string | null;
  status: 'active';
}

export class ReviewerAdminApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code: string | null,
    readonly retryAfterSeconds: number | null = null,
  ) {
    super(message);
    this.name = 'ReviewerAdminApiError';
  }
}

const roleAssignmentsUrl = '/api/v1/admin/security/role-assignments';
const accountSearchUrl = '/api/v1/admin/security/users/search';
const rolePath = (userId: string) =>
  `/api/v1/admin/security/users/${encodeURIComponent(userId)}/roles/publication_reviewer`;
const csrfUrl = '/api/v1/auth/csrf';
const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const idempotencyKeyPattern = /^[A-Za-z0-9._~-]{16,128}$/;
const csrfTokenPattern = /^[A-Za-z0-9_-]{43}$/;
const searchPattern = /^[A-Za-z0-9._+@-]{3,320}$/;
const readTimeoutMs = 5_000;
const writeTimeoutMs = 12_000;

@Injectable({ providedIn: 'root' })
export class ReviewerAdminApiService {
  private readonly http = inject(HttpClient);

  async getAssignments(status: ReviewerAssignmentStatus): Promise<ReviewerAssignment[]> {
    if (!['active', 'revoked', 'all'].includes(status)) {
      throw invalidResponse('INVALID_ASSIGNMENT_STATUS');
    }
    const params = new HttpParams().set('role', 'publication_reviewer').set('status', status);
    try {
      const response: unknown = await firstValueFrom(
        this.http.get<unknown>(roleAssignmentsUrl, { params }).pipe(timeout(readTimeoutMs)),
      );
      return parseAssignments(response);
    } catch (error) {
      throw toReviewerAdminError(error);
    }
  }

  async searchEligibleAccounts(query: string): Promise<ReviewerAccountCandidate[]> {
    const normalizedQuery = query.trim();
    if (!uuidPattern.test(normalizedQuery) && !searchPattern.test(normalizedQuery)) {
      throw invalidResponse('INVALID_ACCOUNT_SEARCH');
    }
    try {
      const response: unknown = await firstValueFrom(
        this.http
          .post<unknown>(
            accountSearchUrl,
            { query: normalizedQuery },
            { headers: await this.csrfHeaders() },
          )
          .pipe(timeout(readTimeoutMs)),
      );
      return parseAccountCandidates(response);
    } catch (error) {
      throw toReviewerAdminError(error);
    }
  }

  async grantReviewer(
    userId: string,
    reasonCode: ReviewerGrantReason,
    idempotencyKey: string,
  ): Promise<ReviewerAssignment> {
    if (
      !uuidPattern.test(userId) ||
      !['REVIEW_CAPACITY', 'TEMPORARY_COVERAGE', 'ROLE_CORRECTION'].includes(reasonCode) ||
      !idempotencyKeyPattern.test(idempotencyKey)
    ) {
      throw invalidResponse('INVALID_REVIEWER_GRANT');
    }
    try {
      const response: unknown = await firstValueFrom(
        this.http
          .put<unknown>(
            rolePath(userId),
            { reasonCode },
            {
              headers: (await this.csrfHeaders()).set('Idempotency-Key', idempotencyKey),
            },
          )
          .pipe(timeout(writeTimeoutMs)),
      );
      if (!isRecord(response) || !isRecord(response['assignment'])) throw invalidResponse();
      const assignment = parseAssignment(response['assignment']);
      if (assignment.userId !== userId || assignment.revokedAt !== null) throw invalidResponse();
      return assignment;
    } catch (error) {
      throw toReviewerAdminError(error);
    }
  }

  async revokeReviewer(
    userId: string,
    reasonCode: ReviewerRevokeReason,
    idempotencyKey: string,
  ): Promise<void> {
    if (
      !uuidPattern.test(userId) ||
      !['ACCESS_ENDED', 'ROLE_CHANGE', 'SECURITY_REVIEW'].includes(reasonCode) ||
      !idempotencyKeyPattern.test(idempotencyKey)
    ) {
      throw invalidResponse('INVALID_REVIEWER_REVOCATION');
    }
    try {
      await firstValueFrom(
        this.http
          .delete<void>(rolePath(userId), {
            body: { reasonCode },
            headers: (await this.csrfHeaders()).set('Idempotency-Key', idempotencyKey),
          })
          .pipe(timeout(writeTimeoutMs)),
      );
    } catch (error) {
      throw toReviewerAdminError(error);
    }
  }

  private async csrfHeaders(): Promise<HttpHeaders> {
    const response: unknown = await firstValueFrom(
      this.http.get<unknown>(csrfUrl).pipe(timeout(readTimeoutMs)),
    );
    if (!isRecord(response) || typeof response['token'] !== 'string') {
      throw invalidResponse('INVALID_CSRF_RESPONSE');
    }
    if (!csrfTokenPattern.test(response['token'])) throw invalidResponse('INVALID_CSRF_RESPONSE');
    return new HttpHeaders({ 'X-CSRF-Token': response['token'] });
  }
}

function parseAssignments(value: unknown): ReviewerAssignment[] {
  if (!isRecord(value) || !Array.isArray(value['items']) || value['items'].length > 2_000) {
    throw invalidResponse();
  }
  const assignments = value['items'].map(parseAssignment);
  if (new Set(assignments.map(({ assignmentId }) => assignmentId)).size !== assignments.length) {
    throw invalidResponse();
  }
  return assignments;
}

function parseAssignment(value: unknown): ReviewerAssignment {
  if (
    !isRecord(value) ||
    !isUuid(value['assignmentId']) ||
    !isUuid(value['userId']) ||
    !isText(value['displayName'], 200) ||
    !isNullableText(value['maskedContactEmail'], 320) ||
    !['active', 'pending', 'suspended', 'deleted'].includes(String(value['userStatus'])) ||
    !isNullableUuid(value['assignedByUserId']) ||
    !isDateTime(value['assignedAt']) ||
    !['REVIEW_CAPACITY', 'TEMPORARY_COVERAGE', 'ROLE_CORRECTION'].includes(
      String(value['reasonCode']),
    ) ||
    !isNullableDateTime(value['revokedAt']) ||
    !isNullableUuid(value['revokedByUserId']) ||
    !isNullableRevokeReason(value['revocationReasonCode']) ||
    !isNullableUuid(value['lastEventId']) ||
    !isNullableEventAction(value['lastEventActionCode']) ||
    !isNullableUuid(value['lastEventActorUserId']) ||
    !isNullableDateTime(value['lastEventOccurredAt']) ||
    !isNullableEventReason(value['lastEventReasonCode']) ||
    (value['lastEventId'] === null) !== (value['lastEventActionCode'] === null) ||
    (value['lastEventId'] === null) !== (value['lastEventActorUserId'] === null) ||
    (value['lastEventId'] === null) !== (value['lastEventOccurredAt'] === null) ||
    (value['lastEventId'] === null) !== (value['lastEventReasonCode'] === null) ||
    (value['revokedAt'] === null) !== (value['revokedByUserId'] === null) ||
    (value['revokedAt'] === null) !== (value['revocationReasonCode'] === null)
  ) {
    throw invalidResponse();
  }
  return {
    assignmentId: value['assignmentId'],
    userId: value['userId'],
    displayName: value['displayName'],
    maskedContactEmail: value['maskedContactEmail'],
    userStatus: value['userStatus'] as ReviewerAssignment['userStatus'],
    assignedByUserId: value['assignedByUserId'],
    assignedAt: value['assignedAt'],
    reasonCode: value['reasonCode'] as ReviewerGrantReason,
    revokedAt: value['revokedAt'],
    revokedByUserId: value['revokedByUserId'],
    revocationReasonCode: value['revocationReasonCode'] as ReviewerRevokeReason | null,
    lastEventId: value['lastEventId'],
    lastEventActionCode: value['lastEventActionCode'] as 'GRANTED' | 'REVOKED' | null,
    lastEventActorUserId: value['lastEventActorUserId'],
    lastEventOccurredAt: value['lastEventOccurredAt'],
    lastEventReasonCode: value['lastEventReasonCode'] as
      ReviewerGrantReason | ReviewerRevokeReason | null,
  };
}

function parseAccountCandidates(value: unknown): ReviewerAccountCandidate[] {
  if (!isRecord(value) || !Array.isArray(value['items']) || value['items'].length > 20) {
    throw invalidResponse();
  }
  const candidates = value['items'].map((item): ReviewerAccountCandidate => {
    if (
      !isRecord(item) ||
      !isUuid(item['userId']) ||
      !isText(item['displayName'], 200) ||
      !isNullableText(item['maskedContactEmail'], 320) ||
      item['status'] !== 'active'
    ) {
      throw invalidResponse();
    }
    return {
      userId: item['userId'],
      displayName: item['displayName'],
      maskedContactEmail: item['maskedContactEmail'],
      status: 'active',
    };
  });
  if (new Set(candidates.map(({ userId }) => userId)).size !== candidates.length) {
    throw invalidResponse();
  }
  return candidates;
}

function toReviewerAdminError(error: unknown): ReviewerAdminApiError {
  if (error instanceof ReviewerAdminApiError) return error;
  if (error instanceof HttpErrorResponse) {
    const body = isRecord(error.error) ? error.error : null;
    const code = body && typeof body['code'] === 'string' ? body['code'] : null;
    return new ReviewerAdminApiError(
      'REVIEWER_ADMIN_REQUEST_FAILED',
      error.status,
      code,
      parseRetryAfter(error.headers.get('Retry-After')),
    );
  }
  return new ReviewerAdminApiError('REVIEWER_ADMIN_REQUEST_FAILED', 0, null);
}

function parseRetryAfter(value: string | null): number | null {
  if (!value) return null;
  const seconds = /^\d{1,6}$/.test(value)
    ? Number(value)
    : Math.ceil((Date.parse(value) - Date.now()) / 1000);
  return Number.isFinite(seconds) && seconds > 0 ? Math.min(seconds, 86_400) : null;
}

function invalidResponse(code = 'INVALID_RESPONSE'): ReviewerAdminApiError {
  return new ReviewerAdminApiError(code, 0, code);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isUuid(value: unknown): value is string {
  return typeof value === 'string' && uuidPattern.test(value);
}

function isNullableUuid(value: unknown): value is string | null {
  return value === null || isUuid(value);
}

function isText(value: unknown, maxLength: number): value is string {
  return typeof value === 'string' && value.trim().length > 0 && value.length <= maxLength;
}

function isNullableText(value: unknown, maxLength: number): value is string | null {
  return value === null || (typeof value === 'string' && value.length <= maxLength);
}

function isDateTime(value: unknown): value is string {
  return typeof value === 'string' && Number.isFinite(Date.parse(value));
}

function isNullableDateTime(value: unknown): value is string | null {
  return value === null || isDateTime(value);
}

function isNullableRevokeReason(value: unknown): value is ReviewerRevokeReason | null {
  return (
    value === null || ['ACCESS_ENDED', 'ROLE_CHANGE', 'SECURITY_REVIEW'].includes(String(value))
  );
}

function isNullableEventAction(value: unknown): value is 'GRANTED' | 'REVOKED' | null {
  return value === null || value === 'GRANTED' || value === 'REVOKED';
}

function isNullableEventReason(
  value: unknown,
): value is ReviewerGrantReason | ReviewerRevokeReason | null {
  return (
    value === null ||
    [
      'REVIEW_CAPACITY',
      'TEMPORARY_COVERAGE',
      'ROLE_CORRECTION',
      'ACCESS_ENDED',
      'ROLE_CHANGE',
      'SECURITY_REVIEW',
    ].includes(String(value))
  );
}
