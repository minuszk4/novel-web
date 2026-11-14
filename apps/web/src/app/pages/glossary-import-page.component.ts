import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import {
  GlossaryApiError,
  GlossaryApiService,
  GlossaryApplyAttempt,
  GlossaryCatalogItem,
  GlossaryCurrentTerm,
  GlossaryImportAction,
  GlossaryImportApplyResult,
  GlossaryImportCandidate,
  GlossaryImportIssue,
  GlossaryImportPreview,
  GlossaryMetadataSnapshot,
  GlossaryResolvedTerm,
} from '../core/glossary-api.service';
import { PolicyOnboardingService } from '../core/policy-onboarding.service';

type PageState = 'loading' | 'guest' | 'read-only' | 'not-found' | 'error' | 'ready' | 'saved';
type BusyAction = 'preview' | 'prepare-apply' | 'apply' | null;

interface ImportRowDraft {
  action: GlossaryImportAction | null;
  targetTermId: string | null;
  term: GlossaryResolvedTerm;
  aliasesText: string;
  priorityText: string;
}

const maxFileBytes = 1_048_576;
const supportedExtensions = new Set(['json', 'csv', 'tsv']);

@Component({
  selector: 'app-glossary-import-page',
  imports: [RouterLink],
  templateUrl: './glossary-import-page.component.html',
  styleUrl: './glossary-import-page.component.css',
})
export class GlossaryImportPageComponent {
  protected readonly policy = inject(PolicyOnboardingService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly api = inject(GlossaryApiService);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly state = signal<PageState>('loading');
  protected readonly busy = signal<BusyAction>(null);
  protected readonly snapshot = signal<GlossaryMetadataSnapshot | null>(null);
  protected readonly selectedFile = signal<File | null>(null);
  protected readonly fileError = signal<string | null>(null);
  protected readonly preview = signal<GlossaryImportPreview | null>(null);
  protected readonly rows = signal<Record<number, ImportRowDraft>>({});
  protected readonly operationError = signal<string | null>(null);
  protected readonly staleNotice = signal(false);
  protected readonly issues = signal<GlossaryImportIssue[]>([]);
  protected readonly issueCount = signal<number | null>(null);
  protected readonly issuesTruncated = signal(false);
  protected readonly applyAttempt = signal<GlossaryApplyAttempt | null>(null);
  protected readonly applyResult = signal<GlossaryImportApplyResult | null>(null);
  protected readonly noChanges = signal(false);
  protected readonly requiresSignIn = signal(false);
  protected readonly glossaryId = signal<string | null>(null);
  protected readonly draftIsAhead = computed(() => {
    const item = this.snapshot()?.glossary;
    return !!item?.canWrite && (item.latestRevisionNumber ?? 0) > (item.activeRevisionNumber ?? 0);
  });
  protected readonly canPreview = computed(
    () =>
      this.state() === 'ready' &&
      this.snapshot()?.glossary.canWrite === true &&
      !!this.selectedFile() &&
      this.busy() === null,
  );
  protected readonly allRowsSkipped = computed(() => {
    const preview = this.preview();
    return (
      !!preview &&
      preview.importedTerms.every(
        (candidate) => this.rows()[candidate.rowNumber]?.action === 'SKIP',
      )
    );
  });
  protected readonly canApply = computed(() => {
    const preview = this.preview();
    if (
      this.state() !== 'ready' ||
      !preview ||
      !this.selectedFile() ||
      this.busy() !== null ||
      this.applyAttempt() !== null ||
      this.noChanges() ||
      this.issues().length > 0
    ) {
      return false;
    }
    return preview.importedTerms.every((candidate) => {
      const draft = this.rows()[candidate.rowNumber];
      if (!draft || !draft.action || (candidate.matchesTruncated && draft.action !== 'SKIP')) {
        return false;
      }
      if (draft.action === 'SKIP') return true;
      if (this.termError(candidate, draft)) return false;
      return draft.action === 'ADD'
        ? draft.targetTermId === null
        : draft.targetTermId !== null && this.findCurrentTerm(draft.targetTermId) !== null;
    });
  });

  constructor() {
    this.route.paramMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((params) => {
      const id = params.get('glossaryId');
      this.glossaryId.set(id);
      this.resetFlow();
      void this.load(id);
    });
  }

  async retryLoad(): Promise<void> {
    await this.load(this.glossaryId());
  }

  async onFileSelected(event: Event): Promise<void> {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0] ?? null;
    input.value = '';
    if (!file) return;
    if (this.applyAttempt()) {
      this.fileError.set(
        'Lần lưu trước chưa rõ kết quả. Hãy gửi lại đúng yêu cầu đó trước khi đổi tệp.',
      );
      return;
    }
    const extension = file.name.split('.').at(-1)?.toLowerCase() ?? '';
    if (!supportedExtensions.has(extension)) {
      this.selectedFile.set(null);
      this.clearPreview();
      this.fileError.set(
        'Chỉ nhận tệp JSON, CSV hoặc TSV. Hãy chọn mẫu đúng định dạng rồi thử lại.',
      );
      return;
    }
    if (file.size < 1 || file.size > maxFileBytes) {
      this.selectedFile.set(null);
      this.clearPreview();
      this.fileError.set('Tệp phải lớn hơn 0 byte và không vượt quá 1 MiB.');
      return;
    }
    this.selectedFile.set(file);
    this.fileError.set(null);
    this.requiresSignIn.set(false);
    this.clearPreview();
  }

