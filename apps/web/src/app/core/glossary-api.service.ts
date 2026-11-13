import {
  HttpClient,
  HttpErrorResponse,
  HttpHeaders,
  HttpParams,
  HttpResponse,
} from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom, timeout } from 'rxjs';

export type GlossaryScope = 'USER_GLOBAL' | 'LIBRARY' | 'WORK' | 'VOLUME' | 'CHAPTER';
export type GlossaryImportFormat = 'JSON' | 'CSV' | 'TSV';
export type GlossaryCasePolicy = 'SENSITIVE' | 'INSENSITIVE';
export type GlossarySuggestedAction = 'ADD' | 'UPDATE' | 'UNCHANGED' | 'RESOLVE_CONFLICT';
export type GlossaryImportAction = 'ADD' | 'REPLACE' | 'MERGE' | 'SKIP';

export interface GlossaryCatalogItem {
  id: string;
  name: string;
  scope: GlossaryScope;
  sourceLanguage: string;
  targetLanguage: string;
  version: number;
  activeRevisionId: string | null;
  createdAt: string;
}

export interface GlossaryPage {
  items: GlossaryCatalogItem[];
  nextCursor: string | null;
}

export interface GlossaryCurrentTerm {
  id: string;
  ordinal: number;
  sourceTerm: string;
  targetTerm: string | null;
  priority: number;
  casePolicy: GlossaryCasePolicy;
  matchMode: 'WHOLE_PHRASE';
  doNotTranslate: boolean;
  contextNote: string | null;
  aliases: string[];
}

export interface GlossaryActiveRevision {
  glossaryId: string;
  name: string;
  scope: GlossaryScope;
  sourceLanguage: string;
  targetLanguage: string;
  version: number;
  activeRevisionId: string;
  revisionNumber: number;
  parentRevisionId: string | null;
  revisionCreatedAt: string;
  terms: GlossaryCurrentTerm[];
  etag: string;
}

export interface GlossaryImportedTerm {
  rowNumber: number;
  sourceTerm: string;
  targetTerm: string | null;
  priority: number;
  casePolicy: GlossaryCasePolicy;
  matchMode: 'WHOLE_PHRASE';
  doNotTranslate: boolean;
  contextNote: string | null;
  aliases: string[];
}

export interface GlossaryPatternMatch {
  termId: string;
  reason: 'CANONICAL_TERM_MATCH' | 'PATTERN_COLLISION';
}

export interface GlossaryImportCandidate {
  rowNumber: number;
  importedTerm: GlossaryImportedTerm;
  suggestedAction: GlossarySuggestedAction;
  existingMatches: GlossaryPatternMatch[];
  matchesTruncated: boolean;
}

export interface GlossaryImportPreview {
  glossaryId: string;
  glossaryName: string;
  scope: GlossaryScope;
  sourceLanguage: string;
  targetLanguage: string;
  glossaryVersion: number;
  activeRevisionId: string | null;
  activeRevisionNumber: number | null;
  baseRevisionId: string | null;
  baseRevisionNumber: number | null;
  currentTerms: GlossaryCurrentTerm[];
  importedTerms: GlossaryImportCandidate[];
  counts: {
    imported: number;
    add: number;
    update: number;
    unchanged: number;
    requiresResolution: number;
  };
  translationImpact: {
    status: 'UNAVAILABLE';
    affectedTranslationUnitCount: number | null;
    detail: string;
  };
  etag: string;
}

export interface GlossaryResolvedTerm {
  sourceTerm: string;
  targetTerm: string | null;
  priority: number;
  casePolicy: GlossaryCasePolicy;
  matchMode: 'WHOLE_PHRASE';
  doNotTranslate: boolean;
  contextNote: string | null;
  aliases: string[];
}

export interface GlossaryImportResolution {
  rowNumber: number;
  action: GlossaryImportAction;
  targetTermId: string | null;
  term: GlossaryResolvedTerm | null;
}

