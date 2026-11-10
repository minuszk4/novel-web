import { Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { DemoStateService } from '../core/demo-state.service';

@Component({
  selector: 'app-search-page',
  imports: [RouterLink],
  templateUrl: './search-page.component.html',
})
export class SearchPageComponent {
  protected readonly demo = inject(DemoStateService);
  protected readonly query = signal('');

  updateQuery(event: Event): void {
    this.query.set((event.target as HTMLInputElement).value.trim().toLocaleLowerCase('vi'));
  }

  clearQuery(input: HTMLInputElement): void {
    this.query.set('');
    input.focus();
  }

  get hasMatch(): boolean {
    const work = this.demo.importedWork();
    if (!work) return false;
    return !this.query() || `${work.title} ${work.author}`.toLocaleLowerCase('vi').includes(this.query());
  }
}
