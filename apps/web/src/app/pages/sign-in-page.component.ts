import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { firstValueFrom, timeout } from 'rxjs';
import { FirebaseWebConfiguration, GoogleIdentityService } from '../core/google-identity.service';

interface FirebaseSignInBootstrap {
  firebaseConfig: FirebaseWebConfiguration;
  state: string;
  locale: 'vi' | 'en';
  attemptExpiresAt: string;
}

interface FirebaseSessionResponse {
  returnTo: string;
}

type GoogleSignInState = 'loading' | 'ready' | 'unavailable' | 'rate-limited' | 'error';

@Component({
  selector: 'app-sign-in-page',
  imports: [RouterLink],
  templateUrl: './sign-in-page.component.html',
})
export class SignInPageComponent {
  private readonly http = inject(HttpClient);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly googleIdentity = inject(GoogleIdentityService);
  private activeState?: string;

  readonly googleState = signal<GoogleSignInState>('loading');
  readonly googleMessage = signal('Đang chuẩn bị đăng nhập an toàn với Google.');
  readonly googleRetryable = signal(false);

  constructor() {
    void this.prepareFirebaseSignIn();
  }

  async retryGoogleSignIn(): Promise<void> {
    await this.prepareFirebaseSignIn();
  }

  startGoogleSignIn(): void {
    if (this.googleState() !== 'ready' || !this.activeState) return;

    const state = this.activeState;
    this.googleState.set('loading');
    this.googleMessage.set('Đang mở cửa sổ đăng nhập Google an toàn…');
    this.googleRetryable.set(false);

    try {
      // Call immediately inside the click handler so browsers preserve popup activation.
      void this.completeSignIn(this.googleIdentity.signInWithGoogle(), state);
    } catch {
      this.googleState.set('error');
      this.googleMessage.set('Không mở được cửa sổ đăng nhập. Hãy thử lại.');
      this.googleRetryable.set(true);
    }
  }

  private async prepareFirebaseSignIn(): Promise<void> {
    this.googleState.set('loading');
    this.googleMessage.set('Đang chuẩn bị đăng nhập an toàn với Google.');
    this.googleRetryable.set(false);
    this.activeState = undefined;

    try {
      const bootstrap = await firstValueFrom(
        this.http
          .get<unknown>('/api/v1/auth/firebase/bootstrap', {
            params: { returnTo: this.safeReturnTo(), locale: 'vi' },
          })
          .pipe(timeout(5_000)),
      );
      if (!isFirebaseSignInBootstrap(bootstrap)) {
        throw new Error('The Firebase sign-in bootstrap response is invalid.');
      }

      await this.googleIdentity.initialize(bootstrap.firebaseConfig);
      this.activeState = bootstrap.state;
      this.googleState.set('ready');
      this.googleMessage.set(
        'Chọn nút bên dưới để đăng nhập; bạn sẽ quay lại Novel sau khi xác minh.',
      );
    } catch (error) {
      const status = error instanceof HttpErrorResponse ? error.status : 0;
      if (status === 429) {
        this.googleState.set('rate-limited');
        this.googleMessage.set(
          'Bạn vừa bắt đầu quá nhiều lần đăng nhập. Hãy chờ một chút rồi thử lại.',
        );
      } else if (status === 503) {
        this.googleState.set('unavailable');
        this.googleMessage.set(
          'Đăng nhập Google qua Firebase chưa được cấu hình hoặc đang tạm thời không khả dụng.',
        );
      } else {
        this.googleState.set('error');
        this.googleMessage.set(
          'Chưa chuẩn bị được Firebase Authentication. Kiểm tra kết nối rồi thử lại.',
        );
      }
      this.googleRetryable.set(true);
    }
  }