export interface GlossaryApplyAttempt {
  /** Serialize once and retain this exact string until a definitive response arrives. */
  requestBody: string;
  idempotencyKey: string;
}

export interface GlossaryImportIssue {
  code: string;
  field: string | null;
  rowNumber: number;
  message: string;
}

export interface GlossaryImportApplyResult {
  draft: {
    glossaryId: string;
    revisionId: string;
    revisionNumber: number;
    parentRevisionId: string | null;
    glossaryVersion: number;
  };
  rows: Array<{
    rowNumber: number;
    action: GlossaryImportAction;
    termId: string | null;
  }>;
  skippedRows: number;
  replayed: boolean;
  etag: string;
}

export class GlossaryApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code: string | null,
    readonly currentEtag: string | null = null,
    readonly rowNumber: number | null = null,
    readonly issues: GlossaryImportIssue[] = [],
  ) {
    super(message);
    this.name = 'GlossaryApiError';
  }
}

const glossariesUrl = '/api/v1/glossaries';
const csrfUrl = '/api/v1/auth/csrf';
const requestTimeoutMs = 10_000;
const maxFileBytes = 1_048_576;
const maxBase64Characters = 1_398_104;
const maxRequestBytes = 2_097_152;
const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const etagPattern = /^"v[1-9][0-9]{0,18}"$/;
const csrfTokenPattern = /^[A-Za-z0-9_-]{43}$/;
const idempotencyKeyPattern = /^[A-Za-z0-9._~-]{16,128}$/;

@Injectable({ providedIn: 'root' })
export class GlossaryApiService {
  private readonly http = inject(HttpClient);

  async listGlossaries(
    options: {
      cursor?: string;
      limit?: number;
      scope?: GlossaryScope;
      libraryId?: string;
      query?: string;
    } = {},
  ): Promise<GlossaryPage> {
    const { cursor, limit = 25, scope, libraryId, query } = options;
    if (
      !Number.isInteger(limit) ||
      limit < 1 ||
      limit > 100 ||
      (cursor !== undefined && (!cursor || cursor.length > 512)) ||
      (scope !== undefined && !isScope(scope)) ||
      (libraryId !== undefined && !isUuid(libraryId)) ||
      (query !== undefined && [...query].length > 160)
    ) {
      throw new GlossaryApiError('INVALID_GLOSSARY_QUERY', 400, 'INVALID_GLOSSARY_QUERY');
    }
    try {
      let params = new HttpParams().set('limit', limit);
      if (cursor) params = params.set('cursor', cursor);
      if (scope) params = params.set('scope', scope);
      if (libraryId) params = params.set('libraryId', libraryId);
      if (query) params = params.set('q', query);
      const value: unknown = await firstValueFrom(
        this.http.get<unknown>(glossariesUrl, { params }).pipe(timeout(requestTimeoutMs)),
      );
      return parseGlossaryPage(value, limit);
    } catch (error) {
      throw toGlossaryApiError(error);
    }
  }

  async getActiveRevision(glossaryId: string): Promise<GlossaryActiveRevision> {
    if (!isUuid(glossaryId)) throw invalidQuery();
    try {
      const response = await firstValueFrom(
        this.http
          .get<unknown>(`${glossariesUrl}/${glossaryId}/active-revision`, { observe: 'response' })
          .pipe(timeout(requestTimeoutMs)),
      );
      return parseActiveRevision(response, glossaryId);
    } catch (error) {
      throw toGlossaryApiError(error);
    }
  }

