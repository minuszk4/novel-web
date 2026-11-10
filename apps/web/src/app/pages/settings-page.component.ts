import { Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { DemoStateService } from '../core/demo-state.service';

@Component({
  selector: 'app-settings-page',
  imports: [RouterLink],
  templateUrl: './settings-page.component.html',
})
export class SettingsPageComponent {
  protected readonly demo = inject(DemoStateService);
}
