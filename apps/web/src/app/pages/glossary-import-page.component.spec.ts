import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { signal } from '@angular/core';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  GlossaryApiError,
  GlossaryApiService,
  GlossaryImportApplyResult,
  GlossaryImportPreview,
  GlossaryMetadataSnapshot,
} from '../core/glossary-api.service';
import { PolicyOnboardingService } from '../core/policy-onboarding.service';
import { GlossaryImportPageComponent } from './glossary-import-page.component';

const glossaryId = '11111111-1111-4111-8111-111111111111';
const activeRevisionId = '22222222-2222-4222-8222-222222222222';
const baseRevisionId = '33333333-3333-4333-8333-333333333333';
const currentTermId = '44444444-4444-4444-8444-444444444444';
const secondTermId = '55555555-5555-4555-8555-555555555555';
const timestamp = '2026-11-14T05:00:00Z';

function makeSnapshot(version = 4, canWrite = true): GlossaryMetadataSnapshot {
  return {
    glossary: {
      id: glossaryId,
      name: 'Tu luyện',
      scope: 'LIBRARY',
      sourceLanguage: 'zh-Hans',
      targetLanguage: 'vi',
      version,
      activeRevisionId,
      activeRevisionNumber: 1,
      latestRevisionNumber: canWrite ? 2 : null,
      createdAt: timestamp,
      canWrite,
    },
    etag: `"v${version}"`,
  };
}

function importedTerm(sourceTerm: string, targetTerm: string) {
  return {
    rowNumber: 2,
    sourceTerm,
    targetTerm,
    priority: 0,
    casePolicy: 'INSENSITIVE' as const,
    matchMode: 'WHOLE_PHRASE' as const,
    doNotTranslate: false,
    contextNote: null,
    aliases: ['New Alias'],
  };
}

function makePreview(version = 4): GlossaryImportPreview {
  const currentTerms = [
    {
      id: currentTermId,
      ordinal: 1,
      sourceTerm: '金丹',
      targetTerm: 'Kim Đan',
      priority: 2,
      casePolicy: 'INSENSITIVE' as const,
      matchMode: 'WHOLE_PHRASE' as const,
      doNotTranslate: false,
      contextNote: 'Cảnh giới đã duyệt',
      aliases: ['Golden Core'],
    },
    {
      id: secondTermId,
      ordinal: 2,
      sourceTerm: 'Golden Core',
      targetTerm: 'Kim Đan',
      priority: 1,
      casePolicy: 'INSENSITIVE' as const,
      matchMode: 'WHOLE_PHRASE' as const,
      doNotTranslate: false,
      contextNote: null,
      aliases: [],
    },
  ];
  return {
    glossaryId,
    glossaryName: 'Tu luyện',
    scope: 'LIBRARY',
    sourceLanguage: 'zh-Hans',
    targetLanguage: 'vi',
    glossaryVersion: version,
    activeRevisionId,
    activeRevisionNumber: 1,
    baseRevisionId,
    baseRevisionNumber: 2,
    currentTerms,
    importedTerms: [
      {
        rowNumber: 2,
        importedTerm: importedTerm('丹田', 'Đan điền'),
        suggestedAction: 'ADD',
        existingMatches: [],
        matchesTruncated: false,
      },
      {
        rowNumber: 3,
        importedTerm: {
          ...importedTerm('金丹', 'Golden Core'),
          rowNumber: 3,
          aliases: ['Kim Dan', 'New Alias'],
        },
        suggestedAction: 'UPDATE',
        existingMatches: [{ termId: currentTermId, reason: 'CANONICAL_TERM_MATCH' }],
        matchesTruncated: false,
      },
      {
        rowNumber: 4,
        importedTerm: { ...importedTerm('Kim Dan', 'Kim Đan'), rowNumber: 4 },
        suggestedAction: 'RESOLVE_CONFLICT',
        existingMatches: [
          { termId: currentTermId, reason: 'PATTERN_COLLISION' },
          { termId: secondTermId, reason: 'PATTERN_COLLISION' },
        ],
        matchesTruncated: false,
      },
      {
        rowNumber: 5,
        importedTerm: { ...importedTerm('Core', 'Lõi'), rowNumber: 5 },
        suggestedAction: 'RESOLVE_CONFLICT',
        existingMatches: [{ termId: secondTermId, reason: 'PATTERN_COLLISION' }],
        matchesTruncated: true,
      },
    ],
    counts: { imported: 4, add: 1, update: 1, unchanged: 0, requiresResolution: 2 },
    translationImpact: {
      status: 'UNAVAILABLE',
      affectedTranslationUnitCount: null,
      detail: 'Translation units are not implemented yet.',
    },
    etag: `"v${version}"`,
  };
}

