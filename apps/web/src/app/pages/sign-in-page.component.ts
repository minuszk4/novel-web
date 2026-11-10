import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import {
  AfterViewInit,
  Component,
  ElementRef,
  OnDestroy,
  ViewChild,
  inject,
  signal,
} from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { firstValueFrom, timeout } from 'rxjs';
import { GoogleIdentityService, type GoogleAccountsId } from '../core/google-identity.service';

interface GoogleSignInBootstrap {
  clientId: string;
  loginUri: string;
  nonce: string;
  locale: 'vi' | 'en';
  attemptExpiresAt: string;
}

type GoogleSignInState = 'loading' | 'ready' | 'unavailable' | 'rate-limited' | 'error';

@Component({
  selector: 'app-sign-in-page',
  imports: [RouterLink],
  templateUrl: './sign-in-page.component.html',
})
export class SignInPageComponent implements AfterViewInit, OnDestroy {
  @ViewChild('googleButtonHost', { static: true })
  private googleButtonHost!: ElementRef<HTMLElement>;

  private readonly http = inject(HttpClient);
  private readonly route = inject(ActivatedRoute);
  private readonly googleIdentity = inject(GoogleIdentityService);
  private destroyed = false;

  readonly googleState = signal<GoogleSignInState>('loading');
  readonly googleMessage = signal('Đang chuẩn bị đăng nhập an toàn với Google.');
  readonly googleRetryable = signal(false);

  ngAfterViewInit(): void {
    void this.prepareGoogleSignIn();
  }

  ngOnDestroy(): void {
    this.destroyed = true;
  }

  async retryGoogleSignIn(): Promise<void> {
    await this.prepareGoogleSignIn();
  }

  private async prepareGoogleSignIn(): Promise<void> {
    this.googleState.set('loading');
    this.googleMessage.set('Đang chuẩn bị đăng nhập an toàn với Google.');
    this.googleRetryable.set(false);
    this.googleButtonHost.nativeElement.replaceChildren();

    try {
      const bootstrap = await firstValueFrom(
        this.http
          .get<unknown>('/api/v1/auth/google/bootstrap', {
            params: { returnTo: this.safeReturnTo(), locale: 'vi' },
          })
          .pipe(timeout(5_000)),
      );
      if (!isGoogleSignInBootstrap(bootstrap)) {
        throw new Error('The sign-in bootstrap response is invalid.');
      }

      const google = await this.googleIdentity.load();
      if (this.destroyed) return;

      google.initialize({
        client_id: bootstrap.clientId,
        ux_mode: 'redirect',
        login_uri: bootstrap.loginUri,
        nonce: bootstrap.nonce,
        auto_select: false,
      });
      const width = Math.floor(
        Math.min(400, Math.max(120, this.googleButtonHost.nativeElement.clientWidth)),
      );
      google.renderButton(this.googleButtonHost.nativeElement, {
        type: 'standard',
        theme: 'outline',
        size: 'large',
        text: 'signin_with',
        shape: 'rectangular',
        logo_alignment: 'left',
        width,
        locale: bootstrap.locale,
      });

      this.googleState.set('ready');
      this.googleMessage.set(
        'Chọn nút Google để tiếp tục; bạn sẽ quay lại Novel sau khi đăng nhập.',
      );
    } catch (error) {
      if (this.destroyed) return;
      const status = error instanceof HttpErrorResponse ? error.status : 0;
      if (status === 429) {
        this.googleState.set('rate-limited');
        this.googleMessage.set(
          'Bạn vừa bắt đầu quá nhiều lần đăng nhập. Hãy chờ một chút rồi thử lại.',
        );
      } else if (status === 503) {
        this.googleState.set('unavailable');
        this.googleMessage.set(
          'Đăng nhập Google chưa được cấu hình hoặc đang tạm thời không khả dụng.',
        );
      } else {
        this.googleState.set('error');
        this.googleMessage.set(
          'Chưa tải được dịch vụ đăng nhập Google. Kiểm tra kết nối rồi thử lại.',
        );
      }
      this.googleRetryable.set(true);
    }
  }

  private safeReturnTo(): string {
    const candidate = this.route.snapshot.queryParamMap.get('returnTo');
    if (
      !candidate ||
      candidate.length > 512 ||
      !candidate.startsWith('/') ||
      candidate.startsWith('//') ||
      candidate.includes('\\') ||
      /[\u0000-\u001f\u007f]/.test(candidate)
    ) {
      return '/app/home';
    }

    const rawPath = candidate.split(/[?#]/, 1)[0];
    if (containsUnsafePathSyntax(rawPath)) return '/app/home';

    try {
      const target = new URL(candidate, window.location.origin);
      if (target.origin !== window.location.origin) {
        return '/app/home';
      }
      return `${target.pathname}${target.search}${target.hash}`;
    } catch {
      return '/app/home';
    }
  }
}

function isGoogleSignInBootstrap(value: unknown): value is GoogleSignInBootstrap {
  if (!value || typeof value !== 'object') return false;
  const bootstrap = value as Partial<GoogleSignInBootstrap>;
  if (
    typeof bootstrap.clientId !== 'string' ||
    bootstrap.clientId.trim().length === 0 ||
    typeof bootstrap.nonce !== 'string' ||
    !/^[A-Za-z0-9_-]{43}$/.test(bootstrap.nonce) ||
    (bootstrap.locale !== 'vi' && bootstrap.locale !== 'en') ||
    typeof bootstrap.attemptExpiresAt !== 'string' ||
    !Number.isFinite(Date.parse(bootstrap.attemptExpiresAt)) ||
    Date.parse(bootstrap.attemptExpiresAt) <= Date.now() ||
    typeof bootstrap.loginUri !== 'string'
  ) {
    return false;
  }

  try {
    const callback = new URL(bootstrap.loginUri);
    const secure = callback.protocol === 'https:';
    const localDevelopment = callback.protocol === 'http:' && callback.hostname === 'localhost';
    return (
      (secure || localDevelopment) &&
      callback.pathname === '/api/v1/auth/google/credential' &&
      !callback.username &&
      !callback.password &&
      !callback.search &&
      !callback.hash
    );
  } catch {
    return false;
  }
}

function containsUnsafePathSyntax(pathname: string): boolean {
  let decodedPath: string;
  try {
    decodedPath = decodeURIComponent(pathname);
  } catch {
    return true;
  }
  return [pathname, decodedPath].some(
    (path) =>
      path.startsWith('//') ||
      path.includes('\\') ||
      /[\u0000-\u001f\u007f]/.test(path) ||
      path.split('/').some((segment) => segment === '.' || segment === '..'),
  );
}
