import { Component, OnDestroy, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { DemoStateService } from '../core/demo-state.service';

@Component({
  selector: 'app-search-page',
  imports: [RouterLink],
  templateUrl: './search-page.component.html',
})
export class SearchPageComponent implements OnDestroy {
  protected readonly demo = inject(DemoStateService);
  protected readonly query = signal('');
  protected readonly resultAnnouncement = signal('');
  private resultAnnouncementTimer?: ReturnType<typeof setTimeout>;

  ngOnDestroy(): void {
    if (this.resultAnnouncementTimer !== undefined) {
      clearTimeout(this.resultAnnouncementTimer);
    }
  }

  updateQuery(event: Event): void {
    this.query.set((event.target as HTMLInputElement).value.trim().toLocaleLowerCase('vi'));
    this.scheduleResultAnnouncement();
  }

  clearQuery(input: HTMLInputElement): void {
    this.query.set('');
    input.focus();
    this.announceResultsImmediately();
  }

  announceResultsImmediately(): void {
    if (this.resultAnnouncementTimer !== undefined) {
      clearTimeout(this.resultAnnouncementTimer);
      this.resultAnnouncementTimer = undefined;
    }
    this.resultAnnouncement.set(this.createResultAnnouncement());
  }

  private scheduleResultAnnouncement(): void {
    if (this.resultAnnouncementTimer !== undefined) {
      clearTimeout(this.resultAnnouncementTimer);
    }
    this.resultAnnouncementTimer = setTimeout(() => {
      this.resultAnnouncementTimer = undefined;
      this.resultAnnouncement.set(this.createResultAnnouncement());
    }, 300);
  }

  private createResultAnnouncement(): string {
    const workAvailable = this.demo.importedWork();
    if (!this.query()) {
      return workAvailable ? 'Có 1 kết quả trong thư viện riêng tư.' : 'Thư viện vẫn đang trống.';
    }
    return this.hasMatch
      ? `Tìm thấy 1 kết quả cho “${this.query()}”.`
      : `Không tìm thấy kết quả cho “${this.query()}”.`;
  }

  get hasMatch(): boolean {
    const work = this.demo.importedWork();
    if (!work) return false;
    return (
      !this.query() || `${work.title} ${work.author}`.toLocaleLowerCase('vi').includes(this.query())
    );
  }
}
