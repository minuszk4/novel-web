import { provideRouter } from '@angular/router';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { NotificationApiService } from '../core/notification-api.service';
import { NotificationsPageComponent } from './notifications-page.component';

const notificationId = '11111111-1111-4111-8111-111111111111';
const chapter = {
  notificationId,
  eventType: 'CHAPTER_PUBLISHED' as const,
  createdAt: '2026-11-18T10:00:00Z',
  readAt: null,
  available: true,
  workId: '22222222-2222-4222-8222-222222222222',
  workTitle: 'Tác phẩm công khai',
  chapterId: '33333333-3333-4333-8333-333333333333',
  chapterTitle: 'Chương mới',
  chapterOrdinal: 5,
};

describe('NotificationsPageComponent', () => {
  let api: {
    listMine: ReturnType<typeof vi.fn>;
    markRead: ReturnType<typeof vi.fn>;
    markAllRead: ReturnType<typeof vi.fn>;
    refreshUnreadCount: ReturnType<typeof vi.fn>;
    unreadCount: ReturnType<typeof vi.fn>;
  };

  beforeEach(() => {
    api = {
      listMine: vi.fn().mockResolvedValue({ items: [chapter], nextCursor: null, unreadCount: 1 }),
      markRead: vi.fn().mockResolvedValue('2026-11-18T10:01:00Z'),
      markAllRead: vi.fn().mockResolvedValue({ updatedCount: 1, hasMore: false }),
      refreshUnreadCount: vi.fn().mockResolvedValue(undefined),
      unreadCount: vi.fn().mockReturnValue(0),
    };
    TestBed.configureTestingModule({
      imports: [NotificationsPageComponent],
      providers: [provideRouter([]), { provide: NotificationApiService, useValue: api }],
    });
  });

  it('shows a public chapter update and marks it read after server confirmation', async () => {
    const fixture = TestBed.createComponent(NotificationsPageComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Chương mới');
    const button = fixture.nativeElement.querySelector(
      'button[aria-label="Đánh dấu đã đọc: Chương mới"]',
    ) as HTMLButtonElement;
    button.click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(api.markRead).toHaveBeenCalledWith(notificationId);
    expect(fixture.nativeElement.textContent).toContain('Đã đánh dấu thông báo là đã đọc.');
  });

  it('shows neutral content for an unavailable update without revealing identifiers', async () => {
    api.listMine.mockResolvedValue({
      items: [
        {
          ...chapter,
          eventType: 'UPDATE_UNAVAILABLE',
          available: false,
          workId: null,
          workTitle: null,
          chapterId: null,
          chapterTitle: null,
          chapterOrdinal: null,
        },
      ],
      nextCursor: null,
      unreadCount: 1,
    });
    const fixture = TestBed.createComponent(NotificationsPageComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Cập nhật không khả dụng');
    expect(fixture.nativeElement.textContent).not.toContain('Tác phẩm công khai');
    expect(fixture.nativeElement.textContent).not.toContain('Chương mới');
  });
});
