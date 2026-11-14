import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { SystemStatusService } from '../core/system-status.service';
import {
  TranslationJobApiError,
  TranslationJobApiService,
} from '../core/translation-job-api.service';
import { TranslationPreviewActionComponent } from './translation-preview-action.component';

const chapterId = '77777777-7777-4777-8777-777777777777';
const revisionId = '88888888-8888-4888-8888-888888888888';
const jobId = '66666666-6666-4666-8666-666666666666';
const fakeStatus = (enabled: boolean) => ({
  apiVersion: 'v1',
  environment: 'local',
  integrations: { googleOidcEnabled: false, momoTestEnabled: false, momoEnvironment: 'TEST' },
  features: {
    importProcessingEnabled: false,
    cultivationEnabled: false,
    commerceSandboxEnabled: false,
    translationFakePreviewEnabled: enabled,
  },
});

describe('TranslationPreviewActionComponent', () => {
  const enqueue = vi.fn();
  const navigate = vi.fn();
  const status = signal(fakeStatus(false));

  beforeEach(() => {
    enqueue.mockReset();
    navigate.mockReset();
    status.set(fakeStatus(false));
    TestBed.configureTestingModule({
      imports: [TranslationPreviewActionComponent],
      providers: [
        { provide: TranslationJobApiService, useValue: { enqueue } },
        { provide: SystemStatusService, useValue: { status } },
        { provide: Router, useValue: { navigate } },
      ],
    });
  });

  it('keeps the action hidden unless the server says the local API is enabled', () => {
    const fixture = TestBed.createComponent(TranslationPreviewActionComponent);
    fixture.componentRef.setInput('chapterId', chapterId);
    fixture.componentRef.setInput('sourceRevisionId', revisionId);
    fixture.componentRef.setInput('sourceLanguage', 'vi');
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('button')).toBeNull();
    status.set(fakeStatus(true));
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('KẾT QUẢ GIẢ');
    expect(fixture.nativeElement.querySelector('button').textContent).toContain('Tạo bản xem thử');
  });

  it('starts only the supported opposite language and navigates to the opaque job status', async () => {
    status.set(fakeStatus(true));
    enqueue.mockResolvedValue({
      jobId,
      replayed: false,
      location: `/api/v1/translation/jobs/${jobId}`,
    });
    const fixture = TestBed.createComponent(TranslationPreviewActionComponent);
    fixture.componentRef.setInput('chapterId', chapterId);
    fixture.componentRef.setInput('sourceRevisionId', revisionId);
    fixture.componentRef.setInput('sourceLanguage', 'vi');
    fixture.detectChanges();

    fixture.nativeElement.querySelector('button').click();
    await fixture.whenStable();

    expect(enqueue).toHaveBeenCalledTimes(1);
    expect(enqueue.mock.calls[0].slice(0, 3)).toEqual([chapterId, revisionId, 'en']);
    expect(enqueue.mock.calls[0][3]).toMatch(/^[A-Za-z0-9._~-]{16,128}$/);
    expect(navigate).toHaveBeenCalledWith(['/app/translation/jobs', jobId]);
  });

  it('keeps an ambiguous create retry on the same idempotency key', async () => {
    status.set(fakeStatus(true));
    enqueue.mockRejectedValueOnce(new TranslationJobApiError(0, null)).mockResolvedValueOnce({
      jobId,
      replayed: true,
      location: `/api/v1/translation/jobs/${jobId}`,
    });
    const fixture = TestBed.createComponent(TranslationPreviewActionComponent);
    fixture.componentRef.setInput('chapterId', chapterId);
    fixture.componentRef.setInput('sourceRevisionId', revisionId);
    fixture.componentRef.setInput('sourceLanguage', 'en');
    fixture.detectChanges();

    fixture.nativeElement.querySelector('button').click();
    await fixture.whenStable();
    fixture.detectChanges();
    const key = enqueue.mock.calls[0][3];
    expect(fixture.nativeElement.textContent).toContain('Gửi lại cùng yêu cầu');

    fixture.nativeElement.querySelector('button').click();
    await fixture.whenStable();

    expect(enqueue.mock.calls[1].slice(0, 3)).toEqual([chapterId, revisionId, 'vi']);
    expect(enqueue.mock.calls[1][3]).toBe(key);
    expect(navigate).toHaveBeenCalledWith(['/app/translation/jobs', jobId]);
  });

  it('does not show unsupported source-language pairs', () => {
    status.set(fakeStatus(true));
    const fixture = TestBed.createComponent(TranslationPreviewActionComponent);
    fixture.componentRef.setInput('chapterId', chapterId);
    fixture.componentRef.setInput('sourceRevisionId', revisionId);
    fixture.componentRef.setInput('sourceLanguage', 'zh-Hans');
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('button')).toBeNull();
  });
});
