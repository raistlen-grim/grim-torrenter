import { CanDeactivateFn } from '@angular/router';
import { Observable } from 'rxjs';

/** A routed page that can hold edits it hasn't saved yet. */
export interface HasUnsavedChanges {
  /** True (or an Observable of the user's answer) when it's fine to navigate away. */
  confirmLeave(): boolean | Observable<boolean>;
}

/** Asks before leaving a page with unsaved edits - see SettingsPage.confirmLeave() and
 * design_docs/0096. */
export const unsavedChangesGuard: CanDeactivateFn<HasUnsavedChanges> = (component) => component.confirmLeave();
