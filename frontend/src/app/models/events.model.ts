/** Matches the backend's EventType enum (grimtorrenter-engine, events package) - a closed set
 * so the Events page can render a fixed icon/label per type rather than an arbitrary string.
 * See design_docs/0055. */
export type EventType = 'ADDED' | 'COMPLETED' | 'ERROR' | 'REMOVED' | 'SEEDING_LIMIT_REACHED';

/** Matches the backend's LibraryEvent record - a curated library-management feed (torrent
 * added/completed/errored/removed, an auto-pause from a reached seeding limit), not a raw
 * debug log. infoHash/torrentName are null for a (currently hypothetical) engine-wide event
 * not tied to any one torrent. See design_docs/0055. */
export interface LibraryEvent {
  timestamp: string;
  type: EventType;
  infoHash: string | null;
  torrentName: string | null;
  message: string | null;
}
