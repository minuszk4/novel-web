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
export type GlossaryExportFormat = 'json' | 'csv' | 'tsv';
export type GlossaryExportRevision = 'active' | 'latest';
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
  activeRevisionNumber: number | null;
  latestRevisionNumber: number | null;
  createdAt: string;
  canWrite: boolean;
}

export interface GlossaryPage {
  items: GlossaryCatalogItem[];
  nextCursor: string | null;
}

export interface GlossaryMetadataSnapshot {
  glossary: GlossaryCatalogItem;
  etag: string;
}

export interface GlossaryExportDownload {
  blob: Blob;
  fileName: string;
  etag: string;
  format: GlossaryExportFormat;
  revision: GlossaryExportRevision;
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

export interface GlossaryWritableRevision {
  glossaryId: string;
  name: string;
  scope: GlossaryScope;
  sourceLanguage: string;
  targetLanguage: string;
  version: number;
  activeRevisionId: string | null;
  revisionId: string;
  revisionNumber: number;
  parentRevisionId: string | null;
  revisionCreatedAt: string;
  terms: GlossaryCurrentTerm[];
  etag: string;
}

export interface GlossaryDraftTermInput {
  id: string;
  sourceTerm: string;
  targetTerm: string | null;
  priority: number;
  casePolicy: GlossaryCasePolicy;
  matchMode: 'WHOLE_PHRASE';
  doNotTranslate: boolean;
  contextNote: string | null;
  aliases: string[];
}

export interface GlossaryDraftCreated {
  glossaryId: string;
  revisionId: string;
  revisionNumber: number;
  parentRevisionId: string | null;
  glossaryVersion: number;
  etag: string;
}

export type GlossaryRevisionChangeKind = 'ADDED' | 'REMOVED' | 'UPDATED';
export type GlossaryLintSeverity = 'WARNING' | 'ERROR';

export interface GlossaryPreviewTerm {
  id: string;
  revisionId: string;
  sourceLanguage: string;
  sourceTerm: string;
  targetTerm: string | null;
  aliases: string[];
  scope: GlossaryScope;
  priority: number;
  casePolicy: GlossaryCasePolicy;
  matchMode: 'WHOLE_PHRASE';
  doNotTranslate: boolean;
  ordinal: number;
  contextNote: string | null;
}

export interface GlossaryRevisionChange {
  kind: GlossaryRevisionChangeKind;
  termId: string;
  before: GlossaryPreviewTerm | null;
  after: GlossaryPreviewTerm | null;
}

export interface GlossaryRevisionLintIssue {
  code: string;
  severity: GlossaryLintSeverity;
  termIds: string[];
  affectedValue: string;
}

export interface GlossaryRevisionMatch {
  termId: string;
  revisionId: string;
  matchedText: string;
  targetTerm: string;
  startUtf16: number;
  endUtf16: number;
  alias: boolean;
  doNotTranslate: boolean;
  scope: GlossaryScope;
  priority: number;
}

export interface GlossaryRevisionConflict {
  matchedText: string;
  startUtf16: number;
  endUtf16: number;
  competingTermIds: string[];
  code: string;
}

export interface GlossaryRevisionPreview {
  glossaryId: string;
  baseRevisionId: string | null;
  proposedRevisionId: string;
  glossaryVersion: number;
  canPublish: boolean;
  changeCount: number;
  changes: GlossaryRevisionChange[];
  changesTruncated: boolean;
  errorCount: number;
  warningCount: number;
  lintIssues: GlossaryRevisionLintIssue[];
  lintIssuesTruncated: boolean;
  matchCount: number;
  matches: GlossaryRevisionMatch[];
  matchesTruncated: boolean;
  conflictCount: number;
  conflicts: GlossaryRevisionConflict[];
  conflictsTruncated: boolean;
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
    readonly issueCount: number | null = null,
    readonly issuesTruncated: boolean = false,
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

  async getGlossary(glossaryId: string): Promise<GlossaryCatalogItem> {
    if (!isUuid(glossaryId)) throw invalidQuery();
    try {
      const value: unknown = await firstValueFrom(
        this.http.get<unknown>(`${glossariesUrl}/${glossaryId}`).pipe(timeout(requestTimeoutMs)),
      );
      return parseCatalogItem(value, glossaryId);
    } catch (error) {
      throw toGlossaryApiError(error);
    }
  }

