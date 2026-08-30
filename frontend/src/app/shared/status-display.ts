import { EventType } from '../models/events.model';
import { ServiceState, ServiceStatus } from '../models/system.model';
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
 * outcome (a limit doing exactly what it was set to do), not a problem. SERVER_STARTED is
 * 'active' - a genuinely notable timeline marker (e.g. correlating other events against an
 * auto-updater like Watchtower recreating the container), not routine noise.
 * DHT_UNAVAILABLE/PEER_SERVER_UNAVAILABLE (design_docs/0059) are 'alarm' - the same condition
 * the Services page shows live, just with a timestamp; same icon as SERVICE_DISPLAY below for
 * that same reason. MAGNET_ADD_FAILED (design_docs/0060) is likewise 'alarm' - the real
 * failure reason lives in the event's own message field (torrentName is always null for this
 * type, see EventType's own Javadoc), which the Events page template already renders as
 * plain text. */
const EVENT_TYPE_DISPLAY: Record<EventType, StatusDisplay> = {
  ADDED: { icon: 'pi-plus-circle', label: 'Added', tone: 'active' },
  COMPLETED: { icon: 'pi-check-circle', label: 'Completed', tone: 'active' },
  ERROR: { icon: 'pi-exclamation-triangle', label: 'Error', tone: 'alarm' },
  REMOVED: { icon: 'pi-trash', label: 'Removed', tone: 'dim' },
  SEEDING_LIMIT_REACHED: { icon: 'pi-pause', label: 'Seeding limit reached', tone: 'dim' },
  SERVER_STARTED: { icon: 'pi-server', label: 'Server started', tone: 'active' },
  DHT_UNAVAILABLE: { icon: 'pi-sitemap', label: 'DHT unavailable', tone: 'alarm' },
  PEER_SERVER_UNAVAILABLE: { icon: 'pi-sign-in', label: 'Peer server unavailable', tone: 'alarm' },
  MAGNET_ADD_FAILED: { icon: 'pi-link', label: 'Magnet add failed', tone: 'alarm' },
};

export function eventTypeDisplay(type: EventType): StatusDisplay {
  return EVENT_TYPE_DISPLAY[type];
}

/** Engine-wide singleton subsystems only (DHT, the inbound peer server) - see
 * design_docs/0059. name is matched against TorrentEngine's own stable identifiers
 * ("dht"/"peerServer"); an unrecognized name falls back to itself as the label rather than
 * throwing, so a future backend-only addition degrades gracefully instead of breaking the
 * page. */
const SERVICE_DISPLAY: Record<string, { label: string; icon: string }> = {
  dht: { label: 'DHT', icon: 'pi-sitemap' },
  peerServer: { label: 'Peer Server', icon: 'pi-sign-in' },
};

/** RUNNING/DISABLED/FAILED map onto the same 'active'/'dim'/'alarm' ink-weight vocabulary
 * every other status display in this app already uses (see StatusTone's own Javadoc) -
 * DISABLED is 'dim' like STOPPED/Paused, a deliberately inert state, not a problem. */
const SERVICE_STATE_TONE: Record<ServiceState, StatusTone> = {
  RUNNING: 'active',
  DISABLED: 'dim',
  FAILED: 'alarm',
};

export function serviceStatusDisplay(status: ServiceStatus): StatusDisplay {
  const display = SERVICE_DISPLAY[status.name] ?? { label: status.name, icon: 'pi-question-circle' };
  return { icon: display.icon, label: display.label, tone: SERVICE_STATE_TONE[status.state] };
}
