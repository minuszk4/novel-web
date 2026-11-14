import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { signal } from '@angular/core';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  AuthoringApiError,
  AuthoringApiService,
  PrivateManuscriptListItem,
  PrivateManuscriptListPage,
} from '../core/authoring-api.service';
import { PolicyOnboardingService } from '../core/policy-onboarding.service';
import { AuthoringManuscriptsPageComponent } from './authoring-manuscripts-page.component';

const firstWorkId = '11111111-1111-4111-8111-111111111111';
const secondWorkId = '22222222-2222-4222-8222-222222222222';
const timestamp = '2026-11-15T12:00:00Z';

function manuscript(id = firstWorkId, title = 'Hành trình tu luyện'): PrivateManuscriptListItem {
  return {
    id,
    title,
    author: 'Bút danh',
    language: 'vi',
    visibility: 'private',
    publicationState: 'draft',
    chapterCount: 3,
    createdAt: timestamp,
  };
}

function page(
  items: PrivateManuscriptListItem[] = [],
  nextCursor: string | null = null,
): PrivateManuscriptListPage {
  return { items, nextCursor };
}

describe('AuthoringManuscriptsPageComponent', () => {
  let api: { listPrivateManuscripts: ReturnType<typeof vi.fn> };
  let policyStatus: ReturnType<typeof signal>;

  beforeEach(() => {
    api = { listPrivateManuscripts: vi.fn().mockResolvedValue(page()) };
    policyStatus = signal('accepted');
    TestBed.configureTestingModule({
      imports: [AuthoringManuscriptsPageComponent],
      providers: [
        provideRouter([]),
        { provide: AuthoringApiService, useValue: api },
        { provide: PolicyOnboardingService, useValue: { status: policyStatus } },
      ],
    });
  });

  it('shows an honest private empty state and a create action', async () => {
    const fixture = TestBed.createComponent(AuthoringManuscriptsPageComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(api.listPrivateManuscripts).toHaveBeenCalledWith(undefined, 25);
    expect(fixture.nativeElement.textContent).toContain('Chưa có bản thảo nào');
    expect(fixture.nativeElement.textContent).toContain('Riêng tư mặc định');
    expect(fixture.nativeElement.querySelector('a[href="/app/authoring/new"]')).not.toBeNull();
  });

  it('renders only owner metadata and links each private draft to its outline', async () => {
    api.listPrivateManuscripts.mockResolvedValue(
      page([manuscript(firstWorkId, 'Truyện có <script> chữ')]),
    );
    const fixture = TestBed.createComponent(AuthoringManuscriptsPageComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('Truyện có <script> chữ');
    expect(text).toContain('3 chương');
    expect(text).toContain('Bản nháp riêng tư');
    expect(fixture.nativeElement.querySelector('script')).toBeNull();
    expect(
      fixture.nativeElement.querySelector('a[href="/app/authoring/' + firstWorkId + '/chapters"]'),
    ).not.toBeNull();
    expect(text).not.toContain('revisionId');
    expect(text).not.toContain('normalizedContent');
  });

  it('keeps demo visitors away from owner APIs and offers a return link', async () => {
    policyStatus.set('demo');
    const fixture = TestBed.createComponent(AuthoringManuscriptsPageComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(api.listPrivateManuscripts).not.toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain('Đăng nhập để xem bản thảo');
    expect(
      fixture.nativeElement.querySelector('a[href="/sign-in?returnTo=%2Fapp%2Fauthoring"]'),
    ).not.toBeNull();
  });

  it('loads an additional cursor page without duplicate works', async () => {
    api.listPrivateManuscripts
      .mockResolvedValueOnce(page([manuscript()], 'YWJj.ZGVm'))
      .mockResolvedValueOnce(page([manuscript(secondWorkId, 'Bản thảo thứ hai')]));
    const fixture = TestBed.createComponent(AuthoringManuscriptsPageComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    const loadMore = fixture.nativeElement.querySelector(
      '.authoring-manuscripts-load-more',
    ) as HTMLButtonElement;
    loadMore.click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(api.listPrivateManuscripts).toHaveBeenNthCalledWith(2, 'YWJj.ZGVm', 25);
    expect(fixture.nativeElement.textContent).toContain('Bản thảo thứ hai');
    expect(fixture.nativeElement.querySelectorAll('.authoring-manuscript-card')).toHaveLength(2);
    expect(fixture.nativeElement.querySelector('.authoring-manuscripts-load-more')).toBeNull();
  });

  it('retains a safe error state with an explicit retry', async () => {
    api.listPrivateManuscripts.mockRejectedValueOnce(
      new AuthoringApiError(503, 'STORE_UNAVAILABLE'),
    );
    const fixture = TestBed.createComponent(AuthoringManuscriptsPageComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Danh sách tạm thời chưa sử dụng được');
    const retry = fixture.nativeElement.querySelector('button') as HTMLButtonElement;
    expect(retry.textContent).toContain('Thử tải lại');
    api.listPrivateManuscripts.mockResolvedValue(page([manuscript()]));
    retry.click();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Hành trình tu luyện');
  });
});
