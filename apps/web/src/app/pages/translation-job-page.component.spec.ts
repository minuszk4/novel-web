import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  TranslationJobApiError,
  TranslationJobApiService,
  TranslationJobStatus,
} from '../core/translation-job-api.service';
import { TranslationJobPageComponent } from './translation-job-page.component';

const jobId = '66666666-6666-4666-8666-666666666666';
const workId = '33333333-3333-4333-8333-333333333333';
const chapterId = '77777777-7777-4777-8777-777777777777';
const revisionId = '88888888-8888-4888-8888-888888888888';

const makeStatus = (statusName: string, canManage = false): TranslationJobStatus => ({
  job: {
    id: jobId,
    workId,
    chapterId,
    sourceRevisionId: revisionId,
    sourceRevisionCurrent: true,
    workTitle: 'Tác phẩm riêng tư',
    chapterTitle: 'Cánh cửa mở',
    chapterOrdinal: 1,
    canManage,
    sourceLanguage: 'vi',
    targetLanguage: 'en',
    providerId: 'fake-local',
    status: statusName as TranslationJobStatus['job']['status'],
    totalUnits: 2,
    completedUnits: statusName === 'translating' ? 1 : 0,
    failedUnits: statusName === 'failed' ? 1 : 0,
    queuedUnits: statusName === 'queued' || statusName === 'translating' ? 1 : 0,
    runningUnits: statusName === 'translating' ? 0 : 0,
    retryWaitUnits: 0,
    cancelledUnits: 0,
    failureCode: statusName === 'failed' ? 'TEMPORARY' : null,
    candidateId: statusName === 'review_required' ? revisionId : null,
    candidateStatus: statusName === 'review_required' ? 'ready_for_review' : null,
    createdAt: '2026-11-14T10:00:00Z',
    updatedAt: '2026-11-14T10:00:01Z',
    startedAt: '2026-11-14T10:00:01Z',
    completedAt: null,
    cancelRequestedAt: null,
  },
  terminalUnits: statusName === 'translating' ? 1 : statusName === 'review_required' ? 2 : 0,
  progressPercent: statusName === 'translating' ? 50 : statusName === 'review_required' ? 100 : 0,
});

describe('TranslationJobPageComponent', () => {
  const getStatus = vi.fn();
  const retry = vi.fn();
  const cancel = vi.fn();

  beforeEach(() => {
    getStatus.mockReset();
    retry.mockReset();
    cancel.mockReset();
    TestBed.configureTestingModule({
      imports: [TranslationJobPageComponent],
      providers: [
        provideRouter([]),
        {
          provide: ActivatedRoute,
          useValue: { paramMap: of(convertToParamMap({ jobId })) },
        },
        { provide: TranslationJobApiService, useValue: { getStatus, retry, cancel } },
      ],
    });
  });

  it('renders progress, fake-local disclosure and the source return path without chapter body text', async () => {
    getStatus.mockResolvedValue({ status: makeStatus('translating'), retryAfterSeconds: 2 });
    const fixture = TestBed.createComponent(TranslationJobPageComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Cánh cửa mở');
    expect(fixture.nativeElement.textContent).toContain('KẾT QUẢ GIẢ');
    expect(fixture.nativeElement.textContent).toContain('50%');
    expect(fixture.nativeElement.textContent).toContain('phần đã xử lý');
    expect(fixture.nativeElement.textContent).not.toContain('sourceText');
    expect(fixture.nativeElement.querySelector('progress').value).toBe(50);
    expect(fixture.nativeElement.querySelector('a[routerlink="/app/reader"]')).toBeNull();
    const link: HTMLAnchorElement | null =
      fixture.nativeElement.querySelector('.translation-job-back');
    expect(link?.getAttribute('href')).toContain(`/app/reader/${workId}/${chapterId}`);
  });

  it('does not offer writer actions to a readable non-writer', async () => {
    getStatus.mockResolvedValue({ status: makeStatus('failed', false), retryAfterSeconds: null });
    const fixture = TestBed.createComponent(TranslationJobPageComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Bản thử gặp lỗi');
    expect(fixture.nativeElement.textContent).not.toContain('Thử lại phần lỗi');
    expect(fixture.nativeElement.textContent).not.toContain('Dừng bản xem thử');
    expect(fixture.nativeElement.textContent).not.toContain('Về Bàn dịch');
  });

  it('offers retry only to a writer when a job has retryable failure units', async () => {
    getStatus.mockResolvedValue({ status: makeStatus('failed', true), retryAfterSeconds: null });
    retry.mockResolvedValue({
      jobId,
      jobStatus: 'queued',
      retriedUnits: 1,
      replayed: false,
      outboxEventId: revisionId,
    });
    const fixture = TestBed.createComponent(TranslationJobPageComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Thử lại phần lỗi');
    expect(fixture.nativeElement.textContent).toContain('Về Bàn dịch');
  });

  it('offers the private candidate comparison only to a writer with a current ready candidate', async () => {
    getStatus.mockResolvedValue({
      status: makeStatus('review_required', true),
      retryAfterSeconds: null,
    });
    const fixture = TestBed.createComponent(TranslationJobPageComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Candidate giả đã sẵn sàng để đối chiếu');
    expect(fixture.nativeElement.textContent).toContain('Bản xem thử chưa được xuất bản');
    expect(fixture.nativeElement.textContent).toContain(
      'chưa thể chỉnh sửa hoặc thay thế nguyên tác',
    );
    const link: HTMLAnchorElement | null =
      fixture.nativeElement.querySelector('a[href*="/candidate"]');
    expect(link?.getAttribute('href')).toContain(`/app/translation/jobs/${jobId}/candidate`);

    getStatus.mockResolvedValue({
      status: makeStatus('review_required', false),
      retryAfterSeconds: null,
    });
    const readerFixture = TestBed.createComponent(TranslationJobPageComponent);
    readerFixture.detectChanges();
    await readerFixture.whenStable();
    readerFixture.detectChanges();
    expect(readerFixture.nativeElement.querySelector('a[href*="/candidate"]')).toBeNull();
    expect(readerFixture.nativeElement.textContent).toContain(
      'Chỉ người có quyền chỉnh sửa thư viện mới được xem nội dung',
    );
  });

  it('keeps the current-source link and hides retry when the job revision is stale', async () => {
    const status = makeStatus('failed', true);
    status.job.sourceRevisionCurrent = false;
    status.job.failureCode = 'TRANSLATION_SOURCE_STALE';
    getStatus.mockResolvedValue({ status, retryAfterSeconds: null });
    const fixture = TestBed.createComponent(TranslationJobPageComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain(
      'Chương đã thay đổi kể từ khi bản thử bắt đầu',
    );
    expect(fixture.nativeElement.textContent).toContain('đọc phiên bản hiện tại');
    expect(fixture.nativeElement.textContent).not.toContain('Thử lại phần lỗi');
    const link: HTMLAnchorElement | null =
      fixture.nativeElement.querySelector('.translation-job-back');
    expect(link?.getAttribute('href')).toContain(`/app/reader/${workId}/${chapterId}`);
  });

  it('shows a clear private-unavailable state for a concealed 404', async () => {
    getStatus.mockRejectedValue(new TranslationJobApiError(404, 'TRANSLATION_JOB_NOT_FOUND'));
    const fixture = TestBed.createComponent(TranslationJobPageComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain(
      'Không tìm thấy bản xem thử hoặc bạn không có quyền xem',
    );
  });
});
