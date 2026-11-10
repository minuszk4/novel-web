import { HttpClient, HttpErrorResponse, HttpHeaders, HttpResponse } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom, timeout } from 'rxjs';
import { AccountLibrarySummary, LibraryApiError, LibraryApiService } from './library-api.service';

const rightsUrl = '/api/v1/imports/rights-statement/current';
const rightsAcceptanceUrl = '/api/v1/imports/rights-statement/acceptance';
const importsUrl = '/api/v1/imports';
const csrfUrl = '/api/v1/auth/csrf';
export const maxAccountImportBytes = 50 * 1024 * 1024;
const previewPageSize = 100;
const apiTimeoutMs = 10_000;
const uploadTimeoutMs = 120_000;
const parserPollMs = 1_000;
const parserMaxWaitMs = 120_000;

export interface ImportRightsStatement {
  version: string;
  effectiveAt: string;
  displayText: string;
}

export interface ImportWarning {
  code: string;
  message: string;
}

export interface ImportChapterPreview {
  id: string;
  ordinal: number;
  title: string;
}

export interface ImportPreview {
  importId: string;
  version: number;
  title: string;
  author: string;
  chapterCount: number;
  language: string;
  chapters: ImportChapterPreview[];
  nextCursor: string | null;
  warnings: ImportWarning[];
}

export interface CommittedImportWork {
  id: string;
  libraryId: string;
  title: string;
  author: string;
  language: string;
  visibility: 'private';
  chapterCount: number;
  createdAt: string;
}

export type AccountImportState =
  | 'idle'
  | 'loading'
  | 'ready'
  | 'accepting'
  | 'creating'
  | 'uploading'
  | 'parsing'
  | 'loading-preview'
  | 'review'
  | 'saving'
  | 'committing'
  | 'committed'
  | 'error';

export class AccountImportError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code: string | null,
    readonly retryable: boolean,
  ) {
    super(message);
    this.name = 'AccountImportError';
  }
}

@Injectable({ providedIn: 'root' })
export class AccountImportService {
  private readonly http = inject(HttpClient);
  private readonly libraryApi = inject(LibraryApiService);

  readonly selectedFile = signal<File | null>(null);
  readonly rightsStatement = signal<ImportRightsStatement | null>(null);
  readonly libraries = signal<AccountLibrarySummary[]>([]);
  readonly selectedLibraryId = signal<string | null>(null);
  readonly rightsConfirmed = signal(false);
  readonly state = signal<AccountImportState>('idle');
  readonly errorMessage = signal<string | null>(null);
  readonly retryable = signal(false);
  readonly stageMessage = signal<string | null>(null);
  readonly job = signal<ImportJob | null>(null);
  readonly preview = signal<ImportPreview | null>(null);
  readonly previewEtag = signal<string | null>(null);
  readonly committedWork = signal<CommittedImportWork | null>(null);
  readonly fileMetadata = computed(() => {
    const file = this.selectedFile();
    if (!file) return null;
    return {
      name: file.name,
      size: file.size,
      type: file.type || 'Không xác định',
      extension: file.name.split('.').pop()?.toLowerCase() ?? '',
    };
  });
  readonly isBusy = computed(() => [
    'loading', 'accepting', 'creating', 'uploading', 'parsing', 'loading-preview', 'saving', 'committing',
  ].includes(this.state()));
  readonly canStart = computed(() => !!this.selectedFile()
    && this.rightsConfirmed()
    && !!this.rightsStatement()
    && !!this.selectedLibraryId()
    && this.libraries().some((library) => library.id === this.selectedLibraryId())
    && !this.isBusy());

  private receiptId: string | null = null;
  private acceptanceKey = newUuid();
  private commitKey = newUuid();
  private uploadTarget: UploadTarget | null = null;

