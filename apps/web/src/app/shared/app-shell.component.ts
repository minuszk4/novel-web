import { Component, computed, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
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
}
