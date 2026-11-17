import {
  Component,
  EventEmitter,
  Input,
  OnChanges,
  Output,
  SimpleChanges,
  inject,
  signal,
} from '@angular/core';
import {
  AuthoringApiError,
  AuthoringApiService,
  PublicationReadiness,
  PublicationRequestReceipt,
  newAuthoringIdempotencyKey,
} from '../core/authoring-api.service';
import { SystemStatusService } from '../core/system-status.service';
import { PublicationRequestFormComponent } from './publication-request-form.component';
import { PublicationRightsPolicyPanelComponent } from './publication-rights-policy-panel.component';

@Component({
  selector: 'app-publication-chapter-release-panel',
  imports: [PublicationRequestFormComponent, PublicationRightsPolicyPanelComponent],
  templateUrl: './publication-chapter-release-panel.component.html',
  styleUrl: './publication-chapter-release-panel.component.css',
})
export class PublicationChapterReleasePanelComponent implements OnChanges {
  @Input({ required: true })
  workId = '';

  @Input({ required: true })
  chapterId = '';

  @Input()
  workTitle = '';

  @Output()
  chapterPublished = new EventEmitter<void>();

  protected readonly readiness = signal<PublicationReadiness | null>(null);
  protected readonly loading = signal(false);
  protected readonly error = signal<AuthoringApiError | null>(null);
  protected readonly openPolicyRequest = signal(0);
  protected readonly confirmRelease = signal(false);
  protected readonly releasing = signal(false);
  protected readonly releaseError = signal<AuthoringApiError | null>(null);
  protected readonly statusMessage = signal('');

  private readonly api = inject(AuthoringApiService);
  private readonly systemStatus = inject(SystemStatusService);
  private requestGeneration = 0;
  private releaseKey: string | null = null;

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['workId'] || changes['chapterId']) void this.refresh();
  }

  protected get policyNeedsAcceptance(): boolean {
    const current = this.readiness();
    return !!current && current.publicationRightsPolicyAcceptedAt === null;
  }

  protected get requestIsPending(): boolean {
    return this.readiness()?.currentRequestState === 'PENDING_REVIEW';
  }

  protected get approvedForRelease(): boolean {
    return this.readiness()?.currentRequestState === 'APPROVED_UNPUBLISHED';
  }

  protected get releaseFeatureEnabled(): boolean {
    return this.systemStatus.status()?.features.publicationReleaseEnabled === true;
  }

  protected get canRelease(): boolean {
    return (
      this.approvedForRelease &&
      this.releaseFeatureEnabled &&
      this.confirmRelease() &&
      !this.releasing() &&
      !!this.readiness()?.snapshotEtag
    );
  }

  protected async refresh(): Promise<void> {
    if (!this.workId || !this.chapterId) return;
    const generation = ++this.requestGeneration;
    this.loading.set(true);
    this.error.set(null);
    try {
      const current = await this.api.getChapterPublicationReadiness(this.workId, this.chapterId);
      if (generation !== this.requestGeneration) return;
      this.readiness.set(current);
      this.releaseError.set(null);
      this.confirmRelease.set(false);
      this.statusMessage.set('');
      if (current.publicationRightsPolicyAcceptedAt === null) {
        this.openPolicyRequest.update((value) => value + 1);
      }
    } catch (error) {
      if (generation !== this.requestGeneration) return;
      this.error.set(toAuthoringError(error));
    } finally {
      if (generation === this.requestGeneration) this.loading.set(false);
    }
  }

  protected onReadinessUpdated(value: PublicationReadiness): void {
    this.readiness.set(value);
  }

  protected onRequestSubmitted(receipt: PublicationRequestReceipt): void {
    const message = `Đã gửi yêu cầu duyệt chương · lần gửi ${receipt.requestVersion}. Chương vẫn chưa công khai.`;
    void this.refresh().then(() => this.statusMessage.set(message));
  }

  protected onPolicyAccepted(): void {
    void this.refresh();
  }

  protected openPolicy(): void {
    this.openPolicyRequest.update((value) => value + 1);
  }

  protected updateReleaseConfirmation(event: Event): void {
    this.confirmRelease.set((event.target as HTMLInputElement).checked);
    this.releaseError.set(null);
  }

  protected async publishChapter(): Promise<void> {
    const current = this.readiness();
    if (!current || !this.canRelease) return;
    this.releasing.set(true);
    this.releaseError.set(null);
    this.statusMessage.set('Đang xác nhận xuất bản chương…');
    const key = this.releaseKey ?? newAuthoringIdempotencyKey();
    this.releaseKey = key;
    try {
      const receipt = await this.api.publishChapterNow(
        this.workId,
        this.chapterId,
        current.snapshotEtag,
        key,
      );
      this.releaseKey = null;
      this.statusMessage.set(
        `Đã công khai chương · phiên bản tác phẩm ${receipt.publicationContentVersion}.`,
      );
      this.chapterPublished.emit();
    } catch (error) {
      const mapped = toAuthoringError(error);
      this.releaseError.set(mapped);
      if (mapped.status !== 0 && mapped.status < 500) this.releaseKey = null;
      if (mapped.status === 412 || mapped.code === 'PUBLICATION_APPROVAL_CHANGED') {
        await this.refresh();
      }
    } finally {
      this.releasing.set(false);
    }
  }

  protected blockerLabel(code: string): string {
    switch (code) {
      case 'PUBLICATION_RIGHTS_POLICY_NOT_CONFIGURED':
        return 'Chưa có chính sách quyền xuất bản hiện hành.';
      case 'PUBLICATION_RIGHTS_POLICY_NOT_ACCEPTED':
        return 'Bạn cần đọc và xác nhận chính sách quyền xuất bản.';
      case 'READY_CHAPTER_REQUIRED':
        return 'Không tìm thấy chương đã lưu để gửi xét duyệt.';
      case 'PUBLICATION_REQUEST_ALREADY_PENDING':
        return 'Đang có yêu cầu chờ người duyệt.';
      default:
        return 'Chưa đủ điều kiện gửi yêu cầu duyệt.';
    }
  }

  protected errorMessage(error: AuthoringApiError): string {
    switch (error.code) {
      case 'DRAFT_CHAPTER_NOT_FOUND':
      case 'MANUSCRIPT_NOT_FOUND':
        return 'Chương nháp không còn khả dụng trên tác phẩm này.';
      case 'POLICY_ACCEPTANCE_REQUIRED':
        return 'Hoàn tất Điều khoản và Thông báo quyền riêng tư trước khi tiếp tục.';
      case 'PUBLICATION_RIGHTS_POLICY_NOT_ACCEPTED':
        return 'Hãy xác nhận chính sách quyền xuất bản hiện hành.';
      case 'PUBLICATION_APPROVAL_REQUIRED':
        return 'Chương chưa có phê duyệt hiện hành của người duyệt.';
      case 'PUBLICATION_SNAPSHOT_STALE':
        return 'Nội dung hoặc trạng thái đã thay đổi. Tải lại rồi thử lại.';
      default:
        return error.status >= 500 || error.status === 0
          ? 'Chưa tải được thông tin duyệt. Kiểm tra kết nối rồi thử lại.'
          : 'Không thể tiếp tục thao tác này.';
    }
  }
}

function toAuthoringError(error: unknown): AuthoringApiError {
  if (error instanceof AuthoringApiError) return error;
  return new AuthoringApiError(0, 'PUBLICATION_CHAPTER_REQUEST_FAILED');
}