  async previewImport(
    glossaryId: string,
    etag: string,
    file: File,
  ): Promise<GlossaryImportPreview> {
    const format = importFormat(file.name);
    if (!isUuid(glossaryId) || !isEtag(etag)) throw invalidQuery();
    if (!format)
      throw new GlossaryApiError('UNSUPPORTED_IMPORT_FORMAT', 415, 'IMPORT_FORMAT_INVALID');
    if (file.size < 1 || file.size > maxFileBytes) {
      throw new GlossaryApiError('IMPORT_FILE_SIZE_INVALID', 413, 'IMPORT_LIMIT_EXCEEDED');
    }
    try {
      const response = await firstValueFrom(
        this.http
          .post<unknown>(`${glossariesUrl}/${glossaryId}/terms/import-preview`, file, {
            headers: new HttpHeaders({
              'Content-Type': mediaType(format),
              'If-Match': etag,
            }),
            observe: 'response',
          })
          .pipe(timeout(requestTimeoutMs)),
      );
      return parseImportPreview(response, glossaryId);
    } catch (error) {
      throw toGlossaryApiError(error);
    }
  }

  async createApplyAttempt(
    file: File,
    resolutions: GlossaryImportResolution[],
  ): Promise<GlossaryApplyAttempt> {
    const format = importFormat(file.name);
    if (!format)
      throw new GlossaryApiError('UNSUPPORTED_IMPORT_FORMAT', 415, 'IMPORT_FORMAT_INVALID');
    if (
      file.size < 1 ||
      file.size > maxFileBytes ||
      !Array.isArray(resolutions) ||
      resolutions.length < 1 ||
      resolutions.length > 1000 ||
      !isValidResolutionList(resolutions)
    ) {
      throw new GlossaryApiError('IMPORT_APPLY_INPUT_INVALID', 413, 'IMPORT_APPLY_INPUT_INVALID');
    }
    try {
      const fileContentBase64 = encodeBase64(new Uint8Array(await file.arrayBuffer()));
      if (fileContentBase64.length > maxBase64Characters) throw invalidQuery();
      const requestBody = JSON.stringify({ format, fileContentBase64, resolutions });
      if (new TextEncoder().encode(requestBody).length > maxRequestBytes) {
        throw new GlossaryApiError(
          'IMPORT_APPLY_REQUEST_TOO_LARGE',
          413,
          'IMPORT_APPLY_LIMIT_EXCEEDED',
        );
      }
      const idempotencyKey = crypto.randomUUID();
      if (!idempotencyKeyPattern.test(idempotencyKey)) throw invalidQuery();
      return { requestBody, idempotencyKey };
    } catch (error) {
      throw toGlossaryApiError(error);
    }
  }

  async applyImport(
    glossaryId: string,
    etag: string,
    attempt: GlossaryApplyAttempt,
  ): Promise<GlossaryImportApplyResult> {
    if (
      !isUuid(glossaryId) ||
      !isEtag(etag) ||
      !attempt ||
      typeof attempt.requestBody !== 'string' ||
      !idempotencyKeyPattern.test(attempt.idempotencyKey) ||
      new TextEncoder().encode(attempt.requestBody).length > maxRequestBytes
    ) {
      throw invalidQuery();
    }
    try {
      const headers = (await this.csrfHeaders())
        .set('Content-Type', 'application/json')
        .set('If-Match', etag)
        .set('Idempotency-Key', attempt.idempotencyKey);
      const response = await firstValueFrom(
        this.http
          .post<unknown>(`${glossariesUrl}/${glossaryId}/terms/import-apply`, attempt.requestBody, {
            headers,
            observe: 'response',
          })
          .pipe(timeout(requestTimeoutMs)),
      );
      return parseApplyResult(response, glossaryId);
    } catch (error) {
      throw toGlossaryApiError(error);
    }
  }

  private async csrfHeaders(): Promise<HttpHeaders> {
    const value: unknown = await firstValueFrom(
      this.http.get<unknown>(csrfUrl).pipe(timeout(requestTimeoutMs)),
    );
    if (
      !isRecord(value) ||
      typeof value['token'] !== 'string' ||
      !csrfTokenPattern.test(value['token'])
    ) {
      throw invalidResponse();
    }
    return new HttpHeaders({ 'X-CSRF-Token': value['token'] });
  }
}