  async loadPrerequisites(): Promise<void> {
    this.state.set('loading');
    this.errorMessage.set(null);
    this.retryable.set(false);
    this.stageMessage.set('Đang tải thư viện và hướng dẫn quyền nhập từ máy chủ…');
    try {
      const [statementResult, librariesResult] = await Promise.allSettled([
        this.getCurrentRightsStatement(),
        this.libraryApi.listLibraries(),
      ]);
      const nextStatement = statementResult.status === 'fulfilled' ? statementResult.value : null;
      const previousStatement = this.rightsStatement();
      if (previousStatement?.version !== nextStatement?.version
          || previousStatement?.effectiveAt !== nextStatement?.effectiveAt
          || previousStatement?.displayText !== nextStatement?.displayText) {
        this.rightsConfirmed.set(false);
        this.receiptId = null;
        this.acceptanceKey = newUuid();
      }
      if (statementResult.status === 'fulfilled') this.rightsStatement.set(statementResult.value);
      else this.rightsStatement.set(null);
      if (librariesResult.status === 'fulfilled') {
        const libraries = librariesResult.value.items;
        this.libraries.set(libraries);
        const current = this.selectedLibraryId();
        const preferred = libraries.find((library) => library.isDefault) ?? libraries[0] ?? null;
        this.selectedLibraryId.set(current && libraries.some((library) => library.id === current)
          ? current : preferred?.id ?? null);
      } else {
        this.libraries.set([]);
        this.selectedLibraryId.set(null);
      }
      this.stageMessage.set(null);
      if (statementResult.status === 'rejected') {
        this.setError(toImportError(statementResult.reason));
      } else if (librariesResult.status === 'rejected') {
        this.setError(toImportError(librariesResult.reason));
      } else if (librariesResult.value.items.length === 0) {
        this.state.set('ready');
        this.setError(new AccountImportError(
          'Chưa có thư viện riêng để nhận tác phẩm. Tải lại sau khi tài khoản có thư viện.', 404, 'LIBRARY_NOT_FOUND', false,
        ));
      } else {
        this.state.set('ready');
      }
    } catch (error) {
      this.setError(toImportError(error));
    }
  }

  selectFile(file: File | null): void {
    this.selectedFile.set(file);
    this.rightsConfirmed.set(false);
    this.receiptId = null;
    this.acceptanceKey = newUuid();
    this.commitKey = newUuid();
    this.uploadTarget = null;
    this.job.set(null);
    this.preview.set(null);
    this.previewEtag.set(null);
    this.committedWork.set(null);
    this.stageMessage.set(null);
    if (this.rightsStatement() && this.libraries().length > 0) {
      this.errorMessage.set(null);
      this.retryable.set(false);
      this.state.set('ready');
    }
  }

  setRightsConfirmed(confirmed: boolean): void {
    this.rightsConfirmed.set(confirmed);
  }

  setLibrary(libraryId: string): void {
    if (this.libraries().some((library) => library.id === libraryId)) {
      this.selectedLibraryId.set(libraryId);
    }
  }

  async startOrResumeImport(): Promise<boolean> {
    if (!this.canStart()) return false;
    this.errorMessage.set(null);
    this.retryable.set(false);

    try {
      if (!this.receiptId) {
        this.state.set('accepting');
        this.stageMessage.set('Đang ghi nhận xác nhận quyền sử dụng…');
        this.receiptId = await this.acceptCurrentRights();
      }

      if (!this.job()) {
        this.state.set('creating');
        this.stageMessage.set('Đang tạo vùng lưu riêng cho tệp…');
        await this.createStagingJob();
      }

      if (this.job()?.status === 'upload_pending') {
        await this.uploadSelectedFile();
      }

      if (this.job()?.status === 'uploaded') {
        await this.queueParse();
      }

      if (this.job()?.status === 'queued' || this.job()?.status === 'scanning' || this.job()?.status === 'parsing') {
        await this.waitForParser();
      }

      if (this.job()?.status !== 'ready_to_commit') {
        throw new AccountImportError(
          'Máy chủ chưa sẵn sàng cho bước xem trước. Hãy thử tải lại trạng thái hoặc bắt đầu một lượt nhập mới.',
          409, 'IMPORT_PREVIEW_NOT_READY', true,
        );
      }

      this.state.set('loading-preview');
      this.stageMessage.set('Đang tải metadata và mục lục riêng tư…');
      const { preview, etag } = await this.getCompletePreview(this.job()!.id);
      this.preview.set(preview);
      this.previewEtag.set(etag);
      this.state.set('review');
      this.stageMessage.set(null);
      this.errorMessage.set(null);
      return true;
    } catch (error) {
      const mapped = toImportError(error);
      if (mapped.code === 'IMPORT_RIGHTS_STATEMENT_STALE') {
        this.rightsConfirmed.set(false);
        this.receiptId = null;
        this.acceptanceKey = newUuid();
        await this.refreshRightsStatement();
      }
      this.setError(mapped);
      return false;
    }
  }

