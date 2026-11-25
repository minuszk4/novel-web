import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  AuthoringApiError,
  AuthoringApiService,
  PrivateAuthoredChapterDraft,
  PrivateChapterOutlineItem,
  PublicationReadiness,
  PublicationRequestReceipt,
} from '../core/authoring-api.service';
import { PublicationRequestFormComponent } from './publication-request-form.component';

const workId = '11111111-1111-4111-8111-111111111111';
const readinessEtag = `"publication-snapshot-${'a'.repeat(64)}"`;
const baseTime = '2026-11-17T05:00:00Z';
const chapters: PrivateChapterOutlineItem[] = [1, 2, 3].map((ordinal) => ({
  id: `22222222-2222-4222-8222-${String(ordinal).padStart(12, '0')}`,
  ordinal,
  title: `Chương ${ordinal}`,
  language: 'vi',
  status: 'ready',
  contentAvailable: true,
  hasSavedDraftContent: true,
  createdAt: baseTime,
}));

function readyState(overrides: Partial<PublicationReadiness> = {}): PublicationReadiness {
  return {
    workId,
    canSubmit: true,
    publicationRightsPolicyAvailable: true,
    publicationRightsPolicyVersion: 'rights-v1',
    publicationRightsPolicyEffectiveAt: '2026-11-15T00:00:00Z',
    publicationRightsPolicyAcceptedAt: '2026-11-16T12:00:00Z',
    publicationContentVersion: 8,
    readyChapterCount: 3,
    currentRequestState: null,
    snapshotEtag: readinessEtag,
    scopes: [
      {
        scopeCode: 'PUBLIC_METADATA',
        ready: true,
        snapshotEtag: `"publication-scope-${'b'.repeat(64)}"`,
      },
      {
        scopeCode: 'CHAPTER_TEXT',
        ready: true,
        snapshotEtag: `"publication-scope-${'c'.repeat(64)}"`,
      },
      {
        scopeCode: 'COMMERCIAL_SALE',
        ready: true,
        snapshotEtag: `"publication-scope-${'d'.repeat(64)}"`,
      },
      {
        scopeCode: 'TRANSLATION_ADAPTATION',
        ready: true,
        snapshotEtag: `"publication-scope-${'e'.repeat(64)}"`,
      },
      {
        scopeCode: 'FREE_PREVIEW',
        ready: true,
        snapshotEtag: `"publication-scope-${'f'.repeat(64)}"`,
      },
    ],
    blockerCodes: [],
    ...overrides,
  };
}

function draft(chapterId = chapters[0].id): PrivateAuthoredChapterDraft {
  return {
    id: chapterId,
    workId,
    title: 'Chương 1',
    status: 'draft',
    revisionNo: 4,
    sourceFormat: 'plain_text',
    normalizedContent: '<script>đây vẫn là văn bản đã lưu</script>',
    segments: [],
    updatedAt: baseTime,
    etag: '"draft-4"',
  };
}

function requestReceipt(): PublicationRequestReceipt {
  return {
    requestId: '33333333-3333-4333-8333-333333333333',
    requestVersion: 1,
    policyVersion: 'rights-v1',
    snapshotSha256: 'e'.repeat(64),
    scopeCodes: ['PUBLIC_METADATA', 'CHAPTER_TEXT'],
    submittedAt: baseTime,
    state: 'PENDING_REVIEW',
  };
}

