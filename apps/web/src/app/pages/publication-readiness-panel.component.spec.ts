import { ComponentFixture, TestBed } from '@angular/core/testing';
import { signal, WritableSignal } from '@angular/core';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  AuthoringApiError,
  AuthoringApiService,
  PublicationReadiness,
  PublicationReleaseReceipt,
  PublicationScheduleStatus,
  PublicationRequestHistoryPage,
  PublicationRequestWithdrawalReceipt,
} from '../core/authoring-api.service';
import { SystemStatusService } from '../core/system-status.service';
import { ApiSystemStatus } from '../core/models';
import { PublicationReadinessPanelComponent } from './publication-readiness-panel.component';

const workId = '11111111-1111-4111-8111-111111111111';
const readinessEtag = `"publication-snapshot-${'a'.repeat(64)}"`;
const scopeEtag = `"publication-scope-${'b'.repeat(64)}"`;
const timestamp = '2026-11-16T12:30:00Z';

function serverStatus(
  publicationReleaseEnabled: boolean,
  publicationSchedulerApiEnabled = false,
): ApiSystemStatus {
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
      publicationSchedulerApiEnabled,
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
      { scopeCode: 'FREE_PREVIEW', ready: true, snapshotEtag: scopeEtag },
      { scopeCode: 'TRANSLATION_ADAPTATION', ready: true, snapshotEtag: scopeEtag },
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

function scheduledStatus(): PublicationScheduleStatus {
  return {
    scheduleId: '66666666-6666-4666-8666-666666666666',
    workId,
    requestId: '77777777-7777-4777-8777-777777777777',
    scheduledAt: '2030-11-17T04:00:00Z',
    state: 'SCHEDULED',
    failureCode: null,
    attemptCount: 0,
    createdAt: timestamp,
    updatedAt: timestamp,
  };
}

function withdrawalReceipt(): PublicationRequestWithdrawalReceipt {
  return {
    eventId: '44444444-4444-4444-8444-444444444444',
    requestId: '55555555-5555-4555-8555-555555555555',
    requestVersion: 2,
    state: 'WITHDRAWN',
    withdrawnAt: timestamp,
  };
}

function publicationHistoryPage(
  requestVersion: number,
  state: 'PENDING_REVIEW' | 'NEEDS_INFORMATION',
  nextCursor: string | null,
): PublicationRequestHistoryPage {
  const submitted = {
    sequence: 1,
    actorType: 'OWNER' as const,
    actionCode: 'SUBMITTED' as const,
    fromState: null,
    toState: 'PENDING_REVIEW' as const,
    reasonCode: null,
    createdAt: '2026-11-17T04:00:00Z',
  };
  const events =
    state === 'NEEDS_INFORMATION'
      ? [
          submitted,
          {
            sequence: 2,
            actorType: 'REVIEWER' as const,
            actionCode: 'NEEDS_INFORMATION' as const,
            fromState: 'PENDING_REVIEW' as const,
            toState: 'NEEDS_INFORMATION' as const,
            reasonCode: 'MORE_INFORMATION' as const,
            createdAt: '2026-11-17T04:10:00Z',
          },
        ]
      : [submitted];
  return {
    items: [
      {
        requestVersion,
        state,
        submittedAt: '2026-11-17T04:00:00Z',
        events,
      },
    ],
    nextCursor,
  };
}

describe('PublicationReadinessPanelComponent', () => {
  let fixture: ComponentFixture<PublicationReadinessPanelComponent>;
  let api: {
    getPublicationReadiness: ReturnType<typeof vi.fn>;
    getPublicationRequestHistory: ReturnType<typeof vi.fn>;
    publishManuscriptNow: ReturnType<typeof vi.fn>;
    withdrawPublicationRequest: ReturnType<typeof vi.fn>;
    getActivePublicationSchedule: ReturnType<typeof vi.fn>;
    scheduleManuscriptRelease: ReturnType<typeof vi.fn>;
    cancelPublicationSchedule: ReturnType<typeof vi.fn>;
  };
  let status: WritableSignal<ApiSystemStatus | null>;

  beforeEach(() => {
    api = {
      getPublicationReadiness: vi.fn().mockResolvedValue(readiness()),
      getPublicationRequestHistory: vi.fn(),
      publishManuscriptNow: vi.fn().mockResolvedValue(receipt()),
      withdrawPublicationRequest: vi.fn().mockResolvedValue(withdrawalReceipt()),
      getActivePublicationSchedule: vi.fn().mockResolvedValue(null),
      scheduleManuscriptRelease: vi.fn().mockResolvedValue({
        scheduleId: scheduledStatus().scheduleId,
        workId,
        requestId: scheduledStatus().requestId,
        state: 'SCHEDULED',
        scheduledAt: scheduledStatus().scheduledAt,
      }),
      cancelPublicationSchedule: vi.fn().mockResolvedValue(undefined),
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

  it('loads owner-safe reason history on demand and appends older request versions', async () => {
    api.getPublicationReadiness.mockResolvedValueOnce(
      readiness({ currentRequestState: 'NEEDS_INFORMATION' }),
    );
    api.getPublicationRequestHistory
      .mockResolvedValueOnce(publicationHistoryPage(2, 'PENDING_REVIEW', 'older-history-cursor'))
      .mockResolvedValueOnce(publicationHistoryPage(1, 'NEEDS_INFORMATION', null));

    await clickButton('Kiểm tra điều kiện xuất bản');
    expect(button('Xem lịch sử xử lý')).not.toBeNull();
    expect(api.getPublicationRequestHistory).not.toHaveBeenCalled();

    await clickButton('Xem lịch sử xử lý');
    expect(api.getPublicationRequestHistory).toHaveBeenCalledWith(workId, null);
    expect(fixture.nativeElement.textContent).toContain('Yêu cầu lần 2');
    expect(fixture.nativeElement.textContent).not.toContain('REVIEWER_USER_ID');
    expect(button('Tải yêu cầu cũ hơn')).not.toBeNull();

    await clickButton('Tải yêu cầu cũ hơn');
    expect(api.getPublicationRequestHistory).toHaveBeenLastCalledWith(
      workId,
      'older-history-cursor',
    );
    expect(fixture.nativeElement.textContent).toContain('Yêu cầu lần 1');
    expect(fixture.nativeElement.textContent).toContain('Cần bổ sung thông tin');
    expect(fixture.nativeElement.textContent).toContain('Nhóm duyệt');
    expect(fixture.nativeElement.textContent).not.toContain('reviewer@example.com');
    expect(button('Tải yêu cầu cũ hơn')).toBeNull();
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

  it('schedules an approved manuscript, keeps it private and lets the owner cancel', async () => {
    status.set(serverStatus(true, true));
    api.getActivePublicationSchedule
      .mockResolvedValueOnce(null)
      .mockResolvedValueOnce(scheduledStatus());

    await clickButton('Kiểm tra điều kiện xuất bản');
    await clickButton('Lên lịch phát hành');
    const input = fixture.nativeElement.querySelector(
      '#publication-scheduled-at',
    ) as HTMLInputElement;
    const localFuture = new Date(Date.now() + 2 * 60 * 60 * 1000);
    localFuture.setMinutes(localFuture.getMinutes() - localFuture.getTimezoneOffset());
    const localValue = localFuture.toISOString().slice(0, 16);
    input.value = localValue;
    input.dispatchEvent(new Event('input', { bubbles: true }));
    fixture.detectChanges();

    await clickButton('Xác nhận lên lịch');
    expect(api.scheduleManuscriptRelease).toHaveBeenCalledWith(
      workId,
      readinessEtag,
      new Date(localValue).toISOString(),
      expect.any(String),
    );
    expect(fixture.nativeElement.textContent).toContain('Lịch phát hành đã lưu');
    expect(fixture.nativeElement.textContent).toContain('Bản thảo vẫn riêng tư');
    expect(button('Xuất bản ngay')).toBeNull();

    await clickButton('Hủy lịch phát hành');
    await clickButton('Xác nhận hủy lịch');
    expect(api.cancelPublicationSchedule).toHaveBeenCalledWith(
      workId,
      scheduledStatus().scheduleId,
    );
    expect(fixture.nativeElement.textContent).not.toContain('Lịch phát hành đã lưu');
    expect(fixture.nativeElement.textContent).toContain('Đã hủy lịch phát hành');
  });

  it('requires an explicit withdrawal confirmation and retries the exact uncertain request', async () => {
    const pendingReadiness = readiness({
      canSubmit: false,
      currentRequestState: 'PENDING_REVIEW',
      blockerCodes: ['PUBLICATION_REQUEST_ALREADY_PENDING'],
    });
    api.getPublicationReadiness
      .mockResolvedValueOnce(pendingReadiness)
      .mockResolvedValueOnce(readiness({ currentRequestState: 'WITHDRAWN' }));
    api.withdrawPublicationRequest
      .mockRejectedValueOnce(
        new AuthoringApiError(503, 'PUBLICATION_REQUEST_WITHDRAWAL_UNAVAILABLE'),
      )
      .mockResolvedValueOnce(withdrawalReceipt());

    await clickButton('Kiểm tra điều kiện xuất bản');
    await clickButton('Rút yêu cầu duyệt');
    expect(fixture.nativeElement.textContent).toContain('Xác nhận rút yêu cầu duyệt');
    expect(api.withdrawPublicationRequest).not.toHaveBeenCalled();

    await clickButton('Xác nhận rút yêu cầu');
    expect(fixture.nativeElement.textContent).toContain('Thử lại sẽ gửi đúng thao tác');
    expect(fixture.nativeElement.textContent).toContain('Thử lại cùng thao tác rút');
    expect(api.getPublicationReadiness).toHaveBeenCalledTimes(1);

    await clickButton('Thử lại cùng thao tác rút');
    expect(api.withdrawPublicationRequest).toHaveBeenCalledTimes(2);
    expect(api.withdrawPublicationRequest.mock.calls[0]).toEqual(
      api.withdrawPublicationRequest.mock.calls[1],
    );
    expect(api.withdrawPublicationRequest.mock.calls[0]).toMatchObject([
      workId,
      readinessEtag,
      expect.any(String),
    ]);
    expect(api.getPublicationReadiness).toHaveBeenCalledTimes(2);
    expect(fixture.nativeElement.textContent).toContain('Yêu cầu đã được rút');
    expect(button('Rút yêu cầu duyệt')).toBeNull();
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