function parseGlossaryPage(value: unknown, limit: number): GlossaryPage {
  if (
    !isRecord(value) ||
    !Array.isArray(value['items']) ||
    value['items'].length > limit ||
    !isNullableBoundedString(value['nextCursor'], 512)
  ) {
    throw invalidResponse();
  }
  const items = value['items'].map(parseCatalogItem);
  if (new Set(items.map((item) => item.id)).size !== items.length) throw invalidResponse();
  return { items, nextCursor: value['nextCursor'] as string | null };
}

function parseCatalogItem(value: unknown): GlossaryCatalogItem {
  if (
    !isRecord(value) ||
    !isUuid(value['id']) ||
    !isBoundedString(value['name'], 1, 120) ||
    !isScope(value['scope']) ||
    !isBoundedString(value['sourceLanguage'], 2, 35) ||
    !isBoundedString(value['targetLanguage'], 2, 35) ||
    !isPositiveInteger(value['version']) ||
    !isNullableUuid(value['activeRevisionId']) ||
    !isTimestamp(value['createdAt'])
  ) {
    throw invalidResponse();
  }
  return {
    id: value['id'],
    name: value['name'],
    scope: value['scope'],
    sourceLanguage: value['sourceLanguage'],
    targetLanguage: value['targetLanguage'],
    version: value['version'],
    activeRevisionId: value['activeRevisionId'],
    createdAt: value['createdAt'],
  };
}

function parseActiveRevision(
  response: HttpResponse<unknown>,
  expectedId: string,
): GlossaryActiveRevision {
  const value = response.body;
  const etag = response.headers.get('ETag');
  if (
    !isRecord(value) ||
    !isEtag(etag) ||
    value['glossaryId'] !== expectedId ||
    !isBoundedString(value['name'], 1, 300) ||
    !isScope(value['scope']) ||
    !isBoundedString(value['sourceLanguage'], 2, 64) ||
    !isBoundedString(value['targetLanguage'], 2, 64) ||
    !isPositiveInteger(value['version']) ||
    !isUuid(value['activeRevisionId']) ||
    !isPositiveInteger(value['revisionNumber']) ||
    !isNullableUuid(value['parentRevisionId']) ||
    !isTimestamp(value['revisionCreatedAt']) ||
    !Array.isArray(value['terms']) ||
    value['terms'].length > 1000
  ) {
    throw invalidResponse();
  }
  const terms = value['terms'].map(parseCurrentTerm);
  if (
    new Set(terms.map((term) => term.id)).size !== terms.length ||
    terms.some((term, index) => term.ordinal !== index + 1)
  ) {
    throw invalidResponse();
  }
  return {
    glossaryId: value['glossaryId'],
    name: value['name'],
    scope: value['scope'],
    sourceLanguage: value['sourceLanguage'],
    targetLanguage: value['targetLanguage'],
    version: value['version'],
    activeRevisionId: value['activeRevisionId'],
    revisionNumber: value['revisionNumber'],
    parentRevisionId: value['parentRevisionId'],
    revisionCreatedAt: value['revisionCreatedAt'],
    terms,
    etag,
  };
}

