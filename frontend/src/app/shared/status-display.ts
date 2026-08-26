import { EventType } from '../models/events.model';
import { TorrentState, TrackerState } from '../models/torrent.model';
import { StatusTone } from './status-indicator/status-indicator';

export interface StatusDisplay {
  icon: string;
  label: string;
  tone: StatusTone;
}

/** Label wording deliberately departs from the raw enum in one place - STOPPED displays
 * as "Paused", matching the style guide's own status vocabulary (design_docs/0033); the
 * backend enum itself is untouched, this is a display-only mapping. */
const TORRENT_STATE_DISPLAY: Record<TorrentState, StatusDisplay> = {
  DOWNLOADING: { icon: 'pi-arrow-down', label: 'Downloading', tone: 'active' },
  SEEDING: { icon: 'pi-arrow-up', label: 'Seeding', tone: 'active' },
  VERIFYING: { icon: 'pi-refresh', label: 'Verifying', tone: 'active' },
  STOPPED: { icon: 'pi-pause', label: 'Paused', tone: 'dim' },
  ERROR: { icon: 'pi-exclamation-triangle', label: 'Error', tone: 'alarm' },
};

export function torrentStateDisplay(state: TorrentState): StatusDisplay {
  return TORRENT_STATE_DISPLAY[state];
}

const TRACKER_STATE_DISPLAY: Record<TrackerState, StatusDisplay> = {
  WORKING: { icon: 'pi-check-circle', label: 'Working', tone: 'active' },
  ERROR: { icon: 'pi-exclamation-triangle', label: 'Error', tone: 'alarm' },
  UNKNOWN: { icon: 'pi-circle', label: 'Unknown', tone: 'dim' },
};

export function trackerStateDisplay(state: TrackerState): StatusDisplay {
  return TRACKER_STATE_DISPLAY[state];
}

/** See design_docs/0055. ERROR reuses the same alarm tone as a torrent/tracker error;
 * SEEDING_LIMIT_REACHED is 'dim' rather than 'alarm' - it's an expected, user-configured
 * outcome (a limit doing exactly what it was set to do), not a problem. */
const EVENT_TYPE_DISPLAY: Record<EventType, StatusDisplay> = {
  ADDED: { icon: 'pi-plus-circle', label: 'Added', tone: 'active' },
  COMPLETED: { icon: 'pi-check-circle', label: 'Completed', tone: 'active' },
  ERROR: { icon: 'pi-exclamation-triangle', label: 'Error', tone: 'alarm' },
  REMOVED: { icon: 'pi-trash', label: 'Removed', tone: 'dim' },
  SEEDING_LIMIT_REACHED: { icon: 'pi-pause', label: 'Seeding limit reached', tone: 'dim' },
};

export function eventTypeDisplay(type: EventType): StatusDisplay {
  return EVENT_TYPE_DISPLAY[type];
}
