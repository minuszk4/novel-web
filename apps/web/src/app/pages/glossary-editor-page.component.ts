import {
  Component,
  DestroyRef,
  ElementRef,
  ViewChild,
  computed,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import {
  GlossaryApiError,
  GlossaryApiService,
  GlossaryCasePolicy,
  GlossaryCatalogItem,
  GlossaryCurrentTerm,
  GlossaryDraftCreated,
  GlossaryDraftTermInput,
  GlossaryMetadataSnapshot,
} from '../core/glossary-api.service';
import { PolicyOnboardingService } from '../core/policy-onboarding.service';

type EditorState =
  | 'loading'
  | 'guest'
  | 'read-only'
  | 'not-found'
  | 'error'
  | 'ready'
  | 'saved'
  | 'stale'
  | 'uncertain';

interface EditableTerm {
  id: string;
  sourceTerm: string;
  targetTerm: string;
  aliasesText: string;
  priorityText: string;
  casePolicy: GlossaryCasePolicy;
  doNotTranslate: boolean;
  contextNote: string;
}

const maxTerms = 1000;
const maxAliasesPerTerm = 25;
const maxAliasesTotal = 5000;

@Component({
  selector: 'app-glossary-editor-page',
  imports: [FormsModule, RouterLink],
  templateUrl: './glossary-editor-page.component.html',
  styleUrl: './glossary-editor-page.component.css',
})
export class GlossaryEditorPageComponent {
  protected readonly policy = inject(PolicyOnboardingService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly api = inject(GlossaryApiService);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly state = signal<EditorState>('loading');
  protected readonly snapshot = signal<GlossaryMetadataSnapshot | null>(null);
  protected readonly terms = signal<EditableTerm[]>([]);
  protected readonly errorMessage = signal<string | null>(null);
  protected readonly requiresSignIn = signal(false);
  protected readonly glossaryId = signal<string | null>(null);
  protected readonly sourceRevisionNumber = signal<number | null>(null);
  protected readonly sourceRevisionId = signal<string | null>(null);
  protected readonly result = signal<GlossaryDraftCreated | null>(null);
  protected readonly pending = signal(false);

  protected readonly aliasesCount = computed(() =>
    this.terms().reduce((count, term) => count + this.aliasesFor(term).length, 0),
  );
  protected readonly isDirty = computed(
    () => this.serializeTerms(this.terms()) !== this.initialTerms,
  );
  protected readonly validationErrors = computed(() => {
    const errors: string[] = [];
    const rows = this.terms();
    if (rows.length > maxTerms) errors.push(`Tối đa ${maxTerms} thuật ngữ trong một bản.`);
    if (this.aliasesCount() > maxAliasesTotal)
      errors.push(`Tối đa ${maxAliasesTotal} bí danh trong một bản.`);
    const ids = new Set<string>();
    rows.forEach((term, index) => {
      if (ids.has(term.id)) errors.push(`Mục ${index + 1}: mã thuật ngữ bị trùng.`);
      ids.add(term.id);
      if (!term.sourceTerm.trim()) errors.push(`Mục ${index + 1}: cần nhập thuật ngữ gốc.`);
      if (codePoints(term.sourceTerm.trim()) > 512)
        errors.push(`Mục ${index + 1}: thuật ngữ gốc vượt 512 ký tự.`);
      if (!term.doNotTranslate && !term.targetTerm.trim())
        errors.push(`Mục ${index + 1}: cần nhập cách dịch hoặc chọn “Giữ nguyên”.`);
      if (codePoints(term.targetTerm.trim()) > 4096)
        errors.push(`Mục ${index + 1}: cách dịch vượt 4.096 ký tự.`);
      const priority = Number(term.priorityText);
      if (!Number.isSafeInteger(priority) || priority < -1_000_000 || priority > 1_000_000) {
        errors.push(`Mục ${index + 1}: độ ưu tiên phải là số nguyên từ −1.000.000 đến 1.000.000.`);
      }
      if (codePoints(term.contextNote) > 2000)
        errors.push(`Mục ${index + 1}: ghi chú vượt 2.000 ký tự.`);
      const aliases = this.aliasesFor(term);
      if (aliases.length > maxAliasesPerTerm)
        errors.push(`Mục ${index + 1}: tối đa ${maxAliasesPerTerm} bí danh.`);
      if (aliases.some((alias) => codePoints(alias) > 512))
        errors.push(`Mục ${index + 1}: mỗi bí danh tối đa 512 ký tự.`);
    });
    return errors;
  });
  protected readonly canSave = computed(
    () =>
      this.state() === 'ready' &&
      !!this.snapshot()?.glossary.canWrite &&
      this.isDirty() &&
      this.validationErrors().length === 0 &&
      !this.pending(),
  );

  private initialTerms = '[]';
  private loadToken = 0;

  @ViewChild('savedHeading')
  set savedHeading(heading: ElementRef<HTMLElement> | undefined) {
    if (heading) queueMicrotask(() => heading.nativeElement.focus());
  }

  constructor() {
    this.route.paramMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((params) => {
      const id = params.get('glossaryId');
      this.glossaryId.set(id);
      void this.load(id, ++this.loadToken);
    });
  }

  async retry(): Promise<void> {
    await this.load(this.glossaryId(), ++this.loadToken);
  }

  canDeactivate(): boolean {
    return (
      this.state() !== 'ready' ||
      !this.isDirty() ||
      window.confirm(
        'Bạn có thay đổi chưa lưu. Rời trang sẽ bỏ các thay đổi này. Bạn có muốn tiếp tục?',
      )
    );
  }

  addTerm(): void {
    if (this.state() !== 'ready' || this.terms().length >= maxTerms) return;
    this.terms.update((terms) => [...terms, emptyTerm(crypto.randomUUID())]);
  }

  removeTerm(id: string): void {
    if (this.state() !== 'ready') return;
    this.terms.update((terms) => terms.filter((term) => term.id !== id));
  }

  updateTerm(id: string, changes: Partial<EditableTerm>): void {
    if (this.state() !== 'ready') return;
    this.terms.update((terms) =>
      terms.map((term) => (term.id === id ? { ...term, ...changes } : term)),
    );
  }

  async saveDraft(): Promise<void> {
    const snapshot = this.snapshot();
    const id = this.glossaryId();
    if (!snapshot || !id || !this.canSave()) return;
    this.pending.set(true);
    this.errorMessage.set(null);
    try {
      const created = await this.api.createDraftRevision(
        id,
        snapshot.etag,
        this.toPayload(this.terms()),
      );
      if (created.glossaryId !== id || created.parentRevisionId !== this.sourceRevisionId()) {
        throw new GlossaryApiError('INVALID_RESPONSE', -1, 'INVALID_RESPONSE');
      }
      this.result.set(created);
      this.state.set('saved');
    } catch (error) {
      if (
        error instanceof GlossaryApiError &&
        error.status === 409 &&
        error.code === 'GLOSSARY_VERSION_STALE'
      ) {
        this.errorMessage.set(
          'Glossary đã được thay đổi ở nơi khác. Hãy tải bản mới nhất và rà soát lại trước khi lưu để tránh ghi đè thay đổi của người khác.',
        );
        this.state.set('stale');
      } else if (error instanceof GlossaryApiError && error.status === 404) {
        this.terms.set([]);
        this.state.set('not-found');
      } else if (error instanceof GlossaryApiError && error.status === 403) {
        this.state.set('guest');
        this.terms.set([]);
        await this.router.navigate(['/app/setup'], {
          queryParams: { returnUrl: `/app/glossaries/${encodeURIComponent(id)}/edit` },
        });
      } else if (error instanceof GlossaryApiError && error.status === 401) {
        this.requiresSignIn.set(true);
        this.errorMessage.set('Phiên đăng nhập đã hết hạn. Đăng nhập lại để tiếp tục.');
        this.state.set('error');
      } else if (error instanceof GlossaryApiError && error.status >= 400 && error.status < 500) {
        this.errorMessage.set(
          'Máy chủ từ chối bản nháp. Kiểm tra các trường đã nhập rồi tải lại glossary nếu lỗi vẫn còn.',
        );
      } else {
        this.errorMessage.set(
          'Chưa xác định được lần lưu có thành công hay không. Không gửi lại tự động; hãy kiểm tra bản mới nhất trước.',
        );
        this.state.set('uncertain');
      }
    } finally {
      this.pending.set(false);
    }
  }

  protected async discardAndReload(): Promise<void> {
    await this.load(this.glossaryId(), ++this.loadToken);
  }

  protected editAnotherDraft(): void {
    this.result.set(null);
    void this.load(this.glossaryId(), ++this.loadToken);
  }

  protected aliasesFor(term: EditableTerm): string[] {
    return term.aliasesText
      .split(/\r?\n/)
      .map((alias) => alias.trim())
      .filter(Boolean);
  }

  protected languageLabel(language: string): string {
    return (
      (
        {
          vi: 'Tiếng Việt',
          en: 'Tiếng Anh',
          'zh-Hans': 'Tiếng Trung giản thể',
          'zh-Hant': 'Tiếng Trung phồn thể',
          ja: 'Tiếng Nhật',
          ko: 'Tiếng Hàn',
        } as Record<string, string>
      )[language] ?? language
    );
  }

  protected signInReturnTo(): string {
    const id = this.glossaryId();
    return id ? `/app/glossaries/${encodeURIComponent(id)}/edit` : '/app/glossaries';
  }

  protected termError(id: string): string | null {
    const index = this.terms().findIndex((term) => term.id === id);
    if (index < 0) return null;
    return (
      this.validationErrors().find((message) => message.startsWith(`Mục ${index + 1}:`)) ?? null
    );
  }

  private async load(id: string | null, token: number): Promise<void> {
    this.snapshot.set(null);
    this.terms.set([]);
    this.result.set(null);
    this.errorMessage.set(null);
    this.requiresSignIn.set(false);
    this.sourceRevisionId.set(null);
    this.sourceRevisionNumber.set(null);
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
      if (token !== this.loadToken) return;
      this.snapshot.set(snapshot);
      if (!snapshot.glossary.canWrite) {
        this.state.set('read-only');
        return;
      }
      let sourceTerms: GlossaryCurrentTerm[] = [];
      if (snapshot.glossary.latestRevisionNumber !== null) {
        const latest = await this.api.getLatestWritableRevision(id);
        if (token !== this.loadToken) return;
        if (
          latest.glossaryId !== id ||
          latest.version !== snapshot.glossary.version ||
          latest.revisionNumber !== snapshot.glossary.latestRevisionNumber ||
          latest.activeRevisionId !== snapshot.glossary.activeRevisionId
        ) {
          throw new GlossaryApiError('GLOSSARY_VERSION_STALE', 409, 'GLOSSARY_VERSION_STALE');
        }
        sourceTerms = latest.terms;
        this.sourceRevisionId.set(latest.revisionId);
        this.sourceRevisionNumber.set(latest.revisionNumber);
      }
      const editable = sourceTerms.map(toEditableTerm);
      this.terms.set(editable);
      this.initialTerms = this.serializeTerms(editable);
      this.state.set('ready');
    } catch (error) {
      if (token !== this.loadToken) return;
      if (error instanceof GlossaryApiError && (error.status === 404 || error.status === 409)) {
        this.errorMessage.set(
          error.status === 409
            ? 'Glossary vừa thay đổi trong lúc tải. Tải lại để bắt đầu từ phiên bản mới nhất.'
            : null,
        );
        this.state.set(error.status === 404 ? 'not-found' : 'error');
      } else if (error instanceof GlossaryApiError && error.status === 403) {
        this.state.set('guest');
        await this.router.navigate(['/app/setup'], {
          queryParams: { returnUrl: this.signInReturnTo() },
        });
      } else {
        this.requiresSignIn.set(error instanceof GlossaryApiError && error.status === 401);
        this.errorMessage.set(
          this.requiresSignIn()
            ? 'Đăng nhập lại để tiếp tục.'
            : 'Không tải được bản mới nhất. Glossary chưa bị thay đổi; hãy thử tải lại.',
        );
        this.state.set('error');
      }
    }
  }

  private toPayload(terms: EditableTerm[]): GlossaryDraftTermInput[] {
    return terms.map((term) => ({
      id: term.id,
      sourceTerm: term.sourceTerm.trim(),
      targetTerm: term.doNotTranslate ? null : term.targetTerm.trim(),
      priority: Number(term.priorityText),
      casePolicy: term.casePolicy,
      matchMode: 'WHOLE_PHRASE',
      doNotTranslate: term.doNotTranslate,
      contextNote: term.contextNote.trim() || null,
      aliases: this.aliasesFor(term),
    }));
  }

  private serializeTerms(terms: EditableTerm[]): string {
    return JSON.stringify(this.toPayload(terms));
  }
}

function toEditableTerm(term: GlossaryCurrentTerm): EditableTerm {
  return {
    id: term.id,
    sourceTerm: term.sourceTerm,
    targetTerm: term.targetTerm ?? '',
    aliasesText: term.aliases.join('\n'),
    priorityText: String(term.priority),
    casePolicy: term.casePolicy,
    doNotTranslate: term.doNotTranslate,
    contextNote: term.contextNote ?? '',
  };
}

function emptyTerm(id: string): EditableTerm {
  return {
    id,
    sourceTerm: '',
    targetTerm: '',
    aliasesText: '',
    priorityText: '0',
    casePolicy: 'INSENSITIVE',
    doNotTranslate: false,
    contextNote: '',
  };
}

function codePoints(value: string): number {
  return Array.from(value).length;
}