function parseImportPreview(
  response: HttpResponse<unknown>,
  expectedId: string,
): GlossaryImportPreview {
  const value = response.body;
  const etag = response.headers.get('ETag');
  if (
    !isRecord(value) ||
    !isEtag(etag) ||
    value['glossaryId'] !== expectedId ||
    !isBoundedString(value['glossaryName'], 1, 300) ||
    !isScope(value['scope']) ||
    !isBoundedString(value['sourceLanguage'], 2, 64) ||
    !isBoundedString(value['targetLanguage'], 2, 64) ||
    !isPositiveInteger(value['glossaryVersion']) ||
    !isNullableUuid(value['activeRevisionId']) ||
    !isNullablePositiveInteger(value['activeRevisionNumber']) ||
    !isNullableUuid(value['baseRevisionId']) ||
    !isNullablePositiveInteger(value['baseRevisionNumber']) ||
    !Array.isArray(value['currentTerms']) ||
    value['currentTerms'].length > 1000 ||
    !Array.isArray(value['importedTerms']) ||
    value['importedTerms'].length > 1000 ||
    !isRecord(value['counts']) ||
    !isRecord(value['translationImpact'])
  ) {
    throw invalidResponse();
  }
  const currentTerms = value['currentTerms'].map(parseCurrentTerm);
  if (
    new Set(currentTerms.map((term) => term.id)).size !== currentTerms.length ||
    currentTerms.some((term, index) => term.ordinal !== index + 1)
  ) {
    throw invalidResponse();
  }
  const currentIds = new Set(currentTerms.map((term) => term.id));
  const importedTerms = value['importedTerms'].map((candidate) =>
    parseImportCandidate(candidate, currentIds),
  );
  if (
    new Set(importedTerms.map((candidate) => candidate.rowNumber)).size !== importedTerms.length ||
    value['counts']['imported'] !== importedTerms.length ||
    !isNonNegativeInteger(value['counts']['add']) ||
    !isNonNegativeInteger(value['counts']['update']) ||
    !isNonNegativeInteger(value['counts']['unchanged']) ||
    !isNonNegativeInteger(value['counts']['requiresResolution']) ||
    value['translationImpact']['status'] !== 'UNAVAILABLE' ||
    (value['translationImpact']['affectedTranslationUnitCount'] !== null &&
      !isNonNegativeInteger(value['translationImpact']['affectedTranslationUnitCount'])) ||
    typeof value['translationImpact']['detail'] !== 'string'
  ) {
    throw invalidResponse();
  }
  return {
    glossaryId: value['glossaryId'],
    glossaryName: value['glossaryName'],
    scope: value['scope'],
    sourceLanguage: value['sourceLanguage'],
    targetLanguage: value['targetLanguage'],
    glossaryVersion: value['glossaryVersion'],
    activeRevisionId: value['activeRevisionId'],
    activeRevisionNumber: value['activeRevisionNumber'],
    baseRevisionId: value['baseRevisionId'],
    baseRevisionNumber: value['baseRevisionNumber'],
    currentTerms,
    importedTerms,
    counts: {
      imported: value['counts']['imported'] as number,
      add: value['counts']['add'] as number,
      update: value['counts']['update'] as number,
      unchanged: value['counts']['unchanged'] as number,
      requiresResolution: value['counts']['requiresResolution'] as number,
    },
    translationImpact: {
      status: 'UNAVAILABLE',
      affectedTranslationUnitCount: value['translationImpact']['affectedTranslationUnitCount'] as
        number | null,
      detail: value['translationImpact']['detail'] as string,
    },
    etag,
  };
}

function parseImportCandidate(value: unknown, currentIds: Set<string>): GlossaryImportCandidate {
  if (
    !isRecord(value) ||
    !isPositiveInteger(value['rowNumber']) ||
    !isRecord(value['importedTerm']) ||
    !isSuggestedAction(value['suggestedAction']) ||
    !Array.isArray(value['existingMatches']) ||
    value['existingMatches'].length > 100 ||
    typeof value['matchesTruncated'] !== 'boolean'
  ) {
    throw invalidResponse();
  }
  const importedTerm = parseImportedTerm(value['importedTerm']);
  if (importedTerm.rowNumber !== value['rowNumber']) throw invalidResponse();
  const existingMatches = value['existingMatches'].map((match) => {
    if (
      !isRecord(match) ||
      !isUuid(match['termId']) ||
      !currentIds.has(match['termId']) ||
      (match['reason'] !== 'CANONICAL_TERM_MATCH' && match['reason'] !== 'PATTERN_COLLISION')
    ) {
      throw invalidResponse();
    }
    return {
      termId: match['termId'],
      reason: match['reason'] as GlossaryPatternMatch['reason'],
    };
  });
  return {
    rowNumber: value['rowNumber'],
    importedTerm,
    suggestedAction: value['suggestedAction'],
    existingMatches,
    matchesTruncated: value['matchesTruncated'],
  };
}