  async savePreview(title: string, author: string, language: string, chapters: ImportChapterPreview[]): Promise<boolean> {
    const job = this.job();
    const etag = this.previewEtag();
    const currentPreview = this.preview();
    if (!job || !etag || !currentPreview || this.isBusy()
        || !isCompleteChapterPermutation(chapters, currentPreview)) return false;
    this.state.set('saving');
    this.errorMessage.set(null);
    this.retryable.set(false);
    this.stageMessage.set('Đang lưu metadata và thứ tự chương…');
    try {
      const headers = await this.csrfHeaders();
      const response = await firstValueFrom(this.http.patch<unknown>(
        `${importsUrl}/${job.id}/preview`,
        { title, author, language, chapterOrder: chapters.map(({ id }) => id) },
        { headers: headers.set('If-Match', etag), observe: 'response' },
      ).pipe(timeout(apiTimeoutMs)));
      const updatedPage = parsePreviewPage(response.body, job.id);
      if (updatedPage.chapterCount !== chapters.length
          || updatedPage.chapters.some((chapter, index) => chapter.id !== chapters[index]?.id)) {
        throw invalidResponse();
      }
      const updatedEtag = response.headers.get('ETag');
      if (!isStrongPreviewEtag(updatedEtag)) throw invalidResponse();
      this.previewEtag.set(updatedEtag);
      this.commitKey = newUuid();
      this.preview.set({
        ...updatedPage,
        chapters: chapters.map((chapter, index) => ({ ...chapter, ordinal: index + 1 })),
        nextCursor: null,
      });
      this.state.set('review');
      this.stageMessage.set(null);
      this.errorMessage.set(null);
      return true;
    } catch (error) {
      const mapped = toImportError(error);
      if (mapped.status === 412 || mapped.code === 'IMPORT_PREVIEW_STALE') {
        try {
          const latest = await this.getCompletePreview(job.id);
          this.preview.set(latest.preview);
          this.previewEtag.set(latest.etag);
        } catch {
          // Keep the current local preview if the fresh server snapshot is unavailable.
        }
      }
      this.state.set('review');
      this.stageMessage.set(null);
      this.errorMessage.set(mapped.status === 412
        ? 'Bản xem trước đã thay đổi trên máy chủ. Mình đã tải lại bản mới; hãy kiểm tra rồi lưu lại.'
        : mapped.message);
      this.retryable.set(mapped.retryable);
      return false;
    }
  }

  async commitImport(): Promise<boolean> {
    const job = this.job();
    const etag = this.previewEtag();
    const preview = this.preview();
    if (!job || !etag || !preview || this.isBusy()) return false;
    this.state.set('committing');
    this.stageMessage.set('Đang lưu tác phẩm riêng tư…');
    this.errorMessage.set(null);
    this.retryable.set(false);
    try {
      const headers = (await this.csrfHeaders())
        .set('If-Match', etag)
        .set('Idempotency-Key', this.commitKey);
      const response: unknown = await firstValueFrom(this.http.post<unknown>(
        `${importsUrl}/${job.id}/commit`, {}, { headers },
      ).pipe(timeout(apiTimeoutMs)));
      const work = parseCommittedWork(response, this.selectedLibraryId());
      this.committedWork.set(work);
      this.job.update((current) => current ? { ...current, status: 'committed' } : current);
      this.state.set('committed');
      this.stageMessage.set(null);
      this.errorMessage.set(null);
      return true;
    } catch (error) {
      const mapped = toImportError(error);
      if (mapped.status === 412 || mapped.code === 'IMPORT_PREVIEW_STALE') {
        try {
          const latest = await this.getCompletePreview(job.id);
          this.preview.set(latest.preview);
          this.previewEtag.set(latest.etag);
          this.commitKey = newUuid();
        } catch {
          // Keep the current preview and allow a safe retry with its original idempotency key.
        }
      }
      this.state.set('review');
      this.stageMessage.set(null);
      this.errorMessage.set(mapped.status === 412
        ? 'Bản xem trước đã đổi. Hãy kiểm tra thông tin mới rồi xác nhận lại.'
        : mapped.message);
      this.retryable.set(mapped.retryable);
      return false;
    }
  }

  private async getCurrentRightsStatement(): Promise<ImportRightsStatement> {
    const response: unknown = await firstValueFrom(this.http.get<unknown>(rightsUrl).pipe(timeout(apiTimeoutMs)));
    if (!isRecord(response)
        || typeof response['version'] !== 'string'
        || !/^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/.test(response['version'])
        || typeof response['effectiveAt'] !== 'string'
        || !Number.isFinite(Date.parse(response['effectiveAt']))
        || typeof response['displayText'] !== 'string'
        || !response['displayText'].trim()
        || [...response['displayText']].length > 8_000) {
      throw invalidResponse();
    }
    return {
      version: response['version'],
      effectiveAt: response['effectiveAt'],
      displayText: response['displayText'],
    };
  }

