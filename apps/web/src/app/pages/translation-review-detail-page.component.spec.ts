import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  TranslationReviewApiError,
  TranslationReviewApiService,
} from '../core/translation-review-api.service';
import { TranslationReviewDetailPageComponent } from './translation-review-detail-page.component';

const submissionId = '11111111-1111-4111-8111-111111111111';
const snapshotDigest = 'a'.repeat(64);
const etag = `"translation-review-${snapshotDigest}"`;
const detail = {
  submissionId,
  status: 'pending_review' as const,
  workTitle: 'Bến mây',
  chapterTitle: 'Chương một',
  submitterDisplayName: 'Tác giả',
  sourceLanguage: 'vi',
  targetLanguage: 'en',
  snapshotDigest,
  segmentCount: 1,
  submittedAt: '2026-11-17T07:00:00Z',
  canDecide: true,
  segments: [
    {
      ordinal: 1,
      editRevision: 2,
      translatedSha256: snapshotDigest,
      sourceText: 'Nguyên tác cần đối chiếu.',
      submittedText: 'Bản dịch do tác giả gửi.',
    },
  ],
};

describe('TranslationReviewDetailPageComponent', () => {
  const getDetail = vi.fn();
  const decide = vi.fn();

  beforeEach(() => {
    getDetail.mockReset();
    decide.mockReset();
    TestBed.configureTestingModule({
      imports: [TranslationReviewDetailPageComponent],
      providers: [
        provideRouter([]),
        {
          provide: ActivatedRoute,
          useValue: { paramMap: of(convertToParamMap({ submissionId })) },
        },
        { provide: TranslationReviewApiService, useValue: { getDetail, decide } },
      ],
    });
  });

  it('shows the submitted snapshot beside the source and states the publication boundary', async () => {
    getDetail.mockResolvedValue({ detail, etag });
    const fixture = TestBed.createComponent(TranslationReviewDetailPageComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Nguyên tác cần đối chiếu.');
    expect(fixture.nativeElement.textContent).toContain('Bản dịch do tác giả gửi.');
    expect(fixture.nativeElement.textContent).toContain('không xuất bản');
    expect(fixture.nativeElement.querySelectorAll('.translation-review-segment')).toHaveLength(1);
  });

  it('retries an uncertain reviewer action with the same action, reason, ETag and idempotency key', async () => {
    getDetail.mockResolvedValue({ detail, etag });
    decide
      .mockRejectedValueOnce(new TranslationReviewApiError(0, 'TRANSLATION_REVIEW_UNAVAILABLE'))
      .mockResolvedValueOnce({
        submissionId,
        actionCode: 'REJECT',
        reasonCode: 'MEANING_ERROR',
        snapshotDigest,
        decidedAt: '2026-11-17T07:05:00Z',
        replayed: true,
      });
    const fixture = TestBed.createComponent(TranslationReviewDetailPageComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    const rejectButton = [...fixture.nativeElement.querySelectorAll('button')].find((button) =>
      button.textContent.includes('Từ chối bản này'),
    ) as HTMLButtonElement;
    rejectButton.click();
    fixture.detectChanges();
    const reason = fixture.nativeElement.querySelector(
      '#translation-review-reason',
    ) as HTMLSelectElement;
    reason.value = 'MEANING_ERROR';
    reason.dispatchEvent(new Event('change', { bubbles: true }));
    fixture.detectChanges();
    const confirm = [...fixture.nativeElement.querySelectorAll('button')].find((button) =>
      button.textContent.includes('Xác nhận và ghi nhận'),
    ) as HTMLButtonElement;
    confirm.click();
    await fixture.whenStable();
    fixture.detectChanges();

    const retry = [...fixture.nativeElement.querySelectorAll('button')].find((button) =>
      button.textContent.includes('Gửi lại đúng quyết định'),
    ) as HTMLButtonElement;
    retry.click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(decide).toHaveBeenCalledTimes(2);
    expect(decide.mock.calls[1]).toEqual(decide.mock.calls[0]);
    expect(decide.mock.calls[0]).toEqual([
      submissionId,
      etag,
      expect.stringMatching(/^[A-Za-z0-9._~-]{16,128}$/),
      'REJECT',
      'MEANING_ERROR',
    ]);
    expect(fixture.nativeElement.textContent).toContain('Đã ghi nhận từ chối');
  });
});
