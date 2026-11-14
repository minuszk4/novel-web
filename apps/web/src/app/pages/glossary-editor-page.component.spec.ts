import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { signal } from '@angular/core';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  GlossaryApiError,
  GlossaryApiService,
  GlossaryCatalogItem,
  GlossaryMetadataSnapshot,
  GlossaryWritableRevision,
} from '../core/glossary-api.service';
import { PolicyOnboardingService } from '../core/policy-onboarding.service';
import { GlossaryEditorPageComponent } from './glossary-editor-page.component';

const glossaryId = '11111111-1111-4111-8111-111111111111';
const activeRevisionId = '22222222-2222-4222-8222-222222222222';
const latestRevisionId = '33333333-3333-4333-8333-333333333333';
const termId = '44444444-4444-4444-8444-444444444444';
const timestamp = '2026-11-14T05:00:00Z';

const writerGlossary: GlossaryCatalogItem = {
  id: glossaryId,
  name: 'Tu luyện',
  scope: 'USER_GLOBAL',
  sourceLanguage: 'zh-Hans',
  targetLanguage: 'vi',
  version: 4,
  activeRevisionId,
  activeRevisionNumber: 1,
  latestRevisionNumber: 2,
  createdAt: timestamp,
  canWrite: true,
};

const snapshot: GlossaryMetadataSnapshot = { glossary: writerGlossary, etag: '"v4"' };
const latestRevision: GlossaryWritableRevision = {
  glossaryId,
  name: writerGlossary.name,
  scope: writerGlossary.scope,
  sourceLanguage: writerGlossary.sourceLanguage,
  targetLanguage: writerGlossary.targetLanguage,
  version: 4,
  activeRevisionId,
  revisionId: latestRevisionId,
  revisionNumber: 2,
  parentRevisionId: activeRevisionId,
  revisionCreatedAt: timestamp,
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
  etag: '"v4"',
};

