import { afterEach, describe, expect, it, vi } from 'vitest';
import { DemoStateService } from './demo-state.service';
import { PrivateWork } from './models';

const storageKey = 'novel.demo.library.v1';
const work: PrivateWork = {
  id: 'demo-work-1',
  title: 'Quán Trọ Ánh Trăng',
  author: 'Nguyệt Kiều',
  sourceName: 'quyen-truyen-rieng.txt',
  importedAt: '2026-11-10T00:00:00.000Z',
  visibility: 'private',
};

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  localStorage.clear();
});

describe('DemoStateService library persistence', () => {
  it('restores imported metadata, reading status, and collection assignment', () => {
    localStorage.setItem(storageKey, JSON.stringify({
      version: 1,
      importedWork: work,
      readingStatusByWork: { [work.id]: 'reading' },
      collections: [{ id: 'collection-weekend', name: 'Đọc cuối tuần' }],
      collectionIdsByWork: { [work.id]: ['collection-weekend'] },
    }));

    const service = new DemoStateService();

    expect(service.importedWork()).toEqual(work);
    expect(service.readingStatusFor(work.id)).toBe('reading');
    expect(service.isWorkInCollection(work.id, 'collection-weekend')).toBe(true);
  });

  it('persists status changes and collection add/remove for a later browser session', () => {
    const service = new DemoStateService();
    service.importedWork.set(work);
    service.setReadingStatus(work.id, 'completed');
    expect(service.createLibraryCollection('Đọc cuối tuần')).toBe(true);
    const collection = service.libraryCollections()[0];

    service.toggleWorkCollection(work.id, collection.id);
    expect(service.isWorkInCollection(work.id, collection.id)).toBe(true);
    service.toggleWorkCollection(work.id, collection.id);

    const reloadedService = new DemoStateService();
    expect(reloadedService.importedWork()).toEqual(work);
    expect(reloadedService.readingStatusFor(work.id)).toBe('completed');
    expect(reloadedService.isWorkInCollection(work.id, collection.id)).toBe(false);
  });

  it('keeps changes in memory and reports when browser storage is unavailable', () => {
    vi.stubGlobal('localStorage', {
      getItem: () => null,
      setItem: () => {
        throw new DOMException('Storage disabled', 'SecurityError');
      },
    });
    const service = new DemoStateService();

    expect(service.createLibraryCollection('Yêu thích')).toBe(true);

    expect(service.libraryCollections()).toHaveLength(1);
    expect(service.libraryStorageAvailable()).toBe(false);
    expect(service.libraryStorageMessage()).toContain('Không lưu được');
  });

  it('does not load malformed or unsupported saved data', () => {
    localStorage.setItem(storageKey, '{not-json');

    const service = new DemoStateService();

    expect(service.importedWork()).toBeNull();
    expect(service.libraryStorageMessage()).toContain('không cho phép đọc');
  });
});