  removeFile(): void {
    if (this.applyAttempt()) return;
    this.selectedFile.set(null);
    this.fileError.set(null);
    this.clearPreview();
  }

  downloadTemplate(format: 'JSON' | 'CSV' | 'TSV'): void {
    const templates: Record<
      typeof format,
      { filename: string; contentType: string; content: string }
    > = {
      JSON: {
        filename: 'glossary-v1.json',
        contentType: 'application/json;charset=utf-8',
        content: JSON.stringify(
          {
            version: 1,
            terms: [
              {
                sourceTerm: '金丹',
                targetTerm: 'Kim Đan',
                aliases: ['Golden Core'],
                priority: 0,
                casePolicy: 'INSENSITIVE',
                matchMode: 'WHOLE_PHRASE',
                doNotTranslate: false,
                contextNote: 'Cảnh giới tu luyện',
              },
            ],
          },
          null,
          2,
        ),
      },
      CSV: {
        filename: 'glossary-v1.csv',
        contentType: 'text/csv;charset=utf-8',
        content:
          'sourceTerm,targetTerm,aliases,priority,casePolicy,matchMode,doNotTranslate,contextNote\n金丹,Kim Đan,"[""Golden Core""]",0,INSENSITIVE,WHOLE_PHRASE,false,Cảnh giới tu luyện\n',
      },
      TSV: {
        filename: 'glossary-v1.tsv',
        contentType: 'text/tab-separated-values;charset=utf-8',
        content:
          'sourceTerm\ttargetTerm\taliases\tpriority\tcasePolicy\tmatchMode\tdoNotTranslate\tcontextNote\n金丹\tKim Đan\t["Golden Core"]\t0\tINSENSITIVE\tWHOLE_PHRASE\tfalse\tCảnh giới tu luyện\n',
      },
    };
    const template = templates[format];
    const url = URL.createObjectURL(new Blob([template.content], { type: template.contentType }));
    const link = document.createElement('a');
    link.href = url;
    link.download = template.filename;
    link.click();
    URL.revokeObjectURL(url);
  }

  async requestPreview(): Promise<void> {
    const id = this.glossaryId();
    const snapshot = this.snapshot();
    const file = this.selectedFile();
    if (!id || !snapshot?.glossary.canWrite || !file || this.busy()) return;
    this.clearPreview();
    this.operationError.set(null);
    this.issues.set([]);
    this.issueCount.set(null);
    this.issuesTruncated.set(false);
    this.requiresSignIn.set(false);
    this.staleNotice.set(false);
    this.busy.set('preview');
    try {
      const result = await this.api.previewImport(id, snapshot.etag, file);
      if (
        result.glossaryId !== snapshot.glossary.id ||
        result.glossaryVersion !== snapshot.glossary.version ||
        result.etag !== snapshot.etag
      ) {
        this.staleNotice.set(true);
        await this.reloadSnapshotPreservingFile();
        this.operationError.set(
          'Glossary vừa thay đổi. Đã tải phiên bản mới; hãy xem trước lại tệp.',
        );
        return;
      }
      this.preview.set(result);
      this.rows.set(createInitialRows(result));
      this.noChanges.set(false);
      this.state.set('ready');
      setTimeout(() => document.getElementById('glossary-import-results-heading')?.focus(), 0);
    } catch (error) {
      await this.handlePreviewError(error);
    } finally {
      this.busy.set(null);
    }
  }