  private async acceptCurrentRights(): Promise<string> {
    const statement = this.rightsStatement();
    if (!statement) throw new AccountImportError('Chưa tải được hướng dẫn quyền nhập.', 503, null, true);
    const headers = (await this.csrfHeaders()).set('Idempotency-Key', this.acceptanceKey);
    const response: unknown = await firstValueFrom(this.http.post<unknown>(rightsAcceptanceUrl, {
      statementVersion: statement.version,
      confirmed: true,
    }, { headers }).pipe(timeout(apiTimeoutMs)));
    if (!isRecord(response)
        || typeof response['id'] !== 'string'
        || !isUuid(response['id'])
        || response['statementVersion'] !== statement.version
        || typeof response['acceptedAt'] !== 'string'
        || !Number.isFinite(Date.parse(response['acceptedAt']))
        || typeof response['statementSha256'] !== 'string'
        || !/^[a-f0-9]{64}$/.test(response['statementSha256'])) {
      throw invalidResponse();
    }
    return response['id'];
  }

  private async createStagingJob(): Promise<void> {
    const file = this.selectedFile();
    const statement = this.rightsStatement();
    const libraryId = this.selectedLibraryId();
    if (!file || !statement || !libraryId || !this.receiptId) {
      throw new AccountImportError('Chọn một tệp EPUB, thư viện và xác nhận quyền nhập trước.', 400, null, false);
    }
    const response: unknown = await firstValueFrom(this.http.post<unknown>(importsUrl, {
      libraryId,
      rightsAcceptanceId: this.receiptId,
      rightsStatementVersion: statement.version,
      rightsConfirmed: true,
      source: {
        kind: 'upload',
        filename: file.name,
        contentType: file.type || null,
        sizeBytes: file.size,
      },
    }, { headers: await this.csrfHeaders() }).pipe(timeout(apiTimeoutMs)));
    if (!isRecord(response)
        || !isRecord(response['job'])
        || !isRecord(response['uploadTarget'])) {
      throw invalidResponse();
    }
    const job = parseJob(response['job']);
    const target = parseUploadTarget(response['uploadTarget'], job.id);
    if (job.status !== 'upload_pending') throw invalidResponse();
    this.job.set(job);
    this.uploadTarget = target;
  }

  private async uploadSelectedFile(): Promise<void> {
    const file = this.selectedFile();
    const job = this.job();
    if (!file || !job) throw new AccountImportError('Tệp hoặc lượt nhập không còn trong phiên này.', 409, null, false);

    // Rotate only when retrying after an expired or missing one-time token.
    if (!this.uploadTarget || Date.parse(this.uploadTarget.expiresAt) <= Date.now() + 2_000) {
      this.state.set('creating');
      this.stageMessage.set('Đang chuẩn bị lại liên kết tải lên an toàn…');
      await this.createStagingJob();
    }
    const target = this.uploadTarget;
    if (!target) throw invalidResponse();

    this.state.set('uploading');
    this.stageMessage.set('Đang tải tệp lên vùng riêng tư…');
    try {
      const csrf = await this.csrfHeaders();
      const headers = new HttpHeaders(target.headers)
        .set('X-CSRF-Token', csrf.get('X-CSRF-Token')!);
      const response = await firstValueFrom(this.http.put(
        target.url,
        file,
        { headers, observe: 'response' },
      ).pipe(timeout(uploadTimeoutMs)));
      if (response.status !== 204) throw invalidResponse();
      this.uploadTarget = null;
      this.job.update((current) => current ? { ...current, status: 'uploaded' } : current);
    } catch (error) {
      const uploadError = toImportError(error);
      const latest = await this.getJob(job.id).catch(() => null);
      if (latest?.status === 'uploaded') {
        this.job.set(latest);
        this.uploadTarget = null;
        return;
      }
      if (latest) this.job.set(latest);
      if (uploadError.status === 410) {
        this.uploadTarget = null;
        throw new AccountImportError(
          'Liên kết tải lên đã hết hạn. Thử lại để tạo liên kết mới; tệp vẫn nằm trên thiết bị.',
          410, 'IMPORT_UPLOAD_TARGET_EXPIRED', true,
        );
      }
      throw uploadError;
    }
  }

