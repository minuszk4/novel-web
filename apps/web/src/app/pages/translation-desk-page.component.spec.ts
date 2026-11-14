import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  TranslationJobApiError,
  TranslationJobApiService,
  TranslationJobStatus,
} from '../core/translation-job-api.service';
import { TranslationDeskPageComponent } from './translation-desk-page.component';

const jobId = '66666666-6666-4666-8666-666666666666';
const workId = '33333333-3333-4333-8333-333333333333';
const chapterId = '77777777-7777-4777-8777-777777777777';
const revisionId = '88888888-8888-4888-8888-888888888888';

const makeStatus = (
  id = jobId,
  overrides: Partial<TranslationJobStatus['job']> = {},
): TranslationJobStatus => {
  const job: TranslationJobStatus['job'] = {
    id,
    workId,
    chapterId,
    sourceRevisionId: revisionId,
    sourceRevisionCurrent: true,
    workTitle: 'Tác phẩm riêng tư',
    chapterTitle: 'Cánh cửa mở',
    chapterOrdinal: 1,
    canManage: true,
    sourceLanguage: 'vi',
    targetLanguage: 'en',
    providerId: 'fake-local',
    status: 'translating',
    totalUnits: 2,
    completedUnits: 1,
    failedUnits: 0,
    queuedUnits: 1,
    runningUnits: 0,
    retryWaitUnits: 0,
    cancelledUnits: 0,
    failureCode: null,
    candidateId: null,
    candidateStatus: null,
    createdAt: '2026-11-14T10:00:00Z',
    updatedAt: '2026-11-14T10:00:01Z',
    startedAt: '2026-11-14T10:00:01Z',
    completedAt: null,
    cancelRequestedAt: null,
    ...overrides,
  };
  const terminalUnits = job.completedUnits + job.failedUnits + job.cancelledUnits;
  return {
    job,
    terminalUnits,
    progressPercent:
      job.totalUnits === 0 ? 0 : Math.min(100, Math.floor((terminalUnits * 100) / job.totalUnits)),
  };
};

describe('TranslationDeskPageComponent', () => {
  const getManageableJobs = vi.fn();

  beforeEach(() => {
    getManageableJobs.mockReset();
    TestBed.configureTestingModule({
      imports: [TranslationDeskPageComponent],
      providers: [
        provideRouter([]),
        { provide: TranslationJobApiService, useValue: { getManageableJobs } },
      ],
    });
  });

  it('renders redacted fake-local job metadata and opens only allowed candidate comparison', async () => {
    getManageableJobs.mockResolvedValue({
      items: [
        makeStatus(jobId, {
          status: 'review_required',
          completedUnits: 2,
          queuedUnits: 0,
          candidateId: revisionId,
          candidateStatus: 'ready_for_review',
        }),
      ],
      nextCursor: null,
    });
    const fixture = TestBed.createComponent(TranslationDeskPageComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Bàn dịch');
    expect(fixture.nativeElement.textContent).toContain('kết quả giả');
    expect(fixture.nativeElement.textContent).toContain('Đơn vị đã xử lý');
    expect(fixture.nativeElement.textContent).toContain('Candidate giả đã sẵn sàng');
    expect(fixture.nativeElement.textContent).not.toContain('sourceText');
    expect(fixture.nativeElement.querySelector('progress').value).toBe(100);
    expect(
      fixture.nativeElement.querySelector(`a[href="/app/translation/jobs/${jobId}/candidate"]`),
    ).not.toBeNull();
  });

  it('appends the next cursor page without reordering or replacing existing jobs', async () => {
    const secondJobId = '99999999-9999-4999-8999-999999999999';
    getManageableJobs
      .mockResolvedValueOnce({ items: [makeStatus()], nextCursor: 'signed.payload' })
      .mockResolvedValueOnce({ items: [makeStatus(secondJobId)], nextCursor: null });
    const fixture = TestBed.createComponent(TranslationDeskPageComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    const loadMore = fixture.nativeElement.querySelector(
      'button.translation-desk-load-more',
    ) as HTMLButtonElement;
    loadMore.click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(getManageableJobs).toHaveBeenNthCalledWith(1, null, 25);
    expect(getManageableJobs).toHaveBeenNthCalledWith(2, 'signed.payload', 25);
    expect(fixture.nativeElement.querySelectorAll('.translation-desk-card').length).toBe(2);
    expect(fixture.nativeElement.textContent).toContain('Đã tải thêm 1 job');
    expect(fixture.nativeElement.querySelector('.translation-desk-load-more')).toBeNull();
  });

  it('keeps the list and asks for a first-page reload when a signed cursor expires', async () => {
    getManageableJobs
      .mockResolvedValueOnce({ items: [makeStatus()], nextCursor: 'signed.payload' })
      .mockRejectedValueOnce(new TranslationJobApiError(400, 'TRANSLATION_CURSOR_INVALID'))
      .mockResolvedValueOnce({ items: [makeStatus()], nextCursor: null });
    const fixture = TestBed.createComponent(TranslationDeskPageComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    fixture.nativeElement.querySelector('.translation-desk-load-more').click();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelectorAll('.translation-desk-card').length).toBe(1);
    expect(fixture.nativeElement.textContent).toContain('Danh sách đã thay đổi');

    fixture.nativeElement.querySelector('.translation-desk-inline-error button').click();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(getManageableJobs).toHaveBeenNthCalledWith(3, null, 25);
    expect(fixture.nativeElement.textContent).toContain('Đã tải lại danh sách');
    expect(fixture.nativeElement.querySelector('.translation-desk-inline-error')).toBeNull();
  });

  it('shows an empty state for actors with no manageable job metadata', async () => {
    getManageableJobs.mockResolvedValue({ items: [], nextCursor: null });
    const fixture = TestBed.createComponent(TranslationDeskPageComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Chưa có job nào bạn có thể quản lý');
    expect(fixture.nativeElement.querySelectorAll('.translation-desk-card').length).toBe(0);
    expect(fixture.nativeElement.textContent).not.toContain('Tác phẩm riêng tư');
  });

  it('offers sign-in with the Translation Desk as a safe return destination', async () => {
    getManageableJobs.mockRejectedValue(new TranslationJobApiError(401, 'UNAUTHENTICATED'));
    const fixture = TestBed.createComponent(TranslationDeskPageComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    const link = fixture.nativeElement.querySelector('a[href^="/sign-in"]') as HTMLAnchorElement;
    expect(link).not.toBeNull();
    expect(decodeURIComponent(link.href)).toContain('/app/translation');
  });
});
