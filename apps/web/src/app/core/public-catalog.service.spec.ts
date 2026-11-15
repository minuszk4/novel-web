import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { PublicCatalogApiError, PublicCatalogApiService } from './public-catalog.service';

const workId = '11111111-1111-4111-8111-111111111111';
const publicWork = {
  id: workId,
  title: 'Truyện công khai',
  author: 'Tác giả',
  sourceLanguage: 'vi',
  readyChapterCount: 3,
};

describe('PublicCatalogApiService', () => {
  let api: PublicCatalogApiService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    api = TestBed.inject(PublicCatalogApiService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('normalizes query filters and validates the allow-listed metadata response', async () => {
    const result = api.listWorks({ query: ' ＤＲＡＧＯＮ ', language: 'VI' });
    const request = http.expectOne((candidate) => candidate.url === '/api/v1/catalog/works');

    expect(request.request.method).toBe('GET');
    expect(request.request.params.get('q')).toBe('dragon');
    expect(request.request.params.get('language')).toBe('vi');
    expect(request.request.params.get('limit')).toBe('20');
    request.flush({ items: [publicWork], nextCursor: 'signed-next-page' });

    await expect(result).resolves.toEqual({ items: [publicWork], nextCursor: 'signed-next-page' });
  });

  it('sends the signed cursor without placing it in a filter or changing the page limit', async () => {
    const result = api.listWorks({
      query: 'dragon',
      language: 'vi',
      cursor: 'signed-cursor',
      limit: 5,
    });
    const request = http.expectOne((candidate) => candidate.url === '/api/v1/catalog/works');

    expect(request.request.params.get('cursor')).toBe('signed-cursor');
    expect(request.request.params.get('limit')).toBe('5');
    request.flush({ items: [], nextCursor: null });
    await expect(result).resolves.toEqual({ items: [], nextCursor: null });
  });

  it('rejects invalid local filters before sending a request', async () => {
    await expect(api.listWorks({ query: 'x'.repeat(121) })).rejects.toMatchObject({
      status: 400,
      code: 'INVALID_CATALOG_QUERY',
    });
    await expect(api.listWorks({ language: 'vi!' })).rejects.toMatchObject({
      status: 400,
      code: 'INVALID_CATALOG_QUERY',
    });
    await expect(api.listWorks({ limit: 51 })).rejects.toMatchObject({
      status: 400,
      code: 'INVALID_CATALOG_QUERY',
    });
    http.expectNone('/api/v1/catalog/works');
  });

  it('rejects unexpected private fields in a catalog response', async () => {
    const result = api.listWorks();
    http
      .expectOne((candidate) => candidate.url === '/api/v1/catalog/works')
      .flush({
        items: [{ ...publicWork, description: 'must not be returned' }],
        nextCursor: null,
      });

    await expect(result).rejects.toEqual(
      new PublicCatalogApiError(502, 'INVALID_CATALOG_RESPONSE'),
    );
  });

  it('preserves the safe disabled-feature problem code for the landing fallback', async () => {
    const result = api.listWorks();
    http
      .expectOne((candidate) => candidate.url === '/api/v1/catalog/works')
      .flush(
        { code: 'PUBLIC_CATALOG_DISABLED' },
        { status: 503, statusText: 'Service Unavailable' },
      );

    await expect(result).rejects.toEqual(new PublicCatalogApiError(503, 'PUBLIC_CATALOG_DISABLED'));
  });
});