  private async queueParse(): Promise<void> {
    const job = this.job();
    if (!job) throw invalidResponse();
    this.state.set('parsing');
    this.stageMessage.set('Đang xếp tệp vào hàng chờ phân tích EPUB…');
    try {
      const response = await firstValueFrom(this.http.post<unknown>(
        `${importsUrl}/${job.id}/parse`, {}, { headers: await this.csrfHeaders() },
      ).pipe(timeout(apiTimeoutMs)));
      const parsed = parseJob(response);
      this.job.set(parsed);
    } catch (error) {
      const mapped = toImportError(error);
      if (mapped.code === 'IMPORT_PARSE_FAILED' || mapped.code === 'IMPORT_CONTENT_INVALID') {
        throw new AccountImportError(mapped.message, mapped.status, mapped.code, false);
      }
      if (mapped.status !== 409) throw mapped;
      const current = await this.getJob(job.id);
      this.job.set(current);
      if (current.status !== 'queued' && current.status !== 'scanning'
          && current.status !== 'parsing' && current.status !== 'ready_to_commit') {
        throw mapped;
      }
    }
  }

  private async waitForParser(): Promise<void> {
    const job = this.job();
    if (!job) throw invalidResponse();
    const deadline = Date.now() + parserMaxWaitMs;
    this.state.set('parsing');
    this.stageMessage.set('Đang phân tích tệp. Có thể mất một lúc với sách dài…');
    while (Date.now() < deadline) {
      await delay(parserPollMs);
      const current = await this.getJob(job.id);
      this.job.set(current);
      if (current.status === 'ready_to_commit') return;
      if (current.status === 'failed') {
        throw new AccountImportError(
          'Không thể phân tích tệp EPUB này. Hãy chọn một EPUB hợp lệ khác; tệp chưa được thêm vào thư viện.',
          422, current.failureCode ?? 'IMPORT_PARSE_FAILED', false,
        );
      }
      if (current.status === 'expired' || current.status === 'cancelled') {
        throw new AccountImportError('Lượt nhập đã hết hạn. Chọn lại tệp và xác nhận quyền để bắt đầu lượt mới.', 410,
          'IMPORT_EXPIRED', false);
      }
      this.stageMessage.set(current.status === 'queued'
        ? 'Tệp đang chờ worker phân tích…'
        : current.status === 'scanning'
          ? 'Đang kiểm tra an toàn tệp trước khi phân tích…'
          : 'Đang phân tích cấu trúc và mục lục EPUB…');
    }
    throw new AccountImportError(
      'Tệp vẫn đang được xử lý. Thử lại để kiểm tra trạng thái; không cần tải lên lần nữa.',
      0, 'IMPORT_PARSE_PENDING', true,
    );
  }

  private async getJob(importId: string): Promise<ImportJob> {
    const response: unknown = await firstValueFrom(this.http.get<unknown>(`${importsUrl}/${importId}`)
      .pipe(timeout(apiTimeoutMs)));
    return parseJob(response);
  }

  private async getCompletePreview(importId: string): Promise<{ preview: ImportPreview; etag: string }> {
    const chapters: ImportChapterPreview[] = [];
    let cursor: string | null = null;
    let etag: string | null = null;
    let first: ImportPreview | null = null;
    const seenCursors = new Set<string>();

    for (let page = 0; page < 20; page++) {
      const params: Record<string, string | number> = { limit: previewPageSize };
      if (cursor) params['cursor'] = cursor;
      const response: HttpResponse<unknown> = await firstValueFrom(this.http.get<unknown>(`${importsUrl}/${importId}/preview`, {
        params,
        observe: 'response',
      }).pipe(timeout(apiTimeoutMs)));
      const value = parsePreviewPage(response.body, importId);
      const responseEtag = response.headers.get('ETag');
      if (!isStrongPreviewEtag(responseEtag) || etag !== null && etag !== responseEtag) {
        throw invalidResponse();
      }
      etag = responseEtag;
      if (!first) first = value;
      chapters.push(...value.chapters);
      cursor = value.nextCursor;
      if (!cursor) break;
      if (seenCursors.has(cursor)) throw invalidResponse();
      seenCursors.add(cursor);
      if (page === 19) throw invalidResponse();
    }

    if (!first || !etag || chapters.length !== first.chapterCount
        || new Set(chapters.map(({ id }) => id)).size !== chapters.length) {
      throw invalidResponse();
    }
    const normalizedChapters = chapters.map((chapter, index) => ({ ...chapter, ordinal: index + 1 }));
    return {
      preview: { ...first, chapters: normalizedChapters, nextCursor: null },
      etag,
    };
  }

  private async refreshRightsStatement(): Promise<void> {
    try {
      this.rightsStatement.set(await this.getCurrentRightsStatement());
    } catch {
      this.rightsStatement.set(null);
    }
  }

