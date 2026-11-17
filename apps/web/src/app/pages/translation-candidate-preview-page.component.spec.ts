import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  TranslationCandidatePreview,
  TranslationJobApiError,
  TranslationJobApiService,
} from '../core/translation-job-api.service';
import { SystemStatusService } from '../core/system-status.service';
import { TranslationReviewApiService } from '../core/translation-review-api.service';
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
      editedText: null,
      editRevision: 0,
      editEtag: '"edit-0"',
    };
  }),
});

describe('TranslationCandidatePreviewPageComponent', () => {
  const getCandidatePreview = vi.fn();
  const saveCandidateSegmentEdit = vi.fn();
  const getReadiness = vi.fn();
  const submitForReview = vi.fn();
  const features = signal({ features: { translationManualSubmissionEnabled: false } });
  let originalConfirm: typeof window.confirm | undefined;

  beforeEach(() => {
    getCandidatePreview.mockReset();
    saveCandidateSegmentEdit.mockReset();
    getReadiness.mockReset();
    submitForReview.mockReset();
    originalConfirm = window.confirm;
    features.set({ features: { translationManualSubmissionEnabled: false } });
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
          useValue: { getCandidatePreview, saveCandidateSegmentEdit },
        },
        {
          provide: TranslationReviewApiService,
          useValue: { getReadiness, submit: submitForReview },
        },
        { provide: SystemStatusService, useValue: { status: features } },
      ],
    });
  });

  afterEach(() => {
    Object.defineProperty(window, 'confirm', {
      configurable: true,
      writable: true,
      value: originalConfirm,
    });
  });

  it('shows the fake-only disclosure and keeps the private edit separate from source and placeholder', async () => {
    getCandidatePreview.mockResolvedValue(makePreview(0, 10));
    const fixture = TestBed.createComponent(TranslationCandidatePreviewPageComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Đây không phải bản dịch.');
    expect(fixture.nativeElement.textContent).toContain('Nguyên tác 1');
    expect(fixture.nativeElement.textContent).toContain('[fake vi->en] Nguyên tác 1');
    expect(fixture.nativeElement.textContent).toContain('Bản chỉnh sửa riêng tư');
    expect(fixture.nativeElement.textContent).toContain('Chưa có chỉnh sửa riêng được lưu');
    expect(fixture.nativeElement.querySelectorAll('textarea')).toHaveLength(0);
    expect(fixture.nativeElement.querySelectorAll('button').length).toBeGreaterThan(0);
    expect(fixture.nativeElement.textContent).toContain('1–10 / 11');
    expect(fixture.nativeElement.textContent).not.toContain('Xuất bản');
    expect(fixture.nativeElement.textContent).not.toContain('Chấp nhận bản dịch');
    expect(fixture.nativeElement.querySelectorAll('.translation-candidate-segment')).toHaveLength(
      10,
    );
    expect(fixture.nativeElement.querySelector('h1').getAttribute('tabindex')).toBe('-1');
    expect(getCandidatePreview).toHaveBeenCalledWith(jobId, 0, 10);
  });

  it('requires explicit save and displays the saved human edit as a separate value', async () => {
    const savedText = 'Bản biên tập đã lưu.';
    getCandidatePreview
      .mockResolvedValueOnce(makePreview(0, 10))
      .mockResolvedValueOnce(withEdit(makePreview(0, 10), savedText, 1));
    saveCandidateSegmentEdit.mockResolvedValue({
      jobId,
      candidateId,
      sourceSegmentId: segmentId,
      editRevision: 1,
      translatedText: savedText,
      createdAt: '2026-11-15T10:00:00Z',
      replayed: false,
      editEtag: '"edit-1"',
    });
    const fixture = TestBed.createComponent(TranslationCandidatePreviewPageComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    const firstEditButton = fixture.nativeElement.querySelector(
      `[data-segment-id="${segmentId}"]`,
    ) as HTMLButtonElement;
    firstEditButton.click();
    fixture.detectChanges();
    const editor = fixture.nativeElement.querySelector('textarea') as HTMLTextAreaElement;
    expect(editor.value).toBe('');
    editor.value = savedText;
    editor.dispatchEvent(new Event('input', { bubbles: true }));
    fixture.detectChanges();

    expect(saveCandidateSegmentEdit).not.toHaveBeenCalled();
    const saveButton = [...fixture.nativeElement.querySelectorAll('button')].find((button) =>
      button.textContent.includes('Lưu chỉnh sửa'),
    ) as HTMLButtonElement;
    expect(saveButton.disabled).toBe(false);
    saveButton.click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(saveCandidateSegmentEdit).toHaveBeenCalledWith(
      jobId,
      segmentId,
      savedText,
      '"edit-0"',
      expect.stringMatching(/^[A-Za-z0-9._~-]{16,128}$/),
    );
    expect(fixture.nativeElement.textContent).toContain(savedText);
    expect(fixture.nativeElement.textContent).toContain('Bản 1 đã lưu');
    expect(fixture.nativeElement.querySelector('textarea')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain(
      'Đã lưu chỉnh sửa riêng tư cho phần 1, bản 1.',
    );
  });

  it('submits only a ready server snapshot and confirms without exposing prose in the receipt', async () => {
    features.set({ features: { translationManualSubmissionEnabled: true } });
    getCandidatePreview.mockResolvedValue(makePreview(0, 1, 1));
    const snapshotEtag = `"translation-review-${'a'.repeat(64)}"`;
    getReadiness.mockResolvedValue({
      readiness: {
        jobId,
        sourceLanguage: 'vi',
        targetLanguage: 'en',
        editedSegments: 1,
        totalSegments: 1,
        missingSegmentOrdinals: [],
        pendingReview: false,
        snapshotDigest: 'a'.repeat(64),
        readyForSubmission: true,
        blockers: [],
      },
      etag: snapshotEtag,
    });
    submitForReview.mockResolvedValue({
      submissionId: '11111111-1111-4111-8111-111111111111',
      status: 'pending_review',
      snapshotDigest: 'a'.repeat(64),
      segmentCount: 1,
      submittedAt: '2026-11-17T07:00:00Z',
      replayed: false,
    });
    const confirm = vi.fn(() => true);
    Object.defineProperty(window, 'confirm', {
      configurable: true,
      writable: true,
      value: confirm,
    });
    const fixture = TestBed.createComponent(TranslationCandidatePreviewPageComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Chuẩn bị gửi người duyệt');
    const check = [...fixture.nativeElement.querySelectorAll('button')].find((button) =>
      button.textContent.includes('Kiểm tra điều kiện gửi'),
    ) as HTMLButtonElement;
    check.click();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('1 / 1');

    const submit = [...fixture.nativeElement.querySelectorAll('button')].find((button) =>
      button.textContent.includes('Gửi 1 đoạn để duyệt'),
    ) as HTMLButtonElement;
    submit.click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(confirm).toHaveBeenCalledOnce();
    expect(submitForReview).toHaveBeenCalledWith(
      jobId,
      snapshotEtag,
      expect.stringMatching(/^[A-Za-z0-9._~-]{16,128}$/),
    );
    expect(fixture.nativeElement.textContent).toContain('Đã gửi để người duyệt xem xét');
    expect(fixture.nativeElement.textContent).not.toContain('Bản dịch do tác giả gửi');
  });

  it('retries an uncertain save with the identical text, ETag and idempotency key', async () => {
    getCandidatePreview
      .mockResolvedValueOnce(makePreview(0, 10))
      .mockResolvedValueOnce(withEdit(makePreview(0, 10), 'Sửa thử', 1));
    saveCandidateSegmentEdit
      .mockRejectedValueOnce(new TranslationJobApiError(0, 'TRANSLATION_UNAVAILABLE'))
      .mockResolvedValueOnce({
        jobId,
        candidateId,
        sourceSegmentId: segmentId,
        editRevision: 1,
        translatedText: 'Sửa thử',
        createdAt: '2026-11-15T10:00:00Z',
        replayed: true,
        editEtag: '"edit-1"',
      });
    const fixture = TestBed.createComponent(TranslationCandidatePreviewPageComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    (
      fixture.nativeElement.querySelector(`[data-segment-id="${segmentId}"]`) as HTMLButtonElement
    ).click();
    fixture.detectChanges();
    const editor = fixture.nativeElement.querySelector('textarea') as HTMLTextAreaElement;
    editor.value = 'Sửa thử';
    editor.dispatchEvent(new Event('input', { bubbles: true }));
    fixture.detectChanges();
    [...fixture.nativeElement.querySelectorAll('button')]
      .find((button) => button.textContent.includes('Lưu chỉnh sửa'))
      .click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Chưa rõ máy chủ đã nhận chỉnh sửa chưa');
    [...fixture.nativeElement.querySelectorAll('button')]
      .find((button) => button.textContent.includes('Gửi lại đúng lần lưu'))
      .click();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(saveCandidateSegmentEdit).toHaveBeenCalledTimes(2);
    expect(saveCandidateSegmentEdit.mock.calls[1]).toEqual(saveCandidateSegmentEdit.mock.calls[0]);
    expect(fixture.nativeElement.textContent).toContain('Sửa thử');
  });

  it('preserves the local draft on 412 and requires an explicit conflict choice', async () => {
    const latest = makePreview(0, 10);
    latest.segments[0] = {
      ...latest.segments[0],
      editedText: 'Bản khác đã lưu',
      editRevision: 1,
      editEtag: '"edit-1"',
    };
    getCandidatePreview.mockResolvedValueOnce(makePreview(0, 10)).mockResolvedValueOnce(latest);
    saveCandidateSegmentEdit
      .mockRejectedValueOnce(new TranslationJobApiError(412, 'TRANSLATION_EDIT_STALE', '"edit-1"'))
      .mockResolvedValueOnce({
        jobId,
        candidateId,
        sourceSegmentId: segmentId,
        editRevision: 2,
        translatedText: 'Bản nháp của tôi',
        createdAt: '2026-11-15T10:00:00Z',
        replayed: false,
        editEtag: '"edit-2"',
      });
    const fixture = TestBed.createComponent(TranslationCandidatePreviewPageComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    (
      fixture.nativeElement.querySelector(`[data-segment-id="${segmentId}"]`) as HTMLButtonElement
    ).click();
    fixture.detectChanges();
    const editor = fixture.nativeElement.querySelector('textarea') as HTMLTextAreaElement;
    editor.value = 'Bản nháp của tôi';
    editor.dispatchEvent(new Event('input', { bubbles: true }));
    fixture.detectChanges();
    [...fixture.nativeElement.querySelectorAll('button')]
      .find((button) => button.textContent.includes('Lưu chỉnh sửa'))
      .click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect((fixture.nativeElement.querySelector('textarea') as HTMLTextAreaElement).value).toBe(
      'Bản nháp của tôi',
    );
    expect(fixture.nativeElement.textContent).toContain('Bản khác đã lưu');
    expect(fixture.nativeElement.textContent).toContain('Giữ bản nháp của tôi');
    const keepButton = [...fixture.nativeElement.querySelectorAll('button')].find((button) =>
      button.textContent.includes('Giữ bản nháp của tôi'),
    ) as HTMLButtonElement;
    keepButton.click();
    fixture.detectChanges();
    const saveButton = [...fixture.nativeElement.querySelectorAll('button')].find((button) =>
      button.textContent.includes('Lưu chỉnh sửa'),
    ) as HTMLButtonElement;
    expect(saveButton.disabled).toBe(false);
    saveButton.click();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(saveCandidateSegmentEdit.mock.calls[1][3]).toBe('"edit-1"');
    expect(saveCandidateSegmentEdit.mock.calls[1][2]).toBe('Bản nháp của tôi');
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

  it('asks before abandoning an unsaved correction on route navigation', async () => {
    getCandidatePreview.mockResolvedValue(makePreview(0, 10));
    const fixture = TestBed.createComponent(TranslationCandidatePreviewPageComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    (
      fixture.nativeElement.querySelector(`[data-segment-id="${segmentId}"]`) as HTMLButtonElement
    ).click();
    fixture.detectChanges();

    const confirm = vi.fn(() => false);
    Object.defineProperty(window, 'confirm', { configurable: true, value: confirm });
    expect(fixture.componentInstance.canDeactivate()).toBe(false);
    expect(confirm).toHaveBeenCalledWith(
      'Rời trang sẽ bỏ bản nháp chỉnh sửa chưa lưu. Bạn có muốn tiếp tục?',
    );
    confirm.mockImplementation(() => true);
    expect(fixture.componentInstance.canDeactivate()).toBe(true);
    Reflect.deleteProperty(window, 'confirm');
  });
});

function withEdit(
  preview: TranslationCandidatePreview,
  text: string,
  revision: number,
): TranslationCandidatePreview {
  preview.segments[0] = {
    ...preview.segments[0],
    editedText: text,
    editRevision: revision,
    editEtag: `"edit-${revision}"`,
  };
  return preview;
}
