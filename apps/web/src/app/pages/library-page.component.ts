import { Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { DemoStateService } from '../core/demo-state.service';

@Component({
  selector: 'app-library-page',
  imports: [RouterLink],
  templateUrl: './library-page.component.html',
})
export class LibraryPageComponent {
  protected readonly demo = inject(DemoStateService);
}
