import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { signal } from '@angular/core';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  AuthoringApiError,
  AuthoringApiService,
  CreatedPrivateChapter,
  PrivateChapterOutlineItem,
  PrivateChapterOutlinePage,
  PrivateManuscriptDetails,
} from '../core/authoring-api.service';
import { PolicyOnboardingService } from '../core/policy-onboarding.service';
import { AuthoringOutlinePageComponent } from './authoring-outline-page.component';
import { SystemStatusService } from '../core/system-status.service';
import { AuthoringCollaborationApiService } from '../core/authoring-collaboration-api.service';

const workId = '11111111-1111-4111-8111-111111111111';
const firstChapterId = '22222222-2222-4222-8222-222222222222';
const secondChapterId = '33333333-3333-4333-8333-333333333333';
const timestamp = '2026-11-14T12:00:00Z';

function chapter(
  id = firstChapterId,
  ordinal = 1,
  title = 'Mở đầu',
  status: PrivateChapterOutlineItem['status'] = 'draft',
): PrivateChapterOutlineItem {
  return {
    id,
    ordinal,
    title,
    language: 'vi',
    status,
    contentAvailable: status === 'ready',
    hasSavedDraftContent: false,
    createdAt: timestamp,
  };
}

function createdChapter(): CreatedPrivateChapter {
  return {
    id: secondChapterId,
    workId,
    ordinal: 2,
    title: 'Đêm đầu tiên ở Thanh Vân Tông',
    language: 'vi',
    status: 'draft',
    createdAt: timestamp,
  };
}

function page(
  items: PrivateChapterOutlineItem[] = [],
  nextCursor: string | null = null,
): PrivateChapterOutlinePage {
  return { items, nextCursor };
}

function manuscript(): PrivateManuscriptDetails {
  return {
    id: workId,
    title: 'Hành trình tu luyện',
    author: 'Bút danh',
    language: 'vi',
    visibility: 'private',
    publicationState: 'draft',
    chapterCount: 0,
    createdAt: timestamp,
    metadataVersion: 1,
    etag: '"manuscript-metadata-1"',
  };
}

