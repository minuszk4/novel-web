import { Component, DestroyRef, ElementRef, ViewChild, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { newTranslationIdempotencyKey } from '../core/translation-job-api.service';
import {
  TranslationReviewAction,
  TranslationReviewApiError,
  TranslationReviewApiService,
  TranslationReviewDecisionReceipt,
  TranslationReviewDetail,
  TranslationReviewReason,
} from '../core/translation-review-api.service';

type DetailPageState = 'loading' | 'ready' | 'error';
interface DecisionAttempt {
  actionCode: TranslationReviewAction;
  reasonCode: TranslationReviewReason | null;
  etag: string;
  idempotencyKey: string;
}

@Component({
  selector: 'app-translation-review-detail-page',
  imports: [RouterLink],
  templateUrl: './translation-review-detail-page.component.html',
  styleUrl: './translation-review-detail-page.component.css',
})
export class TranslationReviewDetailPageComponent {
  protected readonly state = signal<DetailPageState>('loading');
  protected readonly detail = signal<TranslationReviewDetail | null>(null);
  protected readonly pageError = signal<string | null>(null);
  protected readonly accessState = signal<'unauthenticated' | 'policy-required' | null>(null);
  protected readonly staleSource = signal(false);
  protected readonly activeAction = signal<TranslationReviewAction | null>(null);
  protected readonly reasonCode = signal<TranslationReviewReason>('ACCURATE');
  protected readonly saving = signal(false);
  protected readonly uncertain = signal(false);
  protected readonly decisionError = signal<string | null>(null);
  protected readonly receipt = signal<TranslationReviewDecisionReceipt | null>(null);
  protected readonly announcement = signal('');

  private readonly route = inject(ActivatedRoute);
  private readonly api = inject(TranslationReviewApiService);
  private readonly destroyRef = inject(DestroyRef);
  private submissionId: string | null = null;
  private etag: string | null = null;
  private attempt: DecisionAttempt | null = null;
  private requestSequence = 0;

  @ViewChild('pageHeading')
  private set pageHeading(heading: ElementRef<HTMLHeadingElement> | undefined) {
    heading?.nativeElement.focus({ preventScroll: true });
  }

  @ViewChild('decisionResultHeading')
  private set decisionResultHeading(heading: ElementRef<HTMLHeadingElement> | undefined) {
    heading?.nativeElement.focus({ preventScroll: true });
  }

  constructor() {
    this.route.paramMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((params) => {
      this.submissionId = params.get('submissionId');
      this.attempt = null;
      this.activeAction.set(null);
      this.receipt.set(null);
      this.uncertain.set(false);
      void this.loadDetail();
    });
  }

  protected formatDate(value: string): string {
    return new Intl.DateTimeFormat('vi-VN', { dateStyle: 'medium', timeStyle: 'short' }).format(
      new Date(value),
    );
  }

  protected beginDecision(action: TranslationReviewAction): void {
    if (!this.detail()?.canDecide || this.saving() || this.uncertain() || this.receipt()) return;
    this.decisionError.set(null);
    this.activeAction.set(action);
    this.reasonCode.set(action === 'ACCEPT' ? 'ACCURATE' : 'MISSING_CONTENT');
    setTimeout(() => document.getElementById('translation-review-reason')?.focus());
  }

  protected cancelDecision(): void {
    if (this.saving() || this.uncertain()) return;
    this.activeAction.set(null);
    this.decisionError.set(null);
  }

  protected updateReason(event: Event): void {
    const target = event.target;
    if (target instanceof HTMLSelectElement && isReason(target.value))
      this.reasonCode.set(target.value);
  }

  protected async refresh(): Promise<void> {
    if (this.saving()) return;
    this.attempt = null;
    this.uncertain.set(false);
    this.activeAction.set(null);
    await this.loadDetail();
  }

  protected async confirmDecision(): Promise<void> {
    const submissionId = this.submissionId;
    const action = this.activeAction();
    const detail = this.detail();
    if (!submissionId || !action || !detail?.canDecide || this.saving()) return;
    const reason = this.reasonCode();
    if (action === 'REJECT' && !isRejectReason(reason)) {
      this.decisionError.set('Chọn lý do từ chối trước khi ghi nhận quyết định.');
      return;
    }
    const attempt = this.attempt ?? {
      actionCode: action,
      reasonCode: action === 'ACCEPT' ? reason : reason,
      etag: this.etag ?? '',
      idempotencyKey: newTranslationIdempotencyKey(),
    };
    if (!attempt.etag) {
      this.decisionError.set('Thiếu phiên bản kiểm tra. Tải lại chi tiết trước khi quyết định.');
      return;
    }
    this.attempt = attempt;
    this.saving.set(true);
    this.uncertain.set(false);
    this.decisionError.set(null);
    this.announcement.set('Đang ghi nhận quyết định.');
    try {
      const result = await this.api.decide(
        submissionId,
        attempt.etag,
        attempt.idempotencyKey,
        attempt.actionCode,
        attempt.reasonCode,
      );
      this.receipt.set(result);
      this.attempt = null;
      this.detail.update((current) =>
        current
          ? {
              ...current,
              status: result.actionCode === 'ACCEPT' ? 'accepted' : 'rejected',
              canDecide: false,
            }
          : current,
      );
      this.activeAction.set(null);
      this.announcement.set(
        result.replayed ? 'Quyết định trước đó đã được xác nhận.' : 'Đã ghi nhận quyết định.',
      );
    } catch (error) {
      const apiError = error instanceof TranslationReviewApiError ? error : null;
      if (apiError?.status === 0 || (apiError?.status ?? 0) >= 500) {
        this.uncertain.set(true);
        this.decisionError.set(
          'Chưa xác minh được máy chủ đã ghi nhận hay chưa. Gửi lại đúng quyết định để kiểm tra an toàn.',
        );
      } else if (
        apiError?.status === 412 ||
        apiError?.code === 'TRANSLATION_REVIEW_ALREADY_DECIDED' ||
        apiError?.code === 'TRANSLATION_REVIEW_SOURCE_STALE'
      ) {
        this.attempt = null;
        this.uncertain.set(false);
        this.decisionError.set('Bản xem xét đã thay đổi. Tải lại chi tiết trước khi tiếp tục.');
      } else if (apiError?.status === 401 || apiError?.status === 403 || apiError?.status === 404) {
        this.detail.set(null);
        this.state.set('error');
        this.accessState.set(
          apiError.status === 401
            ? 'unauthenticated'
            : apiError.code === 'POLICY_ACCEPTANCE_REQUIRED'
              ? 'policy-required'
              : null,
        );
        this.pageError.set(
          apiError.status === 404
            ? 'Mục này không còn khả dụng cho tài khoản hiện tại.'
            : 'Không thể xác minh quyền truy cập.',
        );
        this.attempt = null;
      } else {
        this.attempt = null;
        this.decisionError.set('Không ghi nhận được quyết định. Kiểm tra lựa chọn rồi thử lại.');
      }
      this.announcement.set(
        this.decisionError() ?? this.pageError() ?? 'Không thể ghi nhận quyết định.',
      );
    } finally {
      this.saving.set(false);
    }
  }

  private async loadDetail(): Promise<void> {
    const submissionId = this.submissionId;
    const sequence = ++this.requestSequence;
    this.detail.set(null);
    this.etag = null;
    this.pageError.set(null);
    this.accessState.set(null);
    this.staleSource.set(false);
    this.state.set('loading');
    if (!submissionId) {
      this.pageError.set('Liên kết bản dịch không hợp lệ.');
      this.state.set('error');
      return;
    }
    try {
      const result = await this.api.getDetail(submissionId);
      if (sequence !== this.requestSequence) return;
      this.detail.set(result.detail);
      this.etag = result.etag;
      this.state.set('ready');
      this.announcement.set(`Đã tải ${result.detail.segmentCount} đoạn cần đối chiếu.`);
    } catch (error) {
      if (sequence !== this.requestSequence) return;
      const apiError = error instanceof TranslationReviewApiError ? error : null;
      this.state.set('error');
      if (apiError?.status === 401) this.accessState.set('unauthenticated');
      else if (apiError?.status === 403 && apiError.code === 'POLICY_ACCEPTANCE_REQUIRED')
        this.accessState.set('policy-required');
      else if (apiError?.status === 409 && apiError.code === 'TRANSLATION_REVIEW_SOURCE_STALE')
        this.staleSource.set(true);
      this.pageError.set(
        this.staleSource()
          ? 'Nguyên tác đã đổi sau khi gửi duyệt. Quyết định này không thể áp dụng cho bản cũ.'
          : apiError?.status === 404
            ? 'Mục này không còn khả dụng cho tài khoản hiện tại.'
            : apiError?.status === 503
              ? 'Không thể xác minh bản đã gửi. Hãy thử tải lại sau.'
              : 'Không thể tải bản đối chiếu. Kiểm tra kết nối rồi thử lại.',
      );
    }
  }
}

function isReason(value: string): value is TranslationReviewReason {
  return [
    'ACCURATE',
    'MINOR_TERMINOLOGY_ISSUE',
    'MISSING_CONTENT',
    'MEANING_ERROR',
    'TERMINOLOGY_ERROR',
    'FORMATTING_ERROR',
    'OTHER',
  ].includes(value);
}

function isRejectReason(value: TranslationReviewReason): boolean {
  return [
    'MISSING_CONTENT',
    'MEANING_ERROR',
    'TERMINOLOGY_ERROR',
    'FORMATTING_ERROR',
    'OTHER',
  ].includes(value);
}
