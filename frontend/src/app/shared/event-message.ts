import { LibraryEvent } from '../models/events.model';
import { Label, Torrent, TransferStats } from '../models/torrent.model';

/** A /ws/torrents message, matching the backend's TorrentEventMessage envelope: a full snapshot
 * (design_docs/0019), one torrent's state change, a library event (design_docs/0055), the
 * label list (design_docs/0077), or the engine-wide transfer figures (design_docs/0102). */
export type TorrentEventMessage =
  | { type: 'snapshot'; payload: Torrent[] }
  | { type: 'state-changed'; payload: Torrent }
  | { type: 'event'; payload: LibraryEvent }
  | { type: 'labels'; payload: Label[] }
  | { type: 'transfer'; payload: TransferStats };

/**
 * Parses and checks one WebSocket frame. Returns null for anything that isn't a message this
 * client understands - bad JSON, an unknown type, a payload of the wrong shape - so one bad frame
 * is dropped instead of throwing out of onmessage or corrupting the torrent list. The checks are
 * shallow, on the fields the client keys on (an info hash, a state, an id); the server is
 * trusted for the rest. See design_docs/0101.
 */
export function parseEventMessage(data: unknown): TorrentEventMessage | null {
  if (typeof data !== 'string') {
    return null;
  }
  let message: unknown;
  try {
    message = JSON.parse(data);
  } catch {
    return null;
  }
  if (!isRecord(message)) {
    return null;
  }
  const payload = message['payload'];
  switch (message['type']) {
    case 'snapshot':
      return Array.isArray(payload) && payload.every(isTorrent) ? { type: 'snapshot', payload } : null;
    case 'state-changed':
      return isTorrent(payload) ? { type: 'state-changed', payload } : null;
    case 'event':
      return isLibraryEvent(payload) ? { type: 'event', payload } : null;
    case 'labels':
      return Array.isArray(payload) && payload.every(isLabel) ? { type: 'labels', payload } : null;
    case 'transfer':
      return isTransfer(payload) ? { type: 'transfer', payload } : null;
    default:
      return null;
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isTorrent(value: unknown): value is Torrent {
  return (
    isRecord(value) &&
    typeof value['infoHash'] === 'string' &&
    typeof value['name'] === 'string' &&
    typeof value['state'] === 'string' &&
    Array.isArray(value['labelIds'])
  );
}

function isLibraryEvent(value: unknown): value is LibraryEvent {
  return isRecord(value) && typeof value['timestamp'] === 'string' && typeof value['type'] === 'string';
}

function isLabel(value: unknown): value is Label {
  return isRecord(value) && typeof value['id'] === 'string' && typeof value['name'] === 'string';
}

function isTransfer(value: unknown): value is TransferStats {
  return (
    isRecord(value) &&
    typeof value['downloadRateBytesPerSec'] === 'number' &&
    typeof value['uploadRateBytesPerSec'] === 'number'
  );
}