describe('AuthoringOutlinePageComponent', () => {
  let api: {
    getPrivateManuscript: ReturnType<typeof vi.fn>;
    getCurrentPublicationRightsPolicy: ReturnType<typeof vi.fn>;
    acceptCurrentPublicationRightsPolicy: ReturnType<typeof vi.fn>;
    updatePrivateManuscript: ReturnType<typeof vi.fn>;
    listPrivateChapters: ReturnType<typeof vi.fn>;
    createPrivateChapter: ReturnType<typeof vi.fn>;
    deletePrivateEmptyChapter: ReturnType<typeof vi.fn>;
  };
  let policyStatus: ReturnType<typeof signal>;

  beforeEach(() => {
    api = {
      getPrivateManuscript: vi.fn().mockResolvedValue(manuscript()),
      getCurrentPublicationRightsPolicy: vi.fn().mockResolvedValue({
        version: 'rights-v1',
        effectiveAt: timestamp,
        displayText: 'Synthetic rights policy for test.',
        policySha256: 'a'.repeat(64),
        acceptedAt: null,
      }),
      acceptCurrentPublicationRightsPolicy: vi.fn().mockResolvedValue({
        id: '55555555-5555-4555-8555-555555555555',
        policyVersion: 'rights-v1',
        policySha256: 'a'.repeat(64),
        acceptedAt: timestamp,
      }),
      updatePrivateManuscript: vi.fn().mockImplementation((_workId, values) =>
        Promise.resolve({
          ...manuscript(),
          ...values,
          metadataVersion: 2,
          etag: '"manuscript-metadata-2"',
        }),
      ),
      listPrivateChapters: vi.fn().mockResolvedValue(page()),
      createPrivateChapter: vi.fn().mockResolvedValue(createdChapter()),
      deletePrivateEmptyChapter: vi.fn().mockResolvedValue(undefined),
    };
    policyStatus = signal('accepted');
    TestBed.configureTestingModule({
      imports: [AuthoringOutlinePageComponent],
      providers: [
        provideRouter([]),
        {
          provide: ActivatedRoute,
          useValue: { paramMap: of(convertToParamMap({ workId })) },
        },
        { provide: AuthoringApiService, useValue: api },
        {
          provide: AuthoringCollaborationApiService,
          useValue: { permissionsForWork: vi.fn().mockResolvedValue(null) },
        },
        { provide: PolicyOnboardingService, useValue: { status: policyStatus } },
        { provide: SystemStatusService, useValue: { status: signal(null) } },
      ],
    });
  });

  it('loads and renders only chapter metadata and a private empty state', async () => {
    const fixture = TestBed.createComponent(AuthoringOutlinePageComponent);
    fixture.detectChanges();
    await settle(fixture);
    fixture.detectChanges();

    expect(api.listPrivateChapters).toHaveBeenCalledWith(workId, undefined, 25);
    expect(api.getPrivateManuscript).toHaveBeenCalledWith(workId);
    expect(fixture.nativeElement.querySelector('h1')?.textContent).toContain('Hành trình tu luyện');
    expect(fixture.nativeElement.textContent).toContain('Bút danh · Ngôn ngữ vi · 0 chương');
    expect(fixture.nativeElement.textContent).toContain('Chỉ bạn có thể xem mục lục này');
    expect(fixture.nativeElement.textContent).toContain('Chưa có chương nào');
    expect(fixture.nativeElement.querySelector('input')).toBeNull();
    expect(fixture.nativeElement.querySelector('a[href*="/reader/"]')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('Bản thảo riêng tư · Chưa xuất bản');
    expect(api.getCurrentPublicationRightsPolicy).not.toHaveBeenCalled();
  });

  it('lets the owner stage drafts on a public work without offering public metadata or release controls', async () => {
    api.getPrivateManuscript.mockResolvedValueOnce({
      ...manuscript(),
      visibility: 'public',
      publicationState: 'published',
      chapterCount: 2,
    });
    api.listPrivateChapters.mockResolvedValueOnce(
      page([chapter(firstChapterId, 1, 'Already released', 'ready'), chapter(secondChapterId, 2)]),
    );
    const fixture = TestBed.createComponent(AuthoringOutlinePageComponent);
    fixture.detectChanges();
    await settle(fixture);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('TÁC PHẨM ĐANG PHÁT HÀNH');
    expect(fixture.nativeElement.textContent).toContain('Đã phát hành');
    expect(fixture.nativeElement.textContent).toContain('Bản nháp riêng tư');
    expect(fixture.nativeElement.querySelector('.authoring-outline-metadata-trigger')).toBeNull();
    expect(fixture.nativeElement.querySelector('app-publication-readiness-panel')).toBeNull();
    expect(fixture.nativeElement.querySelector('app-publication-rights-policy-panel')).toBeNull();
    expect(fixture.nativeElement.querySelectorAll('.authoring-outline-edit')).toHaveLength(1);
    expect(api.getCurrentPublicationRightsPolicy).not.toHaveBeenCalled();
  });

  it('shows the rights policy only after the author opens the policy step', async () => {
    const fixture = TestBed.createComponent(AuthoringOutlinePageComponent);
    fixture.detectChanges();
    await settle(fixture);
    fixture.detectChanges();

    const openPolicy = [...fixture.nativeElement.querySelectorAll('button')].find((button) =>
      button.textContent.includes('Kiểm tra chính sách quyền xuất bản'),
    ) as HTMLButtonElement;
    expect(openPolicy).toBeTruthy();
    expect(api.getCurrentPublicationRightsPolicy).not.toHaveBeenCalled();

    openPolicy.click();
    fixture.detectChanges();
    await settle(fixture);
    fixture.detectChanges();

    expect(api.getCurrentPublicationRightsPolicy).toHaveBeenCalledWith(workId);
    expect(fixture.nativeElement.textContent).toContain('Synthetic rights policy for test.');
    expect(fixture.nativeElement.textContent).toContain('Chưa xuất bản');
    expect(fixture.nativeElement.querySelector('input[type="checkbox"]')).toBeTruthy();
  });

  it('edits only private manuscript metadata and returns focus after a confirmed save', async () => {
    const fixture = TestBed.createComponent(AuthoringOutlinePageComponent);
    fixture.detectChanges();
    await settle(fixture);
    fixture.detectChanges();
    fixture.componentInstance.openMetadataForm();
    fixture.detectChanges();

    const title = fixture.nativeElement.querySelector(
      '#authoring-metadata-title',
    ) as HTMLInputElement;
    expect(title).toBeTruthy();
    expect(document.activeElement).toBe(title);
    expect(fixture.nativeElement.textContent).toContain('Bản thảo vẫn ở chế độ riêng tư');
    fixture.componentInstance.updateMetadataField('title', inputEvent('  Tên mới  '));
    fixture.componentInstance.updateMetadataField('author', inputEvent(' Bút danh mới '));
    fixture.componentInstance.updateMetadataField('language', inputEvent(' ja '));

    await fixture.componentInstance.saveMetadata(submitEvent());
    fixture.detectChanges();

    expect(api.updatePrivateManuscript).toHaveBeenCalledWith(
      workId,
      { title: 'Tên mới', author: 'Bút danh mới', language: 'ja' },
      '"manuscript-metadata-1"',
    );
    expect(fixture.nativeElement.querySelector('h1')?.textContent).toContain('Tên mới');
    expect(fixture.nativeElement.textContent).toContain('Bút danh mới · Ngôn ngữ ja · 0 chương');
    expect(fixture.nativeElement.textContent).toContain('Đã lưu thông tin bản thảo.');
    await vi.waitFor(() => {
      expect(document.activeElement).toBe(
        fixture.nativeElement.querySelector('.authoring-outline-metadata-trigger'),
      );
    });
  });

  it('marks each invalid metadata field and focuses the first one without calling the API', async () => {
    const fixture = TestBed.createComponent(AuthoringOutlinePageComponent);
    fixture.detectChanges();
    await settle(fixture);
    fixture.componentInstance.openMetadataForm();
    fixture.detectChanges();
    fixture.componentInstance.updateMetadataField('title', inputEvent('😀'.repeat(301)));
    fixture.componentInstance.updateMetadataField('author', inputEvent('a'.repeat(201)));
    fixture.componentInstance.updateMetadataField('language', inputEvent('x'));

    await fixture.componentInstance.saveMetadata(submitEvent());
    fixture.detectChanges();

    expect(api.updatePrivateManuscript).not.toHaveBeenCalled();
    for (const field of ['title', 'author', 'language']) {
      const input = fixture.nativeElement.querySelector(
        `#authoring-metadata-${field}`,
      ) as HTMLInputElement;
      expect(input.getAttribute('aria-invalid')).toBe('true');
      expect(input.getAttribute('aria-describedby')).toContain(`authoring-metadata-${field}-error`);
      expect(
        fixture.nativeElement.querySelector(`#authoring-metadata-${field}-error`),
      ).toBeTruthy();
    }
    expect(document.activeElement).toBe(
      fixture.nativeElement.querySelector('#authoring-metadata-title'),
    );
  });

  it('keeps local metadata after a stale response and applies it only after explicit choice', async () => {
    const latest = {
      ...manuscript(),
      title: 'Server title',
      metadataVersion: 2,
      etag: '"manuscript-metadata-2"',
    };
    api.updatePrivateManuscript.mockRejectedValueOnce(
      new AuthoringApiError(412, 'MANUSCRIPT_METADATA_STALE'),
    );
    api.getPrivateManuscript.mockResolvedValueOnce(manuscript()).mockResolvedValueOnce(latest);
    const fixture = TestBed.createComponent(AuthoringOutlinePageComponent);
    fixture.detectChanges();
    await settle(fixture);
    fixture.componentInstance.openMetadataForm();
    fixture.componentInstance.updateMetadataField('title', inputEvent('My local title'));

    await fixture.componentInstance.saveMetadata(submitEvent());
    fixture.detectChanges();
    await settle(fixture);

    expect(fixture.nativeElement.textContent).toContain('Server title');
    expect(
      (fixture.nativeElement.querySelector('#authoring-metadata-title') as HTMLInputElement).value,
    ).toBe('My local title');
    expect(fixture.nativeElement.textContent).toContain('Dùng thông tin trên máy chủ');

    await fixture.componentInstance.saveMetadataOnLatest();
    fixture.detectChanges();
    expect(api.updatePrivateManuscript).toHaveBeenNthCalledWith(
      2,
      workId,
      { title: 'My local title', author: 'Bút danh', language: 'vi' },
      latest.etag,
    );
  });

  it('lets the author explicitly discard local metadata and use the server version', async () => {
    const latest = {
      ...manuscript(),
      title: 'Latest server title',
      author: 'Latest pen',
      metadataVersion: 2,
      etag: '"manuscript-metadata-2"',
    };
    api.updatePrivateManuscript.mockRejectedValueOnce(
      new AuthoringApiError(412, 'MANUSCRIPT_METADATA_STALE'),
    );
    api.getPrivateManuscript.mockResolvedValueOnce(manuscript()).mockResolvedValueOnce(latest);
    const fixture = TestBed.createComponent(AuthoringOutlinePageComponent);
    fixture.detectChanges();
    await settle(fixture);
    fixture.componentInstance.openMetadataForm();
    fixture.componentInstance.updateMetadataField('title', inputEvent('Local unsaved title'));
    await fixture.componentInstance.saveMetadata(submitEvent());
    fixture.detectChanges();
    await settle(fixture);

    const confirmation = vi.fn().mockReturnValue(false);
    const previousDescriptor = Object.getOwnPropertyDescriptor(window, 'confirm');
    Object.defineProperty(window, 'confirm', {
      configurable: true,
      writable: true,
      value: confirmation,
    });
    try {
      fixture.componentInstance.useServerMetadata();
      fixture.detectChanges();
      expect(fixture.nativeElement.querySelector('#authoring-metadata-title')).toBeTruthy();
      expect(
        (fixture.nativeElement.querySelector('#authoring-metadata-title') as HTMLInputElement)
          .value,
      ).toBe('Local unsaved title');
      expect(confirmation).toHaveBeenCalledOnce();

      confirmation.mockReturnValue(true);
      fixture.componentInstance.useServerMetadata();
      fixture.detectChanges();
      expect(fixture.nativeElement.querySelector('h1')?.textContent).toContain(
        'Latest server title',
      );
      expect(fixture.nativeElement.querySelector('#authoring-metadata-title')).toBeNull();
      expect(fixture.nativeElement.textContent).not.toContain('Local unsaved title');
      expect(fixture.nativeElement.textContent).toContain('Đã dùng thông tin bản thảo mới nhất');
    } finally {
      if (previousDescriptor) {
        Object.defineProperty(window, 'confirm', previousDescriptor);
      } else {
        Reflect.deleteProperty(window, 'confirm');
      }
    }
  });

  it('asks before leaving dirty metadata and blocks departure while a save is in flight', async () => {
    const fixture = TestBed.createComponent(AuthoringOutlinePageComponent);
    fixture.detectChanges();
    await settle(fixture);
    fixture.componentInstance.openMetadataForm();
    fixture.componentInstance.updateMetadataField('title', inputEvent('Unsaved title'));
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

    let resolveUpdate!: (value: PrivateManuscriptDetails) => void;
    api.updatePrivateManuscript.mockReturnValueOnce(
      new Promise<PrivateManuscriptDetails>((resolve) => {
        resolveUpdate = resolve;
      }),
    );
    const save = fixture.componentInstance.saveMetadata(submitEvent());
    fixture.detectChanges();
    expect(fixture.componentInstance.canDeactivate()).toBe(false);
    expect(
      fixture.nativeElement.querySelector('.authoring-outline-save-status')?.textContent,
    ).toContain('Đang lưu thông tin bản thảo');
    expect(
      (fixture.nativeElement.querySelector('#authoring-metadata-title') as HTMLInputElement)
        .disabled,
    ).toBe(true);
    resolveUpdate({
      ...manuscript(),
      title: 'Unsaved title',
      metadataVersion: 2,
      etag: '"manuscript-metadata-2"',
    });
    await save;
  });

  it('retries an uncertain metadata save with the exact values and same ETag', async () => {
    api.updatePrivateManuscript
      .mockRejectedValueOnce(new AuthoringApiError(503, 'TEMPORARY_FAILURE'))
      .mockResolvedValueOnce({ ...manuscript(), title: 'Recovered title', metadataVersion: 2 });
    const fixture = TestBed.createComponent(AuthoringOutlinePageComponent);
    fixture.detectChanges();
    await settle(fixture);
    fixture.componentInstance.openMetadataForm();
    fixture.componentInstance.updateMetadataField('title', inputEvent('Recovered title'));

    await fixture.componentInstance.saveMetadata(submitEvent());
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('#authoring-metadata-title')?.disabled).toBe(true);
    expect(fixture.nativeElement.textContent).toContain('Thử xác nhận lại');

    await fixture.componentInstance.saveMetadata(submitEvent());
    fixture.detectChanges();
    expect(api.updatePrivateManuscript).toHaveBeenCalledTimes(2);
    expect(api.updatePrivateManuscript.mock.calls[1]).toEqual(
      api.updatePrivateManuscript.mock.calls[0],
    );
    expect(fixture.nativeElement.querySelector('h1')?.textContent).toContain('Recovered title');
  });

  it('shows existing chapter status and does not offer chapter-content editing', async () => {
    api.listPrivateChapters.mockResolvedValue(
      page([chapter(firstChapterId, 1, 'Mở đầu đã có nội dung', 'ready')]),
    );
    const fixture = TestBed.createComponent(AuthoringOutlinePageComponent);
    fixture.detectChanges();
    await settle(fixture);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Mở đầu đã có nội dung');
    expect(fixture.nativeElement.textContent).toContain('Có nội dung đọc được');
    expect(fixture.nativeElement.textContent).not.toContain('Mở trình soạn thảo');
    expect(fixture.nativeElement.querySelector('a[href*="/reader/"]')).toBeNull();
  });

  it('links private draft rows to the chapter editor while keeping ready rows read-only', async () => {
    api.listPrivateChapters.mockResolvedValue(
      page([
        chapter(firstChapterId, 1, 'Chưa viết'),
        chapter(secondChapterId, 2, 'Đã sẵn sàng', 'ready'),
      ]),
    );
    const fixture = TestBed.createComponent(AuthoringOutlinePageComponent);
    fixture.detectChanges();
    await settle(fixture);
    fixture.detectChanges();

    const draftLink = fixture.nativeElement.querySelector(
      `a[href="/app/authoring/${workId}/chapters/${firstChapterId}"]`,
    );
    expect(draftLink?.textContent).toContain('Viết chương');
    const readyRow = [...fixture.nativeElement.querySelectorAll('.authoring-outline-row')].find(
      (row: Element) => row.textContent?.includes('Đã sẵn sàng'),
    );
    expect(readyRow?.querySelector('a')).toBeNull();
  });

  it('moves keyboard focus into the title field when the add form opens', async () => {
    const fixture = TestBed.createComponent(AuthoringOutlinePageComponent);
    fixture.detectChanges();
    await settle(fixture);
    fixture.componentInstance.openAddForm();
    fixture.detectChanges();

    const input = fixture.nativeElement.querySelector('#authoring-chapter-title');
    expect(input).not.toBeNull();
    expect(document.activeElement).toBe(input);

    fixture.componentInstance.closeAddForm();
    fixture.detectChanges();
    await settle(fixture);
    expect(document.activeElement).toBe(
      fixture.nativeElement.querySelector('.authoring-outline-list-heading button'),
    );
  });

  it('keeps demo visitors away from private authoring APIs and offers Google sign-in', async () => {
    policyStatus.set('demo');
    const fixture = TestBed.createComponent(AuthoringOutlinePageComponent);
    fixture.detectChanges();
    await settle(fixture);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Đăng nhập để mở dàn ý');
    expect(fixture.nativeElement.querySelector('a.button-primary')?.getAttribute('href')).toBe(
      `/sign-in?returnTo=%2Fapp%2Fauthoring%2F${workId}%2Fchapters`,
    );
    expect(api.listPrivateChapters).not.toHaveBeenCalled();
    expect(api.getPrivateManuscript).not.toHaveBeenCalled();
    expect(api.createPrivateChapter).not.toHaveBeenCalled();
  });

  it('validates an empty title before posting a chapter', async () => {
    const fixture = TestBed.createComponent(AuthoringOutlinePageComponent);
    fixture.detectChanges();
    await settle(fixture);
    fixture.componentInstance.openAddForm();
    fixture.componentInstance.updateChapterTitle(inputEvent('   '));
    await fixture.componentInstance.addChapter(submitEvent());
    fixture.detectChanges();
    await settle(fixture);

    expect(api.createPrivateChapter).not.toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain('Tên chương cần có từ 1 đến 300 ký tự.');
    expect(
      fixture.nativeElement.querySelector('#authoring-chapter-title')?.getAttribute('aria-invalid'),
    ).toBe('true');
  });

  it('counts Unicode code points so 300 emoji fit the title limit', async () => {
    const fixture = TestBed.createComponent(AuthoringOutlinePageComponent);
    fixture.detectChanges();
    await settle(fixture);
    fixture.componentInstance.openAddForm();
    fixture.componentInstance.updateChapterTitle(inputEvent('😀'.repeat(301)));
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('301 / 300 ký tự');
    await fixture.componentInstance.addChapter(submitEvent());
    fixture.detectChanges();

    expect(api.createPrivateChapter).not.toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain('Tên chương cần có từ 1 đến 300 ký tự.');
  });

  it('creates a private blank chapter and announces that no content was created', async () => {
    api.listPrivateChapters.mockResolvedValue(page([chapter()]));
    api.getPrivateManuscript.mockResolvedValue({ ...manuscript(), chapterCount: 1 });
    const fixture = TestBed.createComponent(AuthoringOutlinePageComponent);
    fixture.detectChanges();
    await settle(fixture);
    fixture.componentInstance.openAddForm();
    fixture.componentInstance.updateChapterTitle(inputEvent(' Đêm đầu tiên ở Thanh Vân Tông '));

    await fixture.componentInstance.addChapter(submitEvent());
    fixture.detectChanges();

    expect(api.createPrivateChapter).toHaveBeenCalledTimes(1);
    expect(api.createPrivateChapter.mock.calls[0].slice(0, 2)).toEqual([
      workId,
      'Đêm đầu tiên ở Thanh Vân Tông',
    ]);
    expect(api.createPrivateChapter.mock.calls[0][2]).toMatch(/^[A-Za-z0-9._~-]{16,128}$/);
    expect(fixture.nativeElement.textContent).toContain('Đã thêm Chương 2');
    expect(fixture.nativeElement.textContent).toContain('Đêm đầu tiên ở Thanh Vân Tông');
    expect(fixture.nativeElement.textContent).toContain('Chưa lưu nội dung');
    expect(fixture.nativeElement.textContent).toContain('2 chương');
    expect(fixture.nativeElement.querySelector('input')).toBeNull();
    expect(document.activeElement).toBe(
      fixture.nativeElement.querySelector('.authoring-outline-list-heading button'),
    );
  });

  it('retries an uncertain blank chapter request with the same title and idempotency key', async () => {
    api.createPrivateChapter
      .mockRejectedValueOnce(new AuthoringApiError(503, 'TEMPORARY_FAILURE'))
      .mockResolvedValueOnce(createdChapter());
    const fixture = TestBed.createComponent(AuthoringOutlinePageComponent);
    fixture.detectChanges();
    await settle(fixture);
    fixture.componentInstance.openAddForm();
    fixture.componentInstance.updateChapterTitle(inputEvent('Chương hai'));

    await fixture.componentInstance.addChapter(submitEvent());
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('đừng tải lại trang lúc này');
    expect(fixture.nativeElement.querySelector('#authoring-chapter-title')?.disabled).toBe(true);

    await fixture.componentInstance.addChapter(submitEvent());
    fixture.detectChanges();

    expect(api.createPrivateChapter).toHaveBeenCalledTimes(2);
    expect(api.createPrivateChapter.mock.calls[1]).toEqual(api.createPrivateChapter.mock.calls[0]);
    expect(fixture.nativeElement.textContent).toContain('Đã thêm Chương 2');
  });

  it('requires explicit confirmation, supports keeping an empty chapter, and restores focus', async () => {
    api.listPrivateChapters.mockResolvedValue(page([chapter()]));
    const fixture = TestBed.createComponent(AuthoringOutlinePageComponent);
    fixture.detectChanges();
    await settle(fixture);

    const trigger = fixture.nativeElement.querySelector(
      `#authoring-delete-chapter-${firstChapterId}`,
    );
    trigger.click();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('không thể khôi phục');
    expect(document.activeElement).toBe(
      fixture.nativeElement.querySelector(`#authoring-confirm-delete-chapter-${firstChapterId}`),
    );
    expect(api.deletePrivateEmptyChapter).not.toHaveBeenCalled();

    fixture.nativeElement.querySelector('.authoring-outline-form-actions .button-outline').click();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(document.activeElement).toBe(
      fixture.nativeElement.querySelector(`#authoring-delete-chapter-${firstChapterId}`),
    );
    expect(api.deletePrivateEmptyChapter).not.toHaveBeenCalled();
  });

  it('removes an empty chapter only after confirmation and updates count with a polite status', async () => {
    api.listPrivateChapters.mockResolvedValue(page([chapter()]));
    api.getPrivateManuscript.mockResolvedValue({ ...manuscript(), chapterCount: 1 });
    const fixture = TestBed.createComponent(AuthoringOutlinePageComponent);
    fixture.detectChanges();
    await settle(fixture);
    fixture.componentInstance.requestDeleteEmptyChapter(chapter());
    fixture.detectChanges();
    await fixture.whenStable();
    await fixture.componentInstance.confirmDeleteEmptyChapter(chapter());
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(api.deletePrivateEmptyChapter).toHaveBeenCalledExactlyOnceWith(workId, firstChapterId);
    expect(fixture.nativeElement.querySelectorAll('.authoring-outline-row')).toHaveLength(0);
    expect(fixture.nativeElement.textContent).toContain('Đã xóa chương trống “Mở đầu”');
    expect(
      fixture.nativeElement.querySelector('.authoring-outline-manuscript-meta')?.textContent,
    ).toContain('0 chương');
    expect(document.activeElement).toBe(
      fixture.nativeElement.querySelector('.authoring-outline-list-heading button'),
    );
  });

  it('keeps a concurrently saved chapter and hides its delete action after a 409', async () => {
    api.listPrivateChapters.mockResolvedValue(page([chapter()]));
    api.deletePrivateEmptyChapter.mockRejectedValue(
      new AuthoringApiError(409, 'DRAFT_CHAPTER_HAS_CONTENT'),
    );
    const fixture = TestBed.createComponent(AuthoringOutlinePageComponent);
    fixture.detectChanges();
    await settle(fixture);
    fixture.componentInstance.requestDeleteEmptyChapter(chapter());
    fixture.detectChanges();
    await fixture.whenStable();
    await fixture.componentInstance.confirmDeleteEmptyChapter(chapter());
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Nội dung vẫn được giữ nguyên');
    expect(fixture.nativeElement.textContent).toContain('Có bản nháp đã lưu');
    expect(
      fixture.nativeElement.querySelector(`#authoring-delete-chapter-${firstChapterId}`),
    ).toBeNull();
    expect(
      fixture.nativeElement.querySelector(`#authoring-edit-chapter-${firstChapterId}`),
    ).not.toBeNull();
    expect(document.activeElement).toBe(
      fixture.nativeElement.querySelector(`#authoring-edit-chapter-${firstChapterId}`),
    );
  });

  it.each([
    [
      401,
      '',
      'Phiên đăng nhập hết hạn',
      'Đăng nhập lại',
      `/sign-in?returnTo=%2Fapp%2Fauthoring%2F${workId}%2Fchapters`,
    ],
    [
      403,
      'POLICY_ACCEPTANCE_REQUIRED',
      'Hãy xác nhận chính sách hiện hành',
      'Xem chính sách tài khoản',
      `/app/setup?returnUrl=%2Fapp%2Fauthoring%2F${workId}%2Fchapters`,
    ],
    [404, '', 'Dàn ý không khả dụng', 'Về thư viện', '/app/library'],
  ] as const)(
    'keeps the empty chapter and offers safe recovery for delete status %i',
    async (status, code, message, label, href) => {
      api.listPrivateChapters.mockResolvedValue(page([chapter()]));
      api.deletePrivateEmptyChapter.mockRejectedValue(new AuthoringApiError(status, code));
      const fixture = TestBed.createComponent(AuthoringOutlinePageComponent);
      fixture.detectChanges();
      await settle(fixture);

      fixture.componentInstance.requestDeleteEmptyChapter(chapter());
      fixture.detectChanges();
      await fixture.whenStable();
      await fixture.componentInstance.confirmDeleteEmptyChapter(chapter());
      fixture.detectChanges();
      await fixture.whenStable();
      fixture.detectChanges();

      expect(fixture.nativeElement.querySelectorAll('.authoring-outline-row')).toHaveLength(1);
      expect(fixture.nativeElement.textContent).toContain(message);
      const recoveryLink = fixture.nativeElement.querySelector(
        '.authoring-outline-delete-confirm a',
      );
      expect(recoveryLink?.textContent).toContain(label);
      expect(recoveryLink?.getAttribute('href')).toBe(href);
    },
  );

  it('offers an exact safe retry when deletion outcome is uncertain', async () => {
    api.listPrivateChapters.mockResolvedValue(page([chapter()]));
    api.deletePrivateEmptyChapter
      .mockRejectedValueOnce(new AuthoringApiError(503, 'TEMPORARY_FAILURE'))
      .mockResolvedValueOnce(undefined);
    const fixture = TestBed.createComponent(AuthoringOutlinePageComponent);
    fixture.detectChanges();
    await settle(fixture);

    fixture.componentInstance.requestDeleteEmptyChapter(chapter());
    fixture.detectChanges();
    await fixture.whenStable();
    await fixture.componentInstance.confirmDeleteEmptyChapter(chapter());
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Thử xác nhận lại');
    expect(fixture.nativeElement.querySelectorAll('.authoring-outline-row')).toHaveLength(1);

    await fixture.componentInstance.confirmDeleteEmptyChapter(chapter());
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(api.deletePrivateEmptyChapter).toHaveBeenCalledTimes(2);
    expect(fixture.nativeElement.querySelectorAll('.authoring-outline-row')).toHaveLength(0);
    expect(fixture.nativeElement.textContent).toContain('Đã xóa chương trống');
  });

  it('suppresses duplicate delete activation while the request is pending', async () => {
    api.listPrivateChapters.mockResolvedValue(page([chapter()]));
    let finishDelete!: () => void;
    api.deletePrivateEmptyChapter.mockReturnValueOnce(
      new Promise<void>((resolve) => {
        finishDelete = resolve;
      }),
    );
    const fixture = TestBed.createComponent(AuthoringOutlinePageComponent);
    fixture.detectChanges();
    await settle(fixture);
    fixture.componentInstance.requestDeleteEmptyChapter(chapter());
    fixture.detectChanges();
    await fixture.whenStable();

    const firstAttempt = fixture.componentInstance.confirmDeleteEmptyChapter(chapter());
    fixture.detectChanges();
    const confirmButton = fixture.nativeElement.querySelector(
      `#authoring-confirm-delete-chapter-${firstChapterId}`,
    ) as HTMLButtonElement;
    expect(confirmButton.disabled).toBe(true);
    await fixture.componentInstance.confirmDeleteEmptyChapter(chapter());
    expect(api.deletePrivateEmptyChapter).toHaveBeenCalledExactlyOnceWith(workId, firstChapterId);

    finishDelete();
    await firstAttempt;
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelectorAll('.authoring-outline-row')).toHaveLength(0);
  });

  it('loads another cursor page and deduplicates chapter rows by id', async () => {
    const cursor = 'YWJj.ZGVm';
    api.listPrivateChapters
      .mockResolvedValueOnce(page([chapter()], cursor))
      .mockResolvedValueOnce(page([chapter(), chapter(secondChapterId, 2, 'Chương hai')]));
    const fixture = TestBed.createComponent(AuthoringOutlinePageComponent);
    fixture.detectChanges();
    await settle(fixture);

    await fixture.componentInstance.loadMore();
    fixture.detectChanges();

    expect(api.listPrivateChapters).toHaveBeenNthCalledWith(2, workId, cursor, 25);
    expect(fixture.nativeElement.querySelectorAll('.authoring-outline-row')).toHaveLength(2);
    expect(fixture.nativeElement.textContent).toContain('Chương hai');
  });
});

function inputEvent(value: string): Event {
  return { target: { value } } as unknown as Event;
}

function submitEvent(): Event {
  return { preventDefault: vi.fn() } as unknown as Event;
}

async function settle(fixture: ComponentFixture<AuthoringOutlinePageComponent>): Promise<void> {
  await vi.waitFor(async () => {
    await fixture.whenStable();
    fixture.detectChanges();
    expect(
      fixture.nativeElement.querySelector('.authoring-outline-status')?.textContent ?? '',
    ).not.toContain('Đang tải dàn ý riêng tư');
  });
}
