import {
  AfterViewInit,
  Component,
  ElementRef,
  computed,
  inject,
  signal,
  ViewChild,
} from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import {
  AccountImportService,
  ImportChapterPreview,
  ImportPreview,
} from '../core/account-import.service';
import { DemoStateService } from '../core/demo-state.service';
import { PolicyOnboardingService } from '../core/policy-onboarding.service';
import { ImportCancelControlComponent } from '../shared/import-cancel-control.component';

@Component({
  selector: 'app-import-review-page',
  imports: [DecimalPipe, ImportCancelControlComponent, RouterLink],
  templateUrl: './import-review-page.component.html',
})
export class ImportReviewPageComponent implements AfterViewInit {
  protected readonly chapterPageSize = 50;
  protected readonly demo = inject(DemoStateService);
  protected readonly accountImport = inject(AccountImportService);
  protected readonly policy = inject(PolicyOnboardingService);
  protected readonly accountMode = computed(() => this.policy.accountIdentity() !== null);
  protected readonly title = signal('');
  protected readonly author = signal('');
  protected readonly language = signal('');
  protected readonly chapters = signal<ImportChapterPreview[]>([]);
  protected readonly chapterPage = signal(0);
  protected readonly chapterPageCount = computed(() =>
    Math.max(1, Math.ceil(this.chapters().length / this.chapterPageSize)),
  );
  protected readonly fileType = computed(
    () => this.accountImport.fileMetadata()?.extension.toUpperCase() ?? 'TỆP',
  );
  protected readonly visibleChapters = computed(() => {
    const start = this.chapterPage() * this.chapterPageSize;
    return this.chapters().slice(start, start + this.chapterPageSize);
  });
  protected readonly hasConflict = signal(false);
  protected readonly hasUnsavedChanges = computed(() => {
    const preview = this.accountImport.preview();
    if (!preview) return false;
    return (
      this.title() !== preview.title ||
      this.author() !== preview.author ||
      this.language() !== preview.language ||
      this.chapters().some((chapter, index) => chapter.id !== preview.chapters[index]?.id)
    );
  });
  protected readonly metadataValid = computed(
    () =>
      this.title().trim().length > 0 &&
      [...this.title().trim()].length <= 300 &&
      [...this.author()].length <= 200 &&
      this.language().trim().length >= 2 &&
      this.language().trim().length <= 35,
  );
  protected readonly canSave = computed(
    () =>
      this.accountMode() &&
      this.hasUnsavedChanges() &&
      this.metadataValid() &&
      !this.accountImport.isBusy() &&
      !this.hasConflict(),
  );
  protected readonly canCommit = computed(
    () =>
      this.accountMode() &&
      !!this.accountImport.preview() &&
      !this.hasUnsavedChanges() &&
      !this.hasConflict() &&
      !this.accountImport.isBusy() &&
      this.accountImport.state() !== 'committed',
  );
  private readonly router = inject(Router);
  @ViewChild('reviewHeading') private reviewHeading?: ElementRef<HTMLHeadingElement>;

  constructor() {
    if (this.accountMode()) {
      const preview = this.accountImport.preview();
      if (!preview) {
        void this.router.navigateByUrl('/app/import');
        return;
      }
      this.setFromPreview(preview);
      return;
    }
    const file = this.demo.selectedFile();
    if (!file || !this.demo.rightsConfirmed()) {
      void this.router.navigateByUrl('/app/import');
      return;
    }
    this.title.set(file.name.replace(/\.[^.]+$/, ''));
  }

  ngAfterViewInit(): void {
    this.reviewHeading?.nativeElement.focus({ preventScroll: true });
  }

  updateTitle(event: Event): void {
    this.title.set((event.target as HTMLInputElement).value);
  }

  updateAuthor(event: Event): void {
    this.author.set((event.target as HTMLInputElement).value);
  }

  updateLanguage(event: Event): void {
    this.language.set((event.target as HTMLInputElement).value);
  }

  moveChapter(index: number, direction: -1 | 1): void {
    const current = this.chapters();
    const nextIndex = index + direction;
    if (nextIndex < 0 || nextIndex >= current.length) return;
    const reordered = [...current];
    [reordered[index], reordered[nextIndex]] = [reordered[nextIndex], reordered[index]];
    this.chapters.set(reordered);
  }

  setChapterPage(page: number): void {
    if (page >= 0 && page < this.chapterPageCount()) this.chapterPage.set(page);
  }

  async saveChanges(): Promise<void> {
    if (!this.canSave()) return;
    const currentVersion = this.accountImport.preview()?.version;
    const currentEtag = this.accountImport.previewEtag();
    const saved = await this.accountImport.savePreview(
      this.title().trim(),
      this.author().trim(),
      this.language().trim(),
      this.chapters(),
    );
    if (saved) {
      const preview = this.accountImport.preview();
      if (preview) this.setFromPreview(preview);
      this.hasConflict.set(false);
      return;
    }
    if (
      this.accountImport.preview()?.version !== currentVersion ||
      this.accountImport.previewEtag() !== currentEtag
    ) {
      this.hasConflict.set(true);
    }
  }

  reloadLatestPreview(): void {
    const preview = this.accountImport.preview();
    if (!preview) return;
    this.setFromPreview(preview);
    this.hasConflict.set(false);
  }

  async commitPrivateImport(): Promise<void> {
    if (!this.canCommit()) return;
    const previousEtag = this.accountImport.previewEtag();
    const committed = await this.accountImport.commitImport();
    if (committed) this.hasConflict.set(false);
    else if (this.accountImport.previewEtag() !== previousEtag) this.hasConflict.set(true);
  }

  finishImport(): void {
    if (this.accountMode()) return;
    if (!this.demo.canReviewImport) return;
    this.demo.commitDemoImport(this.title(), this.author());
    void this.router.navigateByUrl('/app/home');
  }

  startDifferentImport(): void {
    if (this.accountMode() && this.accountImport.hasActiveJob()) return;
    this.accountImport.selectFile(null);
    void this.router.navigateByUrl('/app/import');
  }

  private setFromPreview(preview: ImportPreview): void {
    this.title.set(preview.title);
    this.author.set(preview.author);
    this.language.set(preview.language);
    this.chapters.set([...preview.chapters]);
    this.chapterPage.update((page) =>
      Math.min(page, Math.max(0, Math.ceil(preview.chapters.length / this.chapterPageSize) - 1)),
    );
  }
}
