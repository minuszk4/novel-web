import { HttpClient, HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom, timeout } from 'rxjs';

const preferencesUrl = '/api/v1/me/privacy-preferences';
const csrfUrl = '/api/v1/auth/csrf';
const csrfPattern = /^[A-Za-z0-9_-]{43}$/;
const etagPattern = /^"privacy-preferences-v(0|[1-9][0-9]{0,18})"$/;
const requestTimeoutMs = 10_000;

export interface AccountPrivacyPreferences {
  analyticsEnabled: boolean;
  contentProviderSharingEnabled: boolean;
  version: number;
  updatedAt: string | null;
}

export interface VersionedAccountPrivacyPreferences {
  preferences: AccountPrivacyPreferences;
  etag: string;
}

export class AccountPrivacyPreferencesApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
  ) {
    super(code);
  }
}

@Injectable({ providedIn: 'root' })
export class AccountPrivacyPreferencesApiService {
  private readonly http = inject(HttpClient);

  async get(): Promise<VersionedAccountPrivacyPreferences> {
    try {
      const response = await firstValueFrom(
        this.http
          .get<unknown>(preferencesUrl, { observe: 'response' })
          .pipe(timeout(requestTimeoutMs)),
      );
      return parseVersionedPreferences(response.body, response.headers.get('ETag'));
    } catch (error) {
      throw mapError(error);
    }
  }

  async update(
    preferences: Pick<
      AccountPrivacyPreferences,
      'analyticsEnabled' | 'contentProviderSharingEnabled'
    >,
    etag: string,
  ): Promise<VersionedAccountPrivacyPreferences> {
    if (!etagPattern.test(etag)) {
      throw new AccountPrivacyPreferencesApiError(400, 'IF_MATCH_INVALID');
    }
    try {
      const response = await firstValueFrom(
        this.http
          .put<unknown>(preferencesUrl, preferences, {
            headers: (await this.csrfHeaders()).set('If-Match', etag),
            observe: 'response',
          })
          .pipe(timeout(requestTimeoutMs)),
      );
      return parseVersionedPreferences(response.body, response.headers.get('ETag'));
    } catch (error) {
      throw mapError(error);
    }
  }

  private async csrfHeaders(): Promise<HttpHeaders> {
    try {
      const response: unknown = await firstValueFrom(
        this.http.get<unknown>(csrfUrl).pipe(timeout(requestTimeoutMs)),
      );
      if (
        !isObject(response) ||
        typeof response['token'] !== 'string' ||
        !csrfPattern.test(response['token'])
      ) {
        throw new AccountPrivacyPreferencesApiError(503, 'CSRF_UNAVAILABLE');
      }
      return new HttpHeaders({ 'X-CSRF-Token': response['token'] });
    } catch (error) {
      throw mapError(error);
    }
  }
}

function parseVersionedPreferences(
  value: unknown,
  etag: string | null,
): VersionedAccountPrivacyPreferences {
  if (
    !isObject(value) ||
    Object.keys(value).length !== 4 ||
    !Object.hasOwn(value, 'analyticsEnabled') ||
    !Object.hasOwn(value, 'contentProviderSharingEnabled') ||
    !Object.hasOwn(value, 'version') ||
    !Object.hasOwn(value, 'updatedAt') ||
    typeof value['analyticsEnabled'] !== 'boolean' ||
    typeof value['contentProviderSharingEnabled'] !== 'boolean' ||
    !Number.isSafeInteger(value['version']) ||
    Number(value['version']) < 0 ||
    !(value['updatedAt'] === null || isTimestamp(value['updatedAt'])) ||
    typeof etag !== 'string' ||
    !etagPattern.test(etag) ||
    Number(etag.match(etagPattern)?.[1]) !== value['version'] ||
    (value['version'] === 0) !== (value['updatedAt'] === null)
  ) {
    throw new AccountPrivacyPreferencesApiError(503, 'INVALID_PRIVACY_PREFERENCES_RESPONSE');
  }
  return {
    preferences: {
      analyticsEnabled: value['analyticsEnabled'],
      contentProviderSharingEnabled: value['contentProviderSharingEnabled'],
      version: Number(value['version']),
      updatedAt: value['updatedAt'] as string | null,
    },
    etag,
  };
}

function isTimestamp(value: unknown): value is string {
  return typeof value === 'string' && Number.isFinite(Date.parse(value));
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function mapError(error: unknown): AccountPrivacyPreferencesApiError {
  if (error instanceof AccountPrivacyPreferencesApiError) return error;
  if (error instanceof HttpErrorResponse) {
    const problem = isObject(error.error) ? error.error : {};
    const code = typeof problem['code'] === 'string' ? problem['code'] : 'REQUEST_FAILED';
    return new AccountPrivacyPreferencesApiError(error.status, code);
  }
  return new AccountPrivacyPreferencesApiError(0, 'NETWORK_UNAVAILABLE');
}
