import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ReaderApiService, ReaderHistoryItem } from '../core/reader-api.service';
import { ReadingHistoryPageComponent } from './reading-history-page.component';

const workId = '66666666-6666-4666-8666-666666666666';
const chapterId = '77777777-7777-4777-8777-777777777777';
const lastReadAt = '2026-11-11T10:00:00Z';
const item: ReaderHistoryItem = {
  work: {
    id: workId,
    libraryId: '33333333-3333-4333-8333-333333333333',
    title: 'Gió qua núi',
    author: 'Linh An',
    language: 'vi',
    visibility: 'private',
    chapterCount: 8,
    createdAt: '2026-11-10T10:00:00Z',
    lastReadAt,
  },
  chapter: { id: chapterId, ordinal: 3, title: 'Qua khỏi dốc' },
  progress: {
    workId,
    chapterId,
    segmentAnchor: 'chapter-3-p2',
    scrollRatio: 0.4,
    version: 4,
    updatedAt: lastReadAt,
  },
};

describe('ReadingHistoryPageComponent', () => {
  let api: {
    getReadingHistory: ReturnType<typeof vi.fn>;
    deleteReadingHistoryWork: ReturnType<typeof vi.fn>;
    clearReadingHistory: ReturnType<typeof vi.fn>;
  };

  beforeEach(() => {
    api = {
      getReadingHistory: vi.fn().mockResolvedValue({ items: [item], nextCursor: null }),
      deleteReadingHistoryWork: vi.fn().mockResolvedValue(undefined),
      clearReadingHistory: vi.fn().mockResolvedValue(undefined),
    };
    TestBed.configureTestingModule({
      imports: [ReadingHistoryPageComponent],
      providers: [provideRouter([]), { provide: ReaderApiService, useValue: api }],
    });
  });

  it('shows the last readable chapter, last-read date and continue-reading destination', async () => {
    const fixture = TestBed.createComponent(ReadingHistoryPageComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    const element: HTMLElement = fixture.nativeElement;
    expect(element.querySelector('h1')?.textContent?.trim()).toBe('Lịch sử đọc');
    expect(element.textContent).toContain('Qua khỏi dốc');
    expect(element.textContent).toContain('11/11/2026');
    expect(element.querySelector('a.button-primary')?.getAttribute('href')).toBe(
      `/app/reader/${workId}/${chapterId}`,
    );
    expect(element.textContent).toContain('365 ngày gần nhất');
  });

  it('confirms that clearing a work also removes its resume position', async () => {
    api.getReadingHistory
      .mockResolvedValueOnce({ items: [item], nextCursor: null })
      .mockResolvedValueOnce({ items: [], nextCursor: null });
    const fixture = TestBed.createComponent(ReadingHistoryPageComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    const dialog = fixture.nativeElement.querySelector('dialog') as HTMLDialogElement;
    Object.defineProperty(dialog, 'showModal', { value: vi.fn(), configurable: true });
    Object.defineProperty(dialog, 'close', { value: vi.fn(), configurable: true });
    const remove = fixture.nativeElement.querySelector(
      'button[aria-label="Xóa lịch sử đọc của Gió qua núi"]',
    ) as HTMLButtonElement;
    remove.click();
    fixture.detectChanges();
    expect(dialog.textContent).toContain('vị trí đọc đã lưu');
    expect(dialog.textContent).toContain('Gió qua núi');

    const confirm = Array.from(dialog.querySelectorAll('button')).find((button) =>
      button.textContent?.includes('Xóa lịch sử'),
    ) as HTMLButtonElement;
    confirm.click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(api.deleteReadingHistoryWork).toHaveBeenCalledWith(workId);
    expect(fixture.nativeElement.textContent).toContain('Đã xóa “Gió qua núi” khỏi lịch sử.');
    expect(fixture.nativeElement.textContent).toContain('Chưa có vị trí đọc gần đây');
  });
});
