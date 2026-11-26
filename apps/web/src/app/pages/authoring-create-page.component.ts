import { Component, ElementRef, ViewChild, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import {
  AuthoringApiError,
  AuthoringApiService,
  CreatedPrivateManuscript,
  newAuthoringIdempotencyKey,
} from '../core/authoring-api.service';
import { PolicyOnboardingService } from '../core/policy-onboarding.service';

interface CreateAttempt {
  title: string;
  author: string;
  language: string;
  idempotencyKey: string;
}

@Component({
  selector: 'app-authoring-create-page',
  imports: [RouterLink],
  templateUrl: './authoring-create-page.component.html',
  styleUrl: './authoring-create-page.component.css',
})
export class AuthoringCreatePageComponent {
  @ViewChild('titleField') private titleField?: ElementRef<HTMLInputElement>;
  @ViewChild('authorField') private authorField?: ElementRef<HTMLInputElement>;
  @ViewChild('languageField') private languageField?: ElementRef<HTMLInputElement>;

  protected readonly policy = inject(PolicyOnboardingService);
  private readonly api = inject(AuthoringApiService);
  protected readonly title = signal('');
  protected readonly author = signal('');
  protected readonly language = signal('vi');
  protected readonly titleError = signal<string | null>(null);
  protected readonly authorError = signal<string | null>(null);
  protected readonly languageError = signal<string | null>(null);
  protected readonly createError = signal<string | null>(null);
  protected readonly isSaving = signal(false);
  protected readonly isUncertain = signal(false);
  protected readonly created = signal<CreatedPrivateManuscript | null>(null);
  protected readonly titleLength = computed(() => [...this.title()].length);
  protected readonly isLocked = computed(() => this.isSaving() || this.isUncertain());
  protected readonly submitLabel = computed(() => {
    if (this.isSaving()) return 'Đang tạo bản thảo…';
    if (this.isUncertain()) return 'Thử xác nhận lại';
    return 'Tạo bản thảo riêng tư';
  });

  private pendingAttempt: CreateAttempt | null = null;

  updateTitle(event: Event): void {
    if (this.isUncertain()) return;
    this.title.set((event.target as HTMLInputElement).value.slice(0, 300));
    this.titleError.set(null);
    this.createError.set(null);
    this.pendingAttempt = null;
  }

  updateAuthor(event: Event): void {
    if (this.isUncertain()) return;
    this.author.set((event.target as HTMLInputElement).value.slice(0, 200));
    this.authorError.set(null);
    this.createError.set(null);
    this.pendingAttempt = null;
  }

  updateLanguage(event: Event): void {
    if (this.isUncertain()) return;
    this.language.set((event.target as HTMLInputElement).value.slice(0, 35));
    this.languageError.set(null);
    this.createError.set(null);
    this.pendingAttempt = null;
  }

  async createDraft(event: Event): Promise<void> {
    event.preventDefault();
    if (
      this.policy.status() !== 'accepted' ||
      this.isSaving() ||
      this.created() ||
      (this.isUncertain() && !this.pendingAttempt)
    ) {
      return;
    }

    let attempt = this.pendingAttempt;
    if (!attempt || !this.isUncertain()) {
      const title = this.title().trim();
      const author = this.author().trim();
      const language = this.language().trim();
      if (!this.validate(title, author, language)) {
        this.focusFirstInvalidField();
        return;
      }
      attempt = {
        title,
        author,
        language,
        idempotencyKey: newAuthoringIdempotencyKey(),
      };
      this.pendingAttempt = attempt;
    }

    this.createError.set(null);
    this.isSaving.set(true);
    try {
      const draft = await this.api.createPrivateManuscript(
        { title: attempt.title, author: attempt.author, language: attempt.language },
        attempt.idempotencyKey,
      );
      this.created.set(draft);
      this.pendingAttempt = null;
      this.isUncertain.set(false);
    } catch (error) {
      if (isUncertainOutcome(error)) {
        this.isUncertain.set(true);
        this.createError.set(
          'Chưa xác nhận được kết quả. Giữ nguyên thông tin và thử lại để kiểm tra đúng yêu cầu này; đừng tải lại trang lúc này.',
        );
      } else {
        this.pendingAttempt = null;
        this.isUncertain.set(false);
        this.createError.set(createErrorMessage(error));
      }
    } finally {
      this.isSaving.set(false);
    }
  }

  createAnother(): void {
    this.created.set(null);
    this.title.set('');
    this.author.set('');
    this.language.set('vi');
    this.clearErrors();
    this.pendingAttempt = null;
  }

  private validate(title: string, author: string, language: string): boolean {
    this.clearErrors();
    if (!title || [...title].length > 300) {
      this.titleError.set('Tên tác phẩm cần có từ 1 đến 300 ký tự.');
      return false;
    }
    if ([...author].length > 200) {
      this.authorError.set('Bút danh tối đa 200 ký tự.');
      return false;
    }
    if ([...language].length < 2 || [...language].length > 35) {
      this.languageError.set('Nhập mã ngôn ngữ từ 2 đến 35 ký tự, ví dụ vi hoặc zh-Hans.');
      return false;
    }
    return true;
  }

  private clearErrors(): void {
    this.titleError.set(null);
    this.authorError.set(null);
    this.languageError.set(null);
    this.createError.set(null);
  }

  private focusFirstInvalidField(): void {
    const field = this.titleError()
      ? this.titleField?.nativeElement
      : this.authorError()
        ? this.authorField?.nativeElement
        : this.languageField?.nativeElement;
    if (!field) return;
    field.scrollIntoView?.({ block: 'center', behavior: 'auto' });
    field.focus({ preventScroll: true });
  }
}

function isUncertainOutcome(error: unknown): boolean {
  return error instanceof AuthoringApiError && (error.status === 0 || error.status >= 500);
}

function createErrorMessage(error: unknown): string {
  if (!(error instanceof AuthoringApiError)) {
    return 'Không thể tạo bản thảo lúc này. Kiểm tra kết nối rồi thử lại.';
  }
  if (error.status === 401) return 'Phiên đăng nhập đã hết hạn. Hãy đăng nhập lại rồi thử lại.';
  if (error.status === 403) {
    return 'Tài khoản cần xác nhận Điều khoản và Chính sách quyền riêng tư hiện hành trước khi tạo bản thảo.';
  }
  if (error.status === 409 && error.code === 'IDEMPOTENCY_KEY_REUSED') {
    return 'Yêu cầu tạo bị xung đột. Thử gửi lại với thông tin hiện tại; hệ thống sẽ dùng một khóa an toàn mới.';
  }
  if (error.status === 400)
    return 'Thông tin chưa hợp lệ. Kiểm tra lại tên, bút danh và mã ngôn ngữ.';
  return 'Không thể tạo bản thảo lúc này. Hãy thử lại sau.';
}
