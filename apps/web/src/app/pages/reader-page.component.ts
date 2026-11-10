import {
  Component,
  DestroyRef,
  ElementRef,
  HostListener,
  inject,
  signal,
  ViewChild,
} from '@angular/core';
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
  private panelReturnFocus: HTMLElement | null = null;

  @ViewChild('tocPanel') private set tocPanel(panel: ElementRef<HTMLElement> | undefined) {
    if (panel) this.focusPanel(panel.nativeElement);
  }

  @ViewChild('settingsPanel') private set settingsPanel(
    panel: ElementRef<HTMLElement> | undefined,
  ) {
    if (panel) this.focusPanel(panel.nativeElement);
  }

  constructor(route: ActivatedRoute) {
    route.paramMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((params) => {
      const routeId = params.get('chapterId');
      const index = this.chapters.findIndex((chapter) => chapter.id === routeId);
      this.chapterIndex.set(index >= 0 ? index : 0);
      this.tocOpen.set(false);
      this.settingsOpen.set(false);
      this.panelReturnFocus = null;
      if (typeof window !== 'undefined') {
        requestAnimationFrame(() => {
          document
            .querySelector<HTMLElement>('.reader-content-scroller')
            ?.scrollTo({ top: 0, behavior: 'auto' });
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
    this.closePanel(false);
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

  toggleToc(event?: Event): void {
    if (this.tocOpen()) {
      this.closePanel();
      return;
    }
    this.openPanel('toc', event);
  }

  toggleSettings(event?: Event): void {
    if (this.settingsOpen()) {
      this.closePanel();
      return;
    }
    this.openPanel('settings', event);
  }

  closePanel(restoreFocus = true): void {
    const returnFocus = this.panelReturnFocus;
    this.tocOpen.set(false);
    this.settingsOpen.set(false);
    this.panelReturnFocus = null;
    if (restoreFocus && returnFocus?.isConnected) returnFocus.focus({ preventScroll: true });
  }

  formatPercent(value: number): string {
    return `${value}%`;
  }

  @HostListener('document:keydown', ['$event'])
  onKeydown(event: KeyboardEvent): void {
    const panel = this.openPanelElement();
    if (event.key === 'Escape' && panel) {
      this.closePanel();
      event.preventDefault();
      return;
    }
    if (event.key === 'Tab' && panel) {
      this.keepTabFocusInsidePanel(event, panel);
      return;
    }

    const target = event.target as HTMLElement | null;
    if (target?.closest('input, textarea, select, [contenteditable="true"]')) return;
    if (event.ctrlKey || event.metaKey) return;

    // Modifier-based single-letter shortcuts avoid hijacking screen-reader browse keys.
    if (!event.altKey || !event.shiftKey) return;
    if (event.key.toLowerCase() === 't') {
      this.toggleToc(event);
      event.preventDefault();
    } else if (event.key.toLowerCase() === 'j' && this.nextChapter) {
      this.openChapter(this.nextChapter.id);
      event.preventDefault();
    } else if (event.key.toLowerCase() === 'k' && this.previousChapter) {
      this.openChapter(this.previousChapter.id);
      event.preventDefault();
    }
  }

  private openPanel(panel: 'toc' | 'settings', event?: Event): void {
    if (!this.tocOpen() && !this.settingsOpen()) {
      this.panelReturnFocus = this.getPanelOpener(event);
    }
    this.tocOpen.set(panel === 'toc');
    this.settingsOpen.set(panel === 'settings');
  }

  private getPanelOpener(event?: Event): HTMLElement | null {
    const currentTarget = event?.currentTarget;
    if (currentTarget instanceof HTMLElement) return currentTarget;

    const eventTarget = event?.target;
    if (eventTarget instanceof HTMLElement) return eventTarget;

    return document.activeElement instanceof HTMLElement ? document.activeElement : null;
  }

  private openPanelElement(): HTMLElement | null {
    if (this.tocOpen()) return document.getElementById('reader-toc-panel');
    if (this.settingsOpen()) return document.getElementById('reader-settings-panel');
    return null;
  }

  private focusPanel(panel: HTMLElement): void {
    const initialTarget = panel.querySelector<HTMLElement>('[data-panel-initial-focus]') ?? panel;
    initialTarget.focus({ preventScroll: true });
  }

  private keepTabFocusInsidePanel(event: KeyboardEvent, panel: HTMLElement): void {
    const focusable = Array.from(
      panel.querySelectorAll<HTMLElement>(
        'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
      ),
    );
    if (focusable.length === 0) {
      event.preventDefault();
      panel.focus({ preventScroll: true });
      return;
    }

    const first = focusable[0];
    const last = focusable[focusable.length - 1];
    const active = document.activeElement;
    if (event.shiftKey && (active === first || !panel.contains(active))) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && (active === last || !panel.contains(active))) {
      event.preventDefault();
      first.focus();
    }
  }
}
