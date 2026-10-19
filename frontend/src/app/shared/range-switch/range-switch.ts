import { ChangeDetectionStrategy, Component, input, model } from '@angular/core';

export interface RangeOption<T extends string> {
  value: T;
  label: string;
}

/**
 * A small segmented control for a graph's time window ("1 min / 2 h / 7 d") - buttons with
 * aria-pressed in a labelled group, so it reads as one choice from a set. Sits in the filter row
 * above the chart it scopes (design_docs/0102).
 */
@Component({
  selector: 'app-range-switch',
  template: `
    <div class="range-switch" role="group" [attr.aria-label]="label()">
      @for (option of options(); track option.value) {
        <button
          type="button"
          class="range-option"
          [class.selected]="option.value === value()"
          [attr.aria-pressed]="option.value === value()"
          (click)="value.set(option.value)"
        >
          {{ option.label }}
        </button>
      }
    </div>
  `,
  styleUrl: './range-switch.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RangeSwitch<T extends string> {
  readonly options = input.required<readonly RangeOption<T>[]>();
  readonly label = input.required<string>();
  readonly value = model.required<T>();
}
