import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/** 'active' = full-strength accent ink (something in motion or healthy).
 * 'dim' = 55% opacity, plain ink (deliberately inert - paused, unknown, not yet needed).
 * 'alarm' = the one reserved alarm color, full strength (only ever an actual error).
 * See design_docs/0033/0032's style-guide reconciliation: status is carried by ink weight
 * and icon, not by PrimeNG's severity-color palette - "one hue, one alarm," not a five-tag
 * rainbow. */
export type StatusTone = 'active' | 'dim' | 'alarm';

/** A small icon+label status display, styled by ink weight rather than a colored badge -
 * shared by every place a torrent/peer/tracker status appears (list row, detail header,
 * Peers tab, Trackers tab) so the visual language stays in one place instead of four
 * separate severity mappings. See design_docs/0033. */
@Component({
  selector: 'app-status-indicator',
  template: `
    <span
      class="status-indicator"
      [class.tone-active]="tone() === 'active'"
      [class.tone-dim]="tone() === 'dim'"
      [class.tone-alarm]="tone() === 'alarm'"
    >
      <i [class]="'pi ' + icon()" aria-hidden="true"></i>
      {{ label() }}
    </span>
  `,
  styleUrl: './status-indicator.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StatusIndicator {
  readonly icon = input.required<string>();
  readonly label = input.required<string>();
  readonly tone = input.required<StatusTone>();
}
