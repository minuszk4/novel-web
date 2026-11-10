import { Component, ElementRef, computed, inject, ViewChild } from '@angular/core';
import { DatePipe } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import { AccountImportService, maxAccountImportBytes } from '../core/account-import.service';
import { DemoStateService } from '../core/demo-state.service';
import { ImportFileMetadata } from '../core/models';
import { PolicyOnboardingService } from '../core/policy-onboarding.service';

const supportedExtensions = ['epub', 'txt', 'html', 'htm', 'md', 'markdown'];

@Component({
  selector: 'app-import-page',
  imports: [DatePipe, RouterLink],
  templateUrl: './import-page.component.html',
})
export class ImportPageComponent {
  protected readonly demo = inject(DemoStateService);
  protected readonly accountImport = inject(AccountImportService);
  protected readonly policy = inject(PolicyOnboardingService);
  protected readonly accountMode = computed(() => this.policy.accountIdentity() !== null);
  protected readonly selectedFile = computed(() => this.accountMode()
    ? this.accountImport.fileMetadata()
    : this.demo.selectedFile());
  protected readonly rightsConfirmed = computed(() => this.accountMode()
    ? this.accountImport.rightsConfirmed()
    : this.demo.rightsConfirmed());
  protected readonly continueDisabled = computed(() => this.accountMode()
    ? !this.accountImport.canStart()
      || this.accountImport.state() === 'error' && !this.accountImport.retryable()
    : !this.demo.canReviewImport);
  protected errorMessage = '';
  private readonly router = inject(Router);

  @ViewChild('workFileInput') private workFileInput?: ElementRef<HTMLInputElement>;

  constructor() {
    if (this.accountMode()) {
      void this.accountImport.loadPrerequisites();
    } else if (!this.demo.canContinueSetup) {
      void this.router.navigateByUrl('/app/setup');
    }
  }

  onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    this.errorMessage = '';
    if (!file) return;

    const extension = file.name.split('.').pop()?.toLowerCase() ?? '';
    const allowedExtensions = this.accountMode() ? ['epub'] : supportedExtensions;
    if (!allowedExtensions.includes(extension)) {
      this.clearSelectedFile();
      this.errorMessage = this.accountMode()
        ? 'Tài khoản hiện chỉ nhận EPUB 3. TXT, HTML và Markdown chưa có bộ phân tích trên máy chủ.'
        : 'Định dạng này chưa được hỗ trợ. Hãy chọn EPUB, TXT, HTML hoặc Markdown.';
      input.value = '';
      return;
    }

    if (this.accountMode() && (file.size < 1 || file.size > maxAccountImportBytes)) {
      this.clearSelectedFile();
      this.errorMessage = 'Tệp EPUB phải lớn hơn 0 byte và không vượt quá 50 MB.';
      input.value = '';
      return;
    }

    const metadata: ImportFileMetadata = {
      name: file.name,
      size: file.size,
      type: file.type || 'Không xác định',
      extension,
    };
    if (this.accountMode()) {
      this.accountImport.selectFile(file);
    } else {
      // The demo intentionally keeps metadata only. It never reads or uploads file bytes.
      this.demo.selectFile(metadata);
    }
  }

  async continueToReview(): Promise<void> {
    if (this.accountMode()) {
      if (this.accountImport.state() === 'review') {
        await this.router.navigateByUrl('/app/import/review');
        return;
      }
      const ready = await this.accountImport.startOrResumeImport();
      if (ready) await this.router.navigateByUrl('/app/import/review');
      return;
    }
    if (this.demo.canReviewImport) await this.router.navigateByUrl('/app/import/review');
  }

  chooseDifferentFile(): void {
    const input = this.workFileInput?.nativeElement;
    if (!input) return;
    if (this.accountMode()) this.accountImport.selectFile(null);
    else this.demo.selectFile(null);
    input.value = '';
    input.click();
  }

  async retryPrerequisites(): Promise<void> {
    await this.accountImport.loadPrerequisites();
  }

  private clearSelectedFile(): void {
    if (this.accountMode()) this.accountImport.selectFile(null);
    else this.demo.selectFile(null);
  }

  formatSize(bytes: number): string {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`;
    return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  }
}