  setAction(candidate: GlossaryImportCandidate, action: GlossaryImportAction): void {
    if (this.applyAttempt() || this.busy() || !this.allowedActions(candidate).includes(action))
      return;
    const target = candidate.existingMatches.length === 1 ? candidate.existingMatches[0] : null;
    let targetTermId: string | null = null;
    let term = importedToResolved(candidate.importedTerm);
    if (action === 'REPLACE' && target?.reason === 'CANONICAL_TERM_MATCH') {
      targetTermId = target.termId;
    } else if (action === 'MERGE' && target) {
      targetTermId = target.termId;
      const current = this.findCurrentTerm(target.termId);
      if (current) term = mergeTerm(current, candidate.importedTerm);
    }
    this.updateRow(candidate.rowNumber, {
      action,
      targetTermId,
      term,
      aliasesText: term.aliases.join('\n'),
      priorityText: String(term.priority),
    });
  }

  setMergeTarget(candidate: GlossaryImportCandidate, event: Event): void {
    if (this.applyAttempt() || this.busy()) return;
    const targetId = (event.target as HTMLSelectElement).value;
    const target = candidate.existingMatches.find((match) => match.termId === targetId);
    const current = target ? this.findCurrentTerm(target.termId) : null;
    if (!current) {
      this.updateRow(candidate.rowNumber, { targetTermId: null });
      return;
    }
    const term = mergeTerm(current, candidate.importedTerm);
    this.updateRow(candidate.rowNumber, {
      targetTermId: current.id,
      term,
      aliasesText: term.aliases.join('\n'),
      priorityText: String(term.priority),
    });
  }

  setText(
    candidate: GlossaryImportCandidate,
    field: 'sourceTerm' | 'targetTerm' | 'contextNote',
    event: Event,
  ): void {
    const draft = this.rowDraft(candidate.rowNumber);
    if (!draft || this.applyAttempt() || this.busy()) return;
    const value = (event.target as HTMLInputElement | HTMLTextAreaElement).value;
    const assignedValue = field === 'sourceTerm' || value.length > 0 ? value : null;
    const term = { ...draft.term, [field]: assignedValue } as GlossaryResolvedTerm;
    this.updateRow(candidate.rowNumber, { term });
  }

  setAliases(candidate: GlossaryImportCandidate, event: Event): void {
    const draft = this.rowDraft(candidate.rowNumber);
    if (!draft || this.applyAttempt() || this.busy()) return;
    const aliasesText = (event.target as HTMLTextAreaElement).value;
    const aliases = aliasesText
      .split(/\r?\n/)
      .map((alias) => alias.trim())
      .filter(Boolean);
    this.updateRow(candidate.rowNumber, { aliasesText, term: { ...draft.term, aliases } });
  }

  setPriority(candidate: GlossaryImportCandidate, event: Event): void {
    const draft = this.rowDraft(candidate.rowNumber);
    if (!draft || this.applyAttempt() || this.busy()) return;
    const priorityText = (event.target as HTMLInputElement).value;
    const priority = priorityText.trim() ? Number(priorityText) : Number.NaN;
    this.updateRow(candidate.rowNumber, {
      priorityText,
      term: { ...draft.term, priority },
    });
  }

  setCasePolicy(candidate: GlossaryImportCandidate, event: Event): void {
    const draft = this.rowDraft(candidate.rowNumber);
    if (!draft || this.applyAttempt() || this.busy()) return;
    const value = (event.target as HTMLSelectElement).value;
    if (value !== 'SENSITIVE' && value !== 'INSENSITIVE') return;
    this.updateRow(candidate.rowNumber, { term: { ...draft.term, casePolicy: value } });
  }

  setDoNotTranslate(candidate: GlossaryImportCandidate, event: Event): void {
    const draft = this.rowDraft(candidate.rowNumber);
    if (!draft || this.applyAttempt() || this.busy()) return;
    this.updateRow(candidate.rowNumber, {
      term: { ...draft.term, doNotTranslate: (event.target as HTMLInputElement).checked },
    });
  }

