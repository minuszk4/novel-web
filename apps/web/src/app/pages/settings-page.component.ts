import { Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { DemoStateService } from '../core/demo-state.service';
import { PolicyOnboardingService } from '../core/policy-onboarding.service';
import { WorkSubscriptionSettingsComponent } from './work-subscription-settings.component';
import { AccountDataExportSettingsComponent } from './account-data-export-settings.component';

@Component({
  selector: 'app-settings-page',
  imports: [RouterLink, WorkSubscriptionSettingsComponent, AccountDataExportSettingsComponent],
  templateUrl: './settings-page.component.html',
})
export class SettingsPageComponent {
  protected readonly demo = inject(DemoStateService);
  protected readonly policy = inject(PolicyOnboardingService);
}
