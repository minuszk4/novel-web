import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { signal } from '@angular/core';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  GlossaryApiError,
  GlossaryApiService,
  GlossaryCatalogItem,
} from '../core/glossary-api.service';
import { PolicyOnboardingService } from '../core/policy-onboarding.service';
import { GlossaryCatalogPageComponent } from './glossary-catalog-page.component';

const glossaryId = '11111111-1111-4111-8111-111111111111';
const draftId = '22222222-2222-4222-8222-222222222222';

const writerGlossary: GlossaryCatalogItem = {
  id: glossaryId,
  name: 'Tu luyện',
  scope: 'USER_GLOBAL',
  sourceLanguage: 'zh-Hans',
  targetLanguage: 'vi',
  version: 3,
  activeRevisionId: '33333333-3333-4333-8333-333333333333',
  activeRevisionNumber: 1,
  latestRevisionNumber: 2,
  createdAt: '2026-11-14T05:00:00Z',
  canWrite: true,
};

const readonlyGlossary: GlossaryCatalogItem = {
  ...writerGlossary,
  id: draftId,
  name: 'Từ vựng chia sẻ',
  latestRevisionNumber: null,
  canWrite: false,
};

describe('GlossaryCatalogPageComponent', () => {
  let api: {
    listGlossaries: ReturnType<typeof vi.fn>;
    createUserGlossary: ReturnType<typeof vi.fn>;
  };
  let status: ReturnType<typeof signal>;

  beforeEach(() => {
    api = {
      listGlossaries: vi.fn().mockResolvedValue({
        items: [writerGlossary, readonlyGlossary],
        nextCursor: 'cursor-next',
      }),
      createUserGlossary: vi.fn(),
    };
    status = signal('accepted');
    TestBed.configureTestingModule({
      imports: [GlossaryCatalogPageComponent],
      providers: [
        provideRouter([]),
        { provide: GlossaryApiService, useValue: api },
        { provide: PolicyOnboardingService, useValue: { status } },
      ],
    });
  });

  it('lists glossaries, hides IDs, and exposes draft state only to writers', async () => {
    const fixture = TestBed.createComponent(GlossaryCatalogPageComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    const element: HTMLElement = fixture.nativeElement;
    expect(api.listGlossaries).toHaveBeenCalledWith({
      limit: 25,
      query: undefined,
      scope: undefined,
    });
    expect(element.textContent).toContain('Tu luyện');
    expect(element.textContent).toContain('Tiếng Trung giản thể');
    expect(element.textContent).toContain('Có bản nháp');
    expect(element.textContent).toContain('Chỉ xem');
    expect(element.textContent).not.toContain(glossaryId);
    expect(
      element.querySelector('a[aria-label="Mở glossary Tu luyện"]')?.getAttribute('href'),
    ).toBe('/app/glossaries/' + glossaryId);
  });

  it('announces loading while the first catalog request is pending', async () => {
    let finishLoad:
      ((value: { items: GlossaryCatalogItem[]; nextCursor: string | null }) => void) | undefined;
    api.listGlossaries.mockReturnValueOnce(
      new Promise((resolve) => {
        finishLoad = resolve;
      }),
    );
    const fixture = TestBed.createComponent(GlossaryCatalogPageComponent);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Đang tải glossary từ tài khoản');
    finishLoad!({ items: [], nextCursor: null });
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('Chưa có glossary nào');
    });
  });

  it('does not let an older response replace a newer filtered catalog', async () => {
    let finishOldLoad:
      ((value: { items: GlossaryCatalogItem[]; nextCursor: string | null }) => void) | undefined;
    api.listGlossaries.mockReturnValueOnce(
      new Promise((resolve) => {
        finishOldLoad = resolve;
      }),
    );
    const fixture = TestBed.createComponent(GlossaryCatalogPageComponent);
    fixture.detectChanges();
    const query = fixture.nativeElement.querySelector('input[type="search"]') as HTMLInputElement;
    query.value = 'tu luyện';
    query.dispatchEvent(new Event('input'));
    const form = fixture.nativeElement.querySelector('form[role="search"]') as HTMLFormElement;
    form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('Tu luyện');
    });

    finishOldLoad!({ items: [], nextCursor: null });
    await new Promise((resolve) => setTimeout(resolve, 0));
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Tu luyện');
    expect(fixture.nativeElement.textContent).not.toContain('Chưa có glossary nào');
  });

  it('offers retry after a catalog read error', async () => {
    api.listGlossaries
      .mockRejectedValueOnce(
        new GlossaryApiError('SERVICE_UNAVAILABLE', 503, 'SERVICE_UNAVAILABLE'),
      )
      .mockResolvedValueOnce({ items: [writerGlossary], nextCursor: null });
    const fixture = TestBed.createComponent(GlossaryCatalogPageComponent);
    fixture.detectChanges();
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('Chưa tải được danh sách glossary');
    });
    const retry = fixture.nativeElement.querySelector(
      '.library-sync-error button',
    ) as HTMLButtonElement;
    retry.click();
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('Tu luyện');
    });
    expect(api.listGlossaries).toHaveBeenCalledTimes(2);
  });

  it('shows a sign-in action when the session expires during a catalog read', async () => {
    api.listGlossaries.mockRejectedValueOnce(
      new GlossaryApiError('UNAUTHENTICATED', 401, 'UNAUTHENTICATED'),
    );
    const fixture = TestBed.createComponent(GlossaryCatalogPageComponent);
    fixture.detectChanges();
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('Đăng nhập lại');
    });
    expect(fixture.nativeElement.querySelector('a[routerLink="/sign-in"]')).toBeDefined();
  });

  it('returns policy-denied catalog reads to account setup', async () => {
    api.listGlossaries.mockRejectedValueOnce(
      new GlossaryApiError('POLICY_REQUIRED', 403, 'POLICY_REQUIRED'),
    );
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    const fixture = TestBed.createComponent(GlossaryCatalogPageComponent);
    fixture.detectChanges();
    await vi.waitFor(() => {
      expect(navigate).toHaveBeenCalledWith(['/app/setup'], {
        queryParams: { returnUrl: '/app/glossaries' },
      });
    });
  });

  it('treats an empty scope-filtered result as no matches and can clear the filter', async () => {
    api.listGlossaries
      .mockResolvedValueOnce({ items: [writerGlossary], nextCursor: null })
      .mockResolvedValueOnce({ items: [], nextCursor: null });
    const fixture = TestBed.createComponent(GlossaryCatalogPageComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    const scope = fixture.nativeElement.querySelector(
      '.glossary-scope-field select',
    ) as HTMLSelectElement;
    scope.value = 'WORK';
    scope.dispatchEvent(new Event('change'));
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('Không tìm thấy glossary phù hợp');
    });
    expect(api.listGlossaries).toHaveBeenLastCalledWith({
      limit: 25,
      query: undefined,
      scope: 'WORK',
    });
    const clear = Array.from(
      fixture.nativeElement.querySelectorAll('button') as NodeListOf<HTMLButtonElement>,
    ).find((button) => button.textContent?.includes('Xóa bộ lọc'));
    expect(clear).toBeDefined();
    clear!.click();
    await vi.waitFor(() => {
      expect(api.listGlossaries).toHaveBeenLastCalledWith({
        limit: 25,
        query: undefined,
        scope: undefined,
      });
    });
  });

  it('submits search and scope, then appends the next cursor page', async () => {
    api.listGlossaries
      .mockResolvedValueOnce({ items: [writerGlossary], nextCursor: 'cursor-next' })
      .mockResolvedValueOnce({ items: [writerGlossary], nextCursor: 'cursor-next' })
      .mockResolvedValueOnce({ items: [readonlyGlossary], nextCursor: null });
    const fixture = TestBed.createComponent(GlossaryCatalogPageComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    const query = fixture.nativeElement.querySelector('input[type="search"]') as HTMLInputElement;
    query.value = '  tu luyện  ';
    query.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    const form = fixture.nativeElement.querySelector('form[role="search"]') as HTMLFormElement;
    form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    await fixture.whenStable();
    fixture.detectChanges();
    expect(api.listGlossaries).toHaveBeenLastCalledWith({
      limit: 25,
      query: 'tu luyện',
      scope: undefined,
    });

    const buttons = fixture.nativeElement.querySelectorAll(
      'button',
    ) as NodeListOf<HTMLButtonElement>;
    const more = Array.from(buttons).find((button) => button.textContent?.includes('Tải thêm'));
    expect(more).toBeDefined();
    more!.click();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(api.listGlossaries).toHaveBeenLastCalledWith({
      limit: 25,
      cursor: 'cursor-next',
      query: 'tu luyện',
      scope: undefined,
    });
    expect(fixture.nativeElement.textContent).toContain('Từ vựng chia sẻ');
  });

  it('creates a personal glossary once and navigates to its detail', async () => {
    const created = { ...writerGlossary, id: '44444444-4444-4444-8444-444444444444' };
    api.createUserGlossary.mockResolvedValue(created);
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    const fixture = TestBed.createComponent(GlossaryCatalogPageComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    const buttons = fixture.nativeElement.querySelectorAll(
      'button',
    ) as NodeListOf<HTMLButtonElement>;
    const open = Array.from(buttons).find((button) => button.textContent?.includes('Tạo glossary'));
    expect(open).toBeDefined();
    open!.click();
    fixture.detectChanges();
    const inputs = fixture.nativeElement.querySelectorAll('.glossary-create-form input');
    const name = inputs[0] as HTMLInputElement;
    name.value = 'Tên riêng';
    name.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    const form = fixture.nativeElement.querySelector('.glossary-create-form') as HTMLFormElement;
    form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    await fixture.whenStable();

    expect(api.createUserGlossary).toHaveBeenCalledWith({
      name: 'Tên riêng',
      sourceLanguage: 'zh-Hans',
      targetLanguage: 'vi',
    });
    expect(navigate).toHaveBeenCalledWith(['/app/glossaries', created.id]);
  });

  it('requires checking the list before retrying an uncertain create', async () => {
    api.createUserGlossary.mockRejectedValue(
      new GlossaryApiError('REQUEST_TIMEOUT', 0, 'REQUEST_TIMEOUT'),
    );
    const fixture = TestBed.createComponent(GlossaryCatalogPageComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    const component = fixture.componentInstance as unknown as {
      createOpen: ReturnType<typeof signal>;
      createName: ReturnType<typeof signal>;
      createGlossary: (event: Event) => Promise<void>;
    };
    component.createOpen.set(true);
    component.createName.set('Tên riêng');
    await component.createGlossary({ preventDefault: vi.fn() } as unknown as Event);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('kiểm tra trước khi tạo lại');
    expect(api.createUserGlossary).toHaveBeenCalledTimes(1);
    const submit = fixture.nativeElement.querySelector(
      '.glossary-create-form button[type="submit"]',
    ) as HTMLButtonElement;
    expect(submit.disabled).toBe(true);

    const refresh = Array.from(
      fixture.nativeElement.querySelectorAll('button') as NodeListOf<HTMLButtonElement>,
    ).find((button) => button.textContent?.includes('Tải lại danh sách'));
    expect(refresh).toBeDefined();
    refresh!.click();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(api.listGlossaries).toHaveBeenCalledTimes(2);
    expect(submit.disabled).toBe(false);
  });

  it('shows an empty state and allows creating the first glossary', async () => {
    api.listGlossaries.mockResolvedValue({ items: [], nextCursor: null });
    const fixture = TestBed.createComponent(GlossaryCatalogPageComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Chưa có glossary nào');
    expect(fixture.nativeElement.querySelector('.glossary-empty button')).toBeDefined();
  });

  it('shows the sign-in prompt without calling the account API in demo mode', () => {
    status.set('demo');
    const fixture = TestBed.createComponent(GlossaryCatalogPageComponent);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Đăng nhập để xem glossary');
    expect(api.listGlossaries).not.toHaveBeenCalled();
  });
});
