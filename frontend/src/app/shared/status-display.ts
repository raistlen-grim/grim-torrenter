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
