import { Component, ElementRef, ViewChild, inject } from '@angular/core';
import { Router } from '@angular/router';
import { AccountImportService } from '../core/account-import.service';

@Component({
  selector: 'app-import-cancel-control',
  templateUrl: './import-cancel-control.component.html',
})
export class ImportCancelControlComponent {
  protected readonly accountImport = inject(AccountImportService);
  private readonly router = inject(Router);

  @ViewChild('confirmDialog') private confirmDialog?: ElementRef<HTMLDialogElement>;

  requestCancellation(): void {
    this.confirmDialog?.nativeElement.showModal();
  }

  async confirmCancellation(): Promise<void> {
    if (await this.accountImport.cancelCurrentImport()) {
      this.confirmDialog?.nativeElement.close();
      if (this.router.url === '/app/import/review') {
        await this.router.navigateByUrl('/app/import');
      }
    }
  }
}