  private async completeSignIn(signIn: Promise<string>, state: string): Promise<void> {
    try {
      const idToken = await signIn;
      const response = await firstValueFrom(
        this.http
          .post<unknown>('/api/v1/auth/firebase/session', { idToken, state })
          .pipe(timeout(10_000)),
      );
      const returnTo = safeReturnToFromResponse(response);
      this.activeState = undefined;
      await this.googleIdentity.clearTemporarySession().catch(() => undefined);
      await this.router.navigateByUrl(returnTo);
    } catch (error) {
      await this.googleIdentity.clearTemporarySession().catch(() => undefined);
      this.activeState = undefined;
      const status = error instanceof HttpErrorResponse ? error.status : 0;
      const code = error instanceof Error ? (error as Error & { code?: string }).code : undefined;
      if (code === 'auth/popup-closed-by-user') {
        this.googleState.set('error');
        this.googleMessage.set('Cửa sổ đăng nhập đã được đóng. Bạn có thể thử lại.');
      } else if (status === 401) {
        this.googleState.set('error');
        this.googleMessage.set('Không xác minh được tài khoản Google. Hãy bắt đầu lại.');
      } else {
        this.googleState.set('error');
        this.googleMessage.set('Đăng nhập chưa hoàn tất. Hãy thử lại sau ít phút.');
      }
      this.googleRetryable.set(true);
    }
  }

  private safeReturnTo(): string {
    const candidate = this.route.snapshot.queryParamMap.get('returnTo');
    if (!candidate || !isSafeRelativePath(candidate)) return '/app/home';

    try {
      const target = new URL(candidate, window.location.origin);
      if (target.origin !== window.location.origin) return '/app/home';
      return `${target.pathname}${target.search}${target.hash}`;
    } catch {
      return '/app/home';
    }
  }
}

function isFirebaseSignInBootstrap(value: unknown): value is FirebaseSignInBootstrap {
  if (!value || typeof value !== 'object') return false;
  const bootstrap = value as Partial<FirebaseSignInBootstrap>;
  const config = bootstrap.firebaseConfig;
  if (
    !config ||
    typeof config.apiKey !== 'string' ||
    config.apiKey.trim().length < 20 ||
    typeof config.authDomain !== 'string' ||
    !/^[A-Za-z0-9.-]{1,253}$/.test(config.authDomain) ||
    typeof config.projectId !== 'string' ||
    !/^[a-z0-9-]{1,30}$/.test(config.projectId) ||
    typeof config.appId !== 'string' ||
    config.appId.trim().length < 10 ||
    typeof bootstrap.state !== 'string' ||
    !/^[A-Za-z0-9_-]{43}$/.test(bootstrap.state) ||
    (bootstrap.locale !== 'vi' && bootstrap.locale !== 'en') ||
    typeof bootstrap.attemptExpiresAt !== 'string' ||
    !Number.isFinite(Date.parse(bootstrap.attemptExpiresAt)) ||
    Date.parse(bootstrap.attemptExpiresAt) <= Date.now()
  ) {
    return false;
  }
  return true;
}

function safeReturnToFromResponse(value: unknown): string {
  if (typeof value !== 'object' || value === null) return '/app/home';
  const returnTo = (value as Partial<FirebaseSessionResponse>).returnTo;
  if (typeof returnTo !== 'string' || !isSafeRelativePath(returnTo)) return '/app/home';
  return returnTo;
}

function isSafeRelativePath(candidate: string): boolean {
  if (
    candidate.length > 512 ||
    !candidate.startsWith('/') ||
    candidate.startsWith('//') ||
    candidate.includes('\\') ||
    /[\u0000-\u001f\u007f]/.test(candidate)
  ) {
    return false;
  }

  const rawPath = candidate.split(/[?#]/, 1)[0];
  let decodedPath: string;
  try {
    decodedPath = decodeURIComponent(rawPath);
  } catch {
    return false;
  }
  return [rawPath, decodedPath].every(
    (path) =>
      !path.startsWith('//') &&
      !path.includes('\\') &&
      !/[\u0000-\u001f\u007f]/.test(path) &&
      !path.split('/').some((segment) => segment === '.' || segment === '..'),
  );
}