  async saveDraft(): Promise<void> {
    const id = this.glossaryId();
    const preview = this.preview();
    const file = this.selectedFile();
    if (!id || !preview || !file || this.busy()) return;
    if (!this.applyAttempt() && !this.canApply()) return;
    if (!this.applyAttempt() && this.allRowsSkipped()) {
      this.noChanges.set(true);
      this.operationError.set(null);
      return;
    }

    this.operationError.set(null);
    this.issues.set([]);
    this.issueCount.set(null);
    this.issuesTruncated.set(false);
    let attempt = this.applyAttempt();
    if (!attempt) {
      this.busy.set('prepare-apply');
      try {
        attempt = await this.api.createApplyAttempt(file, buildResolutions(preview, this.rows()));
      } catch {
        this.operationError.set(
          'Không thể đọc tệp để chuẩn bị yêu cầu lưu. Tệp chưa được gửi; hãy chọn lại rồi thử lại.',
        );
        this.busy.set(null);
        return;
      }
      this.applyAttempt.set(attempt);
    }
    this.busy.set('apply');
    try {
      const result = await this.api.applyImport(id, preview.etag, attempt);
      this.applyResult.set(result);
      this.applyAttempt.set(null);
      this.noChanges.set(false);
      this.snapshot.update((snapshot) =>
        snapshot
          ? {
              ...snapshot,
              glossary: {
                ...snapshot.glossary,
                version: result.draft.glossaryVersion,
                latestRevisionNumber: result.draft.revisionNumber,
              },
              etag: result.etag,
            }
          : snapshot,
      );
      this.state.set('saved');
      setTimeout(() => document.getElementById('glossary-import-saved-heading')?.focus(), 0);
    } catch (error) {
      this.handleApplyError(error);
    } finally {
      this.busy.set(null);
    }
  }

  retryPreview(): void {
    void this.requestPreview();
  }

  focusIssue(issue: GlossaryImportIssue): void {
    if (issue.rowNumber > 0) {
      const row = document.getElementById(`glossary-import-row-${issue.rowNumber}`);
      row?.focus();
    } else {
      document.getElementById('glossary-import-results-heading')?.focus();
    }
  }

  editorFor(candidate: GlossaryImportCandidate): ImportRowDraft | null {
    return this.rows()[candidate.rowNumber] ?? null;
  }

  currentMatches(candidate: GlossaryImportCandidate): GlossaryCurrentTerm[] {
    const currentTerms = this.preview()?.currentTerms ?? [];
    const ids = new Set(candidate.existingMatches.map((match) => match.termId));
    return currentTerms.filter((term) => ids.has(term.id));
  }

  issueFor(candidate: GlossaryImportCandidate): GlossaryImportIssue | null {
    return this.issues().find((issue) => issue.rowNumber === candidate.rowNumber) ?? null;
  }

  hasGeneralIssue(): boolean {
    return this.issues().some((issue) => issue.rowNumber === 0);
  }

  allowedActions(candidate: GlossaryImportCandidate): GlossaryImportAction[] {
    if (candidate.matchesTruncated) return ['SKIP'];
    switch (candidate.suggestedAction) {
      case 'ADD':
        return ['ADD', 'SKIP'];
      case 'UPDATE': {
        const matches = candidate.existingMatches;
        if (matches.length === 1 && matches[0].reason === 'CANONICAL_TERM_MATCH') {
          return ['REPLACE', 'MERGE', 'SKIP'];
        }
        return matches.length > 0 ? ['MERGE', 'SKIP'] : ['SKIP'];
      }
      case 'UNCHANGED':
        return ['SKIP'];
      case 'RESOLVE_CONFLICT':
        return candidate.existingMatches.length > 0 ? ['ADD', 'MERGE', 'SKIP'] : ['SKIP'];
    }
  }

  actionLabel(action: GlossaryImportAction, candidate: GlossaryImportCandidate): string {
    const source = candidate.importedTerm.sourceTerm;
    switch (action) {
      case 'ADD':
        return `Thêm mục — “${source}”`;
      case 'REPLACE':
        return `Thay thế — “${source}”`;
      case 'MERGE':
        return `Gộp bí danh — “${source}”`;
      case 'SKIP':
        return `Bỏ qua dòng ${candidate.rowNumber}`;
    }
  }

  actionDescription(candidate: GlossaryImportCandidate): string {
    if (candidate.matchesTruncated) {
      return 'Có hơn 100 kết quả va chạm. Danh sách dưới đây chỉ là một phần; hiện chỉ có thể bỏ qua dòng này.';
    }
    switch (candidate.suggestedAction) {
      case 'ADD':
        return 'Dòng này sẽ tạo một mục thuật ngữ mới với mã ổn định do máy chủ cấp.';
      case 'UPDATE':
        return 'Dòng này khớp với thuật ngữ hiện có. Hãy chọn thay thế, gộp hoặc bỏ qua.';
      case 'UNCHANGED':
        return 'Dòng này giống nội dung hiện tại và được bỏ qua mặc định.';
      case 'RESOLVE_CONFLICT':
        return 'Dòng này trùng mẫu với mục hiện có. Bạn có thể gộp, giữ thành mục riêng sau khi sửa để hết va chạm, hoặc bỏ qua.';
    }
  }

