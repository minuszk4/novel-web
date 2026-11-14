import { CanDeactivateFn } from '@angular/router';
import type { TranslationCandidatePreviewPageComponent } from './translation-candidate-preview-page.component';

export const translationCandidateEditUnsavedGuard: CanDeactivateFn<
  TranslationCandidatePreviewPageComponent
> = (component) => component.canDeactivate();
