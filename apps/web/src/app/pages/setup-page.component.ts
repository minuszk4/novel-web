import { Component, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { DemoStateService } from '../core/demo-state.service';
import { PolicyOnboardingService } from '../core/policy-onboarding.service';

@Component({
  selector: 'app-setup-page',
  imports: [RouterLink],
  templateUrl: './setup-page.component.html',
})
export class SetupPageComponent {
  protected readonly demo = inject(DemoStateService);
  protected readonly policy = inject(PolicyOnboardingService);
  protected readonly termsChecked = signal(false);
  protected readonly privacyChecked = signal(false);
  protected readonly submitting = signal(false);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  constructor() {
    void this.policy.load();
  }

  get canContinue(): boolean {
    const status = this.policy.status();
    if (status === 'accepted') return true;
    return (status === 'demo' || status === 'pending' || status === 'stale')
      && this.termsChecked()
      && this.privacyChecked();
  }

  get isServerDecisionPending(): boolean {
    return this.policy.status() === 'pending' || this.policy.status() === 'stale';
  }

  setTermsChecked(checked: boolean): void {
    this.termsChecked.set(checked);
  }

  setPrivacyChecked(checked: boolean): void {
    this.privacyChecked.set(checked);
  }

  async continue(): Promise<void> {
    if (!this.canContinue || this.submitting()) return;
    this.submitting.set(true);
    try {
      if (this.policy.status() === 'demo') {
        this.demo.acceptTerms(this.termsChecked());
        this.demo.acknowledgePrivacy(this.privacyChecked());
      } else if (this.isServerDecisionPending) {
        const previousTermsVersion = this.policy.currentPolicies()?.terms.version;
        const previousPrivacyVersion = this.policy.currentPolicies()?.privacyNotice.version;
        await this.policy.acceptCurrentPolicies();
        if (this.policy.status() !== 'accepted') {
          const current = this.policy.currentPolicies();
          if (current && (current.terms.version !== previousTermsVersion
            || current.privacyNotice.version !== previousPrivacyVersion)) {
            this.termsChecked.set(false);
            this.privacyChecked.set(false);
          }
          return;
        }
      }
      await this.router.navigateByUrl(this.safeReturnUrl());
    } finally {
      this.submitting.set(false);
    }
  }

  async retry(): Promise<void> {
    this.termsChecked.set(false);
    this.privacyChecked.set(false);
    await this.policy.load(true);
  }

  private safeReturnUrl(): string {
    const candidate = this.route.snapshot.queryParamMap.get('returnUrl');
    const allowedPaths = new Set([
      '/app/home',
      '/app/library',
      '/app/search',
      '/app/import',
      '/app/import/review',
      '/app/cultivation',
      '/app/settings',
    ]);
    return candidate && allowedPaths.has(candidate) ? candidate : '/app/home';
  }
}