function parseCurrentTerm(value: unknown): GlossaryCurrentTerm {
  if (
    !isRecord(value) ||
    !isUuid(value['id']) ||
    !isPositiveInteger(value['ordinal']) ||
    !isBoundedString(value['sourceTerm'], 1, 512) ||
    !isNullableBoundedString(value['targetTerm'], 4096) ||
    !isIntegerInRange(value['priority'], -1_000_000, 1_000_000) ||
    !isCasePolicy(value['casePolicy']) ||
    value['matchMode'] !== 'WHOLE_PHRASE' ||
    typeof value['doNotTranslate'] !== 'boolean' ||
    !isNullableBoundedString(value['contextNote'], 2000) ||
    !isStringArray(value['aliases'], 25, 512)
  ) {
    throw invalidResponse();
  }
  return {
    id: value['id'],
    ordinal: value['ordinal'],
    sourceTerm: value['sourceTerm'],
    targetTerm: value['targetTerm'],
    priority: value['priority'],
    casePolicy: value['casePolicy'],
    matchMode: 'WHOLE_PHRASE',
    doNotTranslate: value['doNotTranslate'],
    contextNote: value['contextNote'],
    aliases: value['aliases'],
  };
}

function parseImportedTerm(value: unknown): GlossaryImportedTerm {
  if (
    !isRecord(value) ||
    !isPositiveInteger(value['rowNumber']) ||
    !isBoundedString(value['sourceTerm'], 1, 512) ||
    !isNullableBoundedString(value['targetTerm'], 4096) ||
    !isIntegerInRange(value['priority'], -1_000_000, 1_000_000) ||
    !isCasePolicy(value['casePolicy']) ||
    value['matchMode'] !== 'WHOLE_PHRASE' ||
    typeof value['doNotTranslate'] !== 'boolean' ||
    !isNullableBoundedString(value['contextNote'], 2000) ||
    !isStringArray(value['aliases'], 25, 512)
  ) {
    throw invalidResponse();
  }
  return {
    rowNumber: value['rowNumber'],
    sourceTerm: value['sourceTerm'],
    targetTerm: value['targetTerm'],
    priority: value['priority'],
    casePolicy: value['casePolicy'],
    matchMode: 'WHOLE_PHRASE',
    doNotTranslate: value['doNotTranslate'],
    contextNote: value['contextNote'],
    aliases: value['aliases'],
  };
}

function parseApplyResult(
  response: HttpResponse<unknown>,
  expectedId: string,
): GlossaryImportApplyResult {
  const value = response.body;
  const etag = response.headers.get('ETag');
  if (
    !isRecord(value) ||
    !isEtag(etag) ||
    !isRecord(value['draft']) ||
    value['draft']['glossaryId'] !== expectedId ||
    !isUuid(value['draft']['revisionId']) ||
    !isPositiveInteger(value['draft']['revisionNumber']) ||
    !isNullableUuid(value['draft']['parentRevisionId']) ||
    !isPositiveInteger(value['draft']['glossaryVersion']) ||
    etag !== `"v${value['draft']['glossaryVersion']}"` ||
    !Array.isArray(value['rows']) ||
    value['rows'].length > 1000 ||
    !isNonNegativeInteger(value['skippedRows']) ||
    typeof value['replayed'] !== 'boolean'
  ) {
    throw invalidResponse();
  }
  const rows = value['rows'].map((row) => {
    if (
      !isRecord(row) ||
      !isPositiveInteger(row['rowNumber']) ||
      !isImportAction(row['action']) ||
      !isNullableUuid(row['termId']) ||
      (row['action'] === 'SKIP') !== (row['termId'] === null)
    ) {
      throw invalidResponse();
    }
    return { rowNumber: row['rowNumber'], action: row['action'], termId: row['termId'] };
  });
  if (
    new Set(rows.map((row) => row.rowNumber)).size !== rows.length ||
    rows.filter((row) => row.action === 'SKIP').length !== value['skippedRows']
  ) {
    throw invalidResponse();
  }
  return {
    draft: {
      glossaryId: value['draft']['glossaryId'],
      revisionId: value['draft']['revisionId'],
      revisionNumber: value['draft']['revisionNumber'],
      parentRevisionId: value['draft']['parentRevisionId'],
      glossaryVersion: value['draft']['glossaryVersion'],
    },
    rows,
    skippedRows: value['skippedRows'],
    replayed: value['replayed'],
    etag,
  };
}

