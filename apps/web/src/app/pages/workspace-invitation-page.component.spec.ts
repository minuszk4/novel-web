import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { TestBed, type ComponentFixture } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { WorkspaceInvitationPageComponent } from './workspace-invitation-page.component';

const invitationId = '55555555-5555-4555-8555-555555555555';
const invitationToken = 'A'.repeat(43);
const workspaceId = '11111111-1111-4111-8111-111111111111';
const csrf = 'B'.repeat(43);

describe('WorkspaceInvitationPageComponent', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    sessionStorage.clear();
    window.history.replaceState(
      {},
      '',
      `/workspace-invitations/${invitationId}#token=${invitationToken}`,
    );
    TestBed.configureTestingModule({
      imports: [WorkspaceInvitationPageComponent],
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
  });

  afterEach(() => {
    http.verify();
    sessionStorage.clear();
    window.history.replaceState({}, '', `/workspace-invitations/${invitationId}`);
    TestBed.resetTestingModule();
  });

  it('captures the email token into session storage and strips it from browser history', async () => {
    const fixture = TestBed.createComponent(WorkspaceInvitationPageComponent);
    fixture.detectChanges();
    expect(window.location.hash).toBe('');
    expect(sessionStorage.getItem(`novel.workspace-invitation-token.v1.${invitationId}`)).toBe(
      invitationToken,
    );

    http.expectOne('/api/v1/auth/csrf').flush({ token: csrf });
    await new Promise((resolve) => setTimeout(resolve, 0));
    const request = http.expectOne(`/api/v1/me/workspace-invitations/${invitationId}/resolve`);
    expect(request.request.urlWithParams).not.toContain(invitationToken);
    expect(request.request.body).toEqual({ token: invitationToken });
    request.flush({
      id: invitationId,
      workspaceId,
      workspaceName: 'Nhóm biên tập',
      role: 'editor',
      expiresAt: '2026-11-25T02:00:00Z',
    });
    await settle(fixture);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Nhóm biên tập');
    expect(fixture.nativeElement.textContent).toContain('biên tập viên');
  });

  it('retains the short-lived token for Google sign-in and returns without putting it in the URL', async () => {
    const fixture = TestBed.createComponent(WorkspaceInvitationPageComponent);
    fixture.detectChanges();
    http
      .expectOne('/api/v1/auth/csrf')
      .flush({ code: 'UNAUTHENTICATED' }, { status: 401, statusText: 'Unauthorized' });
    await settle(fixture);
    fixture.detectChanges();
    const link = fixture.nativeElement.querySelector('a[href^="/sign-in?"]') as HTMLAnchorElement;
    expect(link).toBeTruthy();
    expect(decodeURIComponent(link.getAttribute('href') ?? '')).toContain(
      `/workspace-invitations/${invitationId}`,
    );
    expect(link.getAttribute('href')).not.toContain(invitationToken);
    expect(sessionStorage.getItem(`novel.workspace-invitation-token.v1.${invitationId}`)).toBe(
      invitationToken,
    );
  });

  it('consumes a one-use invitation after acceptance and clears the browser token', async () => {
    const fixture = TestBed.createComponent(WorkspaceInvitationPageComponent);
    fixture.detectChanges();
    http.expectOne('/api/v1/auth/csrf').flush({ token: csrf });
    await new Promise((resolve) => setTimeout(resolve, 0));
    http.expectOne(`/api/v1/me/workspace-invitations/${invitationId}/resolve`).flush({
      id: invitationId,
      workspaceId,
      workspaceName: 'Nhóm biên tập',
      role: 'editor',
      expiresAt: '2026-11-25T02:00:00Z',
    });
    await settle(fixture);
    fixture.detectChanges();
    (fixture.nativeElement.querySelector('button[type="button"]') as HTMLButtonElement).click();
    http.expectOne('/api/v1/auth/csrf').flush({ token: csrf });
    await new Promise((resolve) => setTimeout(resolve, 0));
    const accept = http.expectOne(`/api/v1/me/workspace-invitations/${invitationId}/accept`);
    expect(accept.request.body).toEqual({ token: invitationToken });
    accept.flush({ id: invitationId, status: 'accepted' });
    await settle(fixture);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Bạn đã tham gia workspace');
    expect(
      sessionStorage.getItem(`novel.workspace-invitation-token.v1.${invitationId}`),
    ).toBeNull();
  });
});

async function settle(fixture: ComponentFixture<WorkspaceInvitationPageComponent>): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, 0));
  await fixture.whenStable();
  fixture.detectChanges();
}