describe('GlossaryEditorPageComponent', () => {
  let api: {
    getGlossarySnapshot: ReturnType<typeof vi.fn>;
    getLatestWritableRevision: ReturnType<typeof vi.fn>;
    createDraftRevision: ReturnType<typeof vi.fn>;
    previewGlossaryRevision: ReturnType<typeof vi.fn>;
  };
  let canWrite: boolean;

  beforeEach(() => {
    canWrite = true;
    api = {
      getGlossarySnapshot: vi.fn().mockImplementation(async () => ({
        ...snapshot,
        glossary: { ...writerGlossary, canWrite, latestRevisionNumber: canWrite ? 2 : null },
      })),
      getLatestWritableRevision: vi.fn().mockResolvedValue(latestRevision),
      createDraftRevision: vi.fn().mockResolvedValue({
        glossaryId,
        revisionId: '55555555-5555-4555-8555-555555555555',
        revisionNumber: 3,
        parentRevisionId: latestRevisionId,
        glossaryVersion: 5,
        etag: '"v5"',
      }),
      previewGlossaryRevision: vi.fn().mockResolvedValue({
        glossaryId,
        baseRevisionId: activeRevisionId,
        proposedRevisionId: latestRevisionId,
        glossaryVersion: 4,
        canPublish: true,
        changeCount: 1,
        changes: [
          {
            kind: 'UPDATED',
            termId,
            before: {
              id: termId,
              revisionId: activeRevisionId,
              sourceLanguage: 'zh-Hans',
              sourceTerm: '金丹',
              targetTerm: 'Kim Đan cũ',
              aliases: ['Golden Core'],
              scope: 'USER_GLOBAL',
              priority: 0,
              casePolicy: 'INSENSITIVE',
              matchMode: 'WHOLE_PHRASE',
              doNotTranslate: false,
              ordinal: 1,
              contextNote: 'Cảnh giới tu luyện',
            },
            after: {
              id: termId,
              revisionId: latestRevisionId,
              sourceLanguage: 'zh-Hans',
              sourceTerm: '金丹',
              targetTerm: 'Kim Đan',
              aliases: ['Golden Core'],
              scope: 'USER_GLOBAL',
              priority: 0,
              casePolicy: 'INSENSITIVE',
              matchMode: 'WHOLE_PHRASE',
              doNotTranslate: false,
              ordinal: 1,
              contextNote: 'Bậc Kim Đan trong hệ tu luyện',
            },
          },
        ],
        changesTruncated: false,
        errorCount: 0,
        warningCount: 1,
        lintIssues: [
          {
            code: 'SOURCE_TERM_SHORT',
            severity: 'WARNING',
            termIds: [termId],
            affectedValue: '金丹',
          },
        ],
        lintIssuesTruncated: false,
        matchCount: 1,
        matches: [
          {
            termId,
            revisionId: latestRevisionId,
            matchedText: 'Golden Core',
            targetTerm: 'Kim Đan',
            startUtf16: 0,
            endUtf16: 11,
            alias: true,
            doNotTranslate: false,
            scope: 'USER_GLOBAL',
            priority: 0,
          },
        ],
        matchesTruncated: false,
        conflictCount: 0,
        conflicts: [],
        conflictsTruncated: false,
        etag: '"v4"',
      }),
    };
    TestBed.configureTestingModule({
      imports: [GlossaryEditorPageComponent],
      providers: [
        provideRouter([]),
        { provide: ActivatedRoute, useValue: { paramMap: of(convertToParamMap({ glossaryId })) } },
        { provide: GlossaryApiService, useValue: api },
        { provide: PolicyOnboardingService, useValue: { status: signal('accepted') } },
      ],
    });
  });

  it('edits the latest full snapshot and creates a separate immutable draft', async () => {
    const fixture = TestBed.createComponent(GlossaryEditorPageComponent);
    fixture.detectChanges();
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(
        (fixture.nativeElement.querySelector('.glossary-editor-field input') as HTMLInputElement)
          ?.value,
      ).toBe('金丹');
    });
    expect(api.getGlossarySnapshot).toHaveBeenCalledWith(glossaryId);
    expect(api.getLatestWritableRevision).toHaveBeenCalledWith(glossaryId);
    expect(fixture.nativeElement.textContent).toContain('Bản đang áp dụng: 1');
    expect(fixture.nativeElement.textContent).toContain('không thay đổi bản đang được áp dụng');

    const source = fixture.nativeElement.querySelector(
      '.glossary-editor-field input',
    ) as HTMLInputElement;
    source.value = 'Kim Đan cảnh giới';
    source.dispatchEvent(new Event('input', { bubbles: true }));
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(
      fixture.nativeElement.querySelector('.glossary-editor-preview-actions button').disabled,
    ).toBe(true);
    expect(fixture.nativeElement.textContent).toContain('Có chỉnh sửa chưa lưu');

    const confirm = vi.fn().mockReturnValue(false);
    vi.stubGlobal('confirm', confirm);
    expect(fixture.componentInstance.canDeactivate()).toBe(false);
    expect(confirm).toHaveBeenCalledOnce();
    vi.unstubAllGlobals();

    const save = Array.from(
      fixture.nativeElement.querySelectorAll('button') as NodeListOf<HTMLButtonElement>,
    ).find((button) => button.textContent?.trim() === 'Lưu bản nháp') as HTMLButtonElement;
    expect(save.disabled).toBe(false);
    save.click();
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('Đã tạo bản nháp 3');
    });
    await vi.waitFor(() => {
      expect(document.activeElement).toBe(
        fixture.nativeElement.querySelector('#glossary-editor-saved-heading'),
      );
    });
    expect(api.createDraftRevision).toHaveBeenCalledWith(glossaryId, '"v4"', [
      expect.objectContaining({ id: termId, sourceTerm: 'Kim Đan cảnh giới' }),
    ]);
    expect(fixture.nativeElement.textContent).toContain('Bản đang áp dụng vẫn là 1');
  });

  it('does not fetch or expose a writer draft to a reader', async () => {
    canWrite = false;
    const fixture = TestBed.createComponent(GlossaryEditorPageComponent);
    fixture.detectChanges();
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('chỉ cho phép xem');
    });
    expect(api.getLatestWritableRevision).not.toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).not.toContain('Kim Đan');
    expect(fixture.nativeElement.textContent).not.toContain('Lưu bản nháp');
    expect(fixture.nativeElement.textContent).not.toContain('Xem trước thay đổi');
    expect(api.previewGlossaryRevision).not.toHaveBeenCalled();
  });

  it('previews only the saved latest snapshot and keeps lint separate from sample diagnostics', async () => {
    const fixture = TestBed.createComponent(GlossaryEditorPageComponent);
    fixture.detectChanges();
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('Xem trước thay đổi');
    });
    const sample = fixture.nativeElement.querySelector(
      '.glossary-editor-preview-sample textarea',
    ) as HTMLTextAreaElement;
    sample.value = 'Golden Core appears.';
    sample.dispatchEvent(new Event('input', { bubbles: true }));
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    const previewButton = fixture.nativeElement.querySelector(
      '.glossary-editor-preview-actions button',
    ) as HTMLButtonElement;
    expect(previewButton.disabled).toBe(false);
    previewButton.click();

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('Không có lỗi lint chặn xuất bản');
      expect(fixture.nativeElement.textContent).toContain('xung đột mẫu');
      expect(fixture.nativeElement.textContent).toContain('Golden Core → Kim Đan');
      expect(fixture.nativeElement.textContent).toContain('Vị trí 1');
      expect(fixture.nativeElement.textContent).toContain('Ghi chú: Bậc Kim Đan trong hệ tu luyện');
      expect(fixture.nativeElement.textContent).toContain('Thuật ngữ quá ngắn');
    });
    expect(api.previewGlossaryRevision).toHaveBeenCalledWith(
      glossaryId,
      latestRevisionId,
      '"v4"',
      'Golden Core appears.',
    );
    expect(api.createDraftRevision).not.toHaveBeenCalled();
    expect(fixture.nativeElement.querySelector('#glossary-editor-terms-heading')).not.toBeNull();
    expect(fixture.nativeElement.textContent).toContain('Bản đang áp dụng: 1');
  });

  it('blocks another preview after a stale response until the writer reloads', async () => {
    api.previewGlossaryRevision.mockRejectedValue(
      new GlossaryApiError('GLOSSARY_VERSION_STALE', 409, 'GLOSSARY_VERSION_STALE'),
    );
    const fixture = TestBed.createComponent(GlossaryEditorPageComponent);
    fixture.detectChanges();
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('Xem trước thay đổi');
    });
    const button = fixture.nativeElement.querySelector(
      '.glossary-editor-preview-actions button',
    ) as HTMLButtonElement;
    button.click();
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('Glossary đã thay đổi sau khi tải');
    });
    expect(
      (
        fixture.nativeElement.querySelector(
          '.glossary-editor-preview-actions button',
        ) as HTMLButtonElement
      ).disabled,
    ).toBe(true);
    expect(api.createDraftRevision).not.toHaveBeenCalled();
    fixture.nativeElement.querySelector('.glossary-editor-preview-error button').click();
    await vi.waitFor(() => expect(api.getGlossarySnapshot).toHaveBeenCalledTimes(2));
  });

  it('stops on a stale version and requires an explicit reload before continuing', async () => {
    api.createDraftRevision.mockRejectedValue(
      new GlossaryApiError('GLOSSARY_VERSION_STALE', 409, 'GLOSSARY_VERSION_STALE'),
    );
    const fixture = TestBed.createComponent(GlossaryEditorPageComponent);
    fixture.detectChanges();
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(
        (fixture.nativeElement.querySelector('.glossary-editor-field input') as HTMLInputElement)
          ?.value,
      ).toBe('金丹');
    });
    const source = fixture.nativeElement.querySelector(
      '.glossary-editor-field input',
    ) as HTMLInputElement;
    source.value = 'Changed';
    source.dispatchEvent(new Event('input', { bubbles: true }));
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    fixture.nativeElement.querySelector('.glossary-editor-save-bar button').click();
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('Glossary đã thay đổi');
    });
    expect(api.createDraftRevision).toHaveBeenCalledTimes(1);
    expect(fixture.nativeElement.textContent).toContain('Tải bản mới nhất và bỏ nội dung đang sửa');
  });
});
