import { Peer } from '../models/torrent.model';

/** Sort order for a peer list: peers moving data first, idle ones last. See design_docs/0076. */
export const ACTIVITY_RANK: Record<Peer['activity'], number> = { ACTIVE: 0, WAITING: 1, IDLE: 2 };

export function activityLabel(activity: Peer['activity']): string {
  switch (activity) {
    case 'ACTIVE':
      return 'Active';
    case 'WAITING':
      return 'Waiting';
    default:
      return 'Idle';
  }
}

/** The sentence behind a peer's activity marker - the choke/interest detail the old row showed
 * as four separate icons, now only spelled out on demand (tooltip / dialog). The activity value
 * itself is computed by the backend (design_docs/0076); this only words it. */
export function activityDetail(peer: Peer): string {
  switch (peer.activity) {
    case 'ACTIVE':
      return 'Active — data moved in the last 10 seconds';
    case 'WAITING':
      return peer.peerChoking ? 'Waiting — they are choking us' : 'Waiting — requested, no data yet';
    default:
      return peer.peerInterested && peer.amChoking
        ? 'Idle — nothing we need; they want data from us and we are choking them'
        : 'Idle — nothing we need from them';
  }
}

/** Same rank-then-slowly-changing-key ordering everywhere a peer list is shown. The secondary
 * key is lifetime bytes moved (monotonic, so it changes slowly) rather than a live rate, so rows
 * don't reshuffle on every 3s poll while someone is reading or hovering one. */
export function comparePeersByActivity(a: Peer, b: Peer): number {
  return (
    ACTIVITY_RANK[a.activity] - ACTIVITY_RANK[b.activity] ||
    b.downloadedBytes + b.uploadedBytes - (a.downloadedBytes + a.uploadedBytes) ||
    `${a.address}:${a.port}`.localeCompare(`${b.address}:${b.port}`)
  );
}
