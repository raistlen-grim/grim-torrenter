import { FormControl } from '@angular/forms';

/** Mirrors the backend's shared negative/0/positive sentinel convention (SeedingLimitOverride,
 * design_docs/0054; TorrentLimitOverride, design_docs/0072) as a form-friendly 3-way choice,
 * rather than exposing the raw number directly - "Use default"/"Custom"/"No limit" is what a
 * user actually picks between; the sentinel encoding is a dialog's own implementation detail to
 * translate to and from. Shared by seeding-limits-dialog and torrent-limits-dialog rather than
 * each declaring its own copy. */
export type LimitMode = 'default' | 'custom' | 'unlimited';

export function modeFor(sentinel: number): LimitMode {
  if (sentinel < 0) {
    return 'default';
  }
  return sentinel === 0 ? 'unlimited' : 'custom';
}

export function sentinelFor(mode: LimitMode, customValue: number): number {
  if (mode === 'default') {
    return -1;
  }
  return mode === 'unlimited' ? 0 : customValue;
}

export function modeOptions(defaultLabel: string): { label: string; value: LimitMode }[] {
  return [
    { label: defaultLabel, value: 'default' },
    { label: 'Custom', value: 'custom' },
    { label: 'No limit', value: 'unlimited' },
  ];
}

/** The value field only makes sense while its mode is 'custom' - disabled otherwise, same
 * enable/disable-on-a-sibling-control's-value idea rate-limit-settings' own
 * syncUnlimitedDisabled already established for its 2-state case. */
export function syncValueDisabled(mode: FormControl<LimitMode>, value: FormControl<number>): void {
  const apply = (currentMode: LimitMode) => (currentMode === 'custom' ? value.enable() : value.disable());
  apply(mode.value);
  mode.valueChanges.subscribe(apply);
}
