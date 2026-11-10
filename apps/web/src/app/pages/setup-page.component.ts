import { Component, inject } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { DemoStateService } from '../core/demo-state.service';

@Component({
  selector: 'app-setup-page',
  imports: [RouterLink],
  templateUrl: './setup-page.component.html',
})
export class SetupPageComponent {
  protected readonly demo = inject(DemoStateService);
  private readonly router = inject(Router);

  continue(): void {
    if (!this.demo.canContinueSetup) return;
    void this.router.navigateByUrl('/app/home');
  }
}
