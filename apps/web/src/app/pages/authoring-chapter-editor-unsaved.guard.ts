import { CanDeactivateFn } from '@angular/router';
import type { AuthoringChapterEditorPageComponent } from './authoring-chapter-editor-page.component';

export const authoringChapterEditorUnsavedGuard: CanDeactivateFn<
  AuthoringChapterEditorPageComponent
> = (component) => component.canDeactivate();
