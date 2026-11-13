import { HttpClient, HttpErrorResponse, HttpHeaders, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom, timeout } from 'rxjs';

export interface CultivationRank {
  key: string;
  group: string;
  name: string;
  thresholdXp: number;
  sortOrder: number;
}

export interface CultivationProfile {
  enabled: boolean;
  profileVersion: number;
  totalXp: number;
  todayXpEarned: number;
  currentRank: CultivationRank;
  nextRank: CultivationRank | null;
  xpToNextRank: number;
  progressRatio: number;
  rankCatalogVersion: string;
  policyVersion: string;
  locale: string;
  xpPerActiveMinute: number;
  dailyXpCap: number;
  nextDailyResetAt: string;
  rankCatalog: CultivationRank[];
}

export interface CultivationDailyActivity {
  activityDate: string;
  xpEarned: number;
}

export interface CultivationActivityPage {
  items: CultivationDailyActivity[];
  nextCursor: string | null;
}

export class CultivationApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code: string | null,
  ) {
    super(message);
    this.name = 'CultivationApiError';
  }
}

const profileUrl = '/api/v1/me/cultivation';
const settingsUrl = `${profileUrl}/settings`;
const activityUrl = `${profileUrl}/activity`;
const csrfUrl = '/api/v1/auth/csrf';
const requestTimeoutMs = 10_000;
const csrfTokenPattern = /^[A-Za-z0-9_-]{43}$/;

@Injectable({ providedIn: 'root' })
export class CultivationApiService {
  private readonly http = inject(HttpClient);

  async getProfile(): Promise<CultivationProfile> {
    try {
      const response: unknown = await firstValueFrom(
        this.http.get<unknown>(profileUrl).pipe(timeout(requestTimeoutMs)),
      );
      return parseProfile(response);
    } catch (error) {
      throw toCultivationApiError(error);
    }
  }

  async getActivity(cursor?: string, limit = 30): Promise<CultivationActivityPage> {
    if (
      (cursor !== undefined && (!cursor || cursor.length > 256)) ||
      !Number.isInteger(limit) ||
      limit < 1 ||
      limit > 100
    ) {
      throw new CultivationApiError('Invalid cultivation activity query.', 400, 'INVALID_QUERY');
    }
    try {
      let params = new HttpParams().set('limit', limit);
      if (cursor) params = params.set('cursor', cursor);
      const response: unknown = await firstValueFrom(
        this.http.get<unknown>(activityUrl, { params }).pipe(timeout(requestTimeoutMs)),
      );
      return parseActivityPage(response, limit);
    } catch (error) {
      throw toCultivationApiError(error);
    }
  }

  async setEnabled(enabled: boolean): Promise<CultivationProfile> {
    if (typeof enabled !== 'boolean') {
      throw new CultivationApiError('Invalid cultivation setting.', 400, 'INVALID_SETTING');
    }
    try {
      const csrf = await firstValueFrom(
        this.http.get<unknown>(csrfUrl).pipe(timeout(requestTimeoutMs)),
      );
      if (!isCsrfResponse(csrf)) {
        throw new CultivationApiError('The server returned an invalid CSRF token.', -1, null);
      }
      const response: unknown = await firstValueFrom(
        this.http
          .put<unknown>(
            settingsUrl,
            { enabled },
            {
              headers: new HttpHeaders({ 'X-CSRF-Token': csrf.token }),
            },
          )
          .pipe(timeout(requestTimeoutMs)),
      );
      return parseProfile(response);
    } catch (error) {
      throw toCultivationApiError(error);
    }
  }
}

