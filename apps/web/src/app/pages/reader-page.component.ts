import { Component, DestroyRef, HostListener, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { DemoStateService } from '../core/demo-state.service';
import { demoChapters, demoWork } from '../core/demo-story';

@Component({
  selector: 'app-reader-page',
  imports: [RouterLink],
  templateUrl: './reader-page.component.html',
})
export class ReaderPageComponent {
  protected readonly demo = inject(DemoStateService);
  protected readonly demoWorkTitle = demoWork.title;
  protected readonly chapters = demoChapters;
  protected readonly chapterIndex = signal(0);
  protected readonly tocOpen = signal(false);
  protected readonly settingsOpen = signal(false);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  constructor(route: ActivatedRoute) {
    route.paramMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((params) => {
      const routeId = params.get('chapterId');
      const index = this.chapters.findIndex((chapter) => chapter.id === routeId);
      this.chapterIndex.set(index >= 0 ? index : 0);
      this.tocOpen.set(false);
      this.settingsOpen.set(false);
      if (typeof window !== 'undefined') {
        requestAnimationFrame(() => {
          document.querySelector<HTMLElement>('.reader-content-scroller')?.scrollTo({ top: 0, behavior: 'auto' });
          document.getElementById('reader-title')?.focus({ preventScroll: true });
        });
      }
    });
  }

  get chapter() {
    return this.chapters[this.chapterIndex()];
  }

  get previousChapter() {
    return this.chapters[this.chapterIndex() - 1] ?? null;
  }

  get nextChapter() {
    return this.chapters[this.chapterIndex() + 1] ?? null;
  }

  openChapter(id: string): void {
    this.tocOpen.set(false);
    void this.router.navigate(['/read/demo-story', id]);
  }

  recordProgress(event: Event): void {
    const element = event.target as HTMLElement;
    const availableScroll = element.scrollHeight - element.clientHeight;
    const percent = availableScroll <= 0 ? 100 : (element.scrollTop / availableScroll) * 100;
    this.demo.saveReaderProgress(this.chapter.id, percent);
  }

  changeFont(amount: number): void {
    this.demo.changeReaderFontSize(this.demo.readerFontSize() + amount);
  }

  toggleBookmark(): void {
    this.demo.toggleBookmark(this.chapter.id);
  }

  toggleToc(): void {
    this.tocOpen.update((open) => !open);
    this.settingsOpen.set(false);
  }

  toggleSettings(): void {
    this.settingsOpen.update((open) => !open);
    this.tocOpen.set(false);
  }

  formatPercent(value: number): string {
    return `${value}%`;
  }

  @HostListener('document:keydown', ['$event'])
  onKeydown(event: KeyboardEvent): void {
    const target = event.target as HTMLElement | null;
    if (target?.closest('input, textarea, select, [contenteditable="true"]')) return;
    if (event.altKey || event.ctrlKey || event.metaKey) return;

    if (event.key === 'Escape' && (this.tocOpen() || this.settingsOpen())) {
      this.tocOpen.set(false);
      this.settingsOpen.set(false);
      event.preventDefault();
      return;
    }
    // Modifier-based single-letter shortcuts avoid hijacking screen-reader browse keys.
    if (!event.altKey || !event.shiftKey) return;
    if (event.key.toLowerCase() === 't') {
      this.tocOpen.update((open) => !open);
      this.settingsOpen.set(false);
      event.preventDefault();
    } else if (event.key.toLowerCase() === 'j' && this.nextChapter) {
      this.openChapter(this.nextChapter.id);
      event.preventDefault();
    } else if (event.key.toLowerCase() === 'k' && this.previousChapter) {
      this.openChapter(this.previousChapter.id);
      event.preventDefault();
    }
  }
}
