import { CanDeactivateFn } from '@angular/router';
import type { AuthoringOutlinePageComponent } from './authoring-outline-page.component';

export const authoringOutlineUnsavedGuard: CanDeactivateFn<AuthoringOutlinePageComponent> = (
  component,
) => component.canDeactivate();
