import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';

import { PieceState } from '../../models/torrent.model';
import { TorrentService } from '../../services/torrent.service';
import { pollWhileInput } from '../../shared/poll-while-input';

const POLL_INTERVAL_MS = 3000;

/**
 * A colored grid of every piece's state, plus a text summary alongside it - the grid
 * itself is marked decorative (role="img" with the same text as its accessible name)
 * rather than requiring a screen reader to distinguish thousands of individually-colored
 * cells. See design_docs/0031.
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

  readonly pieces = toSignal(
    pollWhileInput(this.infoHash, POLL_INTERVAL_MS, (infoHash) => this.torrentService.pieces(infoHash)),
    { initialValue: [] as PieceState[] },
  );

  readonly summary = computed(() => {
    const pieces = this.pieces();
    const complete = pieces.filter((p) => p === 'COMPLETE').length;
    return pieces.length === 0 ? 'No piece data yet' : `${complete} / ${pieces.length} pieces complete`;
  });
}
