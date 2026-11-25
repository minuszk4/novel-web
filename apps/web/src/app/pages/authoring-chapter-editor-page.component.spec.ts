import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { signal } from '@angular/core';
import { of } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  AuthoringApiError,
  AuthoringApiService,
  CreatedPrivateDraftRevision,
  PrivateAuthoredChapterDraft,
  PrivateManuscriptDetails,
  PublicationReadiness,
} from '../core/authoring-api.service';
import { PolicyOnboardingService } from '../core/policy-onboarding.service';
import { ApiSystemStatus } from '../core/models';
import { SystemStatusService } from '../core/system-status.service';
import { AuthoringChapterEditorPageComponent } from './authoring-chapter-editor-page.component';
import { AuthoringCollaborationApiService } from '../core/authoring-collaboration-api.service';

const workId = '11111111-1111-4111-8111-111111111111';
const chapterId = '22222222-2222-4222-8222-222222222222';
const revisionId = '44444444-4444-4444-8444-444444444444';
const timestamp = '2026-11-14T12:00:00Z';

function draft(
  revisionNo = 0,
  normalizedContent = '',
  etag = `"draft-${revisionNo}"`,
): PrivateAuthoredChapterDraft {
  return {
    id: chapterId,
    workId,
    title: 'Chương mở đầu',
    status: 'draft',
    revisionNo,
    sourceFormat: revisionNo === 0 ? null : 'plain_text',
    normalizedContent,
    segments: normalizedContent
      ? [
          {
            ordinal: 1,
            type: 'paragraph',
            sourceText: normalizedContent,
            stableAnchor: 'chapter-22222222-p1',
          },
        ]
      : [],
    updatedAt: timestamp,
    etag,
  };
}

function chapterReadiness(): PublicationReadiness {
  return {
    workId,
    canSubmit: false,
    publicationRightsPolicyAvailable: true,
    publicationRightsPolicyVersion: 'rights-v1',
    publicationRightsPolicyEffectiveAt: timestamp,
    publicationRightsPolicyAcceptedAt: timestamp,
    publicationContentVersion: 2,
    readyChapterCount: 1,
    currentRequestState: 'PENDING_REVIEW',
    snapshotEtag: `"publication-snapshot-${'a'.repeat(64)}"`,
    scopes: [
      { scopeCode: 'PUBLIC_METADATA', ready: true, snapshotEtag: '"scope-metadata"' },
      { scopeCode: 'CHAPTER_TEXT', ready: true, snapshotEtag: '"scope-text"' },
      { scopeCode: 'COMMERCIAL_SALE', ready: false, snapshotEtag: '"scope-sale"' },
      {
        scopeCode: 'TRANSLATION_ADAPTATION',
        ready: false,
        snapshotEtag: '"scope-translation"',
      },
    ],
    blockerCodes: ['PUBLICATION_REQUEST_ALREADY_PENDING'],
  };
}

const releaseDisabledStatus: ApiSystemStatus = {
  apiVersion: 'v1',
  environment: 'test',
  integrations: {
    googleOidcEnabled: false,
    momoTestEnabled: false,
    momoEnvironment: 'TEST',
  },
  features: {
    importProcessingEnabled: false,
    cultivationEnabled: false,
    commerceSandboxEnabled: false,
    publicationReleaseEnabled: false,
    translationFakePreviewEnabled: false,
  },
};

function revision(revisionNo: number, etag = `"draft-${revisionNo}"`): CreatedPrivateDraftRevision {
  return {
    id: revisionId,
    chapterId,
    revisionNo,
    sourceFormat: 'plain_text',
    createdAt: timestamp,
    etag,
  };
}

function manuscript(visibility: 'private' | 'public' = 'private'): PrivateManuscriptDetails {
  return {
    id: workId,
    title: 'Tác phẩm thử nghiệm',
    author: 'Tác giả',
    language: 'vi',
    visibility,
    publicationState: visibility === 'private' ? 'draft' : 'published',
    chapterCount: 1,
    createdAt: timestamp,
    metadataVersion: 1,
    etag: '"manuscript-1"',
  };
}

