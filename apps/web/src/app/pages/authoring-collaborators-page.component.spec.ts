import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { TestBed, type ComponentFixture } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { AuthoringCollaboratorsPageComponent } from './authoring-collaborators-page.component';

const workId = '11111111-1111-4111-8111-111111111111';
const csrf = 'D'.repeat(43);
const management = { collaborators: [], invitations: [] };

describe('AuthoringCollaboratorsPageComponent', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<AuthoringCollaboratorsPageComponent>;

  beforeEach(() => {
    sessionStorage.clear();
    TestBed.configureTestingModule({
      imports: [AuthoringCollaboratorsPageComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { paramMap: convertToParamMap({ workId }) } },
        },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(AuthoringCollaboratorsPageComponent);
  });

  afterEach(() => {
    http.verify();
    fixture.destroy();
    sessionStorage.clear();
    TestBed.resetTestingModule();
  });

  it('requires email-contact consent and retries an uncertain create with the same UUID and body', async () => {
    fixture.detectChanges();
    http.expectOne(`/api/v1/authoring/manuscripts/${workId}/contributors`).flush(management, {
      headers: { 'Cache-Control': 'no-store' },
    });
    await settle();
    fixture.detectChanges();

    const email = fixture.nativeElement.querySelector('#collaborator-email') as HTMLInputElement;
    email.value = '  editor@example.test ';
    email.dispatchEvent(new Event('input', { bubbles: true }));
    const edit = fixture.nativeElement.querySelector(
      '.collaboration-form fieldset input:not([disabled])',
    ) as HTMLInputElement;
    edit.checked = true;
    edit.dispatchEvent(new Event('change', { bubbles: true }));
    const consent = fixture.nativeElement.querySelector(
      '.collaboration-consent input',
    ) as HTMLInputElement;
    consent.checked = true;
    consent.dispatchEvent(new Event('change', { bubbles: true }));
    fixture.detectChanges();

    const form = fixture.nativeElement.querySelector('.collaboration-form') as HTMLFormElement;
    form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    http.expectOne('/api/v1/auth/csrf').flush({ token: csrf });
    await settle();
    const first = http.match((request) => request.method === 'PUT')[0];
    expect(first.request.url).toMatch(
      new RegExp(`/authoring/manuscripts/${workId}/invitations/[0-9a-f-]+$`),
    );
    expect(first.request.body).toMatchObject({
      email: 'editor@example.test',
      permissions: ['EDIT', 'VIEW'],
      confirmedPermissionToEmail: true,
    });
    const invitationPath = first.request.url;
    const invitationId = invitationPath.split('/').at(-1)!;
    first.flush({ code: 'TEMPORARY_FAILURE' }, { status: 503, statusText: 'Unavailable' });
    await settle();
    fixture.detectChanges();
    expect(sessionStorage.getItem(`novel.authoring-invitation-create.v1.${workId}`)).toContain(
      invitationId,
    );

    form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    http.expectOne('/api/v1/auth/csrf').flush({ token: csrf });
    await settle();
    const retry = http.expectOne({ method: 'PUT', url: invitationPath });
    expect(retry.request.body).toEqual(first.request.body);
    retry.flush(
      {
        id: invitationId,
        permissions: ['EDIT', 'VIEW'],
        status: 'pending',
        expiresAt: '2026-12-02T01:00:00Z',
        message: 'Queued.',
      },
      { status: 202, statusText: 'Accepted', headers: { 'Cache-Control': 'no-store' } },
    );
    await settle();
    http.expectOne(`/api/v1/authoring/manuscripts/${workId}/contributors`).flush(management, {
      headers: { 'Cache-Control': 'no-store' },
    });
    await settle();
    fixture.detectChanges();
    expect(sessionStorage.getItem(`novel.authoring-invitation-create.v1.${workId}`)).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('Đã xếp hàng lời mời');
  });
});

async function settle(): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, 0));
}
