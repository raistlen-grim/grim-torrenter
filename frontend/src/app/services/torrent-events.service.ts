import { Injectable, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';

import { LibraryEvent } from '../models/events.model';
import { Torrent, TransferStats } from '../models/torrent.model';
import { TorrentEventMessage, parseEventMessage } from '../shared/event-message';
import { AuthService } from './auth.service';
import { LabelService } from './label.service';
import { TorrentService } from './torrent.service';

/** Caps how many live-pushed library events this tab keeps in memory - a long-running tab
 * shouldn't accumulate unbounded history client-side just because the backend's own retention
 * window (design_docs/0055) is measured in days, not events. The EventsPage merges this with
 * its own REST-loaded page of history, so trimming here only affects how far back a page left
 * open the whole time can scroll without a reload - the backend's own log is unaffected. */
const MAX_BUFFERED_LIBRARY_EVENTS = 500;

const RECONNECT_DELAY_MS = 3000;

/** The server's close code for "no valid auth token" (TorrentWebSocket.SESSION_ENDED). Handled
 * like a REST 401: the session is over, so reconnecting can't help. See design_docs/0096. */
const SESSION_ENDED_CLOSE_CODE = 4401;

/**
 * Single source of truth for torrent state on the client. Seeds itself via
 * an initial REST list() call, then stays current via the backend's hybrid
 * push model (periodic snapshot + immediate state-changed events) - see
 * design_docs/0019. Reconnects on socket close since a missed connection
 * self-heals on the next periodic snapshot regardless.
 *
 * <p>Rates, ETAs and the engine-wide totals arrive ready-made from the server (each torrent's
 * fields, and the "transfer" message) - this client computes none of them. See design_docs/0102.
 */
@Injectable({ providedIn: 'root' })
export class TorrentEventsService {
  private readonly torrentService = inject(TorrentService);
  private readonly authService = inject(AuthService);
  private readonly labelService = inject(LabelService);
  private readonly router = inject(Router);

  private readonly torrentsByHash = signal(new Map<string, Torrent>());

  /** The engine-wide figures from the latest "transfer" message - null until the first one
   * arrives (within one snapshot tick of connecting). */
  private readonly latestTransfer = signal<TransferStats | null>(null);
  readonly transfer = this.latestTransfer.asReadonly();

  /** Library events pushed since this service connected (app start, not page mount) - newest
   * first. EventsPage merges this with its own REST-loaded page of older history rather than
   * this service owning that merge, since it has no reason to know about the REST endpoint
   * otherwise. See design_docs/0055. */
  private readonly recentLibraryEvents = signal<LibraryEvent[]>([]);
  readonly libraryEvents = this.recentLibraryEvents.asReadonly();

  readonly torrents = computed<Torrent[]>(() => Array.from(this.torrentsByHash().values()));

  private socket?: WebSocket;
  /** Set while waiting to reconnect after a close. connect() must treat that wait as "already
   * connecting" - otherwise a second caller (app start, then login, both call it) during the gap
   * starts a second, independent reconnect loop, and a flapping connection multiplies. */
  private reconnectTimer?: ReturnType<typeof setTimeout>;

  connect(): void {
    if (this.socket || this.reconnectTimer !== undefined) {
      return;
    }
    this.torrentService.list().subscribe((list) => this.replaceAll(list));
    this.labelService.load();
    this.openSocket();
  }

  /** Closes the socket for good (no reconnect) and forgets everything received, for logout -
   * the next connect(), after logging in again, starts from a fresh REST load. See
   * design_docs/0095. */
  disconnect(): void {
    clearTimeout(this.reconnectTimer);
    this.reconnectTimer = undefined;
    const socket = this.socket;
    this.socket = undefined;
    if (socket) {
      socket.onclose = null;
      socket.close();
    }
    this.torrentsByHash.set(new Map());
    this.latestTransfer.set(null);
    this.recentLibraryEvents.set([]);
  }

  /** The backend has no "removed" push event, so the caller applies this locally
   * right after a successful delete rather than waiting for the next snapshot. */
  removeLocal(infoHash: string): void {
    this.torrentsByHash.update((map) => {
      const next = new Map(map);
      next.delete(infoHash);
      return next;
    });
  }

  /** Sends the current bearer token as a WebSocket subprotocol (the constructor's second
   * argument), not a URL query param - browsers can't set custom headers on a WebSocket
   * handshake at all, but the subprotocol list IS carried as a real Sec-WebSocket-Protocol
   * request header, which TorrentWebSocket's own @OnOpen reads (design_docs/0061). Deliberately
   * not a query param: a reverse proxy in front of this app (recommended for TLS - see that
   * doc) commonly logs the full request URL by default, but not arbitrary headers, so a query
   * param would leak a long-lived credential into proxy access logs. Read fresh on every call
   * (including a reconnect after a dropped connection), so a token obtained after this
   * service's first connect attempt - e.g. LoginPage calling connect() once login succeeds -
   * is still picked up.
   *
   * <p>Not offered at all while the backend is known to have auth switched off: a token left in
   * localStorage from an earlier login would otherwise still be sent, and a browser that offers
   * subprotocols drops the connection unless the server selects one - an endless
   * open/close/reconnect loop with no live updates. See design_docs/0061's 2026-10-01
   * correction. */
  private openSocket(): void {
    const protocol = location.protocol === 'https:' ? 'wss' : 'ws';
    const token = this.authService.authEnabled() === false ? null : this.authService.token();
    const subprotocols = token ? ['bearer', token] : undefined;
    this.socket = new WebSocket(`${protocol}://${location.host}/ws/torrents`, subprotocols);
    this.socket.onmessage = (event) => {
      const message = parseEventMessage(event.data);
      if (message === null) {
        console.warn('[events] ignoring a WebSocket message this client does not understand');
        return;
      }
      this.handleMessage(message);
    };
    this.socket.onclose = (event) => {
      // Code distinguishes who ended it: 1006 = dropped without a close frame (network/proxy),
      // 1008 = Origin rejected, 4401 = no valid auth token (logged out, expired, revoked, or
      // auth just switched on).
      console.debug(`[events] socket closed, code=${event.code} reason=${event.reason || '-'}`);
      this.socket = undefined;
      if (event.code === SESSION_ENDED_CLOSE_CODE) {
        // Same as authInterceptor's 401 handling. No reconnect: LoginPage calls connect() again.
        this.authService.clearToken();
        this.router.navigateByUrl('/login');
        return;
      }
      if (this.authService.authEnabled() === true && !this.authService.token()) {
        // Nothing to authenticate with, so a reconnect would only be refused - wait for login.
        return;
      }
      this.reconnectTimer = setTimeout(() => {
        this.reconnectTimer = undefined;
        this.openSocket();
      }, RECONNECT_DELAY_MS);
    };
  }

  /** Messages arrive already checked (parseEventMessage), so no casts here. */
  private handleMessage(message: TorrentEventMessage): void {
    switch (message.type) {
      case 'snapshot':
        this.replaceAll(message.payload);
        break;
      case 'labels':
        this.labelService.applyServerList(message.payload);
        break;
      case 'event': {
        const event = message.payload;
        this.recentLibraryEvents.update((events) => [event, ...events].slice(0, MAX_BUFFERED_LIBRARY_EVENTS));
        break;
      }
      case 'state-changed':
        this.upsert(message.payload);
        break;
      case 'transfer':
        this.latestTransfer.set(message.payload);
        break;
    }
  }

  /** One map per snapshot, so a torrent removed elsewhere (another tab or client) simply drops
   * out. See design_docs/0096. */
  private replaceAll(list: Torrent[]): void {
    this.torrentsByHash.set(new Map(list.map((t) => [t.infoHash, t])));
  }

  /** Public so a caller that already has a fresh Torrent from a direct REST response
   * (e.g. a just-completed upload) can apply it immediately instead of waiting for the
   * next periodic snapshot or a state-changed push - see design_docs/0029. */
  upsert(torrent: Torrent): void {
    this.torrentsByHash.update((map) => {
      const next = new Map(map);
      next.set(torrent.infoHash, torrent);
      return next;
    });
  }
}
