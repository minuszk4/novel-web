import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { signal } from '@angular/core';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AuthoringApiError, AuthoringApiService } from '../core/authoring-api.service';
import { PolicyOnboardingService } from '../core/policy-onboarding.service';
import { AuthoringCreatePageComponent } from './authoring-create-page.component';

const createdDraft = {
  id: '11111111-1111-4111-8111-111111111111',
  title: 'Hành trình tu luyện',
  author: 'Bút danh',
  language: 'vi',
  publicationState: 'draft' as const,
  visibility: 'private' as const,
  createdAt: '2026-11-14T12:00:00Z',
};

describe('AuthoringCreatePageComponent', () => {
  let api: { createPrivateManuscript: ReturnType<typeof vi.fn> };
  let policyStatus: ReturnType<typeof signal>;

  beforeEach(() => {
    api = { createPrivateManuscript: vi.fn().mockResolvedValue(createdDraft) };
    policyStatus = signal('accepted');
    TestBed.configureTestingModule({
      imports: [AuthoringCreatePageComponent],
      providers: [
        provideRouter([]),
        { provide: AuthoringApiService, useValue: api },
        { provide: PolicyOnboardingService, useValue: { status: policyStatus } },
      ],
    });
  });

  it('offers Google sign-in in demo mode and returns to the create route', () => {
    policyStatus.set('demo');
    const fixture = TestBed.createComponent(AuthoringCreatePageComponent);
    fixture.detectChanges();

    const element: HTMLElement = fixture.nativeElement;
    expect(element.textContent).toContain('Đăng nhập để bắt đầu viết');
    expect(element.querySelector('form')).toBeNull();
    expect(element.querySelector('a.button-primary')?.getAttribute('href')).toBe(
      '/sign-in?returnTo=%2Fapp%2Fauthoring%2Fnew',
    );
    expect(api.createPrivateManuscript).not.toHaveBeenCalled();
  });

  it('validates whitespace-only titles before making an API request', async () => {
    const fixture = TestBed.createComponent(AuthoringCreatePageComponent);
    fixture.detectChanges();
    fixture.componentInstance.updateTitle(inputEvent('   '));

    await fixture.componentInstance.createDraft(submitEvent());
    fixture.detectChanges();

    expect(api.createPrivateManuscript).not.toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain('Tên tác phẩm cần có từ 1 đến 300 ký tự.');
    expect(
      fixture.nativeElement.querySelector('#manuscript-title')?.getAttribute('aria-invalid'),
    ).toBe('true');
  });

  it('creates a private metadata draft and links to the chapter outline', async () => {
    const fixture = TestBed.createComponent(AuthoringCreatePageComponent);
    fixture.detectChanges();
    fixture.componentInstance.updateTitle(inputEvent(' Hành trình tu luyện '));
    fixture.componentInstance.updateAuthor(inputEvent(' Bút danh '));
    fixture.componentInstance.updateLanguage(inputEvent(' vi '));

    await fixture.componentInstance.createDraft(submitEvent());
    fixture.detectChanges();

    expect(api.createPrivateManuscript).toHaveBeenCalledTimes(1);
    expect(api.createPrivateManuscript).toHaveBeenCalledWith(
      { title: 'Hành trình tu luyện', author: 'Bút danh', language: 'vi' },
      expect.stringMatching(/^[A-Za-z0-9._~-]{16,128}$/),
    );
    expect(fixture.nativeElement.textContent).toContain('ĐÃ LƯU RIÊNG TƯ');
    expect(fixture.nativeElement.textContent).toContain('chưa thể nhập nội dung chương');
    expect(fixture.nativeElement.textContent).toContain('xuất bản hay chia sẻ tác phẩm');
    expect(fixture.nativeElement.querySelector('a.button-primary')?.getAttribute('href')).toBe(
      `/app/authoring/${createdDraft.id}/chapters`,
    );
    expect(
      fixture.nativeElement.querySelector('a[routerLink="/app/library"]')?.textContent,
    ).toContain('Về thư viện');
  });

  it('retries an uncertain create with the exact same body and idempotency key', async () => {
    api.createPrivateManuscript
      .mockRejectedValueOnce(new AuthoringApiError(503, 'TEMPORARY_FAILURE'))
      .mockResolvedValueOnce(createdDraft);
    const fixture = TestBed.createComponent(AuthoringCreatePageComponent);
    fixture.detectChanges();
    fixture.componentInstance.updateTitle(inputEvent('Hành trình tu luyện'));

    await fixture.componentInstance.createDraft(submitEvent());
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('đừng tải lại trang lúc này');
    expect(fixture.nativeElement.querySelector('#manuscript-title')?.disabled).toBe(true);

    await fixture.componentInstance.createDraft(submitEvent());
    fixture.detectChanges();

    expect(api.createPrivateManuscript).toHaveBeenCalledTimes(2);
    expect(api.createPrivateManuscript.mock.calls[1]).toEqual(
      api.createPrivateManuscript.mock.calls[0],
    );
    expect(fixture.nativeElement.textContent).toContain('ĐÃ LƯU RIÊNG TƯ');
  });
});

function inputEvent(value: string): Event {
  return { target: { value } } as unknown as Event;
}

function submitEvent(): Event {
  return { preventDefault: vi.fn() } as unknown as Event;
}
