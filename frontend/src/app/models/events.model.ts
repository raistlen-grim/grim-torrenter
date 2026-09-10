/** Matches the backend's EventType enum (grimtorrenter-engine, events package) - a closed set
 * so the Events page can render a fixed icon/label per type rather than an arbitrary string.
 * SERVER_STARTED, DHT_UNAVAILABLE, PEER_SERVER_UNAVAILABLE, and LSD_UNAVAILABLE are engine-wide
 * types (infoHash/torrentName both null) - every other type is torrent-scoped. See
 * design_docs/0055, design_docs/0059 (the first two UNAVAILABLE types), and design_docs/0062
 * (LSD_UNAVAILABLE). MAGNET_ADD_FAILED (design_docs/0060) is a
 * partial exception: infoHash is set (the magnet's own info hash), but torrentName is always
 * null even when the magnet had a display name - that infoHash was never actually added as a
 * real torrent, so the Events page can't safely render it as a link to one; any display name
 * is folded into the message field instead. TRACKER_UNREACHABLE/TRACKER_RECOVERED
 * (design_docs/0055's own addendum) are torrent-scoped like every type above them, with the
 * specific tracker URL folded into the message field. */
export type EventType =
  | 'ADDED'
  | 'COMPLETED'
  | 'ERROR'
  | 'REMOVED'
  | 'SEEDING_LIMIT_REACHED'
  | 'SERVER_STARTED'
  | 'DHT_UNAVAILABLE'
  | 'PEER_SERVER_UNAVAILABLE'
  | 'MAGNET_ADD_FAILED'
  | 'TRACKER_UNREACHABLE'
  | 'TRACKER_RECOVERED'
  | 'LSD_UNAVAILABLE';

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