  private async csrfHeaders(): Promise<HttpHeaders> {
    const response: unknown = await firstValueFrom(this.http.get<unknown>(csrfUrl).pipe(timeout(apiTimeoutMs)));
    if (!isRecord(response) || typeof response['token'] !== 'string'
        || response['token'].length < 16 || response['token'].length > 512) {
      throw invalidResponse();
    }
    return new HttpHeaders({ 'X-CSRF-Token': response['token'] });
  }

  private setError(error: AccountImportError): void {
    this.state.set('error');
    this.stageMessage.set(null);
    this.errorMessage.set(error.message);
    this.retryable.set(error.retryable);
  }
}

interface ImportJob {
  id: string;
  status: 'upload_pending' | 'uploaded' | 'queued' | 'scanning' | 'parsing' | 'ready_to_commit' | 'committed' | 'failed' | 'cancelled' | 'expired';
  createdAt: string;
  expiresAt: string;
  failureCode: string | null;
  warnings: ImportWarning[];
}

interface UploadTarget {
  url: string;
  headers: Record<string, string>;
  expiresAt: string;
}

function parseJob(value: unknown): ImportJob {
  if (!isRecord(value)
      || typeof value['id'] !== 'string' || !isUuid(value['id'])
      || !['upload_pending', 'uploaded', 'queued', 'scanning', 'parsing', 'ready_to_commit', 'committed', 'failed', 'cancelled', 'expired']
        .includes(String(value['status']))
      || typeof value['createdAt'] !== 'string' || !Number.isFinite(Date.parse(value['createdAt']))
      || typeof value['expiresAt'] !== 'string' || !Number.isFinite(Date.parse(value['expiresAt']))
      || value['failureCode'] !== undefined && value['failureCode'] !== null && typeof value['failureCode'] !== 'string') {
    throw invalidResponse();
  }
  const warnings = parseWarnings(value['warnings'] ?? []);
  return {
    id: value['id'],
    status: value['status'] as ImportJob['status'],
    createdAt: value['createdAt'],
    expiresAt: value['expiresAt'],
    failureCode: typeof value['failureCode'] === 'string' ? value['failureCode'] : null,
    warnings,
  };
}

function parseUploadTarget(value: Record<string, unknown>, importId: string): UploadTarget {
  if (value['method'] !== 'PUT'
      || typeof value['url'] !== 'string'
      || !isRecord(value['headers'])
      || typeof value['expiresAt'] !== 'string'
      || !Number.isFinite(Date.parse(value['expiresAt']))) {
    throw invalidResponse();
  }
  let parsed: URL;
  try {
    parsed = new URL(value['url'], window.location.origin);
  } catch {
    throw invalidResponse();
  }
  if (parsed.origin !== window.location.origin
      || parsed.pathname !== `${importsUrl}/${importId}/content`
      || parsed.search || parsed.hash) {
    throw invalidResponse();
  }
  const headers: Record<string, string> = {};
  for (const [name, headerValue] of Object.entries(value['headers'])) {
    const normalized = name.toLowerCase();
    if (typeof headerValue !== 'string'
        || normalized !== 'content-type' && normalized !== 'x-import-upload-token') {
      throw invalidResponse();
    }
    headers[name] = headerValue;
  }
  const contentType = getHeader(headers, 'content-type');
  const uploadToken = getHeader(headers, 'x-import-upload-token');
  if (contentType !== 'application/octet-stream' || !uploadToken || uploadToken.length < 40 || uploadToken.length > 128) {
    throw invalidResponse();
  }
  return { url: `${parsed.pathname}`, headers, expiresAt: value['expiresAt'] };
}

function parsePreviewPage(value: unknown, expectedImportId: string): ImportPreview {
  if (!isRecord(value)
      || value['importId'] !== expectedImportId
      || !Number.isSafeInteger(value['version']) || (value['version'] as number) < 1
      || typeof value['title'] !== 'string' || [...value['title']].length > 300
      || typeof value['author'] !== 'string' || [...value['author']].length > 200
      || !Number.isInteger(value['chapterCount']) || (value['chapterCount'] as number) < 0
      || typeof value['language'] !== 'string' || value['language'].length > 35
      || !Array.isArray(value['chapters']) || value['chapters'].length > previewPageSize
      || value['nextCursor'] !== null && (typeof value['nextCursor'] !== 'string'
        || !value['nextCursor'] || value['nextCursor'].length > 2048)) {
    throw invalidResponse();
  }
  const chapters = value['chapters'].map((chapter, index) => {
    if (!isRecord(chapter)
        || typeof chapter['id'] !== 'string' || !isUuid(chapter['id'])
        || !Number.isInteger(chapter['ordinal']) || (chapter['ordinal'] as number) < 1
        || typeof chapter['title'] !== 'string' || !chapter['title'].trim() || [...chapter['title']].length > 300) {
      throw invalidResponse();
    }
    return { id: chapter['id'], ordinal: chapter['ordinal'] as number, title: chapter['title'] };
  });
  if (new Set(chapters.map(({ id }) => id)).size !== chapters.length) throw invalidResponse();
  return {
    importId: expectedImportId,
    version: value['version'] as number,
    title: value['title'],
    author: value['author'],
    chapterCount: value['chapterCount'] as number,
    language: value['language'],
    chapters,
    nextCursor: value['nextCursor'] as string | null,
    warnings: parseWarnings(value['warnings']),
  };
}