  termError(candidate: GlossaryImportCandidate, draft: ImportRowDraft): string | null {
    const term = draft.term;
    if (draft.action === 'SKIP' || draft.action === null) return null;
    if (!term.sourceTerm.trim() || [...term.sourceTerm].length > 512) {
      return 'Thuật ngữ nguồn cần từ 1 đến 512 ký tự.';
    }
    if (term.targetTerm !== null && [...term.targetTerm].length > 4096) {
      return 'Cách dịch không được dài quá 4.096 ký tự.';
    }
    if (
      !Number.isSafeInteger(term.priority) ||
      term.priority < -1_000_000 ||
      term.priority > 1_000_000
    ) {
      return 'Độ ưu tiên phải là số nguyên từ −1.000.000 đến 1.000.000.';
    }
    if (
      term.aliases.length > 25 ||
      term.aliases.some((alias) => !alias.trim() || [...alias].length > 512)
    ) {
      return 'Mỗi mục có tối đa 25 bí danh, mỗi bí danh không quá 512 ký tự.';
    }
    if (term.contextNote !== null && [...term.contextNote].length > 2000) {
      return 'Ghi chú ngữ cảnh không được dài quá 2.000 ký tự.';
    }
    if (
      candidate.suggestedAction === 'RESOLVE_CONFLICT' &&
      draft.action === 'ADD' &&
      !conflictCandidateAdjusted(candidate, term)
    ) {
      return 'Để giữ thành mục riêng, hãy đổi thuật ngữ nguồn, bí danh, độ ưu tiên hoặc quy tắc hoa/thường để xử lý va chạm.';
    }
    if (draft.action === 'REPLACE' && !draft.targetTermId) {
      return 'Chọn đúng một thuật ngữ gốc để thay thế.';
    }
    if (draft.action === 'MERGE' && !draft.targetTermId) {
      return candidate.existingMatches.length > 1
        ? 'Chọn một thuật ngữ hiện có để gộp.'
        : 'Không tìm thấy mục gốc hợp lệ để gộp.';
    }
    return null;
  }

  isEditingLocked(): boolean {
    return this.busy() !== null || this.applyAttempt() !== null;
  }

  languageLabel(language: string): string {
    return (
      {
        vi: 'Tiếng Việt',
        en: 'Tiếng Anh',
        'zh-Hans': 'Tiếng Trung giản thể',
        'zh-Hant': 'Tiếng Trung phồn thể',
        ja: 'Tiếng Nhật',
        ko: 'Tiếng Hàn',
        fr: 'Tiếng Pháp',
        de: 'Tiếng Đức',
        es: 'Tiếng Tây Ban Nha',
      }[language] ?? language
    );
  }

  scopeLabel(scope: GlossaryCatalogItem['scope']): string {
    return {
      USER_GLOBAL: 'Cá nhân',
      LIBRARY: 'Thư viện',
      WORK: 'Tác phẩm',
      VOLUME: 'Quyển',
      CHAPTER: 'Chương',
    }[scope];
  }

  private async load(id: string | null): Promise<void> {
    this.snapshot.set(null);
    this.operationError.set(null);
    this.requiresSignIn.set(false);
    if (this.policy.status() !== 'accepted') {
      this.state.set('guest');
      return;
    }
    if (!id) {
      this.state.set('not-found');
      return;
    }
    this.state.set('loading');
    try {
      const snapshot = await this.api.getGlossarySnapshot(id);
      if (this.glossaryId() !== id) return;
      this.snapshot.set(snapshot);
      this.state.set(snapshot.glossary.canWrite ? 'ready' : 'read-only');
    } catch (error) {
      if (this.glossaryId() !== id) return;
      await this.handleReadError(error, id);
    }
  }

  private async reloadSnapshotPreservingFile(): Promise<void> {
    const id = this.glossaryId();
    if (!id) return;
    try {
      const snapshot = await this.api.getGlossarySnapshot(id);
      this.snapshot.set(snapshot);
      this.state.set(snapshot.glossary.canWrite ? 'ready' : 'read-only');
    } catch (error) {
      await this.handleReadError(error, id);
    }
  }

  private async handleReadError(error: unknown, id: string): Promise<void> {
    if (error instanceof GlossaryApiError && error.status === 404) {
      this.resetSensitiveState();
      this.state.set('not-found');
      return;
    }
    if (error instanceof GlossaryApiError && error.status === 403) {
      this.resetSensitiveState();
      await this.router.navigate(['/app/setup'], {
        queryParams: { returnUrl: `/app/glossaries/${encodeURIComponent(id)}/import` },
      });
      return;
    }
    this.requiresSignIn.set(error instanceof GlossaryApiError && error.status === 401);
    this.operationError.set(readErrorMessage(error));
    this.state.set('error');
  }

