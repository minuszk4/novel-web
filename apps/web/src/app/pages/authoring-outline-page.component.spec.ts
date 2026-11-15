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
  };
}

describe('AuthoringOutlinePageComponent', () => {
  let api: {
    getPrivateManuscript: ReturnType<typeof vi.fn>;
    listPrivateChapters: ReturnType<typeof vi.fn>;
    createPrivateChapter: ReturnType<typeof vi.fn>;
  };
  let policyStatus: ReturnType<typeof signal>;

  beforeEach(() => {
    api = {
      getPrivateManuscript: vi.fn().mockResolvedValue(manuscript()),
      listPrivateChapters: vi.fn().mockResolvedValue(page()),
      createPrivateChapter: vi.fn().mockResolvedValue(createdChapter()),
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
        { provide: PolicyOnboardingService, useValue: { status: policyStatus } },
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
    expect(fixture.nativeElement.textContent).toContain('Chưa có nội dung');
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
