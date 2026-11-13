import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { signal } from '@angular/core';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  GlossaryActiveRevision,
  GlossaryApiError,
  GlossaryApiService,
  GlossaryCatalogItem,
} from '../core/glossary-api.service';
import { PolicyOnboardingService } from '../core/policy-onboarding.service';
import { GlossaryDetailPageComponent } from './glossary-detail-page.component';

const glossaryId = '11111111-1111-4111-8111-111111111111';
const activeRevisionId = '22222222-2222-4222-8222-222222222222';
const termId = '33333333-3333-4333-8333-333333333333';

const writerGlossary: GlossaryCatalogItem = {
  id: glossaryId,
  name: 'Tu luyện',
  scope: 'USER_GLOBAL',
  sourceLanguage: 'zh-Hans',
  targetLanguage: 'vi',
  version: 3,
  activeRevisionId,
  activeRevisionNumber: 1,
  latestRevisionNumber: 2,
  createdAt: '2026-11-14T05:00:00Z',
  canWrite: true,
};

const activeRevision: GlossaryActiveRevision = {
  glossaryId,
  name: 'Tu luyện',
  scope: 'USER_GLOBAL',
  sourceLanguage: 'zh-Hans',
  targetLanguage: 'vi',
  version: 2,
  activeRevisionId,
  revisionNumber: 1,
  parentRevisionId: null,
  revisionCreatedAt: '2026-11-14T05:00:00Z',
  terms: [
    {
      id: termId,
      ordinal: 1,
      sourceTerm: '金丹',
      targetTerm: 'Kim Đan',
      priority: 0,
      casePolicy: 'INSENSITIVE',
      matchMode: 'WHOLE_PHRASE',
      doNotTranslate: false,
      contextNote: 'Cảnh giới tu luyện',
      aliases: ['Golden Core'],
    },
  ],
  etag: '"v2"',
};

