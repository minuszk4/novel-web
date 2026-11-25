import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { TestBed, type ComponentFixture } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { AuthoringInvitationPageComponent } from './authoring-invitation-page.component';

const invitationId = '33333333-3333-4333-8333-333333333333';
const token = 'B'.repeat(43);
const csrf = 'C'.repeat(43);
const invitation = {
  id: invitationId,
  title: 'Bản thảo riêng tư',
  author: 'Bút danh',
  permissions: ['EDIT', 'VIEW'],
  expiresAt: '2026-12-02T01:00:00Z',
};

describe('AuthoringInvitationPageComponent', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<AuthoringInvitationPageComponent>;

  beforeEach(() => {
    sessionStorage.clear();
    window.history.replaceState({}, '', `/manuscript-invitations/${invitationId}#token=${token}`);
    TestBed.configureTestingModule({
      imports: [AuthoringInvitationPageComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { paramMap: convertToParamMap({ invitationId }) } },
        },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(AuthoringInvitationPageComponent);
  });

  afterEach(() => {
    http.verify();
    fixture.destroy();
    sessionStorage.clear();
    window.history.replaceState({}, '', `/manuscript-invitations/${invitationId}`);
    TestBed.resetTestingModule();
  });

  it('strips the URL fragment, shows only metadata, then accepts with a CSRF body token', async () => {
    fixture.detectChanges();
    expect(window.location.hash).toBe('');
    expect(sessionStorage.getItem(`novel.manuscript-invitation-token.v1.${invitationId}`)).toBe(
      token,
    );

    http.expectOne('/api/v1/auth/csrf').flush({ token: csrf });
    await settle();
    const resolve = http.expectOne(`/api/v1/me/authoring-invitations/${invitationId}/resolve`);
    expect(resolve.request.body).toEqual({ token });
    expect(resolve.request.urlWithParams).not.toContain(token);
    resolve.flush(invitation, { headers: { 'Cache-Control': 'no-store' } });
    await settle();
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Bản thảo riêng tư');
    expect(fixture.nativeElement.textContent).toContain('Chỉnh sửa thông tin và chương nháp');
    expect(fixture.nativeElement.textContent).not.toContain('nội dung chương');

    (fixture.nativeElement.querySelector('button') as HTMLButtonElement).click();
    http.expectOne('/api/v1/auth/csrf').flush({ token: csrf });
    await settle();
    const accept = http.expectOne(`/api/v1/me/authoring-invitations/${invitationId}/accept`);
    expect(accept.request.body).toEqual({ token });
    accept.flush(
      { id: invitationId, status: 'accepted' },
      { headers: { 'Cache-Control': 'no-store' } },
    );
    await settle();
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Bạn đã chấp nhận lời mời');
    expect(
      sessionStorage.getItem(`novel.manuscript-invitation-token.v1.${invitationId}`),
    ).toBeNull();
  });

  it('retains the token through Google sign-in without adding it to the sign-in URL', async () => {
    fixture.detectChanges();
    http
      .expectOne('/api/v1/auth/csrf')
      .flush({ code: 'UNAUTHENTICATED' }, { status: 401, statusText: 'Unauthorized' });
    await settle();
    fixture.detectChanges();
    const link = fixture.nativeElement.querySelector('a[href^="/sign-in?"]') as HTMLAnchorElement;
    expect(link).toBeTruthy();
    expect(decodeURIComponent(link.getAttribute('href') ?? '')).toContain(
      `/manuscript-invitations/${invitationId}`,
    );
    expect(link.getAttribute('href')).not.toContain(token);
    expect(sessionStorage.getItem(`novel.manuscript-invitation-token.v1.${invitationId}`)).toBe(
      token,
    );
  });
});

async function settle(): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, 0));
}
