import { Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { DemoStateService } from '../core/demo-state.service';

@Component({
  selector: 'app-home-page',
  imports: [RouterLink],
  templateUrl: './home-page.component.html',
})
export class HomePageComponent {
  protected readonly demo = inject(DemoStateService);
}
