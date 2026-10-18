import { Injectable, inject } from '@angular/core';
import { ConfirmationService, MenuItem, MessageService } from 'primeng/api';

import { Torrent } from '../models/torrent.model';
import { PendingTorrentAction, TorrentActionsService } from '../services/torrent-actions.service';
import { apiErrorMessage } from '../shared/api-error';
import { copyToClipboard } from '../shared/clipboard';
import { magnetLinkFor } from '../shared/magnet';

/** The per-torrent dialogs the menu opens. Each host (row, details panel) owns its own dialog
 * instances, so it says how to open them. */
export interface TorrentMenuDialogs {
  seedingLimits(): void;
  torrentLimits(): void;
  labels(): void;
}

/**
 * One torrent's actions as the row and the details panel offer them: the "⋯"/right-click menu,
 * and the handlers behind it and behind their inline buttons, with the toast or confirm each
 * needs. One copy, so the two menus can't drift apart again (they had: the panel's lacked
 * Torrent limits… and Labels…, and didn't disable Pause/Recheck for a fetching magnet).
 *
 * <p>Provided by TorrentList, not root, because it needs TorrentList's MessageService and
 * ConfirmationService (whose <p-toast>/<p-confirmDialog> live in its template). The row and the
 * panel both render inside TorrentList, so they get that instance. The pending state itself
 * stays in the root TorrentActionsService. See design_docs/0101.
 *
 * <p>Every handler takes the torrent as it was when the action started, so a toast for a slow
 * request names the right torrent even if the panel has moved on to another.
 */
@Injectable()
export class TorrentCommands {
  private readonly actions = inject(TorrentActionsService);
  private readonly confirmationService = inject(ConfirmationService);
  private readonly messageService = inject(MessageService);

  /** Only what's backed by something real today (design_docs/0043). Reads the shared pending
   * state, so a computed() calling this re-evaluates as actions start and finish.
   *
   * <p>Pause/Resume and Force recheck are off while verifying, while another action is in
   * flight, and for a magnet still fetching metadata (no session to act on yet). Force
   * reannounce needs a torrent that is actually announcing - the backend rejects it otherwise
   * (design_docs/0082). Remove always works on a real record, a fetching magnet included. */
  menuItems(torrent: Torrent, dialogs: TorrentMenuDialogs, onRemoved?: () => void): MenuItem[] {
    const pending = this.actions.pendingFor(torrent.infoHash) !== null;
    const fetching = torrent.state === 'FETCHING_METADATA';
    const disabled = torrent.state === 'VERIFYING' || pending;
    const toggleDisabled = disabled || fetching;
    const running = torrent.state === 'DOWNLOADING' || torrent.state === 'SEEDING';
    const toggleItem: MenuItem =
      torrent.state === 'STOPPED'
        ? { label: 'Resume', icon: 'pi pi-play', disabled: toggleDisabled, command: () => this.resume(torrent) }
        : { label: 'Pause', icon: 'pi pi-pause', disabled: toggleDisabled, command: () => this.pause(torrent) };
    return [
      toggleItem,
      { label: 'Copy magnet link', icon: 'pi pi-copy', command: () => this.copyMagnetLink(torrent) },
      {
        label: 'Force reannounce',
        icon: 'pi pi-megaphone',
        disabled: !running || pending,
        command: () => this.reannounce(torrent),
      },
      { label: 'Force recheck', icon: 'pi pi-refresh', disabled: toggleDisabled, command: () => this.recheck(torrent) },
      { separator: true },
      { label: 'Seeding limits…', icon: 'pi pi-gauge', disabled: fetching, command: () => dialogs.seedingLimits() },
      { label: 'Torrent limits…', icon: 'pi pi-sliders-h', disabled: fetching, command: () => dialogs.torrentLimits() },
      { label: 'Labels…', icon: 'pi pi-tag', disabled: fetching, command: () => dialogs.labels() },
      { separator: true },
      { label: 'Remove', icon: 'pi pi-trash', disabled, command: () => this.remove(torrent, onRemoved) },
      {
        label: 'Remove and delete files…',
        icon: 'pi pi-exclamation-triangle',
        disabled,
        command: () => this.confirmRemoveWithData(torrent, onRemoved),
      },
    ];
  }

  pause(torrent: Torrent): void {
    this.actions.pause(torrent.infoHash).subscribe({ error: (e: unknown) => this.notifyFailed('pause', torrent, e) });
  }

  resume(torrent: Torrent): void {
    this.actions.resume(torrent.infoHash).subscribe({ error: (e: unknown) => this.notifyFailed('resume', torrent, e) });
  }

  /** No confirm: a recheck drops the torrent's peers while it runs but destroys nothing, and the
   * torrent shows Verifying at once. See design_docs/0082. */
  recheck(torrent: Torrent): void {
    this.actions.recheck(torrent.infoHash).subscribe({ error: (e: unknown) => this.notifyFailed('recheck', torrent, e) });
  }

  /** Nothing visible changes when this succeeds, so it gets a toast of its own. */
  reannounce(torrent: Torrent): void {
    this.actions.reannounce(torrent.infoHash).subscribe({
      next: () => this.messageService.add({ severity: 'info', summary: 'Reannouncing', detail: torrent.name }),
      error: (e: unknown) => this.notifyFailed('reannounce', torrent, e),
    });
  }

  /** onRemoved runs after a successful remove - the details panel uses it to close itself. */
  remove(torrent: Torrent, onRemoved?: () => void): void {
    this.removeWith(torrent, false, onRemoved);
  }

  /** No "Are you sure? This action cannot be undone." - the style guide's voice rules forbid it
   * verbatim. "Permanently deleted" already says what matters, and the danger-severity Delete
   * button names the consequence. */
  confirmRemoveWithData(torrent: Torrent, onRemoved?: () => void): void {
    this.confirmationService.confirm({
      header: 'Delete downloaded files?',
      message: `Downloaded files for "${torrent.name}" will be permanently deleted from disk.`,
      icon: 'pi pi-exclamation-triangle',
      acceptLabel: 'Delete',
      rejectLabel: 'Cancel',
      acceptButtonProps: { severity: 'danger' },
      accept: () => this.removeWith(torrent, true, onRemoved),
    });
  }

  copyMagnetLink(torrent: Torrent): void {
    copyToClipboard(magnetLinkFor(torrent.infoHash, torrent.name)).then(
      () => this.messageService.add({ severity: 'success', summary: 'Magnet link copied', detail: torrent.name }),
      () => this.messageService.add({ severity: 'error', summary: 'Could not copy magnet link', detail: torrent.name }),
    );
  }

  private removeWith(torrent: Torrent, deleteData: boolean, onRemoved?: () => void): void {
    this.actions.remove(torrent.infoHash, deleteData).subscribe({
      next: () => onRemoved?.(),
      error: (e: unknown) => this.notifyFailed('remove', torrent, e),
    });
  }

  private notifyFailed(action: PendingTorrentAction | 'recheck' | 'reannounce', torrent: Torrent, error: unknown): void {
    this.messageService.add({
      severity: 'error',
      summary: `Could not ${action} "${torrent.name}"`,
      detail: apiErrorMessage(error, 'Please try again.'),
    });
  }
}