describe('PublicationRequestFormComponent', () => {
  let fixture: ComponentFixture<PublicationRequestFormComponent>;
  let api: {
    getPublicationReadiness: ReturnType<typeof vi.fn>;
    listPrivateChapters: ReturnType<typeof vi.fn>;
    getPrivateChapterDraft: ReturnType<typeof vi.fn>;
    submitPublicationRequest: ReturnType<typeof vi.fn>;
  };

  beforeEach(() => {
    api = {
      getPublicationReadiness: vi.fn().mockResolvedValue(readyState()),
      listPrivateChapters: vi.fn().mockResolvedValue({ items: chapters, nextCursor: null }),
      getPrivateChapterDraft: vi
        .fn()
        .mockImplementation((id: string, chapterId: string) => Promise.resolve(draft(chapterId))),
      submitPublicationRequest: vi.fn().mockResolvedValue(requestReceipt()),
    };
    TestBed.configureTestingModule({
      imports: [PublicationRequestFormComponent],
      providers: [provideRouter([]), { provide: AuthoringApiService, useValue: api }],
    });
    fixture = TestBed.createComponent(PublicationRequestFormComponent);
    fixture.componentRef.setInput('workId', workId);
    fixture.componentRef.setInput('readiness', readyState());
    fixture.detectChanges();
  });

  it('does not read chapter content or submit until the author opens the review step', async () => {
    expect(api.listPrivateChapters).not.toHaveBeenCalled();
    expect(api.getPrivateChapterDraft).not.toHaveBeenCalled();
    expect(api.submitPublicationRequest).not.toHaveBeenCalled();

    await clickButton('Chuẩn bị yêu cầu duyệt');

    expect(api.listPrivateChapters).toHaveBeenCalledWith(workId, undefined, 100);
    expect(api.getPrivateChapterDraft).not.toHaveBeenCalled();
    expect(api.submitPublicationRequest).not.toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain('snapshot nội dung phiên bản 8');
  });

  it('counts and previews the saved private target chapter in a chapter review request', async () => {
    const targetChapter: PrivateChapterOutlineItem = {
      id: '44444444-4444-4444-8444-444444444444',
      ordinal: 4,
      title: 'Chương nháp cần duyệt',
      language: 'vi',
      status: 'draft',
      contentAvailable: false,
      hasSavedDraftContent: true,
      createdAt: baseTime,
    };
    api.listPrivateChapters.mockResolvedValue({
      items: [...chapters, targetChapter],
      nextCursor: null,
    });
    fixture.componentRef.setInput('targetChapterId', targetChapter.id);
    fixture.componentRef.setInput('readiness', readyState({ readyChapterCount: 4 }));
    fixture.detectChanges();

    await clickButton('Chuẩn bị yêu cầu duyệt');

    const targetRow = Array.from(
      fixture.nativeElement.querySelectorAll('li') as NodeListOf<HTMLLIElement>,
    ).find((item) => item.textContent?.includes(targetChapter.title));
    expect(targetRow?.textContent).toContain('Bản lưu mục tiêu · chưa công khai');
    const previewButton = targetRow?.querySelector('button') as HTMLButtonElement | null;
    expect(previewButton?.textContent).toContain('Xem bản đã lưu');
    previewButton?.click();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(api.getPrivateChapterDraft).toHaveBeenCalledWith(workId, targetChapter.id);
    expect(fixture.nativeElement.textContent).toContain('đây vẫn là văn bản đã lưu');
    await chooseBasis('publication-request-metadata-basis', 'ORIGINAL_WORK');
    await chooseBasis('publication-request-chapter-basis', 'ORIGINAL_WORK');
    await setCheckbox('Tôi đã rà soát các phạm vi', true);

    expect(button('Gửi yêu cầu duyệt')?.disabled).toBe(false);
  });

  it('loads the saved chapter preview only after activation and renders content as text', async () => {
    await clickButton('Chuẩn bị yêu cầu duyệt');
    await clickButton('Xem bản đã lưu');

    expect(api.getPrivateChapterDraft).toHaveBeenCalledWith(workId, chapters[0].id);
    const preview = fixture.nativeElement.querySelector(
      '.publication-request-preview pre',
    ) as HTMLElement;
    expect(preview.textContent).toContain('<script>đây vẫn là văn bản đã lưu</script>');
    expect(preview.querySelector('script')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('bản lưu 4');
  });

  it('requires mandatory and selected optional scope bases plus declaration before submit', async () => {
    const submitted: PublicationRequestReceipt[] = [];
    fixture.componentInstance.requestSubmitted.subscribe((receipt) => submitted.push(receipt));
    await clickButton('Chuẩn bị yêu cầu duyệt');
    expect(button('Gửi yêu cầu duyệt')?.disabled).toBe(true);

    await chooseBasis('publication-request-metadata-basis', 'ORIGINAL_WORK');
    await chooseBasis('publication-request-chapter-basis', 'LICENSE_OR_PERMISSION');
    await setCheckbox('Tôi cũng yêu cầu xem xét quyền thương mại', true);
    expect(button('Gửi yêu cầu duyệt')?.disabled).toBe(true);
    await chooseBasis('publication-request-commercial-basis', 'NEEDS_REVIEW');
    await setCheckbox('Tôi cũng yêu cầu xem xét quyền chuyển ngữ', true);
    expect(button('Gửi yêu cầu duyệt')?.disabled).toBe(true);
    await chooseBasis('publication-request-translation-adaptation-basis', 'LICENSE_OR_PERMISSION');
    expect(button('Gửi yêu cầu duyệt')?.disabled).toBe(true);
    await setCheckbox('Tôi đã rà soát các phạm vi', true);
    expect(button('Gửi yêu cầu duyệt')?.disabled).toBe(false);

    await clickButton('Gửi yêu cầu duyệt');

    expect(api.submitPublicationRequest).toHaveBeenCalledTimes(1);
    const [submittedWork, etag, body, key] = api.submitPublicationRequest.mock.calls[0];
    expect(submittedWork).toBe(workId);
    expect(etag).toBe(readinessEtag);
    expect(body).toEqual({
      policyVersion: 'rights-v1',
      scopeClaims: [
        { scopeCode: 'PUBLIC_METADATA', rightsBasisCode: 'ORIGINAL_WORK' },
        { scopeCode: 'CHAPTER_TEXT', rightsBasisCode: 'LICENSE_OR_PERMISSION' },
        { scopeCode: 'COMMERCIAL_SALE', rightsBasisCode: 'NEEDS_REVIEW' },
        { scopeCode: 'TRANSLATION_ADAPTATION', rightsBasisCode: 'LICENSE_OR_PERMISSION' },
      ],
    });
    expect(key).toEqual(expect.any(String));
    expect(submitted).toEqual([requestReceipt()]);
  });

  it('retries an uncertain submission with the exact same body, ETag and idempotency key', async () => {
    api.submitPublicationRequest
      .mockRejectedValueOnce(new AuthoringApiError(503, 'PUBLICATION_REQUEST_UNAVAILABLE'))
      .mockResolvedValueOnce(requestReceipt());
    await prepareCompleteRequest();

    await clickButton('Gửi yêu cầu duyệt');
    expect(fixture.nativeElement.textContent).toContain('Thử lại sẽ gửi đúng yêu cầu');
    expect(button('Gửi yêu cầu duyệt')?.disabled).toBe(true);
    await clickButton('Thử lại cùng yêu cầu');

    expect(api.submitPublicationRequest).toHaveBeenCalledTimes(2);
    expect(api.submitPublicationRequest.mock.calls[1]).toEqual(
      api.submitPublicationRequest.mock.calls[0],
    );
    expect(fixture.nativeElement.textContent).toContain('Bản thảo vẫn riêng tư');
  });

  it('locks claims during Retry-After and retries the same request only after the wait', async () => {
    api.submitPublicationRequest
      .mockRejectedValueOnce(
        new AuthoringApiError(429, 'PUBLICATION_REQUEST_RATE_LIMITED', null, 1),
      )
      .mockResolvedValueOnce(requestReceipt());
    await prepareCompleteRequest();

    await clickButton('Gửi yêu cầu duyệt');
    const metadataBasis = fixture.nativeElement.querySelector(
      '#publication-request-metadata-basis',
    ) as HTMLSelectElement;
    expect(metadataBasis.disabled).toBe(true);
    expect(button('Thử lại cùng yêu cầu')?.disabled).toBe(true);
    await new Promise((resolve) => globalThis.setTimeout(resolve, 1_050));
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(metadataBasis.disabled).toBe(true);
    expect(button('Thử lại cùng yêu cầu')?.disabled).toBe(false);

    await clickButton('Thử lại cùng yêu cầu');

    expect(api.submitPublicationRequest).toHaveBeenCalledTimes(2);
    expect(api.submitPublicationRequest.mock.calls[1]).toEqual(
      api.submitPublicationRequest.mock.calls[0],
    );
  });

  it('reloads readiness and saved chapter list after a stale snapshot', async () => {
    const newer = readyState({
      publicationContentVersion: 9,
      snapshotEtag: `"publication-snapshot-${'f'.repeat(64)}"`,
    });
    api.submitPublicationRequest.mockRejectedValueOnce(
      new AuthoringApiError(412, 'PUBLICATION_SNAPSHOT_STALE'),
    );
    api.getPublicationReadiness.mockResolvedValue(newer);
    await prepareCompleteRequest();

    await clickButton('Gửi yêu cầu duyệt');
    await waitFor(() => fixture.nativeElement.textContent.includes('Snapshot đã được tải lại.'));

    expect(api.getPublicationReadiness).toHaveBeenCalledWith(workId);
    expect(api.listPrivateChapters).toHaveBeenCalledTimes(2);
    expect(fixture.nativeElement.textContent).toContain('Snapshot đã được tải lại.');
    expect(button('Gửi yêu cầu duyệt')?.disabled).toBe(true);
  });

  async function prepareCompleteRequest(): Promise<void> {
    await clickButton('Chuẩn bị yêu cầu duyệt');
    await chooseBasis('publication-request-metadata-basis', 'ORIGINAL_WORK');
    await chooseBasis('publication-request-chapter-basis', 'LICENSE_OR_PERMISSION');
    await setCheckbox('Tôi đã rà soát các phạm vi', true);
    fixture.detectChanges();
  }

  async function clickButton(label: string): Promise<void> {
    const target = button(label);
    if (!target) throw new Error(`Missing button: ${label}`);
    if (target.disabled) throw new Error(`Button is disabled: ${label}`);
    target.click();
    fixture.detectChanges();
    await fixture.whenStable();
    await Promise.resolve();
    fixture.detectChanges();
  }

  async function chooseBasis(id: string, value: string): Promise<void> {
    const select = fixture.nativeElement.querySelector(`#${id}`) as HTMLSelectElement | null;
    if (!select) throw new Error(`Missing select: ${id}`);
    select.value = value;
    select.dispatchEvent(new Event('change', { bubbles: true }));
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  async function setCheckbox(text: string, checked: boolean): Promise<void> {
    const label = Array.from(
      fixture.nativeElement.querySelectorAll('label') as NodeListOf<HTMLLabelElement>,
    ).find((candidate) => candidate.textContent?.includes(text));
    const input = label?.querySelector('input[type="checkbox"]') as HTMLInputElement | null;
    if (!input) throw new Error(`Missing checkbox label: ${text}`);
    input.checked = checked;
    input.dispatchEvent(new Event('change', { bubbles: true }));
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  async function waitFor(ready: () => boolean): Promise<void> {
    for (let attempt = 0; attempt < 30 && !ready(); attempt += 1) {
      await Promise.resolve();
      fixture.detectChanges();
    }
    await fixture.whenStable();
    fixture.detectChanges();
    if (!ready()) {
      throw new Error(
        `Async request did not finish (readiness=${api.getPublicationReadiness.mock.calls.length}, ` +
          `chapter pages=${api.listPrivateChapters.mock.calls.length}, ` +
          `request=${api.submitPublicationRequest.mock.calls.length}).`,
      );
    }
  }

  function button(label: string): HTMLButtonElement | null {
    const buttons = fixture.nativeElement.querySelectorAll(
      'button',
    ) as NodeListOf<HTMLButtonElement>;
    return Array.from(buttons).find((item) => item.textContent?.trim() === label) ?? null;
  }
});