describe('AuthoringChapterEditorPageComponent', () => {
  let api: {
    getPrivateChapterDraft: ReturnType<typeof vi.fn>;
    getPrivateManuscript: ReturnType<typeof vi.fn>;
    getChapterPublicationReadiness: ReturnType<typeof vi.fn>;
    savePrivateChapterDraftRevision: ReturnType<typeof vi.fn>;
  };
  let policyStatus: ReturnType<typeof signal>;

  beforeEach(() => {
    api = {
      getPrivateChapterDraft: vi.fn().mockResolvedValue(draft()),
      getPrivateManuscript: vi.fn().mockResolvedValue(manuscript()),
      getChapterPublicationReadiness: vi.fn().mockResolvedValue(chapterReadiness()),
      savePrivateChapterDraftRevision: vi.fn().mockResolvedValue(revision(1)),
    };
    policyStatus = signal('accepted');
    TestBed.configureTestingModule({
      imports: [AuthoringChapterEditorPageComponent],
      providers: [
        provideRouter([]),
        {
          provide: ActivatedRoute,
          useValue: { paramMap: of(convertToParamMap({ workId, chapterId })) },
        },
        { provide: AuthoringApiService, useValue: api },
        {
          provide: AuthoringCollaborationApiService,
          useValue: { permissionsForWork: vi.fn().mockResolvedValue(null) },
        },
        { provide: PolicyOnboardingService, useValue: { status: policyStatus } },
        {
          provide: SystemStatusService,
          useValue: { status: signal<ApiSystemStatus | null>(releaseDisabledStatus) },
        },
      ],
    });
  });

  afterEach(() => vi.useRealTimers());

  it('loads the private draft as plain text and explains its privacy', async () => {
    const source = '<script>alert("x")</script>\n\nVăn bản chương.';
    api.getPrivateChapterDraft.mockResolvedValue(draft(1, source));
    const fixture = TestBed.createComponent(AuthoringChapterEditorPageComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(api.getPrivateChapterDraft).toHaveBeenCalledWith(workId, chapterId);
    expect(document.activeElement).toBe(fixture.nativeElement.querySelector('h1'));
    expect(fixture.nativeElement.querySelector('#chapter-draft-content').value).toBe(source);
    expect(fixture.nativeElement.querySelector('script')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('Bản thảo riêng tư');
    expect(fixture.nativeElement.textContent).toContain(
      'Bản xem trước chỉ đọc phiên bản đã lưu gần nhất',
    );
    expect(fixture.nativeElement.textContent).toContain('Đã lưu · phiên bản 1');
    expect(fixture.nativeElement.querySelector('a[href*="reader"]')).toBeNull();
  });

  it('shows chapter-scoped review and owner release controls only for a public work', async () => {
    api.getPrivateManuscript.mockResolvedValue(manuscript('public'));
    api.getPrivateChapterDraft.mockResolvedValue(draft(1, 'Saved staged chapter text.'));
    const fixture = await createEditor();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(api.getChapterPublicationReadiness).toHaveBeenCalledWith(workId, chapterId);
    expect(fixture.nativeElement.textContent).toContain('Duyệt chương mới');
    expect(fixture.nativeElement.textContent).toContain(
      'Chương nháp chỉ được công khai sau khi reviewer duyệt và bạn xác nhận xuất bản.',
    );
    expect(fixture.nativeElement.textContent).toContain('Đang chờ người duyệt');
    expect(fixture.nativeElement.querySelector('button[type="submit"]')).toBeNull();
    fixture.destroy();
  });

  it('waits for the first saved draft revision before loading chapter review readiness', async () => {
    api.getPrivateManuscript.mockResolvedValue(manuscript('public'));
    const fixture = await createEditor();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(api.getChapterPublicationReadiness).not.toHaveBeenCalled();
    expect(fixture.nativeElement.querySelector('.chapter-release-panel')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('chưa được xuất bản');
    fixture.destroy();
  });

  it('keeps an approved chapter private while the publication release feature is disabled', async () => {
    api.getPrivateManuscript.mockResolvedValue(manuscript('public'));
    api.getPrivateChapterDraft.mockResolvedValue(draft(1, 'Saved staged chapter text.'));
    api.getChapterPublicationReadiness.mockResolvedValue({
      ...chapterReadiness(),
      canSubmit: true,
      currentRequestState: 'APPROVED_UNPUBLISHED',
      blockerCodes: [],
    });
    const fixture = await createEditor();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Reviewer đã duyệt chương');
    expect(fixture.nativeElement.textContent).toContain(
      'Tác vụ xuất bản đang tắt trong môi trường này',
    );
    expect(fixture.nativeElement.querySelector('.chapter-release-confirm')).toBeNull();
    expect(fixture.nativeElement.querySelector('button[type="submit"]')).toBeNull();
    fixture.destroy();
  });

  it('previews only the saved revision and treats manuscript markup as plain text', async () => {
    const source = '<script>alert("x")</script>\n\nVăn bản chương.';
    api.getPrivateChapterDraft.mockResolvedValue(draft(2, source, '"draft-2"'));
    const fixture = await createEditor();
    const previewButton = fixture.nativeElement.querySelector(
      '.chapter-editor-mode-actions button',
    ) as HTMLButtonElement;

    expect(previewButton.disabled).toBe(false);
    previewButton.click();
    fixture.detectChanges();

    const article = fixture.nativeElement.querySelector('.chapter-editor-preview');
    expect(article.textContent).toContain('Phiên bản 2 · Chưa xuất bản');
    expect(fixture.nativeElement.querySelector('.chapter-editor-preview-content').textContent).toBe(
      source,
    );
    expect(fixture.nativeElement.querySelector('script')).toBeNull();
    expect(fixture.nativeElement.querySelector('#chapter-draft-content')).toBeNull();
    expect(api.savePrivateChapterDraftRevision).not.toHaveBeenCalled();
    expect(fixture.componentInstance['isPreviewMode']()).toBe(true);

    previewButton.click();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('#chapter-draft-content').value).toBe(source);
    expect(fixture.componentInstance['isPreviewMode']()).toBe(false);
  });

  it('disables preview until the first revision exists and whenever edits are unsaved', async () => {
    const fixture = await createEditor();
    let previewButton = fixture.nativeElement.querySelector(
      '.chapter-editor-mode-actions button',
    ) as HTMLButtonElement;
    expect(previewButton.disabled).toBe(true);
    expect(fixture.nativeElement.textContent).toContain(
      'Lưu phiên bản đầu tiên trước khi xem trước.',
    );

    api.getPrivateChapterDraft.mockResolvedValue(draft(1, 'Bản đã lưu.', '"draft-1"'));
    await fixture.componentInstance.retryLoad();
    fixture.detectChanges();
    previewButton = fixture.nativeElement.querySelector(
      '.chapter-editor-mode-actions button',
    ) as HTMLButtonElement;
    expect(previewButton.disabled).toBe(false);

    fixture.componentInstance.updateContent(inputEvent('Thay đổi chưa lưu.'));
    fixture.detectChanges();
    expect(previewButton.disabled).toBe(true);
    expect(fixture.nativeElement.querySelector('.chapter-editor-preview')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain(
      'Lưu hết thay đổi trước khi xem bản đã lưu.',
    );
    expect(api.savePrivateChapterDraftRevision).not.toHaveBeenCalled();
  });

  it('saves a new immutable revision using the current ETag and ordered paragraph anchors', async () => {
    const fixture = await createEditor();
    fixture.componentInstance.updateContent(inputEvent('Đoạn một.\n\nĐoạn hai.'));
    fixture.detectChanges();

    await fixture.componentInstance.saveNow();
    fixture.detectChanges();

    expect(api.savePrivateChapterDraftRevision).toHaveBeenCalledTimes(1);
    const [savedWorkId, savedChapterId, etag, input, idempotencyKey] =
      api.savePrivateChapterDraftRevision.mock.calls[0];
    expect([savedWorkId, savedChapterId, etag]).toEqual([workId, chapterId, '"draft-0"']);
    expect(input).toEqual({
      sourceFormat: 'plain_text',
      normalizedContent: 'Đoạn một.\n\nĐoạn hai.',
      segments: [
        {
          ordinal: 1,
          type: 'paragraph',
          sourceText: 'Đoạn một.',
          stableAnchor: 'chapter-22222222-p1',
        },
        {
          ordinal: 2,
          type: 'paragraph',
          sourceText: 'Đoạn hai.',
          stableAnchor: 'chapter-22222222-p2',
        },
      ],
    });
    expect(idempotencyKey).toMatch(/^[A-Za-z0-9._~-]{16,128}$/);
    expect(fixture.nativeElement.textContent).toContain('Đã lưu · phiên bản 1');
  });

  it('keeps the editor usable during a save and queues newer typing as the next autosave', async () => {
    let completeSave!: (value: CreatedPrivateDraftRevision) => void;
    api.savePrivateChapterDraftRevision.mockImplementation(
      () =>
        new Promise<CreatedPrivateDraftRevision>((resolve) => {
          completeSave = resolve;
        }),
    );
    const fixture = await createEditor();
    fixture.componentInstance.updateContent(inputEvent('Bản gửi đi.'));
    const saving = fixture.componentInstance.saveNow();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('#chapter-draft-content').disabled).toBe(false);

    fixture.componentInstance.updateContent(inputEvent('Bản mới hơn đang viết.'));
    completeSave(revision(1));
    await saving;
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('#chapter-draft-content').value).toBe(
      'Bản mới hơn đang viết.',
    );
    expect(fixture.nativeElement.textContent).toContain('Có thay đổi chưa lưu');
    fixture.destroy();
  });

  it('debounces autosave until typing pauses', async () => {
    vi.useFakeTimers();
    const fixture = await createEditor();
    fixture.componentInstance.updateContent(inputEvent('Một đoạn đã viết.'));
    fixture.detectChanges();

    await vi.advanceTimersByTimeAsync(899);
    expect(api.savePrivateChapterDraftRevision).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(1);
    await flushMicrotasks();

    expect(api.savePrivateChapterDraftRevision).toHaveBeenCalledTimes(1);
    expect(fixture.componentInstance['saveState']()).toBe('saved');
  });

  it('does not send empty or oversized content and exposes a clear validation message', async () => {
    const fixture = await createEditor();
    fixture.componentInstance.updateContent(inputEvent('   '));
    fixture.detectChanges();
    await fixture.componentInstance.saveNow();
    expect(api.savePrivateChapterDraftRevision).not.toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain(
      'Nhập nội dung trước khi lưu phiên bản đầu tiên.',
    );

    fixture.componentInstance.updateContent(inputEvent('x'.repeat(10_485_761)));
    fixture.detectChanges();
    await fixture.componentInstance.saveNow();
    expect(api.savePrivateChapterDraftRevision).not.toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain('Nội dung vượt giới hạn 10 MiB.');
  });

  it('asks the author to split a paragraph that exceeds the 1 MiB segment limit', async () => {
    const fixture = await createEditor();
    fixture.componentInstance.updateContent(inputEvent('x'.repeat(1_048_577)));
    fixture.detectChanges();

    await fixture.componentInstance.saveNow();

    expect(api.savePrivateChapterDraftRevision).not.toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain(
      'Một đoạn văn vượt giới hạn 1 MiB. Hãy chia nội dung thành các đoạn ngắn hơn.',
    );
  });

  it('retries an uncertain save with the exact same payload, ETag and idempotency key', async () => {
    api.savePrivateChapterDraftRevision
      .mockRejectedValueOnce(new AuthoringApiError(0, 'AUTHORING_UNAVAILABLE'))
      .mockResolvedValueOnce(revision(1));
    const fixture = await createEditor();
    fixture.componentInstance.updateContent(inputEvent('Giữ nguyên lần thử.'));
    await fixture.componentInstance.saveNow();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('#chapter-draft-content').disabled).toBe(true);
    expect(fixture.nativeElement.textContent).toContain('Xác nhận lưu lại');
    expect(fixture.nativeElement.textContent).toContain(
      'Xác nhận kết quả lần lưu trước khi xem bản đã lưu.',
    );

    fixture.componentInstance.retryUncertainSave();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(api.savePrivateChapterDraftRevision).toHaveBeenCalledTimes(2);
    expect(api.savePrivateChapterDraftRevision.mock.calls[1]).toEqual(
      api.savePrivateChapterDraftRevision.mock.calls[0],
    );
    expect(fixture.nativeElement.textContent).toContain('Đã lưu · phiên bản 1');
  });

  it('keeps local text during a stale-version conflict and lets the author choose the server version', async () => {
    const remote = draft(2, 'Bản mới từ máy chủ.', '"draft-2"');
    api.getPrivateChapterDraft.mockResolvedValueOnce(draft()).mockResolvedValueOnce(remote);
    api.savePrivateChapterDraftRevision.mockRejectedValueOnce(
      new AuthoringApiError(412, 'DRAFT_REVISION_STALE', '"draft-2"'),
    );
    const fixture = await createEditor();
    fixture.componentInstance.updateContent(inputEvent('Bản người viết đang làm.'));
    await fixture.componentInstance.saveNow();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('#chapter-draft-content').value).toBe(
      'Bản người viết đang làm.',
    );
    expect(fixture.nativeElement.querySelector('#chapter-remote-content').value).toBe(
      'Bản mới từ máy chủ.',
    );
    expect(fixture.nativeElement.textContent).toContain('Bản thảo đã đổi trên máy chủ');

    fixture.componentInstance.useRemoteVersion();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('#chapter-draft-content').value).toBe(
      'Bản mới từ máy chủ.',
    );
    expect(fixture.componentInstance['canDeactivate']()).toBe(true);
    expect(api.savePrivateChapterDraftRevision).toHaveBeenCalledTimes(1);
  });

  it('writes the local draft as a new revision only after the author explicitly chooses it', async () => {
    const remote = draft(4, 'Bản máy chủ.', '"draft-4"');
    api.getPrivateChapterDraft.mockResolvedValueOnce(draft()).mockResolvedValueOnce(remote);
    api.savePrivateChapterDraftRevision
      .mockRejectedValueOnce(new AuthoringApiError(412, 'DRAFT_REVISION_STALE'))
      .mockResolvedValueOnce(revision(5, '"draft-5"'));
    const fixture = await createEditor();
    fixture.componentInstance.updateContent(inputEvent('Bản người viết.'));
    await fixture.componentInstance.saveNow();

    await fixture.componentInstance.saveMyVersionAsNext();
    fixture.detectChanges();

    expect(api.savePrivateChapterDraftRevision).toHaveBeenCalledTimes(2);
    expect(api.savePrivateChapterDraftRevision.mock.calls[1][2]).toBe('"draft-4"');
    expect(api.savePrivateChapterDraftRevision.mock.calls[1][3].normalizedContent).toBe(
      'Bản người viết.',
    );
    expect(api.savePrivateChapterDraftRevision.mock.calls[1][4]).not.toBe(
      api.savePrivateChapterDraftRevision.mock.calls[0][4],
    );
    expect(fixture.nativeElement.textContent).toContain('Đã lưu · phiên bản 5');
  });

  it('warns before leaving while changes are unsaved and permits leaving after discard confirmation', async () => {
    const fixture = await createEditor();
    fixture.componentInstance.updateContent(inputEvent('Chưa lưu.'));
    const confirmation = vi.fn().mockReturnValue(false);
    const previousDescriptor = Object.getOwnPropertyDescriptor(window, 'confirm');
    Object.defineProperty(window, 'confirm', {
      configurable: true,
      writable: true,
      value: confirmation,
    });
    try {
      expect(fixture.componentInstance.canDeactivate()).toBe(false);
      expect(confirmation).toHaveBeenCalledOnce();
      confirmation.mockReturnValue(true);
      expect(fixture.componentInstance.canDeactivate()).toBe(true);
    } finally {
      if (previousDescriptor) {
        Object.defineProperty(window, 'confirm', previousDescriptor);
      } else {
        Reflect.deleteProperty(window, 'confirm');
      }
    }
  });

  it('shows a sign-in route for an unauthenticated user and makes no private API request', async () => {
    policyStatus.set('demo');
    const fixture = TestBed.createComponent(AuthoringChapterEditorPageComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Đăng nhập để viết chương');
    expect(fixture.nativeElement.querySelector('a[href*="sign-in"]')).not.toBeNull();
    expect(api.getPrivateChapterDraft).not.toHaveBeenCalled();
  });
});

async function createEditor() {
  const fixture = TestBed.createComponent(AuthoringChapterEditorPageComponent);
  fixture.detectChanges();
  await fixture.whenStable();
  await flushMicrotasks();
  fixture.detectChanges();
  return fixture;
}

function inputEvent(value: string): Event {
  return { target: { value } } as unknown as Event;
}

async function flushMicrotasks(): Promise<void> {
  await Promise.resolve();
  await Promise.resolve();
  await Promise.resolve();
}