describe('GlossaryDetailPageComponent', () => {
  let api: { getGlossary: ReturnType<typeof vi.fn>; getActiveRevision: ReturnType<typeof vi.fn> };
  let status: ReturnType<typeof signal>;

  beforeEach(() => {
    api = {
      getGlossary: vi.fn().mockResolvedValue(writerGlossary),
      getActiveRevision: vi.fn().mockResolvedValue(activeRevision),
    };
    status = signal('accepted');
    TestBed.configureTestingModule({
      imports: [GlossaryDetailPageComponent],
      providers: [
        provideRouter([]),
        {
          provide: ActivatedRoute,
          useValue: { paramMap: of(convertToParamMap({ glossaryId })) },
        },
        { provide: GlossaryApiService, useValue: api },
        { provide: PolicyOnboardingService, useValue: { status } },
      ],
    });
  });

  it('shows the active terms and draft badge for a writer without displaying the glossary ID', async () => {
    const fixture = TestBed.createComponent(GlossaryDetailPageComponent);
    fixture.detectChanges();
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('Kim Đan');
    });

    expect(api.getGlossary).toHaveBeenCalledWith(glossaryId);
    expect(api.getActiveRevision).toHaveBeenCalledWith(glossaryId);
    expect(fixture.nativeElement.textContent).toContain('Có bản nháp chưa xuất bản');
    expect(fixture.nativeElement.textContent).toContain('Kim Đan');
    expect(fixture.nativeElement.textContent).toContain('Golden Core');
    expect(fixture.nativeElement.textContent).not.toContain(glossaryId);
  });

  it('does not reveal draft state to a reader', async () => {
    api.getGlossary.mockResolvedValue({
      ...writerGlossary,
      canWrite: false,
      latestRevisionNumber: null,
    });
    const fixture = TestBed.createComponent(GlossaryDetailPageComponent);
    fixture.detectChanges();
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('Chỉ xem');
    });

    expect(fixture.nativeElement.textContent).toContain('Chỉ xem');
    expect(fixture.nativeElement.textContent).not.toContain('Có bản nháp');
    expect(fixture.nativeElement.textContent).not.toContain('bản nháp chưa xuất bản');
  });

  it('does not request or reveal an unpublished first revision', async () => {
    api.getGlossary.mockResolvedValue({
      ...writerGlossary,
      activeRevisionId: null,
      activeRevisionNumber: null,
      latestRevisionNumber: 1,
    });
    const fixture = TestBed.createComponent(GlossaryDetailPageComponent);
    fixture.detectChanges();
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('Chưa có bản thuật ngữ đang dùng');
    });

    expect(api.getActiveRevision).not.toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain('Chưa có bản thuật ngữ đang dùng');
    expect(fixture.nativeElement.textContent).toContain('Có bản nháp chưa xuất bản');
    expect(fixture.nativeElement.textContent).not.toContain('Kim Đan');
  });

  it('renders missing glossary as a not-found state', async () => {
    api.getGlossary.mockRejectedValue(new GlossaryApiError('NOT_FOUND', 404, 'NOT_FOUND'));
    const fixture = TestBed.createComponent(GlossaryDetailPageComponent);
    fixture.detectChanges();
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('Không tìm thấy glossary');
    });
    expect(fixture.nativeElement.textContent).toContain('Không tìm thấy glossary');
    expect(api.getActiveRevision).not.toHaveBeenCalled();
  });

  it('offers retry when an active revision request is temporarily unavailable', async () => {
    api.getActiveRevision
      .mockRejectedValueOnce(
        new GlossaryApiError('SERVICE_UNAVAILABLE', 503, 'SERVICE_UNAVAILABLE'),
      )
      .mockResolvedValueOnce(activeRevision);
    const fixture = TestBed.createComponent(GlossaryDetailPageComponent);
    fixture.detectChanges();
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('Máy chủ chưa phản hồi');
    });
    const retry = fixture.nativeElement.querySelector(
      '.library-sync-error button',
    ) as HTMLButtonElement;
    retry.click();
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('Kim Đan');
    });
    expect(api.getGlossary).toHaveBeenCalledTimes(2);
    expect(api.getActiveRevision).toHaveBeenCalledTimes(2);
  });

  it('refuses to show a revision when its ID no longer matches the active pointer', async () => {
    api.getActiveRevision.mockResolvedValue({
      ...activeRevision,
      activeRevisionId: '66666666-6666-4666-8666-666666666666',
    });
    const fixture = TestBed.createComponent(GlossaryDetailPageComponent);
    fixture.detectChanges();
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('Glossary vừa thay đổi');
    });
    expect(fixture.nativeElement.textContent).not.toContain('Kim Đan');
  });

  it('offers a return-to-detail sign-in link when the account session expires', async () => {
    api.getGlossary.mockRejectedValue(
      new GlossaryApiError('UNAUTHENTICATED', 401, 'UNAUTHENTICATED'),
    );
    const fixture = TestBed.createComponent(GlossaryDetailPageComponent);
    fixture.detectChanges();
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('Đăng nhập lại');
    });
    const link = fixture.nativeElement.querySelector('.library-sync-error a') as HTMLAnchorElement;
    expect(link.getAttribute('href')).toContain('/sign-in');
    expect(decodeURIComponent(link.getAttribute('href') ?? '')).toContain(
      '/app/glossaries/' + glossaryId,
    );
  });

  it('returns policy-denied detail reads to setup with the direct route retained', async () => {
    api.getGlossary.mockRejectedValue(
      new GlossaryApiError('POLICY_REQUIRED', 403, 'POLICY_REQUIRED'),
    );
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    const fixture = TestBed.createComponent(GlossaryDetailPageComponent);
    fixture.detectChanges();
    await vi.waitFor(() => {
      expect(navigate).toHaveBeenCalledWith(['/app/setup'], {
        queryParams: { returnUrl: '/app/glossaries/' + glossaryId },
      });
    });
  });
});
