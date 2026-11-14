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
} from '../core/authoring-api.service';
import { PolicyOnboardingService } from '../core/policy-onboarding.service';
import { AuthoringChapterEditorPageComponent } from './authoring-chapter-editor-page.component';

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

describe('AuthoringChapterEditorPageComponent', () => {
  let api: {
    getPrivateChapterDraft: ReturnType<typeof vi.fn>;
    savePrivateChapterDraftRevision: ReturnType<typeof vi.fn>;
  };
  let policyStatus: ReturnType<typeof signal>;

  beforeEach(() => {
    api = {
      getPrivateChapterDraft: vi.fn().mockResolvedValue(draft()),
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
        { provide: PolicyOnboardingService, useValue: { status: policyStatus } },
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
    fixture.detectChanges();

    expect(api.getPrivateChapterDraft).toHaveBeenCalledWith(workId, chapterId);
    expect(document.activeElement).toBe(fixture.nativeElement.querySelector('h1'));
    expect(fixture.nativeElement.querySelector('#chapter-draft-content').value).toBe(source);
    expect(fixture.nativeElement.querySelector('script')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('Bản thảo riêng tư');
    expect(fixture.nativeElement.textContent).toContain(
      'chưa có chức năng xem thử bản dịch cho chương này',
    );
    expect(fixture.nativeElement.textContent).toContain('Đã lưu · phiên bản 1');
    expect(fixture.nativeElement.querySelector('a[href*="reader"]')).toBeNull();
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
