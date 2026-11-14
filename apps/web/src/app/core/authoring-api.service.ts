import { HttpClient, HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom, timeout } from 'rxjs';

export interface CreatePrivateManuscriptInput {
  title: string;
  author: string;
  language: string;
}

export interface CreatedPrivateManuscript {
  id: string;
  title: string;
  author: string;
  language: string;
  publicationState: 'draft';
  visibility: 'private';
  createdAt: string;
}

const manuscriptsUrl = '/api/v1/authoring/manuscripts';
const csrfUrl = '/api/v1/auth/csrf';
const requestTimeoutMs = 5_000;
const csrfTokenPattern = /^[A-Za-z0-9_-]{43}$/;
const idempotencyKeyPattern = /^[A-Za-z0-9._~-]{16,128}$/;
const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const responseFields = new Set([
  'id',
  'title',
  'author',
  'language',
  'publicationState',
  'visibility',
  'createdAt',
]);

@Injectable({ providedIn: 'root' })
export class AuthoringApiService {
  private readonly http = inject(HttpClient);

  async createPrivateManuscript(
    input: CreatePrivateManuscriptInput,
    idempotencyKey: string,
  ): Promise<CreatedPrivateManuscript> {
    const title = input.title.trim();
    const author = input.author.trim();
    const language = input.language.trim();
    if (
      !isBoundedString(title, 1, 300) ||
      !isBoundedString(author, 0, 200) ||
      !isBoundedString(language, 2, 35) ||
      !idempotencyKeyPattern.test(idempotencyKey)
    ) {
      throw new AuthoringApiError(400, 'INVALID_MANUSCRIPT');
    }

    try {
      const headers = (await this.csrfHeaders())
        .set('Content-Type', 'application/json')
        .set('Idempotency-Key', idempotencyKey);
      const response = await firstValueFrom(
        this.http
          .post<unknown>(
            manuscriptsUrl,
            { title, author, language },
            { headers, observe: 'response' },
          )
          .pipe(timeout(requestTimeoutMs)),
      );
      if (response.status !== 201) throw invalidResponse();
      return parseCreatedManuscript(response.body);
    } catch (error) {
      throw toAuthoringApiError(error);
    }
  }

  private async csrfHeaders(): Promise<HttpHeaders> {
    const response: unknown = await firstValueFrom(
      this.http.get<unknown>(csrfUrl).pipe(timeout(requestTimeoutMs)),
    );
    if (
      !isRecord(response) ||
      typeof response['token'] !== 'string' ||
      !csrfTokenPattern.test(response['token'])
    ) {
      throw new AuthoringApiError(503, 'CSRF_UNAVAILABLE');
    }
    return new HttpHeaders({ 'X-CSRF-Token': response['token'] });
  }
}

export class AuthoringApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string | null,
  ) {
    super(code ?? 'AUTHORING_UNAVAILABLE');
    this.name = 'AuthoringApiError';
  }
}

export function newAuthoringIdempotencyKey(): string {
  return globalThis.crypto.randomUUID();
}

function parseCreatedManuscript(value: unknown): CreatedPrivateManuscript {
  if (
    !isRecord(value) ||
    Object.keys(value).some((key) => !responseFields.has(key)) ||
    Object.keys(value).length !== responseFields.size ||
    !isUuid(value['id']) ||
    !isBoundedString(value['title'], 1, 300) ||
    !isBoundedString(value['author'], 0, 200) ||
    !isBoundedString(value['language'], 2, 35) ||
    value['publicationState'] !== 'draft' ||
    value['visibility'] !== 'private' ||
    !isInstant(value['createdAt'])
  ) {
    throw invalidResponse();
  }
  return value as unknown as CreatedPrivateManuscript;
}

function toAuthoringApiError(error: unknown): AuthoringApiError {
  if (error instanceof AuthoringApiError) return error;
  if (error instanceof HttpErrorResponse) {
    const code =
      isRecord(error.error) && typeof error.error['code'] === 'string'
        ? error.error['code'].slice(0, 80)
        : null;
    return new AuthoringApiError(error.status, code);
  }
  return new AuthoringApiError(0, null);
}

function invalidResponse(): AuthoringApiError {
  return new AuthoringApiError(502, 'INVALID_MANUSCRIPT_RESPONSE');
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isUuid(value: unknown): value is string {
  return typeof value === 'string' && uuidPattern.test(value);
}

function isBoundedString(value: unknown, min: number, max: number): value is string {
  return typeof value === 'string' && [...value].length >= min && [...value].length <= max;
}

function isInstant(value: unknown): value is string {
  return typeof value === 'string' && value.length <= 64 && Number.isFinite(Date.parse(value));
}
