import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  AuthoringApiError,
  AuthoringApiService,
  PublicationRightsPolicy,
} from '../core/authoring-api.service';
import { PublicationRightsPolicyPanelComponent } from './publication-rights-policy-panel.component';

const workId = '11111111-1111-4111-8111-111111111111';
const workTitle = 'Hành trình tu luyện';
const policyDigest = 'a'.repeat(64);

function currentPolicy(
  version = 'rights-v1',
  acceptedAt: string | null = null,
): PublicationRightsPolicy {
  return {
    version,
    effectiveAt: '2026-11-15T00:00:00Z',
    displayText: 'Chính sách quyền xuất bản kiểm thử.\nNội dung nguyên văn phải được giữ lại.',
    policySha256: policyDigest,
    acceptedAt,
  };
}

describe('PublicationRightsPolicyPanelComponent', () => {
  let api: {
    getCurrentPublicationRightsPolicy: ReturnType<typeof vi.fn>;
    acceptCurrentPublicationRightsPolicy: ReturnType<typeof vi.fn>;
  };
  let fixture: ComponentFixture<PublicationRightsPolicyPanelComponent>;

  beforeEach(() => {
    api = {
      getCurrentPublicationRightsPolicy: vi.fn().mockResolvedValue(currentPolicy()),
      acceptCurrentPublicationRightsPolicy: vi.fn().mockResolvedValue({
        id: '55555555-5555-4555-8555-555555555555',
        policyVersion: 'rights-v1',
        policySha256: policyDigest,
        acceptedAt: '2026-11-15T12:30:00Z',
      }),
    };
    TestBed.configureTestingModule({
      imports: [PublicationRightsPolicyPanelComponent],
      providers: [provideRouter([]), { provide: AuthoringApiService, useValue: api }],
    });
    fixture = TestBed.createComponent(PublicationRightsPolicyPanelComponent);
    fixture.componentRef.setInput('workId', workId);
    fixture.componentRef.setInput('workTitle', workTitle);
  });

  it('does not request policy or imply publishing until the author opens the policy step', async () => {
    fixture.detectChanges();
    expect(api.getCurrentPublicationRightsPolicy).not.toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain('Bản thảo riêng tư · Chưa xuất bản');
    expect(fixture.nativeElement.textContent).not.toContain('Xác minh quyền');

    await openPolicy(fixture);

    expect(api.getCurrentPublicationRightsPolicy).toHaveBeenCalledWith(workId);
    expect(fixture.nativeElement.textContent).toContain('Chính sách quyền xuất bản kiểm thử.');
    expect(fixture.nativeElement.textContent).toContain('rights-v1');
    expect(fixture.nativeElement.textContent).toContain('Nội dung nguyên văn phải được giữ lại.');
    expect(
      fixture.nativeElement.querySelector('.authoring-rights-document')?.textContent,
    ).toContain('\n');
    expect(fixture.nativeElement.textContent).toContain('không xác minh quyền tác giả');
  });

  it('requires a deliberate checkbox and button action before posting acceptance', async () => {
    fixture.detectChanges();
    await openPolicy(fixture);

    const checkbox = fixture.nativeElement.querySelector(
      '.authoring-rights-checkbox input',
    ) as HTMLInputElement;
    const confirm = fixture.nativeElement.querySelector(
      '.authoring-rights-actions button',
    ) as HTMLButtonElement;
    expect(checkbox.checked).toBe(false);
    expect(confirm.disabled).toBe(true);
    expect(api.acceptCurrentPublicationRightsPolicy).not.toHaveBeenCalled();

    checkbox.checked = true;
    checkbox.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    expect(confirm.disabled).toBe(false);
    expect(api.acceptCurrentPublicationRightsPolicy).not.toHaveBeenCalled();

    confirm.click();
    await settle(fixture);

    expect(api.acceptCurrentPublicationRightsPolicy).toHaveBeenCalledTimes(1);
    expect(api.acceptCurrentPublicationRightsPolicy).toHaveBeenCalledWith(
      workId,
      'rights-v1',
      policyDigest,
      expect.stringMatching(/^[0-9a-f-]{36}$/),
    );
    expect(fixture.nativeElement.textContent).toContain(
      'Đã ghi nhận xác nhận policy phiên bản rights-v1',
    );
    expect(fixture.nativeElement.textContent).toContain('không tạo quyền khai thác');
    expect(fixture.nativeElement.querySelector('.authoring-rights-checkbox')).toBeNull();
  });

  it('renders policy unavailability without a checkbox or acceptance request', async () => {
    api.getCurrentPublicationRightsPolicy.mockRejectedValue(
      new AuthoringApiError(503, 'PUBLICATION_RIGHTS_POLICY_NOT_CONFIGURED'),
    );
    fixture.detectChanges();
    await openPolicy(fixture);

    expect(fixture.nativeElement.textContent).toContain(
      'chưa được cấu hình hoặc chưa thể xác minh',
    );
    expect(fixture.nativeElement.textContent).toContain('Không thể gửi duyệt hoặc công khai');
    expect(fixture.nativeElement.querySelector('input[type="checkbox"]')).toBeNull();
    expect(fixture.nativeElement.querySelector('.authoring-rights-actions button')).toBeNull();
    expect(api.acceptCurrentPublicationRightsPolicy).not.toHaveBeenCalled();
  });

  it('preserves the exact idempotency key after an uncertain response and announces only after success', async () => {
    api.acceptCurrentPublicationRightsPolicy
      .mockRejectedValueOnce(
        new AuthoringApiError(503, 'PUBLICATION_RIGHTS_POLICY_STORE_UNAVAILABLE'),
      )
      .mockResolvedValueOnce({
        id: '55555555-5555-4555-8555-555555555555',
        policyVersion: 'rights-v1',
        policySha256: policyDigest,
        acceptedAt: '2026-11-15T12:30:00Z',
      });
    fixture.detectChanges();
    await openPolicy(fixture);
    await checkAndConfirm(fixture);
    expect(fixture.nativeElement.textContent).toContain('Chưa xác định được kết quả');
    expect(fixture.nativeElement.textContent).not.toContain('Đã ghi nhận xác nhận');
    expect(fixture.nativeElement.querySelector('.authoring-rights-checkbox input')?.disabled).toBe(
      true,
    );

    const retry = [...fixture.nativeElement.querySelectorAll('button')].find((button) =>
      button.textContent.includes('Thử xác nhận lại'),
    ) as HTMLButtonElement;
    retry.click();
    await settle(fixture);

    const [firstAttempt, retryAttempt] = api.acceptCurrentPublicationRightsPolicy.mock.calls;
    expect(retryAttempt[3]).toBe(firstAttempt[3]);
    expect(api.acceptCurrentPublicationRightsPolicy).toHaveBeenCalledTimes(2);
    expect(fixture.nativeElement.textContent).toContain(
      'Đã ghi nhận xác nhận policy phiên bản rights-v1',
    );
  });

  it('reloads a changed policy after a stale response and requires a fresh confirmation', async () => {
    api.getCurrentPublicationRightsPolicy
      .mockResolvedValueOnce(currentPolicy('rights-v1'))
      .mockResolvedValueOnce(currentPolicy('rights-v2'));
    api.acceptCurrentPublicationRightsPolicy.mockRejectedValueOnce(
      new AuthoringApiError(409, 'PUBLICATION_RIGHTS_POLICY_STALE'),
    );
    fixture.detectChanges();
    await openPolicy(fixture);
    await checkAndConfirm(fixture);

    expect(api.getCurrentPublicationRightsPolicy).toHaveBeenCalledTimes(2);
    expect(fixture.nativeElement.textContent).toContain('Chính sách đã đổi');
    expect(fixture.nativeElement.textContent).toContain('rights-v2');
    expect(fixture.nativeElement.textContent).not.toContain('Đã ghi nhận xác nhận');
    expect(fixture.nativeElement.querySelector('.authoring-rights-checkbox input')?.checked).toBe(
      false,
    );
    expect(api.acceptCurrentPublicationRightsPolicy).toHaveBeenCalledTimes(1);
  });

  it('does not offer a second confirmation when the current version is already accepted', async () => {
    api.getCurrentPublicationRightsPolicy.mockResolvedValue(
      currentPolicy('rights-v1', '2026-11-15T12:30:00Z'),
    );
    fixture.detectChanges();
    await openPolicy(fixture);

    expect(fixture.nativeElement.textContent).toContain(
      'Đã ghi nhận xác nhận policy phiên bản rights-v1',
    );
    expect(fixture.nativeElement.querySelector('input[type="checkbox"]')).toBeNull();
    expect(api.acceptCurrentPublicationRightsPolicy).not.toHaveBeenCalled();
  });

  it('resets policy text and consent when the manuscript changes', async () => {
    fixture.detectChanges();
    await openPolicy(fixture);
    const checkbox = fixture.nativeElement.querySelector(
      '.authoring-rights-checkbox input',
    ) as HTMLInputElement;
    checkbox.checked = true;
    checkbox.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    fixture.componentRef.setInput('workId', '77777777-7777-4777-8777-777777777777');
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.authoring-rights-document')).toBeNull();
    expect(fixture.nativeElement.querySelector('input[type="checkbox"]')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('Bản thảo riêng tư · Chưa xuất bản');
  });
});

async function openPolicy(fixture: ComponentFixture<PublicationRightsPolicyPanelComponent>) {
  const button = [...fixture.nativeElement.querySelectorAll('button')].find((item) =>
    item.textContent.includes('Kiểm tra chính sách quyền xuất bản'),
  ) as HTMLButtonElement;
  button.click();
  await settle(fixture);
}

async function checkAndConfirm(fixture: ComponentFixture<PublicationRightsPolicyPanelComponent>) {
  const checkbox = fixture.nativeElement.querySelector(
    '.authoring-rights-checkbox input',
  ) as HTMLInputElement;
  checkbox.checked = true;
  checkbox.dispatchEvent(new Event('change'));
  fixture.detectChanges();
  const button = fixture.nativeElement.querySelector(
    '.authoring-rights-actions button',
  ) as HTMLButtonElement;
  button.click();
  await settle(fixture);
}

async function settle(fixture: ComponentFixture<PublicationRightsPolicyPanelComponent>) {
  await Promise.resolve();
  await Promise.resolve();
  fixture.detectChanges();
  await fixture.whenStable();
  fixture.detectChanges();
}