  private async handlePreviewError(error: unknown): Promise<void> {
    if (
      error instanceof GlossaryApiError &&
      error.status === 409 &&
      error.code === 'GLOSSARY_VERSION_STALE'
    ) {
      this.staleNotice.set(true);
      await this.reloadSnapshotPreservingFile();
      this.operationError.set(
        'Glossary đã đổi sau khi mở trang. Tệp vẫn ở thiết bị này; hãy xem trước lại.',
      );
      return;
    }
    if (error instanceof GlossaryApiError && error.status === 404) {
      this.resetSensitiveState();
      this.state.set('not-found');
      return;
    }
    if (error instanceof GlossaryApiError && error.status === 403) {
      this.resetSensitiveState();
      const id = this.glossaryId();
      if (id) {
        await this.router.navigate(['/app/setup'], {
          queryParams: { returnUrl: `/app/glossaries/${encodeURIComponent(id)}/import` },
        });
      }
      return;
    }
    this.requiresSignIn.set(error instanceof GlossaryApiError && error.status === 401);
    this.issues.set(error instanceof GlossaryApiError ? error.issues : []);
    this.issueCount.set(error instanceof GlossaryApiError ? error.issueCount : null);
    this.issuesTruncated.set(error instanceof GlossaryApiError && error.issuesTruncated);
    this.operationError.set(previewErrorMessage(error));
    this.state.set('ready');
  }

  private handleApplyError(error: unknown): void {
    if (
      error instanceof GlossaryApiError &&
      error.status === 409 &&
      error.code === 'GLOSSARY_VERSION_STALE'
    ) {
      this.applyAttempt.set(null);
      this.clearPreview();
      this.staleNotice.set(true);
      void this.reloadSnapshotPreservingFile().then(() => {
        this.operationError.set(
          'Glossary đã đổi. Quyết định cũ đã bị xóa; hãy xem trước lại tệp trước khi lưu.',
        );
      });
      return;
    }
    if (
      error instanceof GlossaryApiError &&
      error.status === 409 &&
      error.code === 'IMPORT_NO_CHANGES'
    ) {
      this.applyAttempt.set(null);
      this.noChanges.set(true);
      this.operationError.set('Bản kết quả giống glossary hiện tại nên không tạo bản nháp mới.');
      return;
    }
    if (
      error instanceof GlossaryApiError &&
      error.status === 409 &&
      error.code === 'IDEMPOTENCY_KEY_REUSED'
    ) {
      this.applyAttempt.set(null);
      this.operationError.set(
        'Máy chủ từ chối khóa lưu đã dùng cho nội dung khác. Hãy rà soát quyết định rồi xác nhận một lần lưu mới.',
      );
      return;
    }
    if (error instanceof GlossaryApiError && error.status === 404) {
      this.resetSensitiveState();
      this.state.set('not-found');
      return;
    }
    if (error instanceof GlossaryApiError && error.status === 403) {
      this.resetSensitiveState();
      const id = this.glossaryId();
      if (id) {
        void this.router.navigate(['/app/setup'], {
          queryParams: { returnUrl: `/app/glossaries/${encodeURIComponent(id)}/import` },
        });
      }
      return;
    }
    if (error instanceof GlossaryApiError && error.status === 401) {
      this.applyAttempt.set(null);
      this.requiresSignIn.set(true);
      this.operationError.set(
        'Phiên đăng nhập đã hết hạn. Máy chủ chưa nhận yêu cầu lưu; hãy đăng nhập lại, chọn lại tệp và xem trước trước khi lưu.',
      );
      this.state.set('error');
      return;
    }
    if (error instanceof GlossaryApiError && error.status === 422) {
      this.applyAttempt.set(null);
      const issues = error.issues.length
        ? error.issues
        : [
            {
              code: error.code ?? 'IMPORT_RESOLUTION_INVALID',
              field: null,
              rowNumber: error.rowNumber ?? 0,
              message: 'Máy chủ yêu cầu sửa quyết định hoặc tải lại bản xem trước.',
            },
          ];
      this.issues.set(issues);
      this.issueCount.set(error.issueCount ?? issues.length);
      this.issuesTruncated.set(error.issuesTruncated);
      this.operationError.set(applyValidationMessage(error));
      if (error.rowNumber)
        setTimeout(
          () => document.getElementById(`glossary-import-row-${error.rowNumber}`)?.focus(),
          0,
        );
      return;
    }
    if (error instanceof GlossaryApiError && error.status > 0 && error.status < 500) {
      this.applyAttempt.set(null);
      this.operationError.set(applyErrorMessage(error));
      return;
    }
    // An unknown outcome keeps the exact body and key locked for a safe replay.
    this.operationError.set(
      'Chưa rõ máy chủ đã lưu hay chưa. Không sửa lựa chọn; hãy gửi lại đúng yêu cầu để kiểm tra an toàn.',
    );
  }

