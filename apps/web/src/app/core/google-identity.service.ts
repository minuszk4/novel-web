import { DOCUMENT } from '@angular/common';
import { Injectable, inject } from '@angular/core';

export interface GoogleIdentityConfiguration {
  client_id: string;
  ux_mode: 'redirect';
  login_uri: string;
  nonce: string;
  auto_select: false;
}

export interface GoogleButtonConfiguration {
  type: 'standard';
  theme: 'outline';
  size: 'large';
  text: 'signin_with';
  shape: 'rectangular';
  logo_alignment: 'left';
  width: number;
  locale: 'vi' | 'en';
}

export interface GoogleAccountsId {
  initialize(configuration: GoogleIdentityConfiguration): void;
  renderButton(parent: HTMLElement, configuration: GoogleButtonConfiguration): void;
}

declare global {
  interface Window {
    google?: { accounts?: { id?: GoogleAccountsId } };
  }
}

@Injectable({ providedIn: 'root' })
export class GoogleIdentityService {
  private readonly document = inject(DOCUMENT);
  private loadPromise?: Promise<GoogleAccountsId>;

  load(): Promise<GoogleAccountsId> {
    const available = this.getIdentityApi();
    if (available) return Promise.resolve(available);
    if (this.loadPromise) return this.loadPromise;

    this.loadPromise = new Promise<GoogleAccountsId>((resolve, reject) => {
      const script = this.document.createElement('script');
      script.src = 'https://accounts.google.com/gsi/client';
      script.async = true;
      script.defer = true;
      script.dataset['novelGoogleIdentity'] = 'true';

      let settled = false;
      const timeoutId = window.setTimeout(
        () => finish(new Error('Google Identity Services timed out.')),
        10_000,
      );
      const finish = (error?: Error) => {
        if (settled) return;
        settled = true;
        window.clearTimeout(timeoutId);
        script.removeEventListener('load', onLoad);
        script.removeEventListener('error', onError);
        if (error) {
          script.remove();
          this.loadPromise = undefined;
          reject(error);
          return;
        }
        const identityApi = this.getIdentityApi();
        if (!identityApi) {
          script.remove();
          this.loadPromise = undefined;
          reject(new Error('Google Identity Services did not initialize.'));
          return;
        }
        resolve(identityApi);
      };
      const onLoad = () => finish();
      const onError = () => finish(new Error('Google Identity Services failed to load.'));

      script.addEventListener('load', onLoad, { once: true });
      script.addEventListener('error', onError, { once: true });
      this.document.head.append(script);
    });

    return this.loadPromise;
  }

  private getIdentityApi(): GoogleAccountsId | undefined {
    return this.document.defaultView?.google?.accounts?.id;
  }
}
