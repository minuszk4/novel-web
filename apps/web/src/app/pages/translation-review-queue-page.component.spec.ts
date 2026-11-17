import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { TranslationReviewApiService } from '../core/translation-review-api.service';
import { TranslationReviewQueuePageComponent } from './translation-review-queue-page.component';

const submissionId = '11111111-1111-4111-8111-111111111111';

describe('TranslationReviewQueuePageComponent', () => {
  const getQueue = vi.fn();

  beforeEach(() => {
    getQueue.mockReset();
    TestBed.configureTestingModule({
      imports: [TranslationReviewQueuePageComponent],
      providers: [
        provideRouter([]),
        { provide: TranslationReviewApiService, useValue: { getQueue } },
      ],
    });
  });

  it('shows only queue metadata and links to a current source snapshot', async () => {
    getQueue.mockResolvedValue({
      items: [
        {
          submissionId,
          workTitle: 'Bến mây',
          chapterTitle: 'Chương một',
          sourceLanguage: 'vi',
          targetLanguage: 'en',
          segmentCount: 8,
          submitterDisplayName: 'Tác giả',
          submittedAt: '2026-11-17T07:00:00Z',
          sourceCurrent: true,
        },
      ],
      nextCursor: null,
      hasMore: false,
    });
    const fixture = TestBed.createComponent(TranslationReviewQueuePageComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Hàng đợi duyệt bản dịch');
    expect(fixture.nativeElement.textContent).toContain('Bến mây');
    expect(fixture.nativeElement.textContent).toContain('8 đoạn');
    const workLink = [...fixture.nativeElement.querySelectorAll('a')].find((anchor) =>
      anchor.textContent.includes('Bến mây'),
    ) as HTMLAnchorElement | undefined;
    expect(workLink?.getAttribute('href')).toContain(submissionId);
    expect(fixture.nativeElement.textContent).toContain('Duyệt không xuất bản');
    expect(getQueue).toHaveBeenCalledWith(null, 20);
  });

  it('does not link a snapshot when its source has changed', async () => {
    getQueue.mockResolvedValue({
      items: [
        {
          submissionId,
          workTitle: 'Bến mây',
          chapterTitle: 'Chương một',
          sourceLanguage: 'vi',
          targetLanguage: 'en',
          segmentCount: 8,
          submitterDisplayName: 'Tác giả',
          submittedAt: '2026-11-17T07:00:00Z',
          sourceCurrent: false,
        },
      ],
      nextCursor: null,
      hasMore: false,
    });
    const fixture = TestBed.createComponent(TranslationReviewQueuePageComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Nguyên tác đã thay đổi');
    expect(fixture.nativeElement.querySelector(`a[href*="${submissionId}"]`)).toBeNull();
  });
});
