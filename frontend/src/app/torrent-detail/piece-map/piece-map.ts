import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';

import { PiecesResponse } from '../../models/torrent.model';
import { TorrentService } from '../../services/torrent.service';
import { FormatBytesPipe } from '../../shared/format-bytes.pipe';
import { pollWhileInput } from '../../shared/poll-while-input';

const POLL_INTERVAL_MS = 3000;
const EMPTY: PiecesResponse = { pieces: [], pieceLength: 0 };

/**
 * A colored grid of every piece's state, plus a caption alongside it - the grid itself is
 * marked decorative (role="img" with the same text as its accessible name) rather than
 * requiring a screen reader to distinguish thousands of individually-colored cells. See
 * design_docs/0031.
 *
 * <p>Fixed repeat(26, 1fr) columns per README's Pieces tab spec - the guide's own "reduce
 * column count as the panel narrows, squares must never render below 8px" responsive rule is
 * moot at this panel's current fixed 392px width (no narrower breakpoint exists yet - that's
 * explicitly out of scope for this whole restyle pass), so it isn't implemented. Availability
 * is dropped from the caption per the guide's own documented fallback ("if [piece
 * availability] is not already exposed, the caption can drop Availability 2.1x rather than
 * adding a request") - no per-piece availability data exists anywhere in this app.
 *
 * <p>Polls its own endpoint only while mounted (toSignal unsubscribes automatically on
 * destroy, ending the poll) - no separate cleanup needed, per design_docs/0031's
 * "self-contained, on-demand" endpoint pattern.
 */
@Component({
  selector: 'app-piece-map',
  templateUrl: './piece-map.html',
  styleUrl: './piece-map.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PieceMap {
  private readonly torrentService = inject(TorrentService);

  readonly infoHash = input.required<string>();

  private readonly response = toSignal(
    pollWhileInput(this.infoHash, POLL_INTERVAL_MS, (infoHash) => this.torrentService.pieces(infoHash)),
    { initialValue: EMPTY },
  );

  readonly pieces = computed(() => this.response().pieces);
  readonly pieceLength = computed(() => this.response().pieceLength);

  readonly completedCount = computed(() => this.pieces().filter((p) => p === 'COMPLETE').length);

  /** Also the piece grid's own aria-label (role="img") - one accessible name standing in for
   * thousands of individually-colored cells, per this component's own top-level comment. */
  readonly summary = computed(() => {
    const pieces = this.pieces();
    if (pieces.length === 0) {
      return 'No piece data yet';
    }
    return `${this.completedCount()} / ${pieces.length} pieces · ${new FormatBytesPipe().transform(this.pieceLength())} each`;
  });
}
