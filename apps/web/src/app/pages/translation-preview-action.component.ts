import { Component, Input, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { SystemStatusService } from '../core/system-status.service';
import {
  newTranslationIdempotencyKey,
  TranslationJobApiError,
  TranslationJobApiService,
} from '../core/translation-job-api.service';

@Component({
  selector: 'app-translation-preview-action',
  templateUrl: './translation-preview-action.component.html',
})
export class TranslationPreviewActionComponent {
  @Input() chapterId: string | null = null;
  @Input() sourceRevisionId: string | null = null;
  @Input() sourceLanguage: string | null = null;

  private readonly api = inject(TranslationJobApiService);
  private readonly router = inject(Router);
  private readonly systemStatus = inject(SystemStatusService);
  protected readonly busy = signal(false);
  protected readonly retryingSameIntent = signal(false);
  protected readonly errorMessage = signal<string | null>(null);
  private pendingIntentKey: { signature: string; value: string } | null = null;

  protected canStart(): boolean {
    const enabled = this.systemStatus.status()?.features.translationFakePreviewEnabled === true;
    return (
      enabled &&
      !!this.chapterId &&
      !!this.sourceRevisionId &&
      !!targetLanguage(this.sourceLanguage)
    );
  }

  protected targetLanguage(): 'en' | 'vi' | null {
    return targetLanguage(this.sourceLanguage);
  }

  protected async start(): Promise<void> {
    const chapterId = this.chapterId;
    const sourceRevisionId = this.sourceRevisionId;
    const targetLanguage = this.targetLanguage();
    if (!this.canStart() || !chapterId || !sourceRevisionId || !targetLanguage || this.busy())
      return;

    const signature = `${chapterId}\n${sourceRevisionId}\n${targetLanguage}`;
    if (this.pendingIntentKey?.signature !== signature) {
      this.pendingIntentKey = { signature, value: newTranslationIdempotencyKey() };
    }
    this.busy.set(true);
    this.errorMessage.set(null);
    try {
      const result = await this.api.enqueue(
        chapterId,
        sourceRevisionId,
        targetLanguage,
        this.pendingIntentKey.value,
      );
      this.pendingIntentKey = null;
      this.retryingSameIntent.set(false);
      await this.router.navigate(['/app/translation/jobs', result.jobId]);
    } catch (error) {
      this.errorMessage.set(startErrorMessage(error));
      const ambiguous =
        error instanceof TranslationJobApiError && (error.status === 0 || error.status >= 500);
      this.retryingSameIntent.set(ambiguous);
      if (!ambiguous) this.pendingIntentKey = null;
    } finally {
      this.busy.set(false);
    }
  }
}

function targetLanguage(sourceLanguage: string | null): 'en' | 'vi' | null {
  switch (sourceLanguage?.toLowerCase()) {
    case 'vi':
      return 'en';
    case 'en':
      return 'vi';
    default:
      return null;
  }
}

function startErrorMessage(error: unknown): string {
  if (!(error instanceof TranslationJobApiError)) {
    return 'Chưa xác nhận được yêu cầu. Có thể gửi lại cùng yêu cầu để tránh tạo job trùng.';
  }
  if (error.status === 0 || error.status >= 500) {
    return 'Chưa xác nhận được yêu cầu. Gửi lại cùng yêu cầu để tránh tạo job trùng.';
  }
  if (error.status === 401) return 'Phiên đăng nhập đã hết hạn. Hãy đăng nhập lại để tiếp tục.';
  if (error.code === 'POLICY_ACCEPTANCE_REQUIRED') {
    return 'Hãy đọc và chấp nhận Điều khoản cùng Thông báo quyền riêng tư trước khi thử.';
  }
  if (error.code === 'POLICY_NOT_CONFIGURED') {
    return 'Chính sách hiện hành chưa được cấu hình; bản xem thử đang bị khóa.';
  }
  if (error.status === 403) return 'Tài khoản hiện không thể tạo bản xem thử cho chương này.';
  if (error.status === 404) return 'Chương không còn sẵn sàng hoặc không thể truy cập.';
  if (error.code === 'TRANSLATION_SOURCE_STALE') {
    return 'Chương đã thay đổi. Tải lại chương rồi tạo bản xem thử mới.';
  }
  if (error.status === 409) return 'Nguồn chương đã thay đổi; hãy tải lại trước khi thử lại.';
  if (error.status === 413) return 'Chương vượt giới hạn của bản xem thử cục bộ.';
  if (error.status === 422)
    return 'Bản xem thử hiện chỉ hỗ trợ Việt ↔ Anh và chương có văn bản hợp lệ.';
  return 'Không thể tạo bản xem thử lúc này. Hãy kiểm tra kết nối rồi thử lại.';
}
