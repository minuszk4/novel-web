import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  TranslationCandidatePreview,
  TranslationJobApiError,
  TranslationJobApiService,
} from '../core/translation-job-api.service';
import { TranslationCandidatePreviewPageComponent } from './translation-candidate-preview-page.component';

const jobId = '66666666-6666-4666-8666-666666666666';
const candidateId = '99999999-9999-4999-8999-999999999999';
const revisionId = '88888888-8888-4888-8888-888888888888';
const segmentId = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';

const makePreview = (
  offset: number,
  count: number,
  totalSegments = 11,
): TranslationCandidatePreview => ({
  jobId,
  candidateId,
  sourceRevisionId: revisionId,
  sourceRevisionCurrent: true,
  sourceLanguage: 'vi',
  targetLanguage: 'en',
  providerId: 'fake-local',
  candidateStatus: 'ready_for_review',
  pageOffset: offset,
  pageLimit: 10,
  totalSegments,
  segments: Array.from({ length: count }, (_, index) => {
    const ordinal = offset + index + 1;
    return {
      sourceSegmentId:
        index === 0 ? segmentId : `aaaaaaaa-aaaa-4aaa-8aaa-${ordinal.toString().padStart(12, '0')}`,
      ordinal,
      sourceType: 'paragraph' as const,
      sourceText: `Nguyên tác ${ordinal}`,
      fakeResultText: `[fake vi->en] Nguyên tác ${ordinal}`,
    };
  }),
});

describe('TranslationCandidatePreviewPageComponent', () => {
  const getCandidatePreview = vi.fn();

  beforeEach(() => {
    getCandidatePreview.mockReset();
    TestBed.configureTestingModule({
      imports: [TranslationCandidatePreviewPageComponent],
      providers: [
        provideRouter([]),
        {
          provide: ActivatedRoute,
          useValue: { paramMap: of(convertToParamMap({ jobId })) },
        },
        {
          provide: TranslationJobApiService,
          useValue: { getCandidatePreview },
        },
      ],
    });
  });

  it('shows the explicit fake-only disclosure and a responsive source/result pair without edit or publish actions', async () => {
    getCandidatePreview.mockResolvedValue(makePreview(0, 10));
    const fixture = TestBed.createComponent(TranslationCandidatePreviewPageComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Đây không phải bản dịch.');
    expect(fixture.nativeElement.textContent).toContain('Nguyên tác 1');
    expect(fixture.nativeElement.textContent).toContain('[fake vi->en] Nguyên tác 1');
    expect(fixture.nativeElement.textContent).toContain('1–10 / 11');
    expect(fixture.nativeElement.textContent).not.toContain('Xuất bản');
    expect(fixture.nativeElement.textContent).not.toContain('Chấp nhận bản dịch');
    expect(fixture.nativeElement.querySelectorAll('.translation-candidate-segment')).toHaveLength(
      10,
    );
    expect(fixture.nativeElement.querySelector('h1').getAttribute('tabindex')).toBe('-1');
    expect(getCandidatePreview).toHaveBeenCalledWith(jobId, 0, 10);
  });

  it('loads bounded next and previous pages and announces the changed range', async () => {
    getCandidatePreview
      .mockResolvedValueOnce(makePreview(0, 10))
      .mockResolvedValueOnce(makePreview(10, 1))
      .mockResolvedValueOnce(makePreview(0, 10));
    const fixture = TestBed.createComponent(TranslationCandidatePreviewPageComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    const nextButton = [...fixture.nativeElement.querySelectorAll('button')].find((button) =>
      button.textContent.includes('Phần tiếp'),
    ) as HTMLButtonElement;
    nextButton.click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(getCandidatePreview).toHaveBeenNthCalledWith(2, jobId, 10, 10);
    expect(fixture.nativeElement.textContent).toContain('11–11 / 11');
    expect(fixture.nativeElement.textContent).toContain('Đang xem phần 11 đến 11 trên 11.');

    const previousButton = [...fixture.nativeElement.querySelectorAll('button')].find((button) =>
      button.textContent.includes('Phần trước'),
    ) as HTMLButtonElement;
    previousButton.click();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(getCandidatePreview).toHaveBeenNthCalledWith(3, jobId, 0, 10);
    expect(fixture.nativeElement.textContent).toContain('1–10 / 11');
  });

  it('explains a concealed permission failure without exposing candidate content', async () => {
    getCandidatePreview.mockRejectedValue(
      new TranslationJobApiError(404, 'TRANSLATION_CANDIDATE_NOT_FOUND'),
    );
    const fixture = TestBed.createComponent(TranslationCandidatePreviewPageComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[role="alert"]')).not.toBeNull();
    expect(fixture.nativeElement.textContent).toContain(
      'Không tìm thấy candidate hoặc bạn không có quyền xem',
    );
    expect(fixture.nativeElement.textContent).not.toContain('Nguyên tác 1');
  });
});
