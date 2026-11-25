import { DatePipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import {
  AuthoringCollaborationApiError,
  AuthoringCollaborationApiService,
  ManuscriptPermission,
  SharedManuscript,
} from '../core/authoring-collaboration-api.service';
import {
  AuthoringApiError,
  AuthoringApiService,
  PrivateAuthoredChapterDraft,
  PrivateChapterOutlineItem,
} from '../core/authoring-api.service';

type PageState = 'loading' | 'ready' | 'error';

@Component({
  selector: 'app-authoring-shared-page',
  imports: [DatePipe, RouterLink],
  templateUrl: './authoring-shared-page.component.html',
  styleUrl: './authoring-shared-page.component.css',
})
export class AuthoringSharedPageComponent {
  private readonly collaborationApi = inject(AuthoringCollaborationApiService);
  private readonly authoringApi = inject(AuthoringApiService);

  protected readonly state = signal<PageState>('loading');
  protected readonly items = signal<SharedManuscript[]>([]);
  protected readonly expanded = signal<Record<string, boolean>>({});
  protected readonly chapterItems = signal<Record<string, PrivateChapterOutlineItem[]>>({});
  protected readonly loadingWorks = signal<Record<string, boolean>>({});
  protected readonly drafts = signal<Record<string, PrivateAuthoredChapterDraft>>({});
  protected readonly loadingDrafts = signal<Record<string, boolean>>({});
  protected readonly error = signal<string | null>(null);
  protected readonly pageError = signal<string | null>(null);

  constructor() {
    void this.reload();
  }

  protected permissionLabel(permissions: ManuscriptPermission[]): string {
    return permissions.includes('EDIT') ? 'Xem và chỉnh sửa' : 'Chỉ xem';
  }

  protected canEdit(item: SharedManuscript): boolean {
    return item.permissions.includes('EDIT');
  }

  protected async toggleWork(item: SharedManuscript): Promise<void> {
    if (this.expanded()[item.workId]) {
      this.expanded.update((current) => ({ ...current, [item.workId]: false }));
      return;
    }
    this.expanded.update((current) => ({ ...current, [item.workId]: true }));
    if (this.chapterItems()[item.workId]) return;
    this.loadingWorks.update((current) => ({ ...current, [item.workId]: true }));
    this.error.set(null);
    try {
      const page = await this.authoringApi.listPrivateChapters(item.workId, undefined, 25);
      this.chapterItems.update((current) => ({ ...current, [item.workId]: page.items }));
    } catch (error) {
      this.error.set(this.authoringError(error));
    } finally {
      this.loadingWorks.update((current) => ({ ...current, [item.workId]: false }));
    }
  }

  protected async openDraft(
    item: SharedManuscript,
    chapter: PrivateChapterOutlineItem,
  ): Promise<void> {
    const key = `${item.workId}:${chapter.id}`;
    if (this.drafts()[key] || this.loadingDrafts()[key]) return;
    this.loadingDrafts.update((current) => ({ ...current, [key]: true }));
    this.error.set(null);
    try {
      const draft = await this.authoringApi.getPrivateChapterDraft(item.workId, chapter.id);
      this.drafts.update((current) => ({ ...current, [key]: draft }));
    } catch (error) {
      this.error.set(this.authoringError(error));
    } finally {
      this.loadingDrafts.update((current) => ({ ...current, [key]: false }));
    }
  }

  protected draftFor(
    item: SharedManuscript,
    chapter: PrivateChapterOutlineItem,
  ): PrivateAuthoredChapterDraft | null {
    return this.drafts()[`${item.workId}:${chapter.id}`] ?? null;
  }

  protected async retry(): Promise<void> {
    await this.reload();
  }

  private async reload(): Promise<void> {
    this.state.set('loading');
    this.pageError.set(null);
    try {
      this.items.set(await this.collaborationApi.listSharedWorks());
      this.state.set('ready');
    } catch (error) {
      this.pageError.set(
        error instanceof AuthoringCollaborationApiError && error.status === 401
          ? 'Đăng nhập để xem các bản thảo được chia sẻ với bạn.'
          : error instanceof AuthoringCollaborationApiError &&
              error.status === 403 &&
              error.code === 'POLICY_ACCEPTANCE_REQUIRED'
            ? 'Hãy xác nhận chính sách hiện hành để xem các bản thảo được chia sẻ.'
            : error instanceof AuthoringCollaborationApiError && error.status === 503
              ? 'Tính năng cộng tác chưa được bật hoặc chưa cấu hình xong.'
              : 'Chưa tải được danh sách bản thảo được chia sẻ.',
      );
      this.state.set('error');
    }
  }

  private authoringError(error: unknown): string {
    if (error instanceof AuthoringApiError && error.status === 404) {
      return 'Bản thảo này không còn được chia sẻ với bạn. Tải lại danh sách để kiểm tra.';
    }
    if (error instanceof AuthoringApiError && error.status === 403) {
      return 'Bạn không có quyền xem nội dung này. Hãy yêu cầu owner cấp lại quyền.';
    }
    return 'Chưa tải được nội dung. Hãy thử lại sau.';
  }
}