function parseWarnings(value: unknown): ImportWarning[] {
  if (!Array.isArray(value) || value.length > 100) throw invalidResponse();
  return value.map((warning) => {
    if (!isRecord(warning)
        || typeof warning['code'] !== 'string' || warning['code'].length > 96
        || typeof warning['message'] !== 'string' || warning['message'].length > 500) {
      throw invalidResponse();
    }
    return { code: warning['code'], message: warning['message'] };
  });
}

function isCompleteChapterPermutation(
  chapters: ImportChapterPreview[],
  preview: ImportPreview,
): boolean {
  return chapters.length === preview.chapterCount
    && sameChapterIds(chapters, preview.chapters);
}

function sameChapterIds(left: ImportChapterPreview[], right: ImportChapterPreview[]): boolean {
  if (left.length !== right.length) return false;
  const rightIds = new Set(right.map(({ id }) => id));
  return rightIds.size === right.length && left.every(({ id }) => rightIds.has(id))
    && new Set(left.map(({ id }) => id)).size === left.length;
}

function parseCommittedWork(value: unknown, expectedLibraryId: string | null): CommittedImportWork {
  if (!isRecord(value)
      || typeof value['id'] !== 'string' || !isUuid(value['id'])
      || typeof value['libraryId'] !== 'string' || !isUuid(value['libraryId'])
      || expectedLibraryId !== value['libraryId']
      || typeof value['title'] !== 'string' || !value['title'].trim() || [...value['title']].length > 300
      || typeof value['author'] !== 'string' || [...value['author']].length > 200
      || typeof value['language'] !== 'string' || value['language'].length > 35
      || value['visibility'] !== 'private'
      || !Number.isInteger(value['chapterCount']) || (value['chapterCount'] as number) < 0
      || typeof value['createdAt'] !== 'string' || !Number.isFinite(Date.parse(value['createdAt']))) {
    throw invalidResponse();
  }
  return value as unknown as CommittedImportWork;
}

