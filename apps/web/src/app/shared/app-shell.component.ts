import { Component, computed, inject, Injector, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter } from 'rxjs';
import { PolicyOnboardingService } from '../core/policy-onboarding.service';
import { SystemStatusService } from '../core/system-status.service';

@Component({
  selector: 'app-shell',
  imports: [RouterLink, RouterLinkActive, RouterOutlet],
  templateUrl: './app-shell.component.html',
})
export class AppShellComponent {
  protected readonly policy = inject(PolicyOnboardingService);
  protected readonly systemStatus = inject(SystemStatusService);
  protected readonly showTranslationDesk = signal(false);
  private readonly injector = inject(Injector);
  private readonly router = inject(Router);
  protected readonly accountLabel = computed(() => {
    const account = this.policy.accountIdentity();
    return account?.displayName?.trim() || account?.contactEmail || 'Độc giả demo';
  });
  protected readonly accountContext = computed(() =>
    this.policy.accountIdentity() ? 'Tài khoản server' : 'Chưa đăng nhập',
  );
  protected readonly accountInitial = computed(
    () => Array.from(this.accountLabel().trim())[0]?.toLocaleUpperCase('vi-VN') || 'D',
  );

  protected keepFocusedContentAboveMobileNav(event: FocusEvent): void {
    const target = event.target;
    if (!(target instanceof HTMLElement)) return;
    const nav = document.querySelector<HTMLElement>('.mobile-nav');
    if (!nav || getComputedStyle(nav).display === 'none') return;

    const visibleBottom = nav.getBoundingClientRect().top - 12;
    const targetBottom = target.getBoundingClientRect().bottom;
    if (targetBottom > visibleBottom) {
      window.scrollBy({ top: targetBottom - visibleBottom, behavior: 'instant' });
    }
  }

  constructor() {
    void this.checkTranslationDeskAccess();
    this.router.events
      .pipe(
        filter(
          (event): event is NavigationEnd =>
            event instanceof NavigationEnd &&
            event.urlAfterRedirects.startsWith('/app/translation/jobs/'),
        ),
        takeUntilDestroyed(),
      )
      .subscribe(() => void this.checkTranslationDeskAccess());
  }

  private checkingTranslationDeskAccess = false;

  private async checkTranslationDeskAccess(): Promise<void> {
    if (this.checkingTranslationDeskAccess || this.showTranslationDesk()) return;
    this.checkingTranslationDeskAccess = true;
    try {
      await Promise.all([this.policy.load(), this.systemStatus.waitForStatus()]);
      if (
        !this.policy.accountIdentity() ||
        this.systemStatus.status()?.features.translationFakePreviewEnabled !== true
      ) {
        return;
      }
      const { TranslationJobApiService } = await import('../core/translation-job-api.service');
      const page = await this.injector.get(TranslationJobApiService).getManageableJobs(null, 1);
      this.showTranslationDesk.set(page.items.some(({ job }) => job.canManage));
    } catch {
      this.showTranslationDesk.set(false);
    } finally {
      this.checkingTranslationDeskAccess = false;
    }
  }
}