  private updateRow(rowNumber: number, patch: Partial<ImportRowDraft>): void {
    this.rows.update((rows) => ({
      ...rows,
      [rowNumber]: { ...rows[rowNumber], ...patch },
    }));
    this.noChanges.set(false);
    this.issues.update((issues) => {
      const remaining = issues.filter((issue) => issue.rowNumber !== rowNumber);
      const removed = issues.length - remaining.length;
      if (removed) {
        this.issueCount.update((count) =>
          count === null ? null : Math.max(remaining.length, count - removed),
        );
        if (remaining.length === 0) this.operationError.set(null);
      }
      return remaining;
    });
    this.operationError.set(null);
  }

  private rowDraft(rowNumber: number): ImportRowDraft | null {
    return this.rows()[rowNumber] ?? null;
  }

  private findCurrentTerm(termId: string): GlossaryCurrentTerm | null {
    return this.preview()?.currentTerms.find((term) => term.id === termId) ?? null;
  }

  private clearPreview(): void {
    this.preview.set(null);
    this.rows.set({});
    this.applyAttempt.set(null);
    this.applyResult.set(null);
    this.noChanges.set(false);
    this.issues.set([]);
    this.issueCount.set(null);
    this.issuesTruncated.set(false);
    this.operationError.set(null);
    this.staleNotice.set(false);
  }

  private resetSensitiveState(): void {
    this.snapshot.set(null);
    this.selectedFile.set(null);
    this.fileError.set(null);
    this.clearPreview();
  }

  private resetFlow(): void {
    this.resetSensitiveState();
    this.requiresSignIn.set(false);
  }
}

function createInitialRows(preview: GlossaryImportPreview): Record<number, ImportRowDraft> {
  const rows: Record<number, ImportRowDraft> = {};
  for (const candidate of preview.importedTerms) {
    const term = importedToResolved(candidate.importedTerm);
    rows[candidate.rowNumber] = {
      action:
        candidate.suggestedAction === 'ADD'
          ? 'ADD'
          : candidate.suggestedAction === 'UNCHANGED'
            ? 'SKIP'
            : null,
      targetTermId: null,
      term,
      aliasesText: term.aliases.join('\n'),
      priorityText: String(term.priority),
    };
  }
  return rows;
}

function importedToResolved(term: GlossaryImportCandidate['importedTerm']): GlossaryResolvedTerm {
  return {
    sourceTerm: term.sourceTerm,
    targetTerm: term.targetTerm,
    priority: term.priority,
    casePolicy: term.casePolicy,
    matchMode: term.matchMode,
    doNotTranslate: term.doNotTranslate,
    contextNote: term.contextNote,
    aliases: [...term.aliases],
  };
}

function mergeTerm(
  current: GlossaryCurrentTerm,
  imported: GlossaryImportCandidate['importedTerm'],
): GlossaryResolvedTerm {
  const aliases: string[] = [];
  const seen = new Set<string>();
  const matchingKey = (value: string): string => {
    const normalized = value.normalize('NFKC').trim();
    return current.casePolicy === 'INSENSITIVE' ? normalized.toLowerCase() : normalized;
  };
  const sourceKey = matchingKey(current.sourceTerm);
  for (const alias of [...current.aliases, ...imported.aliases]) {
    const key = matchingKey(alias);
    if (!key || key === sourceKey || seen.has(key)) continue;
    seen.add(key);
    aliases.push(alias);
  }
  return {
    sourceTerm: current.sourceTerm,
    targetTerm: current.targetTerm,
    priority: current.priority,
    casePolicy: current.casePolicy,
    matchMode: current.matchMode,
    doNotTranslate: current.doNotTranslate,
    contextNote: current.contextNote,
    aliases,
  };
}

