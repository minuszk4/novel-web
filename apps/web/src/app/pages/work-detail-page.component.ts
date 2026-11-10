import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { demoChapters, demoWork } from '../core/demo-story';

@Component({
  selector: 'app-work-detail-page',
  imports: [RouterLink],
  templateUrl: './work-detail-page.component.html',
})
export class WorkDetailPageComponent {
  protected readonly work = demoWork;
  protected readonly chapters = demoChapters;
}