function applyResult(replayed = false): GlossaryImportApplyResult {
  return {
    draft: {
      glossaryId,
      revisionId: '66666666-6666-4666-8666-666666666666',
      revisionNumber: 3,
      parentRevisionId: baseRevisionId,
      glossaryVersion: 5,
    },
    rows: [
      { rowNumber: 2, action: 'ADD', termId: secondTermId },
      { rowNumber: 3, action: 'MERGE', termId: currentTermId },
      { rowNumber: 4, action: 'SKIP', termId: null },
      { rowNumber: 5, action: 'SKIP', termId: null },
    ],
    skippedRows: 2,
    replayed,
    etag: '"v5"',
  };
}

describe('GlossaryImportPageComponent', () => {
  let api: {
    getGlossarySnapshot: ReturnType<typeof vi.fn>;
    previewImport: ReturnType<typeof vi.fn>;
    createApplyAttempt: ReturnType<typeof vi.fn>;
    applyImport: ReturnType<typeof vi.fn>;
  };
  let status: ReturnType<typeof signal>;

  beforeEach(() => {
    api = {
      getGlossarySnapshot: vi.fn().mockResolvedValue(makeSnapshot()),
      previewImport: vi.fn().mockResolvedValue(makePreview()),
      createApplyAttempt: vi.fn().mockResolvedValue({
        requestBody: '{"file":"same"}',
        idempotencyKey: '123e4567-e89b-42d3-a456-426614174000',
      }),
      applyImport: vi.fn().mockResolvedValue(applyResult()),
    };
    status = signal('accepted');
    TestBed.configureTestingModule({
      imports: [GlossaryImportPageComponent],
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

  it('loads an editable glossary and keeps selected file bytes local until explicit preview', async () => {
    const fixture = await renderPage();
    const file = new File(['private glossary contents'], 'terms.JSON', { type: 'text/plain' });
    selectFile(fixture, file);
    expect(fixture.nativeElement.textContent).toContain('terms.JSON');
    expect(api.previewImport).not.toHaveBeenCalled();

    clickButton(fixture, 'Xem trước nhập');
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('Kết quả xem trước');
    });
    expect(api.getGlossarySnapshot).toHaveBeenCalledWith(glossaryId);
    expect(api.previewImport).toHaveBeenCalledWith(glossaryId, '"v4"', file);
    expect(fixture.nativeElement.textContent).not.toContain(currentTermId);
  });

  it('rejects unsupported and oversized files before calling preview', async () => {
    const fixture = await renderPage();
    selectFile(fixture, new File(['x'], 'terms.xml'));
    expect(fixture.nativeElement.textContent).toContain('Chỉ nhận tệp JSON, CSV hoặc TSV');
    expect(api.previewImport).not.toHaveBeenCalled();

    selectFile(fixture, new File([new Uint8Array(1_048_577)], 'too-large.csv'));
    expect(fixture.nativeElement.textContent).toContain('không vượt quá 1 MiB');
    expect(api.previewImport).not.toHaveBeenCalled();
  });

  it('uses server matches for row choices, preserves merge fields and blocks truncated matches', async () => {
    const fixture = await renderPage();
    selectFile(fixture, new File(['{}'], 'terms.json'));
    clickButton(fixture, 'Xem trước nhập');
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('Kết quả xem trước');
    });

    const updateRow = fixture.nativeElement.querySelector('#glossary-import-row-3') as HTMLElement;
    const updateButtons = [...updateRow.querySelectorAll('button')];
    expect(updateButtons.map((button) => button.textContent?.trim())).toContain('Thay thế');
    expect(updateRow.textContent).toContain('Kim Đan');
    expect(updateRow.textContent).toContain('Golden Core');
    updateButtons.find((button) => button.textContent?.includes('Gộp bí danh'))?.click();
    fixture.detectChanges();
    expect(updateRow.textContent).toContain('Gộp sẽ giữ thuật ngữ nguồn');

    const truncated = fixture.nativeElement.querySelector('#glossary-import-row-5') as HTMLElement;
    expect(truncated.textContent).toContain('danh sách một phần');
    expect(
      [...truncated.querySelectorAll('button')].some((button) =>
        button.textContent?.includes('Gộp'),
      ),
    ).toBe(false);

    const conflict = fixture.nativeElement.querySelector('#glossary-import-row-4') as HTMLElement;
    conflict
      .querySelector('button[aria-label^="Bỏ qua dòng 4"]')
      ?.dispatchEvent(new Event('click'));
    truncated
      .querySelector('button[aria-label^="Bỏ qua dòng 5"]')
      ?.dispatchEvent(new Event('click'));
    fixture.detectChanges();
    clickButton(fixture, 'Lưu bản nháp');
    await vi.waitFor(() => expect(api.applyImport).toHaveBeenCalledTimes(1));
    const resolutions = api.createApplyAttempt.mock.calls[0][1] as Array<Record<string, unknown>>;
    const merge = resolutions.find((row) => row['rowNumber'] === 3);
    expect(merge).toMatchObject({ action: 'MERGE', targetTermId: currentTermId });
    expect(merge?.['term']).toMatchObject({
      sourceTerm: '金丹',
      targetTerm: 'Kim Đan',
      priority: 2,
      contextNote: 'Cảnh giới đã duyệt',
      aliases: ['Golden Core', 'Kim Dan', 'New Alias'],
    });
  });

  it('requires explicit conflict choices and does not submit an all-skip no-op', async () => {
    const fixture = await renderPage();
    selectFile(fixture, new File(['{}'], 'terms.json'));
    clickButton(fixture, 'Xem trước nhập');
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('Kết quả xem trước');
    });
    const conflict = fixture.nativeElement.querySelector('#glossary-import-row-4') as HTMLElement;
    expect(conflict.querySelector('button[aria-pressed="true"]')).toBeNull();

    for (const rowNumber of [2, 3, 4, 5]) {
      const row = fixture.nativeElement.querySelector(
        `#glossary-import-row-${rowNumber}`,
      ) as HTMLElement;
      row.querySelector('button[aria-label^="Bỏ qua dòng"]')?.dispatchEvent(new Event('click'));
    }
    fixture.detectChanges();
    clickButton(fixture, 'Lưu bản nháp');
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Không có thay đổi để lưu');
    expect(api.createApplyAttempt).not.toHaveBeenCalled();
    expect(api.applyImport).not.toHaveBeenCalled();
  });

  it('requires a pattern-affecting correction before keeping a conflict as a new term', async () => {
    const fixture = await renderPage();
    selectFile(fixture, new File(['{}'], 'terms.json'));
    clickButton(fixture, 'Xem trước nhập');
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('Kết quả xem trước');
    });

    const conflict = fixture.nativeElement.querySelector('#glossary-import-row-4') as HTMLElement;
    conflict.querySelector<HTMLButtonElement>('button[aria-label="Thêm mục — “Kim Dan”"]')?.click();
    const update = fixture.nativeElement.querySelector('#glossary-import-row-3') as HTMLElement;
    update.querySelector<HTMLButtonElement>('button[aria-label^="Bỏ qua dòng 3"]')?.click();
    const truncated = fixture.nativeElement.querySelector('#glossary-import-row-5') as HTMLElement;
    truncated.querySelector<HTMLButtonElement>('button[aria-label^="Bỏ qua dòng 5"]')?.click();
    fixture.detectChanges();

    const save = [...fixture.nativeElement.querySelectorAll('button')].find((button) =>
      button.textContent?.includes('Lưu bản nháp'),
    ) as HTMLButtonElement;
    expect(save.disabled).toBe(true);
    expect(conflict.textContent).toContain('để xử lý va chạm');

    const priority = conflict.querySelector('input[type="number"]') as HTMLInputElement;
    priority.value = '3';
    priority.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    expect(save.disabled).toBe(false);
    clickButton(fixture, 'Lưu bản nháp');
    await vi.waitFor(() => expect(api.applyImport).toHaveBeenCalledTimes(1));
    const resolutions = api.createApplyAttempt.mock.calls[0][1] as Array<Record<string, unknown>>;
    expect(resolutions.find((row) => row['rowNumber'] === 4)).toMatchObject({
      action: 'ADD',
      targetTermId: null,
      term: { priority: 3 },
    });
  });

  it('retries an uncertain apply with the exact body and idempotency key', async () => {
    api.previewImport.mockResolvedValue({
      ...makePreview(),
      importedTerms: [makePreview().importedTerms[0]],
      counts: { imported: 1, add: 1, update: 0, unchanged: 0, requiresResolution: 0 },
    });
    api.applyImport
      .mockRejectedValueOnce(new GlossaryApiError('UNKNOWN_OUTCOME', -1, null))
      .mockResolvedValueOnce(applyResult(true));
    const attempt = {
      requestBody: '{"file":"same"}',
      idempotencyKey: '123e4567-e89b-42d3-a456-426614174000',
    };
    api.createApplyAttempt.mockResolvedValue(attempt);
    const fixture = await renderPage();
    selectFile(fixture, new File(['{}'], 'terms.json'));
    clickButton(fixture, 'Xem trước nhập');
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('Kết quả xem trước');
    });

    clickButton(fixture, 'Lưu bản nháp');
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('Chưa rõ máy chủ đã lưu hay chưa');
    });
    expect(fixture.nativeElement.textContent).toContain('Gửi lại đúng yêu cầu lưu');
    clickButton(fixture, 'Gửi lại đúng yêu cầu lưu');
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('Đã lưu bản nháp 3');
    });
    expect(api.createApplyAttempt).toHaveBeenCalledTimes(1);
    expect(api.applyImport).toHaveBeenCalledTimes(2);
    expect(api.applyImport.mock.calls[0][2]).toBe(api.applyImport.mock.calls[1][2]);
    expect(api.applyImport.mock.calls[1][2]).toEqual(attempt);
  });

  it('discards stale decisions, reloads the version and requires a new preview', async () => {
    api.getGlossarySnapshot
      .mockResolvedValueOnce(makeSnapshot(4))
      .mockResolvedValueOnce(makeSnapshot(5));
    api.previewImport.mockResolvedValueOnce(makePreview(4)).mockResolvedValueOnce(makePreview(5));
    api.applyImport.mockRejectedValue(
      new GlossaryApiError('GLOSSARY_VERSION_STALE', 409, 'GLOSSARY_VERSION_STALE', '"v5"'),
    );
    const fixture = await renderPage();
    selectFile(fixture, new File(['{}'], 'terms.json'));
    clickButton(fixture, 'Xem trước nhập');
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('Kết quả xem trước');
    });
    for (const rowNumber of [3, 4, 5]) {
      const row = fixture.nativeElement.querySelector(
        `#glossary-import-row-${rowNumber}`,
      ) as HTMLElement;
      row.querySelector('button[aria-label^="Bỏ qua dòng"]')?.dispatchEvent(new Event('click'));
    }
    const component = fixture.componentInstance as unknown as {
      canApply: () => boolean;
      rows: () => Record<number, { action: string | null }>;
    };
    expect(component.rows()).toMatchObject({
      2: { action: 'ADD' },
      3: { action: 'SKIP' },
      4: { action: 'SKIP' },
      5: { action: 'SKIP' },
    });
    expect(component.canApply()).toBe(true);
    fixture.detectChanges();
    clickButton(fixture, 'Lưu bản nháp');
    await vi.waitFor(() => expect(api.createApplyAttempt).toHaveBeenCalledTimes(1));
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('Quyết định cũ đã bị xóa');
    });
    expect(fixture.nativeElement.querySelector('#glossary-import-results-heading')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('Phiên bản hiện tại: 5');
    expect(fixture.nativeElement.textContent).toContain('terms.json');
    clickButton(fixture, 'Xem trước lại');
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('PHIÊN BẢN 5');
    });
    expect(api.previewImport).toHaveBeenLastCalledWith(glossaryId, '"v5"', expect.any(File));
  });

  it('shows safe parser issues without hiding the selected file', async () => {
    api.previewImport.mockRejectedValue(
      new GlossaryApiError('IMPORT_INVALID', 422, 'IMPORT_INVALID', null, 3, [
        {
          code: 'TERM_INVALID',
          field: 'sourceTerm',
          rowNumber: 3,
          message: 'Thuật ngữ nguồn không hợp lệ.',
        },
      ]),
    );
    const fixture = await renderPage();
    selectFile(fixture, new File(['bad'], 'terms.csv'));
    clickButton(fixture, 'Xem trước nhập');
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('Thuật ngữ nguồn không hợp lệ.');
    });
    expect(fixture.nativeElement.textContent).toContain('terms.csv');
    expect(api.previewImport).toHaveBeenCalledTimes(1);
  });

  it('keeps a reader out of the import editor', async () => {
    api.getGlossarySnapshot.mockResolvedValue(makeSnapshot(4, false));
    const fixture = await renderPage('Glossary này chỉ cho phép xem');
    expect(fixture.nativeElement.textContent).toContain('Glossary này chỉ cho phép xem');
    expect(fixture.nativeElement.querySelector('input[type="file"]')).toBeNull();
    expect(api.previewImport).not.toHaveBeenCalled();
  });
});

async function renderPage(readyText = 'Chọn tệp thuật ngữ') {
  const fixture = TestBed.createComponent(GlossaryImportPageComponent);
  fixture.detectChanges();
  await vi.waitFor(() => {
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain(readyText);
  });
  return fixture;
}

function selectFile(fixture: ComponentFixture<GlossaryImportPageComponent>, file: File): void {
  const input = fixture.nativeElement.querySelector('input[type="file"]') as HTMLInputElement;
  Object.defineProperty(input, 'files', { configurable: true, value: [file] });
  input.dispatchEvent(new Event('change'));
  fixture.detectChanges();
}

function clickButton(fixture: ComponentFixture<GlossaryImportPageComponent>, label: string): void {
  const button = [
    ...(fixture.nativeElement.querySelectorAll('button') as NodeListOf<HTMLButtonElement>),
  ].find((candidate) => candidate.textContent?.replace(/\s+/g, ' ').trim().includes(label));
  if (!button) throw new Error(`Button not found: ${label}`);
  button.click();
  fixture.detectChanges();
}
