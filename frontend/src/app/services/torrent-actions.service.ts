import { Injectable, inject, signal } from '@angular/core';
import { Observable, defer, finalize, tap } from 'rxjs';

import { TorrentEventsService } from './torrent-events.service';
import { TorrentService } from './torrent.service';

export type PendingTorrentAction = 'pause' | 'resume' | 'remove';

/** Pause/resume/remove for one torrent, plus the one shared record of which torrents have such
 * a request in flight. The row, the details panel and the toolbar's Pause all/Resume all each
 * start these actions; keeping the pending state here (keyed by info hash) rather than in each
 * component means all of them show the same torrent as busy, and it survives the details panel
 * closing or switching torrent mid-request - a resume blocks on the tracker and can take a
 * minute. Failure toasts stay with the caller (MessageService is provided by TorrentList, not
 * root). Same simple signal-service pattern as TorrentDetailTabService. See design_docs/0033's
 * 2026-10-01 addenda. */
@Injectable({ providedIn: 'root' })
export class TorrentActionsService {
  private readonly torrentService = inject(TorrentService);
  private readonly events = inject(TorrentEventsService);

  private readonly pending = signal<ReadonlyMap<string, PendingTorrentAction>>(new Map());

  /** Reads the signal, so a computed/template calling this re-evaluates as actions start and
   * finish. */
  pendingFor(infoHash: string): PendingTorrentAction | null {
    return this.pending().get(infoHash) ?? null;
  }

  pause(infoHash: string): Observable<void> {
    return this.track(infoHash, 'pause', this.torrentService.pause(infoHash));
  }

  resume(infoHash: string): Observable<void> {
    return this.track(infoHash, 'resume', this.torrentService.resume(infoHash));
  }

  /** Also drops the torrent from the local list on success, rather than leaving the row visible
   * until the next snapshot. */
  remove(infoHash: string, deleteData = false): Observable<void> {
    return this.track(
      infoHash,
      'remove',
      this.torrentService.remove(infoHash, deleteData).pipe(tap(() => this.events.removeLocal(infoHash))),
    );
  }

  /** Marked pending on subscribe (not on call) and cleared on completion, error or unsubscribe,
   * so an entry can't outlive its request. */
  private track(infoHash: string, action: PendingTorrentAction, request: Observable<void>): Observable<void> {
    return defer(() => {
      this.setPending(infoHash, action);
      return request.pipe(finalize(() => this.setPending(infoHash, null)));
    });
  }

  private setPending(infoHash: string, action: PendingTorrentAction | null): void {
    this.pending.update((current) => {
      const next = new Map(current);
      if (action === null) {
        next.delete(infoHash);
      } else {
        next.set(infoHash, action);
      }
      return next;
    });
  }
}
