import { Injectable, inject, signal } from '@angular/core';

import { ThemePreference } from '../models/settings.model';
import { SettingsService } from './settings.service';

type ResolvedTheme = 'light' | 'dark';

/**
 * Applies the light/dark theme by setting `data-theme` on `<html>` - both PrimeNG (via
 * `darkModeSelector: '[data-theme="dark"]'` in app.config.ts) and this app's own tokens
 * (styles.scss) key off that same attribute, so there's exactly one thing to keep in sync
 * rather than two separate dark-mode mechanisms. `SYSTEM` is never written to the attribute
 * itself - it's resolved here, live, via `matchMedia`, into a concrete `light`/`dark` value,
 * since the attribute-selector form of `darkModeSelector` (needed so an explicit LIGHT/DARK
 * override can actually override the OS) can't also fall back to a media query the way
 * PrimeNG's own `'system'` option does.
 *
 * Started eagerly from App's constructor (see `inject(TorrentEventsService).connect()` there
 * for the same pattern) rather than waiting for first injection, since the whole point is
 * applying the right theme before the user sees anything.
 *
 * <p>Stability note: the preference itself is persisted through the same backend Settings API
 * as every other setting (a deliberate choice over browser localStorage, confirmed with the
 * user - see design_docs/0032's "Manual theme switcher" section), which means, unlike a localStorage-backed preference,
 * it can't be read synchronously before first paint. The constructor applies the OS's current
 * `prefers-color-scheme` immediately as a best-effort guess (correct outright for the common
 * SYSTEM case, and self-corrects the moment the real GET resolves for an explicit LIGHT/DARK
 * override) rather than leaving the page unstyled or guessing light unconditionally.
 */
@Injectable({ providedIn: 'root' })
export class ThemeService {
  private readonly settingsService = inject(SettingsService);
  private readonly media = matchMedia('(prefers-color-scheme: dark)');

  /** Mirrors whichever of the three the user has picked - the confirmed backend value once
   * the initial GET resolves, and live-previewed values from the settings page's own selector
   * before Save (see preview()). Read by AppearanceSettings to initialize its form control. */
  readonly preference = signal<ThemePreference>('SYSTEM');

  constructor() {
    this.applyResolved(this.resolve('SYSTEM'));

    // Only matters while the preference is actually SYSTEM - an explicit LIGHT/DARK override
    // should keep ignoring OS changes, same as it ignores the OS right now.
    this.media.addEventListener('change', () => {
      if (this.preference() === 'SYSTEM') {
        this.applyResolved(this.resolve('SYSTEM'));
      }
    });

    this.settingsService.current().subscribe((settings) => this.preview(settings.theme));
  }

  /** Applies a preference immediately (both the settings page's own selector, for instant
   * visual feedback before its own Save button is clicked, and this service's own startup
   * once the real backend value loads - see the constructor). Deliberately does not persist
   * anything itself; SettingsPage's own save() is still the one place that PUTs to the
   * backend, exactly like every other settings group - this only ever affects what's on
   * screen. */
  preview(preference: ThemePreference): void {
    this.preference.set(preference);
    this.applyResolved(this.resolve(preference));
  }

  private resolve(preference: ThemePreference): ResolvedTheme {
    if (preference === 'SYSTEM') {
      return this.media.matches ? 'dark' : 'light';
    }
    return preference === 'DARK' ? 'dark' : 'light';
  }

  private applyResolved(resolved: ResolvedTheme): void {
    document.documentElement.setAttribute('data-theme', resolved);
  }
}
