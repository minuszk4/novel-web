import { HttpHeaders, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import {
  AccountLibraryCollection,
  AccountWorkState,
  LibraryApiError,
  LibraryApiService,
} from './library-api.service';

const collectionId = '11111111-1111-4111-8111-111111111111';
const workId = '22222222-2222-4222-8222-222222222222';
const collection: AccountLibraryCollection = {
  id: collectionId,
  name: 'Đọc cuối tuần',
  workCount: 2,
  createdAt: '2026-11-10T10:00:00Z',
  updatedAt: '2026-11-10T10:00:00Z',
};
const workState: AccountWorkState = {
  workId,
  readingStatus: 'want-to-read',
  collectionIds: [],
  version: 2,
  updatedAt: '2026-11-10T10:00:00Z',
  etag: '"v2"',
};

describe('LibraryApiService', () => {
  let service: LibraryApiService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(LibraryApiService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('loads only a valid account collection response', async () => {
    const result = service.listCollections();
    http.expectOne({ method: 'GET', url: '/api/v1/me/library/collections' }).flush([collection]);

    await expect(result).resolves.toEqual([collection]);
  });

  it('sends session CSRF before creating and deleting a collection', async () => {
    const creating = service.createCollection(collection.name);
    http.expectOne({ method: 'GET', url: '/api/v1/auth/csrf' }).flush({ token: 'session-bound-token' });
    await Promise.resolve();
    await Promise.resolve();
    const createRequest = http.expectOne({ method: 'POST', url: '/api/v1/me/library/collections' });
    expect(createRequest.request.headers.get('X-CSRF-Token')).toBe('session-bound-token');
    expect(createRequest.request.body).toEqual({ name: collection.name });
    createRequest.flush(collection);
    await expect(creating).resolves.toEqual(collection);

    const deleting = service.deleteCollection(collectionId);
    http.expectOne({ method: 'GET', url: '/api/v1/auth/csrf' }).flush({ token: 'rotated-token' });
    await Promise.resolve();
    await Promise.resolve();
    const deleteRequest = http.expectOne({ method: 'DELETE', url: `/api/v1/me/library/collections/${collectionId}` });
    expect(deleteRequest.request.headers.get('X-CSRF-Token')).toBe('rotated-token');
    deleteRequest.flush(null, { status: 204, statusText: 'No Content' });
    await expect(deleting).resolves.toBeUndefined();
  });

  it('uses the strong ETag and current CSRF token when replacing per-work state', async () => {
    const updating = service.replaceWorkState(workId, workState.etag, {
      readingStatus: 'reading',
      collectionIds: [collectionId],
    });
    http.expectOne({ method: 'GET', url: '/api/v1/auth/csrf' }).flush({ token: 'session-bound-token' });
    await Promise.resolve();
    await Promise.resolve();
    const request = http.expectOne({ method: 'PUT', url: `/api/v1/me/works/${workId}/library-state` });
    expect(request.request.headers.get('If-Match')).toBe('"v2"');
    expect(request.request.headers.get('X-CSRF-Token')).toBe('session-bound-token');
    expect(request.request.body).toEqual({ readingStatus: 'reading', collectionIds: [collectionId] });
    request.flush({ ...workState, readingStatus: 'reading', collectionIds: [collectionId], version: 3 }, {
      headers: new HttpHeaders({ ETag: '"v3"' }),
    });

    await expect(updating).resolves.toMatchObject({
      readingStatus: 'reading',
      collectionIds: [collectionId],
      version: 3,
      etag: '"v3"',
    });
  });

  it('accepts a read-only default state with a null updatedAt and version zero', async () => {
    const defaultState = { ...workState, version: 0, updatedAt: null, etag: '"v0"' };
    const result = service.getWorkState(workId);
    http.expectOne({ method: 'GET', url: `/api/v1/me/works/${workId}/library-state` }).flush(defaultState, {
      headers: new HttpHeaders({ ETag: '"v0"' }),
    });

    await expect(result).resolves.toMatchObject({ version: 0, updatedAt: null, etag: '"v0"' });
  });

  it('returns the latest safe state from a stale-write conflict', async () => {
    const latest = { ...workState, readingStatus: 'completed', version: 3 };
    const updating = service.replaceWorkState(workId, workState.etag, {
      readingStatus: 'reading',
      collectionIds: [],
    });
    http.expectOne({ method: 'GET', url: '/api/v1/auth/csrf' }).flush({ token: 'session-bound-token' });
    await Promise.resolve();
    await Promise.resolve();
    http.expectOne({ method: 'PUT', url: `/api/v1/me/works/${workId}/library-state` }).flush({
      code: 'LIBRARY_STATE_VERSION_STALE',
      latest,
    }, {
      status: 409,
      statusText: 'Conflict',
      headers: new HttpHeaders({ ETag: '"v3"' }),
    });

    await expect(updating).rejects.toBeInstanceOf(LibraryApiError);
    await expect(updating).rejects.toMatchObject({
      status: 409,
      code: 'LIBRARY_STATE_VERSION_STALE',
      latestState: { ...latest, etag: '"v3"' },
    });
  });

  it('rejects malformed collections and ETag/body version mismatches', async () => {
    const malformedCollections = service.listCollections();
    http.expectOne({ method: 'GET', url: '/api/v1/me/library/collections' }).flush([{ ...collection, id: 'not-a-uuid' }]);
    await expect(malformedCollections).rejects.toMatchObject({ code: 'INVALID_API_RESPONSE' });

    const malformedState = service.getWorkState(workId);
    http.expectOne({ method: 'GET', url: `/api/v1/me/works/${workId}/library-state` }).flush(workState, {
      headers: new HttpHeaders({ ETag: '"v1"' }),
    });
    await expect(malformedState).rejects.toMatchObject({ code: 'INVALID_API_RESPONSE' });
  });
});
