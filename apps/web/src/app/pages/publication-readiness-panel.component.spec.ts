import { ComponentFixture, TestBed } from '@angular/core/testing';
import { signal, WritableSignal } from '@angular/core';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  AuthoringApiError,
  AuthoringApiService,
  PublicationReadiness,
  PublicationReleaseReceipt,
} from '../core/authoring-api.service';
import { SystemStatusService } from '../core/system-status.service';
import { ApiSystemStatus } from '../core/models';
import { PublicationReadinessPanelComponent } from './publication-readiness-panel.component';

const workId = '11111111-1111-4111-8111-111111111111';
const readinessEtag = `"publication-snapshot-${'a'.repeat(64)}"`;
const scopeEtag = `"publication-scope-${'b'.repeat(64)}"`;
const timestamp = '2026-11-16T12:30:00Z';

function serverStatus(publicationReleaseEnabled: boolean): ApiSystemStatus {
  return {
    apiVersion: 'v1',
    environment: 'test',
    integrations: {
      googleOidcEnabled: false,
      momoTestEnabled: false,
      momoEnvironment: 'TEST',
    },
    features: {
      importProcessingEnabled: false,
      cultivationEnabled: false,
      commerceSandboxEnabled: false,
      publicCatalogEnabled: false,
      publicationReleaseEnabled,
      translationFakePreviewEnabled: false,
    },
  };
}

function readiness(overrides: Partial<PublicationReadiness> = {}): PublicationReadiness {
  return {
    workId,
    canSubmit: true,
    publicationRightsPolicyAvailable: true,
    publicationRightsPolicyVersion: 'rights-v1',
    publicationRightsPolicyEffectiveAt: '2026-11-15T00:00:00Z',
    publicationRightsPolicyAcceptedAt: '2026-11-16T12:00:00Z',
    publicationContentVersion: 7,
    readyChapterCount: 3,
    currentRequestState: 'APPROVED_UNPUBLISHED',
    snapshotEtag: readinessEtag,
    scopes: [
      { scopeCode: 'PUBLIC_METADATA', ready: true, snapshotEtag: scopeEtag },
      { scopeCode: 'CHAPTER_TEXT', ready: true, snapshotEtag: scopeEtag },
      { scopeCode: 'COMMERCIAL_SALE', ready: true, snapshotEtag: scopeEtag },
    ],
    blockerCodes: [],
    ...overrides,
  };
}

function receipt(): PublicationReleaseReceipt {
  return {
    eventId: '22222222-2222-4222-8222-222222222222',
    workId,
    requestId: '33333333-3333-4333-8333-333333333333',
    state: 'PUBLISHED',
    publishedAt: timestamp,
    publicationContentVersion: 7,
  };
}

