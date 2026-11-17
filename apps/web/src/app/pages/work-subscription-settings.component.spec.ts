import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { signal } from '@angular/core';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { PolicyOnboardingService } from '../core/policy-onboarding.service';
import {
  FollowedWorkPage,
  FollowedWorkSetting,
  WorkSubscriptionApiService,
} from '../core/work-subscription.service';
import { WorkSubscriptionSettingsComponent } from './work-subscription-settings.component';

const publicWorkId = '11111111-1111-4111-8111-111111111111';
const privateWorkId = '22222222-2222-4222-8222-222222222222';
const row = (available: boolean, workId: string): FollowedWorkSetting => ({
  id: workId,
  workId,
  available,
  notificationsEnabled: available,
  version: 3,
  followedAt: '2026-11-17T12:00:00Z',
  title: available ? 'Hành trình tu luyện' : null,
  author: available ? 'Bút danh' : null,
});
const page = (
  items: FollowedWorkSetting[] = [],
  nextCursor: string | null = null,
): FollowedWorkPage => ({
  items,
  nextCursor,
});

describe('WorkSubscriptionSettingsComponent', () => {
  let api: {
    listMine: ReturnType<typeof vi.fn>;
    setNotifications: ReturnType<typeof vi.fn>;
    unfollow: ReturnType<typeof vi.fn>;
  };
  let identity: ReturnType<typeof signal>;

  beforeEach(() => {
    api = {
      listMine: vi.fn().mockResolvedValue(page()),
      setNotifications: vi.fn().mockResolvedValue({
        state: { following: true, notificationsEnabled: false },
        etag: '"subscription-4"',
      }),
      unfollow: vi.fn().mockResolvedValue('"subscription-4"'),
    };
    identity = signal(null);
    TestBed.configureTestingModule({
      imports: [WorkSubscriptionSettingsComponent],
      providers: [
        provideRouter([]),
        { provide: WorkSubscriptionApiService, useValue: api },
        {
          provide: PolicyOnboardingService,
          useValue: { accountIdentity: identity, load: vi.fn() },
        },
      ],
    });
  });

  it('does not query subscriptions for a guest and links to sign-in', async () => {
    const fixture = TestBed.createComponent(WorkSubscriptionSettingsComponent);
    fixture.detectChanges();
    await new Promise((resolve) => setTimeout(resolve, 0));
    await fixture.whenStable();
    fixture.detectChanges();

    expect(api.listMine).not.toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain('Đăng nhập để quản lý');
    expect(
      fixture.nativeElement.querySelector('a[href="/sign-in?returnTo=%2Fapp%2Fsettings"]'),
    ).not.toBeNull();
  });

  it('renders current public metadata and hides all metadata for a withdrawn work', async () => {
    identity.set({ userId: publicWorkId, displayName: 'Reader' });
    api.listMine.mockResolvedValue(page([row(true, publicWorkId), row(false, privateWorkId)]));
    const fixture = TestBed.createComponent(WorkSubscriptionSettingsComponent);
    fixture.detectChanges();
    await new Promise((resolve) => setTimeout(resolve, 0));
    await fixture.whenStable();
    fixture.detectChanges();

    const element: HTMLElement = fixture.nativeElement;
    expect(element.textContent).toContain('Hành trình tu luyện');
    expect(element.querySelector(`a[href="/works/${publicWorkId}"]`)).not.toBeNull();
    expect(element.textContent).toContain('Một tác phẩm bạn đã theo dõi không còn công khai');
    expect(element.textContent).toContain('chỉ xuất hiện khi tính năng này được bật trên máy chủ');
    expect(element.textContent.match(/Bút danh/g)).toHaveLength(1);
    expect(element.textContent).not.toContain('title: null');
  });

  it('uses the current version when muting and reflects the confirmed state', async () => {
    identity.set({ userId: publicWorkId, displayName: 'Reader' });
    api.listMine.mockResolvedValue(page([row(true, publicWorkId)]));
    const fixture = TestBed.createComponent(WorkSubscriptionSettingsComponent);
    fixture.detectChanges();
    await new Promise((resolve) => setTimeout(resolve, 0));
    await fixture.whenStable();
    fixture.detectChanges();

    const toggle = Array.from(fixture.nativeElement.querySelectorAll('button')).find((button) =>
      (button as HTMLButtonElement).textContent?.includes('Tạm tắt lựa chọn'),
    ) as HTMLButtonElement;
    toggle.click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(api.setNotifications).toHaveBeenCalledWith(publicWorkId, false, '"subscription-3"');
    expect(fixture.nativeElement.textContent).toContain('Đã tạm tắt lựa chọn thông báo');
    expect(fixture.nativeElement.textContent).toContain('Bật lại lựa chọn');
  });

  it('lets the owner retire an unavailable subscription and removes the neutral row', async () => {
    identity.set({ userId: publicWorkId, displayName: 'Reader' });
    api.listMine.mockResolvedValue(page([row(false, privateWorkId)]));
    const fixture = TestBed.createComponent(WorkSubscriptionSettingsComponent);
    fixture.detectChanges();
    await new Promise((resolve) => setTimeout(resolve, 0));
    await fixture.whenStable();
    fixture.detectChanges();

    const remove = fixture.nativeElement.querySelector(
      'button[aria-label="Bỏ theo dõi tác phẩm không còn công khai"]',
    ) as HTMLButtonElement;
    remove.click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(api.unfollow).toHaveBeenCalledWith(privateWorkId, '"subscription-3"');
    expect(fixture.nativeElement.textContent).toContain('Bạn chưa theo dõi tác phẩm nào');
    expect(fixture.nativeElement.textContent).toContain('Đã bỏ theo dõi tác phẩm');
  });

  it('loads more pages and suppresses duplicate rows', async () => {
    identity.set({ userId: publicWorkId, displayName: 'Reader' });
    api.listMine
      .mockResolvedValueOnce(page([row(true, publicWorkId)], 'next-cursor'))
      .mockResolvedValueOnce(page([row(true, publicWorkId), row(true, privateWorkId)]));
    const fixture = TestBed.createComponent(WorkSubscriptionSettingsComponent);
    fixture.detectChanges();
    await new Promise((resolve) => setTimeout(resolve, 0));
    await fixture.whenStable();
    fixture.detectChanges();

    const more = Array.from(fixture.nativeElement.querySelectorAll('button')).find((button) =>
      (button as HTMLButtonElement).textContent?.includes('Tải thêm'),
    ) as HTMLButtonElement;
    more.click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(api.listMine).toHaveBeenNthCalledWith(2, 'next-cursor');
    expect(fixture.nativeElement.querySelectorAll('.followed-work-row')).toHaveLength(2);
  });
});
