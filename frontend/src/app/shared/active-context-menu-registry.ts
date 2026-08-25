import { Injectable } from '@angular/core';
import { ContextMenu } from 'primeng/contextmenu';

/**
 * Coordinates the torrent list's per-row context menus (each TorrentRow owns its own
 * independent p-contextMenu instance, per design_docs/0043) so opening one closes whatever
 * other one was already open. PrimeNG's own "click outside closes the menu" behavior doesn't
 * cover this case: a right-click fires only a `contextmenu` event, not a `click`, so a
 * previously-open row's menu never sees the event that would normally dismiss it when a
 * different row is right-clicked next.
 */
@Injectable({ providedIn: 'root' })
export class ActiveContextMenuRegistry {
  private current: ContextMenu | null = null;

  show(menu: ContextMenu, event: MouseEvent): void {
    if (this.current && this.current !== menu) {
      this.current.hide();
    }
    this.current = menu;
    menu.show(event);
  }
}