function importFormat(filename: string): GlossaryImportFormat | null {
  const extension = filename.split('.').at(-1)?.toLowerCase();
  if (extension === 'json') return 'JSON';
  if (extension === 'csv') return 'CSV';
  if (extension === 'tsv') return 'TSV';
  return null;
}

function mediaType(format: GlossaryImportFormat): string {
  switch (format) {
    case 'JSON':
      return 'application/json';
    case 'CSV':
      return 'text/csv; charset=UTF-8';
    case 'TSV':
      return 'text/tab-separated-values; charset=UTF-8';
  }
}

function encodeBase64(bytes: Uint8Array): string {
  const chunkSize = 0x8000;
  let binary = '';
  for (let index = 0; index < bytes.length; index += chunkSize) {
    const chunk = bytes.subarray(index, Math.min(index + chunkSize, bytes.length));
    binary += String.fromCharCode(...chunk);
  }
  return btoa(binary);
}

function toGlossaryApiError(error: unknown): GlossaryApiError {
  if (error instanceof GlossaryApiError) return error;
  if (error instanceof HttpErrorResponse) {
    const body = error.error;
    const code = isRecord(body) && typeof body['code'] === 'string' ? body['code'] : null;
    const rowNumber =
      isRecord(body) && isPositiveInteger(body['rowNumber']) ? body['rowNumber'] : null;
    const issues =
      isRecord(body) && Array.isArray(body['issues']) ? parseIssues(body['issues']) : [];
    const message =
      code === 'GLOSSARY_VERSION_STALE'
        ? 'GLOSSARY_VERSION_STALE'
        : code === 'IDEMPOTENCY_KEY_REUSED'
          ? 'IDEMPOTENCY_KEY_REUSED'
          : (code ?? 'GLOSSARY_REQUEST_FAILED');
    return new GlossaryApiError(
      message,
      error.status,
      code,
      parseEtag(error.headers.get('ETag')),
      rowNumber,
      issues,
    );
  }
  return new GlossaryApiError('GLOSSARY_REQUEST_FAILED', -1, null);
}

function parseEtag(value: string | null): string | null {
  return value && etagPattern.test(value) ? value : null;
}

function parseIssues(value: unknown[]): GlossaryImportIssue[] {
  if (value.length > 100) return [];
  const issues: GlossaryImportIssue[] = [];
  for (const issue of value) {
    if (
      !isRecord(issue) ||
      !isBoundedString(issue['code'], 1, 64) ||
      !isNullableBoundedString(issue['field'], 64) ||
      !isNonNegativeInteger(issue['rowNumber']) ||
      !isBoundedString(issue['message'], 1, 256)
    ) {
      return [];
    }
    issues.push({
      code: issue['code'],
      field: issue['field'],
      rowNumber: issue['rowNumber'],
      message: issue['message'],
    });
  }
  return issues;
}

