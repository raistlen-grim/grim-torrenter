import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { LimitSource } from '../../models/torrent.model';
import { capTitle } from '../cap';

/**
 * The small gauge shown after a rate that a limit is holding back (design_docs/0102): quiet,
 * fixed-width, with the source and limit as its tooltip and accessible name. Render it only
 * while capped.
 */
@Component({
  selector: 'app-cap-marker',
  template: `<i class="pi pi-gauge cap-marker" role="img" [attr.aria-label]="title()" [title]="title()"></i>`,
  styles: `
    :host {
      display: inline-flex;
    }

    .cap-marker {
      font-size: 0.8em;
      opacity: 0.7;
      margin-left: var(--space-1);
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CapMarker {
  readonly limitBytesPerSec = input.required<number>();
  readonly source = input.required<LimitSource>();

  readonly title = computed(() => capTitle(this.limitBytesPerSec(), this.source()));
}
