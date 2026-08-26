import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';

import { LibraryEvent } from '../models/events.model';
import { EventsService } from '../services/events.service';
import { TorrentEventsService } from '../services/torrent-events.service';
import { StatusIndicator } from '../shared/status-indicator/status-indicator';
import { eventTypeDisplay } from '../shared/status-display';

/** Identifies the same underlying event whether it arrived via the initial REST load or a
 * live WebSocket push, so merging the two (see the `events` computed below) can't double an
 * event that happens to show up in both. */
function eventKey(event: LibraryEvent): string {
  return `${event.timestamp}|${event.infoHash}|${event.type}`;
}

/**
 * A curated library-management feed - torrent added/completed/errored/removed, an auto-pause
 * from a reached seeding limit - not a raw debug log. A top-level page (not a torrent-detail
 * drawer tab) since most of these are exactly the "something happened while you weren't
 * looking" kind a per-torrent drawer would hide until that specific torrent happened to be
 * opened. See design_docs/0055.
 *
 * <p>Loaded via GET /api/events on entry, then kept live from TorrentEventsService's own
 * WebSocket-pushed buffer thereafter - merged by eventKey() rather than concatenated, since
 * that buffer already holds anything pushed since app start, which can overlap with what the
 * REST load also returns.
 */
@Component({
  selector: 'app-events-page',
  imports: [DatePipe, RouterLink, StatusIndicator],
  templateUrl: './events-page.html',
  styleUrl: './events-page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EventsPage {
  private readonly eventsService = inject(EventsService);
  private readonly torrentEvents = inject(TorrentEventsService);

  private readonly initial = toSignal(this.eventsService.list(), { initialValue: [] as LibraryEvent[] });

  readonly statusDisplay = eventTypeDisplay;

  readonly events = computed<LibraryEvent[]>(() => {
    const merged = new Map<string, LibraryEvent>();
    for (const event of this.initial()) {
      merged.set(eventKey(event), event);
    }
    for (const event of this.torrentEvents.libraryEvents()) {
      merged.set(eventKey(event), event);
    }
    return Array.from(merged.values()).sort((a, b) => b.timestamp.localeCompare(a.timestamp));
  });
}
