import { ChangeDetectionStrategy, Component } from '@angular/core';

/**
 * The style guide's constructed skull mark (STYLE_GUIDE_NOTES.md "The mark") - six
 * absolutely-positioned rectangles (cranium, two sockets, a nasal notch, a toothed jaw), pure
 * CSS, no image/icon-font glyph. Shared (not duplicated per use site) because it's five parts
 * of nontrivial, position-critical CSS, unlike the single-rule shapes this app usually inlines
 * per component. Font-size-driven per the guide's own construction table - sizes itself off
 * whatever font-size is in effect where `<app-skull-mark>` is placed, the same way the guide's
 * own spec scales "as one unit."
 *
 * <p>The caller must set `--void` (a CSS custom property, inherited through this component's
 * boundary regardless of view encapsulation) to whatever color sits *behind* this mark -
 * that's what the sockets/notch/jaw gaps paint themselves as, to read as cut-outs rather than
 * as a solid block with colored dots on it. Two current uses: `app-header.scss` (the wordmark,
 * `--void: var(--color-accent-900)`, the header bar's own background) and `torrent-list.scss`
 * (the empty-state icon, `--void: var(--color-surface)`) - the guide's own size-ladder
 * ("below 20px the nasal notch and tooth gaps drop out... below 14px use the wordmark
 * instead") isn't implemented, since neither current use renders anywhere near that small;
 * revisit if a future use site needs it.
 */
@Component({
  selector: 'app-skull-mark',
  template: `
    <span class="skull-mark" aria-hidden="true">
      <span class="cranium"></span>
      <span class="socket socket-left"></span>
      <span class="socket socket-right"></span>
      <span class="notch"></span>
      <span class="jaw"></span>
    </span>
  `,
  styleUrl: './skull-mark.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SkullMark {}
