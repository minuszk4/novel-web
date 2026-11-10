import { Component, inject } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { DemoStateService } from '../core/demo-state.service';
import { ImportFileMetadata } from '../core/models';

const supportedExtensions = ['epub', 'txt', 'html', 'htm', 'md', 'markdown'];

@Component({
  selector: 'app-import-page',
  imports: [RouterLink],
  templateUrl: './import-page.component.html',
})
export class ImportPageComponent {
  protected readonly demo = inject(DemoStateService);
  protected errorMessage = '';
  private readonly router = inject(Router);

  constructor() {
    if (!this.demo.canContinueSetup) void this.router.navigateByUrl('/app/setup');
  }

  onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    this.errorMessage = '';
    if (!file) return;

    const extension = file.name.split('.').pop()?.toLowerCase() ?? '';
    if (!supportedExtensions.includes(extension)) {
      this.demo.selectFile(null);
      this.errorMessage = 'Định dạng này chưa được hỗ trợ. Hãy chọn EPUB, TXT, HTML hoặc Markdown.';
      input.value = '';
      return;
    }

    const metadata: ImportFileMetadata = {
      name: file.name,
      size: file.size,
      type: file.type || 'Không xác định',
      extension,
    };
    // The demo intentionally keeps metadata only. It never reads or uploads file bytes.
    this.demo.selectFile(metadata);
  }

  continueToReview(): void {
    if (!this.demo.canReviewImport) return;
    void this.router.navigateByUrl('/app/import/review');
  }

  formatSize(bytes: number): string {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`;
    return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  }
}
