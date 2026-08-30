/** Matches the backend's EventType enum (grimtorrenter-engine, events package) - a closed set
 * so the Events page can render a fixed icon/label per type rather than an arbitrary string.
 * SERVER_STARTED, DHT_UNAVAILABLE, and PEER_SERVER_UNAVAILABLE are engine-wide types
 * (infoHash/torrentName both null) - every other type is torrent-scoped. See design_docs/0055
 * and design_docs/0059 (the two UNAVAILABLE types). */
export type EventType =
  | 'ADDED'
  | 'COMPLETED'
  | 'ERROR'
  | 'REMOVED'
  | 'SEEDING_LIMIT_REACHED'
  | 'SERVER_STARTED'
  | 'DHT_UNAVAILABLE'
  | 'PEER_SERVER_UNAVAILABLE';

/** Matches the backend's LibraryEvent record - a curated library-management feed (torrent
 * added/completed/errored/removed, an auto-pause from a reached seeding limit, the app
 * starting up), not a raw debug log. infoHash/torrentName are null for SERVER_STARTED, the one
 * engine-wide event not tied to any one torrent. See design_docs/0055. */
export interface LibraryEvent {
  timestamp: string;
  type: EventType;
  infoHash: string | null;
  torrentName: string | null;
  message: string | null;
}