  async getGlossarySnapshot(glossaryId: string): Promise<GlossaryMetadataSnapshot> {
    if (!isUuid(glossaryId)) throw invalidQuery();
    try {
      const response = await firstValueFrom(
        this.http
          .get<unknown>(`${glossariesUrl}/${glossaryId}`, { observe: 'response' })
          .pipe(timeout(requestTimeoutMs)),
      );
      const glossary = parseCatalogItem(response.body, glossaryId);
      const etag = response.headers.get('ETag');
      if (!isEtag(etag) || etag !== `"v${glossary.version}"`) throw invalidResponse();
      return { glossary, etag };
    } catch (error) {
      throw toGlossaryApiError(error);
    }
  }

  async createUserGlossary(input: {
    name: string;
    sourceLanguage: string;
    targetLanguage: string;
  }): Promise<GlossaryCatalogItem> {
    const name = input.name.trim();
    const sourceLanguage = input.sourceLanguage.trim();
    const targetLanguage = input.targetLanguage.trim();
    if (
      !isBoundedString(name, 1, 120) ||
      !isBoundedString(sourceLanguage, 2, 35) ||
      !isBoundedString(targetLanguage, 2, 35)
    ) {
      throw invalidQuery();
    }
    try {
      const headers = (await this.csrfHeaders()).set('Content-Type', 'application/json');
      const response = await firstValueFrom(
        this.http
          .post<unknown>(
            glossariesUrl,
            {
              name,
              scope: 'USER_GLOBAL',
              sourceLanguage,
              targetLanguage,
            },
            { headers, observe: 'response' },
          )
          .pipe(timeout(requestTimeoutMs)),
      );
      const item = parseCatalogItem(response.body);
      const etag = response.headers.get('ETag');
      if (
        !isEtag(etag) ||
        item.scope !== 'USER_GLOBAL' ||
        !item.canWrite ||
        item.activeRevisionId !== null ||
        item.activeRevisionNumber !== null ||
        item.latestRevisionNumber !== null ||
        etag !== `"v${item.version}"`
      ) {
        throw invalidResponse();
      }
      return item;
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

  async exportRevision(
    glossaryId: string,
    format: GlossaryExportFormat,
    revision: GlossaryExportRevision,
  ): Promise<GlossaryExportDownload> {
    if (
      !isUuid(glossaryId) ||
      (format !== 'json' && format !== 'csv' && format !== 'tsv') ||
      (revision !== 'active' && revision !== 'latest')
    ) {
      throw invalidQuery();
    }
    try {
      const params = new HttpParams().set('format', format).set('revision', revision);
      const response = await firstValueFrom(
        this.http
          .get(`${glossariesUrl}/${glossaryId}/export`, {
            params,
            observe: 'response',
            responseType: 'blob',
          })
          .pipe(timeout(30_000)),
      );
      const contentType = response.headers.get('Content-Type')?.split(';', 1)[0].trim();
      const expectedContentType =
        format === 'json'
          ? 'application/json'
          : format === 'csv'
            ? 'text/csv'
            : 'text/tab-separated-values';
      const contentDisposition = response.headers.get('Content-Disposition');
      const fileName = parseExportFileName(contentDisposition, format);
      const etag = response.headers.get('ETag');
      if (
        !(response.body instanceof Blob) ||
        contentType !== expectedContentType ||
        !fileName ||
        !isEtag(etag) ||
        !response.headers.get('Cache-Control')?.toLowerCase().includes('no-store') ||
        response.headers.get('X-Content-Type-Options')?.toLowerCase() !== 'nosniff'
      ) {
        throw invalidResponse();
      }
      return { blob: response.body, fileName, etag, format, revision };
    } catch (error) {
      throw toGlossaryApiError(error);
    }
  }

  async getLatestWritableRevision(glossaryId: string): Promise<GlossaryWritableRevision> {
    if (!isUuid(glossaryId)) throw invalidQuery();
    try {
      const response = await firstValueFrom(
        this.http
          .get<unknown>(`${glossariesUrl}/${glossaryId}/latest-revision`, {
            observe: 'response',
          })
          .pipe(timeout(requestTimeoutMs)),
      );
      return parseWritableRevision(response, glossaryId);
    } catch (error) {
      throw toGlossaryApiError(error);
    }
  }

  async createDraftRevision(
    glossaryId: string,
    etag: string,
    terms: GlossaryDraftTermInput[],
  ): Promise<GlossaryDraftCreated> {
    if (!isUuid(glossaryId) || !isEtag(etag) || !isValidDraftTermList(terms)) {
      throw invalidQuery();
    }
    try {
      const headers = (await this.csrfHeaders())
        .set('Content-Type', 'application/json')
        .set('If-Match', etag);
      const response = await firstValueFrom(
        this.http
          .post<unknown>(
            `${glossariesUrl}/${glossaryId}/revisions`,
            { terms },
            { headers, observe: 'response' },
          )
          .pipe(timeout(requestTimeoutMs)),
      );
      return parseDraftCreated(response, glossaryId);
    } catch (error) {
      throw toGlossaryApiError(error);
    }
  }

  async previewGlossaryRevision(
    glossaryId: string,
    revisionId: string,
    etag: string,
    sampleSource: string,
  ): Promise<GlossaryRevisionPreview> {
    if (
      !isUuid(glossaryId) ||
      !isUuid(revisionId) ||
      !isEtag(etag) ||
      typeof sampleSource !== 'string'
    ) {
      throw invalidQuery();
    }
    if (codePoints(sampleSource) > 4000) {
      throw new GlossaryApiError(
        'GLOSSARY_PREVIEW_SAMPLE_TOO_LARGE',
        413,
        'GLOSSARY_PREVIEW_SAMPLE_TOO_LARGE',
      );
    }
    try {
      const headers = (await this.csrfHeaders())
        .set('Content-Type', 'application/json')
        .set('If-Match', etag);
      const response = await firstValueFrom(
        this.http
          .post<unknown>(
            `${glossariesUrl}/${glossaryId}/revisions/${revisionId}/preview`,
            { sampleSource },
            { headers, observe: 'response' },
          )
          .pipe(timeout(requestTimeoutMs)),
      );
      return parseRevisionPreview(response, glossaryId, revisionId, etag, sampleSource);
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
            headers: (await this.csrfHeaders())
              .set('Content-Type', mediaType(format))
              .set('If-Match', etag),
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
  const items = value['items'].map((item) => parseCatalogItem(item));
  if (new Set(items.map((item) => item.id)).size !== items.length) throw invalidResponse();
  return { items, nextCursor: value['nextCursor'] as string | null };
}

function parseCatalogItem(value: unknown, expectedId?: string): GlossaryCatalogItem {
  if (
    !isRecord(value) ||
    !isUuid(value['id']) ||
    (expectedId !== undefined && value['id'] !== expectedId) ||
    !isBoundedString(value['name'], 1, 120) ||
    !isScope(value['scope']) ||
    !isBoundedString(value['sourceLanguage'], 2, 35) ||
    !isBoundedString(value['targetLanguage'], 2, 35) ||
    !isPositiveInteger(value['version']) ||
    !isNullableUuid(value['activeRevisionId']) ||
    !isNullablePositiveInteger(value['activeRevisionNumber']) ||
    !isNullablePositiveInteger(value['latestRevisionNumber']) ||
    (value['activeRevisionId'] === null) !== (value['activeRevisionNumber'] === null) ||
    (value['canWrite'] === false && value['latestRevisionNumber'] !== null) ||
    (value['canWrite'] === true &&
      value['activeRevisionNumber'] !== null &&
      value['latestRevisionNumber'] === null) ||
    (value['activeRevisionNumber'] !== null &&
      value['latestRevisionNumber'] !== null &&
      value['latestRevisionNumber'] < value['activeRevisionNumber']) ||
    !isTimestamp(value['createdAt']) ||
    typeof value['canWrite'] !== 'boolean'
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
    activeRevisionNumber: value['activeRevisionNumber'],
    latestRevisionNumber: value['latestRevisionNumber'],
    createdAt: value['createdAt'],
    canWrite: value['canWrite'],
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

function parseWritableRevision(
  response: HttpResponse<unknown>,
  expectedId: string,
): GlossaryWritableRevision {
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
    etag !== `"v${value['version']}"` ||
    !isNullableUuid(value['activeRevisionId']) ||
    !isUuid(value['revisionId']) ||
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
    revisionId: value['revisionId'],
    revisionNumber: value['revisionNumber'],
    parentRevisionId: value['parentRevisionId'],
    revisionCreatedAt: value['revisionCreatedAt'],
    terms,
    etag,
  };
}

function parseDraftCreated(
  response: HttpResponse<unknown>,
  expectedId: string,
): GlossaryDraftCreated {
  const value = response.body;
  const etag = response.headers.get('ETag');
  if (
    !isRecord(value) ||
    !isEtag(etag) ||
    value['glossaryId'] !== expectedId ||
    !isUuid(value['revisionId']) ||
    !isPositiveInteger(value['revisionNumber']) ||
    !isNullableUuid(value['parentRevisionId']) ||
    !isPositiveInteger(value['glossaryVersion']) ||
    etag !== `"v${value['glossaryVersion']}"`
  ) {
    throw invalidResponse();
  }
  return {
    glossaryId: value['glossaryId'],
    revisionId: value['revisionId'],
    revisionNumber: value['revisionNumber'],
    parentRevisionId: value['parentRevisionId'],
    glossaryVersion: value['glossaryVersion'],
    etag,
  };
}

function parseRevisionPreview(
  response: HttpResponse<unknown>,
  expectedGlossaryId: string,
  expectedRevisionId: string,
  expectedEtag: string,
  sampleSource: string,
): GlossaryRevisionPreview {
  const value = response.body;
  const etag = response.headers.get('ETag');
  if (
    !isRecord(value) ||
    !isEtag(etag) ||
    etag !== expectedEtag ||
    value['glossaryId'] !== expectedGlossaryId ||
    value['proposedRevisionId'] !== expectedRevisionId ||
    !isNullableUuid(value['baseRevisionId']) ||
    !isPositiveInteger(value['glossaryVersion']) ||
    etag !== `"v${value['glossaryVersion']}"` ||
    typeof value['canPublish'] !== 'boolean' ||
    !isNonNegativeInteger(value['changeCount']) ||
    !Array.isArray(value['changes']) ||
    value['changes'].length > 100 ||
    typeof value['changesTruncated'] !== 'boolean' ||
    !isNonNegativeInteger(value['errorCount']) ||
    !isNonNegativeInteger(value['warningCount']) ||
    !Array.isArray(value['lintIssues']) ||
    value['lintIssues'].length > 50 ||
    typeof value['lintIssuesTruncated'] !== 'boolean' ||
    !isNonNegativeInteger(value['matchCount']) ||
    !Array.isArray(value['matches']) ||
    value['matches'].length > 100 ||
    typeof value['matchesTruncated'] !== 'boolean' ||
    !isNonNegativeInteger(value['conflictCount']) ||
    !Array.isArray(value['conflicts']) ||
    value['conflicts'].length > 50 ||
    typeof value['conflictsTruncated'] !== 'boolean'
  ) {
    throw invalidResponse();
  }

  const changes = value['changes'].map(parseRevisionChange);
  const lintIssues = value['lintIssues'].map(parseRevisionLintIssue);
  const matches = value['matches'].map((match) => parseRevisionMatch(match, sampleSource));
  const conflicts = value['conflicts'].map((conflict) =>
    parseRevisionConflict(conflict, sampleSource),
  );
  if (
    value['changeCount'] < changes.length ||
    value['errorCount'] < lintIssues.filter((issue) => issue.severity === 'ERROR').length ||
    value['warningCount'] < lintIssues.filter((issue) => issue.severity === 'WARNING').length ||
    value['canPublish'] !== (value['errorCount'] === 0) ||
    value['matchCount'] < matches.length ||
    value['conflictCount'] < conflicts.length ||
    value['changesTruncated'] !== value['changeCount'] > changes.length ||
    value['lintIssuesTruncated'] !==
      value['errorCount'] + value['warningCount'] > lintIssues.length ||
    value['matchesTruncated'] !== value['matchCount'] > matches.length ||
    value['conflictsTruncated'] !== value['conflictCount'] > conflicts.length ||
    changes.some(
      (change) =>
        (change.before !== null && change.before.revisionId !== value['baseRevisionId']) ||
        (change.after !== null && change.after.revisionId !== expectedRevisionId),
    ) ||
    matches.some((match) => match.revisionId !== expectedRevisionId) ||
    (value['baseRevisionId'] === null && changes.some((change) => change.before !== null))
  ) {
    throw invalidResponse();
  }

  return {
    glossaryId: value['glossaryId'],
    baseRevisionId: value['baseRevisionId'],
    proposedRevisionId: value['proposedRevisionId'],
    glossaryVersion: value['glossaryVersion'],
    canPublish: value['canPublish'],
    changeCount: value['changeCount'],
    changes,
    changesTruncated: value['changesTruncated'],
    errorCount: value['errorCount'],
    warningCount: value['warningCount'],
    lintIssues,
    lintIssuesTruncated: value['lintIssuesTruncated'],
    matchCount: value['matchCount'],
    matches,
    matchesTruncated: value['matchesTruncated'],
    conflictCount: value['conflictCount'],
    conflicts,
    conflictsTruncated: value['conflictsTruncated'],
    etag,
  };
}

function parseRevisionChange(value: unknown): GlossaryRevisionChange {
  if (
    !isRecord(value) ||
    (value['kind'] !== 'ADDED' && value['kind'] !== 'REMOVED' && value['kind'] !== 'UPDATED') ||
    !isUuid(value['termId'])
  ) {
    throw invalidResponse();
  }
  const before = value['before'] === null ? null : parsePreviewTerm(value['before']);
  const after = value['after'] === null ? null : parsePreviewTerm(value['after']);
  if (
    (value['kind'] === 'ADDED' && (before !== null || after === null)) ||
    (value['kind'] === 'REMOVED' && (before === null || after !== null)) ||
    (value['kind'] === 'UPDATED' && (before === null || after === null)) ||
    (before && before.id !== value['termId']) ||
    (after && after.id !== value['termId'])
  ) {
    throw invalidResponse();
  }
  return { kind: value['kind'], termId: value['termId'], before, after };
}

function parsePreviewTerm(value: unknown): GlossaryPreviewTerm {
  if (
    !isRecord(value) ||
    !isUuid(value['id']) ||
    !isUuid(value['revisionId']) ||
    !isBoundedString(value['sourceLanguage'], 2, 64) ||
    !isBoundedString(value['sourceTerm'], 1, 512) ||
    !isNullableBoundedString(value['targetTerm'], 4096) ||
    !isStringArray(value['aliases'], 25, 512) ||
    !isScope(value['scope']) ||
    !isIntegerInRange(value['priority'], -1_000_000, 1_000_000) ||
    !isCasePolicy(value['casePolicy']) ||
    value['matchMode'] !== 'WHOLE_PHRASE' ||
    typeof value['doNotTranslate'] !== 'boolean' ||
    !isPositiveInteger(value['ordinal']) ||
    !isNullableBoundedString(value['contextNote'], 2000) ||
    (value['doNotTranslate'] ? value['targetTerm'] !== null : !value['targetTerm'])
  ) {
    throw invalidResponse();
  }
  return {
    id: value['id'],
    revisionId: value['revisionId'],
    sourceLanguage: value['sourceLanguage'],
    sourceTerm: value['sourceTerm'],
    targetTerm: value['targetTerm'],
    aliases: value['aliases'],
    scope: value['scope'],
    priority: value['priority'],
    casePolicy: value['casePolicy'],
    matchMode: 'WHOLE_PHRASE',
    doNotTranslate: value['doNotTranslate'],
    ordinal: value['ordinal'],
    contextNote: value['contextNote'],
  };
}

function parseRevisionLintIssue(value: unknown): GlossaryRevisionLintIssue {
  if (
    !isRecord(value) ||
    !isBoundedString(value['code'], 1, 64) ||
    (value['severity'] !== 'WARNING' && value['severity'] !== 'ERROR') ||
    !Array.isArray(value['termIds']) ||
    value['termIds'].length > 1000 ||
    !value['termIds'].every(isUuid) ||
    !isBoundedString(value['affectedValue'], 0, 4096)
  ) {
    throw invalidResponse();
  }
  return {
    code: value['code'],
    severity: value['severity'],
    termIds: value['termIds'],
    affectedValue: value['affectedValue'],
  };
}

function parseRevisionMatch(value: unknown, sampleSource: string): GlossaryRevisionMatch {
  if (
    !isRecord(value) ||
    !isUuid(value['termId']) ||
    !isUuid(value['revisionId']) ||
    !isBoundedString(value['matchedText'], 1, 4000) ||
    !isBoundedString(value['targetTerm'], 0, 4096) ||
    !isNonNegativeInteger(value['startUtf16']) ||
    !isPositiveInteger(value['endUtf16']) ||
    value['endUtf16'] <= value['startUtf16'] ||
    value['endUtf16'] > sampleSource.length ||
    sampleSource.slice(value['startUtf16'], value['endUtf16']) !== value['matchedText'] ||
    typeof value['alias'] !== 'boolean' ||
    typeof value['doNotTranslate'] !== 'boolean' ||
    !isScope(value['scope']) ||
    !isIntegerInRange(value['priority'], -1_000_000, 1_000_000)
  ) {
    throw invalidResponse();
  }
  return {
    termId: value['termId'],
    revisionId: value['revisionId'],
    matchedText: value['matchedText'],
    targetTerm: value['targetTerm'],
    startUtf16: value['startUtf16'],
    endUtf16: value['endUtf16'],
    alias: value['alias'],
    doNotTranslate: value['doNotTranslate'],
    scope: value['scope'],
    priority: value['priority'],
  };
}

function parseRevisionConflict(value: unknown, sampleSource: string): GlossaryRevisionConflict {
  if (
    !isRecord(value) ||
    !isBoundedString(value['matchedText'], 1, 4000) ||
    !isNonNegativeInteger(value['startUtf16']) ||
    !isPositiveInteger(value['endUtf16']) ||
    value['endUtf16'] <= value['startUtf16'] ||
    value['endUtf16'] > sampleSource.length ||
    sampleSource.slice(value['startUtf16'], value['endUtf16']) !== value['matchedText'] ||
    !Array.isArray(value['competingTermIds']) ||
    value['competingTermIds'].length < 2 ||
    value['competingTermIds'].length > 1000 ||
    !value['competingTermIds'].every(isUuid) ||
    !isBoundedString(value['code'], 1, 64)
  ) {
    throw invalidResponse();
  }
  return {
    matchedText: value['matchedText'],
    startUtf16: value['startUtf16'],
    endUtf16: value['endUtf16'],
    competingTermIds: value['competingTermIds'],
    code: value['code'],
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
    const issueCount =
      isRecord(body) && isNonNegativeInteger(body['issueCount']) ? body['issueCount'] : null;
    const issuesTruncated =
      isRecord(body) && typeof body['issuesTruncated'] === 'boolean'
        ? body['issuesTruncated']
        : issueCount !== null && issueCount > issues.length;
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
      issueCount,
      issuesTruncated,
    );
  }
  return new GlossaryApiError('GLOSSARY_REQUEST_FAILED', -1, null);
}

function parseEtag(value: string | null): string | null {
  return value && etagPattern.test(value) ? value : null;
}

function parseExportFileName(value: string | null, format: GlossaryExportFormat): string | null {
  if (!value) return null;
  const match =
    /^attachment; filename="([A-Za-z0-9_-]{1,64}-r[1-9][0-9]{0,18}\.(json|csv|tsv))"$/.exec(value);
  return match?.[2] === format ? match[1] : null;
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

function isValidDraftTermList(value: unknown[]): value is GlossaryDraftTermInput[] {
  if (value.length > 1000) return false;
  const ids = new Set<string>();
  let aliasCount = 0;
  for (const term of value) {
    if (
      !isRecord(term) ||
      !isUuid(term['id']) ||
      ids.has(term['id']) ||
      !isBoundedString(term['sourceTerm'], 1, 512) ||
      !isNullableBoundedString(term['targetTerm'], 4096) ||
      !isIntegerInRange(term['priority'], -1_000_000, 1_000_000) ||
      !isCasePolicy(term['casePolicy']) ||
      term['matchMode'] !== 'WHOLE_PHRASE' ||
      typeof term['doNotTranslate'] !== 'boolean' ||
      !isNullableBoundedString(term['contextNote'], 2000) ||
      !isStringArray(term['aliases'], 25, 512) ||
      (!term['doNotTranslate'] && !isBoundedString(term['targetTerm'], 1, 4096))
    ) {
      return false;
    }
    if (
      term['sourceTerm'].trim().length === 0 ||
      (typeof term['targetTerm'] === 'string' &&
        !term['doNotTranslate'] &&
        term['targetTerm'].trim().length === 0) ||
      term['aliases'].some((alias) => alias.trim().length === 0)
    ) {
      return false;
    }
    ids.add(term['id']);
    aliasCount += term['aliases'].length;
    if (aliasCount > 5000) return false;
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

function codePoints(value: string): number {
  return [...value].length;
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
