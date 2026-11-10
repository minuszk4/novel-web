import { Component, inject, signal } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import { DemoStateService } from '../core/demo-state.service';

@Component({
  selector: 'app-import-review-page',
  imports: [DecimalPipe, RouterLink],
  templateUrl: './import-review-page.component.html',
})
export class ImportReviewPageComponent {
  protected readonly demo = inject(DemoStateService);
  protected readonly title = signal('');
  protected readonly author = signal('');
  private readonly router = inject(Router);

  constructor() {
    const file = this.demo.selectedFile();
    if (!file || !this.demo.rightsConfirmed()) {
      void this.router.navigateByUrl('/app/import');
      return;
    }
    this.title.set(file.name.replace(/\.[^.]+$/, ''));
  }

  updateTitle(event: Event): void {
    this.title.set((event.target as HTMLInputElement).value);
  }

  updateAuthor(event: Event): void {
    this.author.set((event.target as HTMLInputElement).value);
  }

  finishImport(): void {
    if (!this.demo.canReviewImport) return;
    this.demo.commitDemoImport(this.title(), this.author());
    void this.router.navigateByUrl('/app/home');
  }
}