function conflictCandidateAdjusted(
  candidate: GlossaryImportCandidate,
  term: GlossaryResolvedTerm,
): boolean {
  const imported = candidate.importedTerm;
  if (
    term.priority !== imported.priority ||
    term.casePolicy !== imported.casePolicy ||
    normalizedPattern(term.sourceTerm, term.casePolicy) !==
      normalizedPattern(imported.sourceTerm, imported.casePolicy)
  ) {
    return true;
  }
  const currentAliases = new Set(
    term.aliases.map((alias) => normalizedPattern(alias, term.casePolicy)),
  );
  const importedAliases = new Set(
    imported.aliases.map((alias) => normalizedPattern(alias, imported.casePolicy)),
  );
  return (
    currentAliases.size !== importedAliases.size ||
    [...currentAliases].some((alias) => !importedAliases.has(alias))
  );
}

function normalizedPattern(value: string, casePolicy: GlossaryResolvedTerm['casePolicy']): string {
  const normalized = value.normalize('NFKC').trim();
  return casePolicy === 'INSENSITIVE' ? normalized.toLowerCase() : normalized;
}

function buildResolutions(preview: GlossaryImportPreview, rows: Record<number, ImportRowDraft>) {
  return preview.importedTerms.map((candidate) => {
    const row = rows[candidate.rowNumber];
    if (!row || !row.action)
      throw new GlossaryApiError('IMPORT_DECISION_REQUIRED', 422, 'IMPORT_DECISION_REQUIRED');
    if (row.action === 'SKIP') {
      return {
        rowNumber: candidate.rowNumber,
        action: 'SKIP' as const,
        targetTermId: null,
        term: null,
      };
    }
    return {
      rowNumber: candidate.rowNumber,
      action: row.action,
      targetTermId: row.targetTermId,
      term: {
        ...row.term,
        sourceTerm: row.term.sourceTerm.trim(),
        aliases: row.term.aliases.map((alias) => alias.trim()),
      },
    };
  });
}

function readErrorMessage(error: unknown): string {
  if (!(error instanceof GlossaryApiError))
    return 'Chưa tải được glossary. Kiểm tra kết nối rồi thử lại.';
  if (error.status === 401) return 'Phiên đăng nhập đã hết hạn. Đăng nhập lại để tiếp tục.';
  if (error.status === 403) return 'Cần hoàn tất thiết lập điều khoản để nhập thuật ngữ.';
  if (error.status > 0 && error.status < 500) return 'Không thể tải glossary này. Hãy thử tải lại.';
  return 'Máy chủ chưa phản hồi. Kiểm tra kết nối rồi thử lại.';
}

function previewErrorMessage(error: unknown): string {
  if (!(error instanceof GlossaryApiError)) return 'Chưa xem trước được tệp. Hãy thử lại.';
  if (error.status === 401)
    return 'Phiên đăng nhập đã hết hạn. Tệp vẫn chỉ nằm trong phiên trình duyệt này.';
  if (error.status === 413) return 'Tệp vượt giới hạn 1 MiB. Chọn tệp nhỏ hơn rồi xem trước lại.';
  if (error.status === 415) return 'Định dạng tệp không được hỗ trợ. Chọn JSON, CSV hoặc TSV.';
  if (error.status === 422)
    return 'Tệp có lỗi định dạng hoặc dữ liệu. Sửa các dòng được báo rồi chọn lại tệp.';
  if (error.status === 429) return 'Bạn thao tác quá nhanh. Chờ một chút rồi thử lại.';
  if (error.status === 0 || error.status < 0 || error.status >= 500)
    return 'Máy chủ chưa phản hồi. Tệp vẫn ở thiết bị này; bạn có thể thử xem trước lại.';
  return 'Không thể xem trước tệp. Kiểm tra định dạng và thử lại.';
}

function applyErrorMessage(error: GlossaryApiError): string {
  if (error.status === 413)
    return 'Yêu cầu nhập vượt giới hạn. Giảm kích thước tệp hoặc số dòng rồi xem trước lại.';
  if (error.status === 415)
    return 'Định dạng tệp không còn được hỗ trợ. Chọn lại tệp JSON, CSV hoặc TSV.';
  if (error.status === 409)
    return 'Yêu cầu không còn phù hợp với phiên bản glossary. Hãy xem trước lại.';
  return 'Máy chủ từ chối lưu bản nháp. Kiểm tra các quyết định rồi thử lại.';
}

function applyValidationMessage(error: GlossaryApiError): string {
  if (error.code === 'IMPORT_NO_CHANGES')
    return 'Không có thay đổi để lưu; glossary vẫn giữ nguyên.';
  return 'Máy chủ chưa tạo bản nháp. Xem các lỗi an toàn bên dưới, sửa quyết định rồi thử lại.';
}
