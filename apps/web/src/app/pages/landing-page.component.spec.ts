import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  PublicCatalogApiError,
  PublicCatalogApiService,
  PublicCatalogPage,
  PublicCatalogWork,
} from '../core/public-catalog.service';
import { SystemStatusService } from '../core/system-status.service';
import { LandingPageComponent } from './landing-page.component';

const workId = '11111111-1111-4111-8111-111111111111';
const secondWorkId = '22222222-2222-4222-8222-222222222222';
const firstWork: PublicCatalogWork = {
  id: workId,
  title: 'Truyện công khai',
  author: 'Tác giả',
  sourceLanguage: 'vi',
  readyChapterCount: 3,
};
const secondWork: PublicCatalogWork = {
  ...firstWork,
  id: secondWorkId,
  title: 'Truyện tiếp theo',
};

describe('LandingPageComponent public catalog', () => {
  let api: { listWorks: ReturnType<typeof vi.fn> };
  let status: { waitForStatus: ReturnType<typeof vi.fn>; refresh: ReturnType<typeof vi.fn> };
  let featureEnabled = false;

  function configure(enabled: boolean, params: Record<string, string> = {}): void {
    featureEnabled = enabled;
    api = { listWorks: vi.fn().mockResolvedValue({ items: [firstWork], nextCursor: null }) };
    status = {
      waitForStatus: vi.fn().mockResolvedValue({
        apiVersion: 'v1',
        environment: 'local',
        integrations: {
          googleOidcEnabled: false,
          momoTestEnabled: false,
          momoEnvironment: 'TEST',
        },
        features: {
          importProcessingEnabled: false,
          cultivationEnabled: false,
          commerceSandboxEnabled: false,
          publicCatalogEnabled: enabled,
          translationFakePreviewEnabled: false,
        },
      }),
      refresh: vi.fn(),
    };
    TestBed.configureTestingModule({
      imports: [LandingPageComponent],
      providers: [
        provideRouter([]),
        {
          provide: ActivatedRoute,
          useValue: { queryParamMap: of(convertToParamMap(params)) },
        },
        { provide: PublicCatalogApiService, useValue: api },
        { provide: SystemStatusService, useValue: status },
      ],
    });
  }

  async function render(): Promise<{
    page: HTMLElement;
    fixture: ComponentFixture<LandingPageComponent>;
  }> {
    const fixture = TestBed.createComponent(LandingPageComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    await vi.waitFor(() => {
      fixture.detectChanges();
      if (featureEnabled) expect(api.listWorks).toHaveBeenCalled();
      else expect(status.waitForStatus).toHaveBeenCalled();
    });
    fixture.detectChanges();
    return { page: fixture.nativeElement as HTMLElement, fixture };
  }

  beforeEach(() => vi.restoreAllMocks());

  it('keeps the private landing unchanged and does not call the catalog when the flag is off', async () => {
    configure(false);
    const { page } = await render();

    expect(api.listWorks).not.toHaveBeenCalled();
    expect(page.querySelector('.public-catalog')).toBeNull();
    expect(page.textContent).toContain('Một thư viện riêng');
  });

  it('renders only allow-listed public metadata and hands reading off through sign-in', async () => {
    configure(true);
    const { page } = await render();

    expect(api.listWorks).toHaveBeenCalledWith({ query: '', language: '', limit: 20 });
    expect(page.textContent).toContain('Truyện công khai');
    expect(page.textContent).toContain('Tác giả');
    expect(page.textContent).toContain('Chương sẵn sàng');
    expect(page.textContent).not.toContain('chapter body');
    expect(page.querySelectorAll('.public-catalog-card')).toHaveLength(1);
    expect(
      page.querySelector<HTMLAnchorElement>('.public-catalog-read-link')?.getAttribute('href'),
    ).toBe(`/sign-in?returnTo=%2Fapp%2Fworks%2F${workId}`);
  });

  it('keeps active filters in the landing URL and validates malformed language tags locally', async () => {
    configure(true);
    const { page } = await render();
    const router = TestBed.inject(Router);
    const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);
    const query = page.querySelector<HTMLInputElement>('#public-catalog-query')!;
    const language = page.querySelector<HTMLInputElement>('#public-catalog-language')!;
    query.value = 'Dragon';
    query.dispatchEvent(new Event('input'));
    language.value = 'vi!';
    language.dispatchEvent(new Event('input'));
    page
      .querySelector<HTMLFormElement>('.public-catalog-filters')!
      .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    await vi.waitFor(() => expect(page.textContent).toContain('Kiểm tra lại từ khóa'));
    expect(navigate).not.toHaveBeenCalled();
    expect(api.listWorks).toHaveBeenCalledTimes(1);

    language.value = 'VI';
    language.dispatchEvent(new Event('input'));
    page
      .querySelector<HTMLFormElement>('.public-catalog-filters')!
      .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    await vi.waitFor(() => expect(navigate).toHaveBeenCalled());
    expect(navigate.mock.calls.at(-1)?.[1]).toMatchObject({
      queryParams: { q: 'dragon', language: 'vi' },
    });
  });

  it('appends a cursor page without replacing prior results', async () => {
    configure(true);
    api.listWorks
      .mockResolvedValueOnce({ items: [firstWork], nextCursor: 'signed-page-2' })
      .mockResolvedValueOnce({
        items: [firstWork, secondWork, secondWork],
        nextCursor: null,
      } satisfies PublicCatalogPage);
    const { page, fixture } = await render();
    page.querySelector<HTMLButtonElement>('.public-catalog-more')!.click();

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(page.querySelectorAll('.public-catalog-card')).toHaveLength(2);
    });
    expect(api.listWorks).toHaveBeenLastCalledWith({
      query: '',
      language: '',
      cursor: 'signed-page-2',
      limit: 20,
    });
    expect(page.textContent).toContain('Truyện công khai');
    expect(page.textContent).toContain('Truyện tiếp theo');
  });

  it('offers retry while keeping search available after a request failure', async () => {
    configure(true);
    api.listWorks.mockRejectedValue(new PublicCatalogApiError(0, 'NETWORK_ERROR'));
    const { page } = await render();

    expect(page.querySelector('#public-catalog-query')).not.toBeNull();
    expect(page.textContent).toContain('Không kết nối được với danh mục');
    expect(page.querySelector('[role="alert"]')).not.toBeNull();
  });

  it('explains the rate-limit wait while preserving catalog search controls', async () => {
    configure(true);
    api.listWorks.mockRejectedValue(
      new PublicCatalogApiError(429, 'PUBLIC_CATALOG_RATE_LIMITED', 8),
    );
    const { page } = await render();

    expect(page.querySelector('#public-catalog-query')).not.toBeNull();
    expect(page.textContent).toContain('Hãy thử lại sau 8 giây');
    expect(page.textContent).toContain('Thử tải lại');
  });
});