function isValidResolutionList(value: unknown[]): value is GlossaryImportResolution[] {
  const rowNumbers = new Set<number>();
  for (const candidate of value) {
    if (
      !isRecord(candidate) ||
      !isPositiveInteger(candidate['rowNumber']) ||
      rowNumbers.has(candidate['rowNumber']) ||
      !isImportAction(candidate['action']) ||
      !isNullableUuid(candidate['targetTermId'])
    ) {
      return false;
    }
    rowNumbers.add(candidate['rowNumber']);
    if (candidate['action'] === 'SKIP') {
      if (candidate['targetTermId'] !== null || candidate['term'] !== null) return false;
      continue;
    }
    if (
      !isRecord(candidate['term']) ||
      !isBoundedString(candidate['term']['sourceTerm'], 1, 512) ||
      !isNullableBoundedString(candidate['term']['targetTerm'], 4096) ||
      !isIntegerInRange(candidate['term']['priority'], -1_000_000, 1_000_000) ||
      !isCasePolicy(candidate['term']['casePolicy']) ||
      candidate['term']['matchMode'] !== 'WHOLE_PHRASE' ||
      typeof candidate['term']['doNotTranslate'] !== 'boolean' ||
      !isNullableBoundedString(candidate['term']['contextNote'], 2000) ||
      !isStringArray(candidate['term']['aliases'], 25, 512)
    ) {
      return false;
    }
    const needsTarget = candidate['action'] === 'REPLACE' || candidate['action'] === 'MERGE';
    if (needsTarget !== isUuid(candidate['targetTermId'])) return false;
  }
  return true;
}

function invalidQuery(): GlossaryApiError {
  return new GlossaryApiError('INVALID_GLOSSARY_REQUEST', 400, 'INVALID_GLOSSARY_REQUEST');
}

function invalidResponse(): GlossaryApiError {
  return new GlossaryApiError(
    'The glossary service returned an invalid response.',
    -1,
    'INVALID_RESPONSE',
  );
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

function isScope(value: unknown): value is GlossaryScope {
  return (
    value === 'USER_GLOBAL' ||
    value === 'LIBRARY' ||
    value === 'WORK' ||
    value === 'VOLUME' ||
    value === 'CHAPTER'
  );
}

function isCasePolicy(value: unknown): value is GlossaryCasePolicy {
  return value === 'SENSITIVE' || value === 'INSENSITIVE';
}

function isSuggestedAction(value: unknown): value is GlossarySuggestedAction {
  return (
    value === 'ADD' || value === 'UPDATE' || value === 'UNCHANGED' || value === 'RESOLVE_CONFLICT'
  );
}

function isImportAction(value: unknown): value is GlossaryImportAction {
  return value === 'ADD' || value === 'REPLACE' || value === 'MERGE' || value === 'SKIP';
}

function isEtag(value: unknown): value is string {
  return typeof value === 'string' && etagPattern.test(value);
}

function isTimestamp(value: unknown): value is string {
  return typeof value === 'string' && value.length <= 64 && Number.isFinite(Date.parse(value));
}

function isBoundedString(value: unknown, min: number, max: number): value is string {
  return typeof value === 'string' && [...value].length >= min && [...value].length <= max;
}

function isNullableBoundedString(value: unknown, max: number): value is string | null {
  return value === null || isBoundedString(value, 0, max);
}

function isStringArray(value: unknown, maxItems: number, maxLength: number): value is string[] {
  return (
    Array.isArray(value) &&
    value.length <= maxItems &&
    value.every((item) => isBoundedString(item, 1, maxLength))
  );
}

function isPositiveInteger(value: unknown): value is number {
  return Number.isSafeInteger(value) && (value as number) > 0;
}

function isNullablePositiveInteger(value: unknown): value is number | null {
  return value === null || isPositiveInteger(value);
}

function isNonNegativeInteger(value: unknown): value is number {
  return Number.isSafeInteger(value) && (value as number) >= 0;
}

function isIntegerInRange(value: unknown, min: number, max: number): value is number {
  return Number.isSafeInteger(value) && (value as number) >= min && (value as number) <= max;
}