function toImportError(error: unknown): AccountImportError {
  if (error instanceof AccountImportError) return error;
  if (error instanceof LibraryApiError) {
    const message = error.status === 0
      ? 'Không thể kết nối để tải danh sách thư viện. Tệp vẫn ở trên thiết bị; hãy thử lại.'
      : error.status === 401
        ? 'Phiên đăng nhập đã hết hạn. Hãy đăng nhập lại để tiếp tục.'
        : error.status >= 500 || error.status === 429
          ? 'Chưa tải được thư viện nhận tác phẩm. Tệp vẫn ở trên thiết bị; hãy tải lại thư viện rồi thử lại.'
          : 'Không thể tải danh sách thư viện của tài khoản. Tệp vẫn ở trên thiết bị.';
    const retryable = error.status === 0 || error.status >= 500 || error.status === 429;
    return new AccountImportError(message, error.status, error.code, retryable);
  }
  if (!(error instanceof HttpErrorResponse)) {
    return new AccountImportError('Máy chủ chưa phản hồi. Tệp vẫn ở trên thiết bị; hãy kiểm tra kết nối rồi thử lại.',
      0, null, true);
  }
  const body = isRecord(error.error) ? error.error : null;
  const code = body && typeof body['code'] === 'string' ? body['code'] : null;
  const retryAfter = error.headers.get('Retry-After');
  const retryText = retryAfter && /^\d+$/.test(retryAfter) ? ` Thử lại sau khoảng ${retryAfter} giây.` : '';
  const messages: Record<string, string> = {
    IMPORT_RIGHTS_STATEMENT_NOT_CONFIGURED: 'Máy chủ chưa có hướng dẫn quyền nhập đã được duyệt. Tệp chưa được tải lên; thử lại sau.',
    POLICY_NOT_CONFIGURED: 'Chính sách tài khoản chưa được cấu hình đầy đủ. Tệp chưa được tải lên.',
    POLICY_ACCEPTANCE_REQUIRED: 'Phiên chính sách đã thay đổi. Hãy đọc và xác nhận lại trước khi nhập.',
    IMPORT_RIGHTS_STATEMENT_STALE: 'Hướng dẫn quyền đã đổi. Hãy đọc phiên bản mới và xác nhận lại.',
    IMPORT_RIGHTS_RECEIPT_ALREADY_USED: 'Xác nhận quyền này đã gắn với một lượt nhập khác. Bắt đầu lượt xác nhận mới.',
    IMPORT_STAGING_QUOTA_EXCEEDED: 'Thư viện đang có quá nhiều tệp chờ xử lý. Hãy thử sau khi lượt cũ hết hạn được dọn.',
    IMPORT_CREATE_RATE_LIMITED: `Bạn vừa bắt đầu nhiều lượt nhập.${retryText}`,
    IMPORT_UPLOAD_RATE_LIMITED: `Bạn vừa tải lên quá nhanh.${retryText}`,
    IMPORT_FILE_TOO_LARGE: 'Tệp vượt giới hạn 50 MB của máy chủ. Chọn một tệp nhỏ hơn.',
    IMPORT_FILE_METADATA_INVALID: 'Máy chủ chỉ chấp nhận tệp EPUB hợp lệ trong luồng nhập tài khoản hiện tại.',
    IMPORT_CONTENT_INVALID: 'Tệp không vượt qua kiểm tra an toàn EPUB. Hãy chọn tệp EPUB khác.',
    IMPORT_FILE_SIZE_MISMATCH: 'Kích thước tệp thay đổi trong lúc tải lên. Hãy chọn lại tệp.',
    IMPORT_PARSE_FAILED: 'Không thể phân tích tệp EPUB. Tệp chưa được thêm vào thư viện; hãy chọn một tệp khác.',
    IMPORT_PREVIEW_STALE: 'Bản xem trước đã thay đổi trên máy chủ. Hãy tải lại trước khi lưu.',
    IMPORT_PREVIEW_EDIT_INVALID: 'Metadata hoặc thứ tự chương chưa hợp lệ. Kiểm tra các trường rồi thử lại.',
    IDEMPOTENCY_KEY_REUSED: 'Lượt lưu trước đã dùng mã thử lại này cho yêu cầu khác. Tải lại trạng thái trước khi tiếp tục.',
    IMPORT_ALREADY_COMMITTED: 'Tác phẩm đã được thêm vào thư viện.',
  };
  if (error.status === 401) {
    return new AccountImportError('Phiên đăng nhập đã hết hạn. Hãy đăng nhập lại để tiếp tục.', 401, code, false);
  }
  const message = code && messages[code]
    ? messages[code]
    : error.status === 413
      ? 'Tệp vượt giới hạn tải lên của máy chủ.'
      : error.status === 0
        ? 'Không kết nối được với máy chủ. Tệp chưa bị xóa; hãy thử lại.'
        : 'Chưa hoàn tất được bước này. Kiểm tra kết nối rồi thử lại; tệp vẫn ở trạng thái riêng tư.';
  const retryable = error.status === 0 || error.status >= 500 || error.status === 429
    || code === 'IMPORT_PARSE_PENDING' || code === 'IMPORT_PREVIEW_STALE'
    || code === 'IMPORT_RIGHTS_STATEMENT_STALE' || code === 'IMPORT_UPLOAD_TARGET_EXPIRED';
  return new AccountImportError(message, error.status, code, retryable);
}

function invalidResponse(): AccountImportError {
  return new AccountImportError('Máy chủ trả về dữ liệu không hợp lệ. Không thể tiếp tục với nội dung chưa xác minh.',
    502, 'INVALID_API_RESPONSE', true);
}

function isStrongPreviewEtag(value: string | null): value is string {
  return typeof value === 'string' && /^"[A-Za-z0-9_-]{43}"$/.test(value);
}

function isUuid(value: string): boolean {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value);
}

function newUuid(): string {
  return crypto.randomUUID();
}

function getHeader(headers: Record<string, string>, requested: string): string | null {
  const match = Object.entries(headers).find(([name]) => name.toLowerCase() === requested);
  return match?.[1] ?? null;
}

function delay(milliseconds: number): Promise<void> {
  return new Promise((resolve) => window.setTimeout(resolve, milliseconds));
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
