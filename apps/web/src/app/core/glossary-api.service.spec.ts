import { HttpHeaders, provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  TestRequest,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  GlossaryApiError,
  GlossaryApiService,
  GlossaryImportResolution,
} from './glossary-api.service';

const glossaryId = '11111111-1111-4111-8111-111111111111';
const activeRevisionId = '22222222-2222-4222-8222-222222222222';
const baseRevisionId = '33333333-3333-4333-8333-333333333333';
const termId = '44444444-4444-4444-8444-444444444444';
const csrfToken = 'A'.repeat(43);
const timestamp = '2026-11-14T05:00:00Z';

const currentTerm = {
  id: termId,
  ordinal: 1,
  sourceTerm: '金丹',
  targetTerm: 'Kim Đan',
  priority: 0,
  casePolicy: 'INSENSITIVE',
  matchMode: 'WHOLE_PHRASE',
  doNotTranslate: false,
  contextNote: null,
  aliases: ['Golden Core'],
};

const previewPayload = {
  glossaryId,
  glossaryName: 'Tu luyện',
  scope: 'LIBRARY',
  sourceLanguage: 'zh-Hans',
  targetLanguage: 'vi',
  glossaryVersion: 4,
  activeRevisionId,
  activeRevisionNumber: 1,
  baseRevisionId,
  baseRevisionNumber: 2,
  currentTerms: [currentTerm],
  importedTerms: [
    {
      rowNumber: 2,
      importedTerm: {
        rowNumber: 2,
        sourceTerm: '丹田',
        targetTerm: 'Đan điền',
        priority: 0,
        casePolicy: 'INSENSITIVE',
        matchMode: 'WHOLE_PHRASE',
        doNotTranslate: false,
        contextNote: null,
        aliases: [],
      },
      suggestedAction: 'ADD',
      existingMatches: [],
      matchesTruncated: false,
    },
  ],
  counts: { imported: 1, add: 1, update: 0, unchanged: 0, requiresResolution: 0 },
  translationImpact: {
    status: 'UNAVAILABLE',
    affectedTranslationUnitCount: null,
    detail: 'Translation units are not implemented yet.',
  },
};

const resolution: GlossaryImportResolution = {
  rowNumber: 2,
  action: 'ADD',
  targetTermId: null,
  term: {
    sourceTerm: '丹田',
    targetTerm: 'Đan điền',
    priority: 0,
    casePolicy: 'INSENSITIVE',
    matchMode: 'WHOLE_PHRASE',
    doNotTranslate: false,
    contextNote: null,
    aliases: [],
  },
};

