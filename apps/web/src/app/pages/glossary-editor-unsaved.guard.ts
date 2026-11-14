import { CanDeactivateFn } from '@angular/router';
import type { GlossaryEditorPageComponent } from './glossary-editor-page.component';

export const glossaryEditorUnsavedGuard: CanDeactivateFn<GlossaryEditorPageComponent> = (
  component,
) => component.canDeactivate();