function parseProfile(value: unknown): CultivationProfile {
  if (!isRecord(value)) throw invalidResponse();
  const rankCatalog = value['rankCatalog'];
  if (!Array.isArray(rankCatalog) || rankCatalog.length < 1 || rankCatalog.length > 100) {
    throw invalidResponse();
  }
  const parsedRanks = rankCatalog.map(parseRank);
  if (parsedRanks.some((rank, index) => rank.sortOrder !== index)) throw invalidResponse();
  const currentRank = parseRank(value['currentRank']);
  const nextRank = value['nextRank'] === null ? null : parseRank(value['nextRank']);
  const resetAt = value['nextDailyResetAt'];
  if (
    typeof value['enabled'] !== 'boolean' ||
    !isNonNegativeInteger(value['profileVersion']) ||
    !isNonNegativeInteger(value['totalXp']) ||
    !isNonNegativeInteger(value['todayXpEarned']) ||
    !isNonNegativeInteger(value['xpToNextRank']) ||
    typeof value['progressRatio'] !== 'number' ||
    !Number.isFinite(value['progressRatio']) ||
    value['progressRatio'] < 0 ||
    value['progressRatio'] > 1 ||
    typeof value['rankCatalogVersion'] !== 'string' ||
    typeof value['policyVersion'] !== 'string' ||
    value['locale'] !== 'vi-VN' ||
    !isPositiveInteger(value['xpPerActiveMinute']) ||
    !isPositiveInteger(value['dailyXpCap']) ||
    typeof resetAt !== 'string' ||
    !Number.isFinite(Date.parse(resetAt)) ||
    !parsedRanks.some((rank) => rank.key === currentRank.key) ||
    (nextRank !== null && !parsedRanks.some((rank) => rank.key === nextRank.key))
  ) {
    throw invalidResponse();
  }
  return {
    enabled: value['enabled'],
    profileVersion: value['profileVersion'],
    totalXp: value['totalXp'],
    todayXpEarned: value['todayXpEarned'],
    currentRank,
    nextRank,
    xpToNextRank: value['xpToNextRank'],
    progressRatio: value['progressRatio'],
    rankCatalogVersion: value['rankCatalogVersion'],
    policyVersion: value['policyVersion'],
    locale: value['locale'],
    xpPerActiveMinute: value['xpPerActiveMinute'],
    dailyXpCap: value['dailyXpCap'],
    nextDailyResetAt: resetAt,
    rankCatalog: parsedRanks,
  };
}

function parseRank(value: unknown): CultivationRank {
  if (
    !isRecord(value) ||
    typeof value['key'] !== 'string' ||
    typeof value['group'] !== 'string' ||
    typeof value['name'] !== 'string' ||
    !isNonNegativeInteger(value['thresholdXp']) ||
    !isNonNegativeInteger(value['sortOrder'])
  ) {
    throw invalidResponse();
  }
  return {
    key: value['key'],
    group: value['group'],
    name: value['name'],
    thresholdXp: value['thresholdXp'],
    sortOrder: value['sortOrder'],
  };
}

function parseActivityPage(value: unknown, limit: number): CultivationActivityPage {
  if (!isRecord(value) || !Array.isArray(value['items']) || value['items'].length > limit) {
    throw invalidResponse();
  }
  const items = value['items'].map((item) => {
    if (
      !isRecord(item) ||
      Object.keys(item).some((key) => key !== 'activityDate' && key !== 'xpEarned') ||
      typeof item['activityDate'] !== 'string' ||
      !/^\d{4}-\d{2}-\d{2}$/.test(item['activityDate']) ||
      !Number.isFinite(Date.parse(`${item['activityDate']}T00:00:00Z`)) ||
      !isPositiveInteger(item['xpEarned'])
    ) {
      throw invalidResponse();
    }
    return { activityDate: item['activityDate'], xpEarned: item['xpEarned'] };
  });
  const nextCursor = value['nextCursor'];
  if (
    nextCursor !== null &&
    (typeof nextCursor !== 'string' || !nextCursor || nextCursor.length > 256)
  ) {
    throw invalidResponse();
  }
  return { items, nextCursor };
}

function isCsrfResponse(value: unknown): value is { token: string } {
  return (
    isRecord(value) && typeof value['token'] === 'string' && csrfTokenPattern.test(value['token'])
  );
}

function toCultivationApiError(error: unknown): CultivationApiError {
  if (error instanceof CultivationApiError) return error;
  if (error instanceof HttpErrorResponse) {
    const body = error.error;
    const code = isRecord(body) && typeof body['code'] === 'string' ? body['code'] : null;
    return new CultivationApiError('Cultivation request failed.', error.status, code);
  }
  return new CultivationApiError('Cultivation request failed.', -1, null);
}

function invalidResponse(): CultivationApiError {
  return new CultivationApiError(
    'The server returned an invalid cultivation response.',
    -1,
    'INVALID_RESPONSE',
  );
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isNonNegativeInteger(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0;
}

function isPositiveInteger(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value > 0;
}