describe('PublicationReadinessPanelComponent', () => {
  let fixture: ComponentFixture<PublicationReadinessPanelComponent>;
  let api: {
    getPublicationReadiness: ReturnType<typeof vi.fn>;
    publishManuscriptNow: ReturnType<typeof vi.fn>;
  };
  let status: WritableSignal<ApiSystemStatus | null>;

  beforeEach(() => {
    api = {
      getPublicationReadiness: vi.fn().mockResolvedValue(readiness()),
      publishManuscriptNow: vi.fn().mockResolvedValue(receipt()),
    };
    status = signal(serverStatus(false));
    TestBed.configureTestingModule({
      imports: [PublicationReadinessPanelComponent],
      providers: [
        provideRouter([]),
        { provide: AuthoringApiService, useValue: api },
        {
          provide: SystemStatusService,
          useValue: {
            status,
          },
        },
      ],
    });
    fixture = TestBed.createComponent(PublicationReadinessPanelComponent);
    fixture.componentRef.setInput('workId', workId);
    fixture.detectChanges();
  });

  it('loads the redacted readiness checklist only after the author asks for it', async () => {
    expect(api.getPublicationReadiness).not.toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain('CHECKLIST RIÊNG TƯ');

    await clickButton('Kiểm tra điều kiện xuất bản');

    expect(api.getPublicationReadiness).toHaveBeenCalledWith(workId);
    expect(fixture.nativeElement.textContent).toContain('Đã duyệt · Chưa công khai');
    expect(fixture.nativeElement.textContent).toContain('Thông tin tác phẩm');
    expect(fixture.nativeElement.textContent).toContain('Nội dung chương');
    expect(fixture.nativeElement.textContent).toContain('Quyền thương mại');
    expect(fixture.nativeElement.textContent).toContain('không xác minh quyền pháp lý');
  });

  it('hides the release control unless both server capability and readiness gates pass', async () => {
    await clickButton('Kiểm tra điều kiện xuất bản');
    expect(button('Xuất bản ngay')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('đang tắt trong môi trường này');

    status.set(serverStatus(true));
    fixture.detectChanges();
    expect(button('Xuất bản ngay')).not.toBeNull();

    api.getPublicationReadiness.mockResolvedValueOnce(
      readiness({ currentRequestState: 'PENDING_REVIEW' }),
    );
    await clickButton('Kiểm tra lại');
    expect(button('Xuất bản ngay')).toBeNull();
  });

  it('requires a separate confirmation and retries an uncertain release with the same key', async () => {
    status.set(serverStatus(true));
    api.publishManuscriptNow
      .mockRejectedValueOnce(new AuthoringApiError(503, 'PUBLICATION_RELEASE_UNAVAILABLE'))
      .mockResolvedValueOnce(receipt());

    await clickButton('Kiểm tra điều kiện xuất bản');
    await clickButton('Xuất bản ngay');
    expect(fixture.nativeElement.textContent).toContain('Xác nhận xuất bản ngay');
    expect(api.publishManuscriptNow).not.toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain('ngay theo các phạm vi');
    expect(fixture.nativeElement.textContent).not.toContain('Hành trình tu luyện');

    await clickButton('Xác nhận xuất bản ngay');
    expect(fixture.nativeElement.textContent).toContain('Thử lại sẽ gửi đúng yêu cầu');
    expect(fixture.nativeElement.textContent).not.toContain(
      'Máy chủ đã xác nhận tác phẩm được công khai',
    );

    await clickButton('Thử lại cùng thao tác');
    const firstAttempt = api.publishManuscriptNow.mock.calls[0];
    const retryAttempt = api.publishManuscriptNow.mock.calls[1];
    expect(firstAttempt).toEqual(retryAttempt);
    expect(firstAttempt).toEqual([workId, readinessEtag, expect.any(String)]);
    expect(fixture.nativeElement.textContent).toContain(
      'Máy chủ đã xác nhận tác phẩm được công khai',
    );
    expect(button('Xuất bản ngay')).toBeNull();
  });

  it('refreshes stale readiness and requires a new explicit confirmation', async () => {
    status.set(serverStatus(true));
    api.publishManuscriptNow.mockRejectedValueOnce(
      new AuthoringApiError(412, 'PUBLICATION_SNAPSHOT_STALE'),
    );

    await clickButton('Kiểm tra điều kiện xuất bản');
    await clickButton('Xuất bản ngay');
    await clickButton('Xác nhận xuất bản ngay');

    expect(api.publishManuscriptNow).toHaveBeenCalledTimes(1);
    expect(api.getPublicationReadiness).toHaveBeenCalledTimes(2);
    expect(fixture.nativeElement.textContent).toContain('Trạng thái bản thảo đã thay đổi');
    expect(fixture.nativeElement.textContent).not.toContain('Xác nhận xuất bản ngay');
    expect(button('Xuất bản ngay')).not.toBeNull();
  });

  it('uses neutral owner-masked copy for a missing manuscript', async () => {
    api.getPublicationReadiness.mockRejectedValueOnce(
      new AuthoringApiError(404, 'MANUSCRIPT_NOT_FOUND'),
    );

    await clickButton('Kiểm tra điều kiện xuất bản');

    expect(fixture.nativeElement.textContent).toContain('Không tìm thấy bản thảo riêng tư này.');
    expect(fixture.nativeElement.textContent).not.toContain('không thuộc sở hữu');
  });

  async function clickButton(label: string): Promise<void> {
    const target = button(label);
    if (!target) throw new Error(`Missing button: ${label}`);
    target.click();
    fixture.detectChanges();
    await fixture.whenStable();
    await Promise.resolve();
    fixture.detectChanges();
  }

  function button(label: string): HTMLButtonElement | null {
    const buttons = fixture.nativeElement.querySelectorAll(
      'button',
    ) as NodeListOf<HTMLButtonElement>;
    return Array.from(buttons).find((item) => item.textContent?.trim() === label) ?? null;
  }
});