describe('GlossaryApiService', () => {
  let service: GlossaryApiService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(GlossaryApiService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('loads and validates actor-visible glossary pages with bounded filters', async () => {
    const pending = service.listGlossaries({
      scope: 'LIBRARY',
      libraryId: glossaryId,
      query: 'tu luyện',
    });
    const request = http.expectOne(
      (candidate) =>
        candidate.method === 'GET' &&
        candidate.url === '/api/v1/glossaries' &&
        candidate.params.get('limit') === '25' &&
        candidate.params.get('scope') === 'LIBRARY' &&
        candidate.params.get('libraryId') === glossaryId &&
        candidate.params.get('q') === 'tu luyện',
    );
    request.flush({
      items: [
        {
          id: glossaryId,
          name: 'Tu luyện',
          scope: 'LIBRARY',
          sourceLanguage: 'zh-Hans',
          targetLanguage: 'vi',
          version: 4,
          activeRevisionId,
          activeRevisionNumber: 1,
          latestRevisionNumber: 1,
          createdAt: timestamp,
          canWrite: true,
        },
      ],
      nextCursor: null,
    });

    await expect(pending).resolves.toMatchObject({
      items: [{ id: glossaryId, scope: 'LIBRARY', version: 4, canWrite: true }],
      nextCursor: null,
    });
  });

  it('fails closed when the catalog omits the server-computed write capability', async () => {
    const pending = service.listGlossaries();
    http.expectOne({ method: 'GET', url: '/api/v1/glossaries?limit=25' }).flush({
      items: [
        {
          id: glossaryId,
          name: 'Tu luyện',
          scope: 'LIBRARY',
          sourceLanguage: 'zh-Hans',
          targetLanguage: 'vi',
          version: 4,
          activeRevisionId,
          activeRevisionNumber: 1,
          latestRevisionNumber: 1,
          createdAt: timestamp,
        },
      ],
      nextCursor: null,
    });

    await expect(pending).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
  });

  it('loads visible metadata by ID and rejects a response for another glossary', async () => {
    const pending = service.getGlossary(glossaryId);
    http.expectOne({ method: 'GET', url: `/api/v1/glossaries/${glossaryId}` }).flush({
      id: glossaryId,
      name: 'Tu luyện',
      scope: 'LIBRARY',
      sourceLanguage: 'zh-Hans',
      targetLanguage: 'vi',
      version: 4,
      activeRevisionId,
      activeRevisionNumber: 1,
      latestRevisionNumber: null,
      createdAt: timestamp,
      canWrite: false,
    });
    await expect(pending).resolves.toMatchObject({ id: glossaryId, canWrite: false });

    const mismatched = service.getGlossary(glossaryId);
    http.expectOne({ method: 'GET', url: `/api/v1/glossaries/${glossaryId}` }).flush({
      id: activeRevisionId,
      name: 'Wrong glossary',
      scope: 'LIBRARY',
      sourceLanguage: 'zh-Hans',
      targetLanguage: 'vi',
      version: 4,
      activeRevisionId: null,
      activeRevisionNumber: null,
      latestRevisionNumber: null,
      createdAt: timestamp,
      canWrite: true,
    });
    await expect(mismatched).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
  });

  it('loads a strong metadata ETag for an unpublished glossary and checks its version', async () => {
    const pending = service.getGlossarySnapshot(glossaryId);
    http.expectOne({ method: 'GET', url: `/api/v1/glossaries/${glossaryId}` }).flush(
      {
        id: glossaryId,
        name: 'Tu luyện',
        scope: 'LIBRARY',
        sourceLanguage: 'zh-Hans',
        targetLanguage: 'vi',
        version: 1,
        activeRevisionId: null,
        activeRevisionNumber: null,
        latestRevisionNumber: 1,
        createdAt: timestamp,
        canWrite: true,
      },
      { headers: new HttpHeaders({ ETag: '"v1"' }) },
    );
    await expect(pending).resolves.toMatchObject({
      glossary: { id: glossaryId, version: 1, activeRevisionId: null },
      etag: '"v1"',
    });

    const mismatched = service.getGlossarySnapshot(glossaryId);
    http.expectOne({ method: 'GET', url: `/api/v1/glossaries/${glossaryId}` }).flush(
      {
        id: glossaryId,
        name: 'Tu luyện',
        scope: 'LIBRARY',
        sourceLanguage: 'zh-Hans',
        targetLanguage: 'vi',
        version: 2,
        activeRevisionId: null,
        activeRevisionNumber: null,
        latestRevisionNumber: 1,
        createdAt: timestamp,
        canWrite: true,
      },
      { headers: new HttpHeaders({ ETag: '"v1"' }) },
    );
    await expect(mismatched).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
  });

  it('creates a private user glossary with CSRF and a matching version ETag', async () => {
    const pending = service.createUserGlossary({
      name: '  Personal terms  ',
      sourceLanguage: ' zh-Hans ',
      targetLanguage: ' vi ',
    });
    http.expectOne({ method: 'GET', url: '/api/v1/auth/csrf' }).flush({ token: csrfToken });
    let request: TestRequest | undefined;
    await vi.waitFor(() => {
      request = http.expectOne({ method: 'POST', url: '/api/v1/glossaries' });
    });
    expect(request).toBeDefined();
    const createRequest = request!;
    expect(createRequest.request.headers.get('X-CSRF-Token')).toBe(csrfToken);
    expect(createRequest.request.body).toEqual({
      name: 'Personal terms',
      scope: 'USER_GLOBAL',
      sourceLanguage: 'zh-Hans',
      targetLanguage: 'vi',
    });
    createRequest.flush(
      {
        id: glossaryId,
        name: 'Personal terms',
        scope: 'USER_GLOBAL',
        sourceLanguage: 'zh-Hans',
        targetLanguage: 'vi',
        version: 1,
        activeRevisionId: null,
        activeRevisionNumber: null,
        latestRevisionNumber: null,
        createdAt: timestamp,
        canWrite: true,
      },
      { status: 201, statusText: 'Created', headers: new HttpHeaders({ ETag: '"v1"' }) },
    );

    await expect(pending).resolves.toMatchObject({ id: glossaryId, canWrite: true });
  });

  it('loads the immutable active revision only with a strong ETag and ordered terms', async () => {
    const pending = service.getActiveRevision(glossaryId);
    http
      .expectOne({ method: 'GET', url: `/api/v1/glossaries/${glossaryId}/active-revision` })
      .flush(
        {
          glossaryId,
          name: 'Tu luyện',
          scope: 'LIBRARY',
          sourceLanguage: 'zh-Hans',
          targetLanguage: 'vi',
          version: 4,
          activeRevisionId,
          revisionNumber: 1,
          parentRevisionId: null,
          revisionCreatedAt: timestamp,
          terms: [currentTerm],
        },
        { headers: new HttpHeaders({ ETag: '"v4"' }) },
      );

    await expect(pending).resolves.toMatchObject({
      activeRevisionId,
      etag: '"v4"',
      terms: [{ id: termId, ordinal: 1 }],
    });
  });

  it('previews the selected raw file using the current ETag without persisting it', async () => {
    const file = new File(['{"version":1,"terms":[]}'], 'terms.json', { type: 'application/json' });
    const pending = service.previewImport(glossaryId, '"v4"', file);
    const request = http.expectOne({
      method: 'POST',
      url: `/api/v1/glossaries/${glossaryId}/terms/import-preview`,
    });
    expect(request.request.headers.get('Content-Type')).toBe('application/json');
    expect(request.request.headers.get('If-Match')).toBe('"v4"');
    expect(request.request.body).toBe(file);
    request.flush(previewPayload, { headers: new HttpHeaders({ ETag: '"v4"' }) });

    await expect(pending).resolves.toMatchObject({
      baseRevisionId,
      baseRevisionNumber: 2,
      activeRevisionId,
      currentTerms: [{ id: termId }],
      importedTerms: [{ rowNumber: 2, suggestedAction: 'ADD' }],
      translationImpact: { status: 'UNAVAILABLE', affectedTranslationUnitCount: null },
      etag: '"v4"',
    });
  });

  it('rejects unsupported and oversized files before sending a request', async () => {
    await expect(
      service.previewImport(glossaryId, '"v4"', new File(['x'], 'terms.xml')),
    ).rejects.toMatchObject({ status: 415, code: 'IMPORT_FORMAT_INVALID' });
    await expect(
      service.previewImport(
        glossaryId,
        '"v4"',
        new File([new Uint8Array(1_048_577)], 'terms.json'),
      ),
    ).rejects.toMatchObject({ status: 413, code: 'IMPORT_LIMIT_EXCEEDED' });
  });

  it('keeps the exact serialized request and key for a safe network retry', async () => {
    const source = '{"version":1,"terms":[{"sourceTerm":"x","targetTerm":"y"}]}';
    const file = new File([source], 'terms.json');
    const attempt = await service.createApplyAttempt(file, [resolution]);
    const envelope = JSON.parse(attempt.requestBody) as Record<string, unknown>;
    expect(envelope['format']).toBe('JSON');
    expect(envelope['fileContentBase64']).toBe(btoa(source));
    expect(attempt.idempotencyKey).toMatch(/^[A-Za-z0-9._~-]{16,128}$/);

    const first = service.applyImport(glossaryId, '"v4"', attempt);
    http.expectOne({ method: 'GET', url: '/api/v1/auth/csrf' }).flush({ token: csrfToken });
    await Promise.resolve();
    await Promise.resolve();
    const firstRequest = http.expectOne({
      method: 'POST',
      url: `/api/v1/glossaries/${glossaryId}/terms/import-apply`,
    });
    expect(firstRequest.request.headers.get('If-Match')).toBe('"v4"');
    expect(firstRequest.request.headers.get('Idempotency-Key')).toBe(attempt.idempotencyKey);
    expect(firstRequest.request.headers.get('X-CSRF-Token')).toBe(csrfToken);
    expect(firstRequest.request.body).toBe(attempt.requestBody);
    firstRequest.flush({ code: 'TEMPORARY_FAILURE' }, { status: 503, statusText: 'Unavailable' });
    await expect(first).rejects.toBeInstanceOf(GlossaryApiError);

    const retry = service.applyImport(glossaryId, '"v4"', attempt);
    http.expectOne({ method: 'GET', url: '/api/v1/auth/csrf' }).flush({ token: csrfToken });
    await Promise.resolve();
    await Promise.resolve();
    const retryRequest = http.expectOne({
      method: 'POST',
      url: `/api/v1/glossaries/${glossaryId}/terms/import-apply`,
    });
    expect(retryRequest.request.headers.get('Idempotency-Key')).toBe(
      firstRequest.request.headers.get('Idempotency-Key'),
    );
    expect(retryRequest.request.body).toBe(firstRequest.request.body);
    retryRequest.flush(
      {
        draft: {
          glossaryId,
          revisionId: baseRevisionId,
          revisionNumber: 3,
          parentRevisionId: activeRevisionId,
          glossaryVersion: 5,
        },
        rows: [{ rowNumber: 2, action: 'ADD', termId }],
        skippedRows: 0,
        replayed: true,
      },
      { headers: new HttpHeaders({ ETag: '"v5"' }) },
    );

    await expect(retry).resolves.toMatchObject({
      draft: { revisionNumber: 3, glossaryVersion: 5 },
      rows: [{ termId, action: 'ADD' }],
      replayed: true,
      etag: '"v5"',
    });
  });

  it('returns typed stale and key-reuse conflicts with the current glossary ETag', async () => {
    const attempt = { requestBody: '{"format":"JSON"}', idempotencyKey: 'glossary-apply-key-0001' };
    const pending = service.applyImport(glossaryId, '"v4"', attempt);
    http.expectOne({ method: 'GET', url: '/api/v1/auth/csrf' }).flush({ token: csrfToken });
    await Promise.resolve();
    await Promise.resolve();
    http
      .expectOne({ method: 'POST', url: `/api/v1/glossaries/${glossaryId}/terms/import-apply` })
      .flush(
        { code: 'GLOSSARY_VERSION_STALE' },
        { status: 409, statusText: 'Conflict', headers: new HttpHeaders({ ETag: '"v5"' }) },
      );

    await expect(pending).rejects.toMatchObject({
      status: 409,
      code: 'GLOSSARY_VERSION_STALE',
      currentEtag: '"v5"',
    });
  });

  it('preserves safe row and field issues returned for an invalid import file', async () => {
    const pending = service.previewImport(glossaryId, '"v4"', new File(['{}'], 'terms.json'));
    http
      .expectOne({ method: 'POST', url: `/api/v1/glossaries/${glossaryId}/terms/import-preview` })
      .flush(
        {
          code: 'GLOSSARY_IMPORT_INVALID',
          issueCount: 1,
          issues: [
            {
              code: 'REQUIRED_FIELD_MISSING',
              field: 'targetTerm',
              rowNumber: 4,
              message: 'A target term is required.',
            },
          ],
          issuesTruncated: false,
        },
        { status: 422, statusText: 'Unprocessable Entity' },
      );

    await expect(pending).rejects.toMatchObject({
      status: 422,
      code: 'GLOSSARY_IMPORT_INVALID',
      issueCount: 1,
      issuesTruncated: false,
      issues: [
        {
          code: 'REQUIRED_FIELD_MISSING',
          field: 'targetTerm',
          rowNumber: 4,
          message: 'A target term is required.',
        },
      ],
    });
  });

  it('surfaces idempotency-key misuse without hiding the current version', async () => {
    const attempt = {
      requestBody: '{"format":"JSON","fileContentBase64":"eA==","resolutions":[]}',
      idempotencyKey: 'glossary-apply-key-0001',
    };
    const pending = service.applyImport(glossaryId, '"v4"', attempt);
    http.expectOne({ method: 'GET', url: '/api/v1/auth/csrf' }).flush({ token: csrfToken });
    await Promise.resolve();
    await Promise.resolve();
    http
      .expectOne({ method: 'POST', url: `/api/v1/glossaries/${glossaryId}/terms/import-apply` })
      .flush(
        { code: 'IDEMPOTENCY_KEY_REUSED' },
        { status: 409, statusText: 'Conflict', headers: new HttpHeaders({ ETag: '"v5"' }) },
      );

    await expect(pending).rejects.toMatchObject({
      status: 409,
      code: 'IDEMPOTENCY_KEY_REUSED',
      currentEtag: '"v5"',
    });
  });

  it('rejects malformed preview payloads instead of exposing partially trusted state', async () => {
    const pending = service.previewImport(glossaryId, '"v4"', new File(['{}'], 'terms.json'));
    http
      .expectOne({ method: 'POST', url: `/api/v1/glossaries/${glossaryId}/terms/import-preview` })
      .flush(
        { ...previewPayload, currentTerms: [{ ...currentTerm, ordinal: 2 }] },
        { headers: new HttpHeaders({ ETag: '"v4"' }) },
      );

    await expect(pending).rejects.toMatchObject({ code: 'INVALID_RESPONSE', status: -1 });
  });
});
