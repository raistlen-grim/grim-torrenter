/** Matches the backend's DhtStatusView. See design_docs/0028's DHT status addendum. */
export interface DhtStatus {
  enabled: boolean;
  nodeCount: number;
}

/** Matches the backend's DiskUsageView. See design_docs/0043. */
export interface DiskUsage {
  freeBytes: number;
}

/** Matches the backend's ResourceUsageView. processCpuLoad is 0.0-1.0, or -1.0 if the JVM
 * can't determine it - passed through as the sentinel the JDK itself returns rather than
 * reinventing an "unavailable" convention. */
export interface ResourceUsage {
  heapUsedBytes: number;
  heapMaxBytes: number;
  processCpuLoad: number;
  availableProcessors: number;
}

/** Matches the backend's TorrentEngine.ServiceState. DEGRADED is DHT-only (a running node
 * whose routing table has stayed sparse) - the peer server never reports it. See
 * design_docs/0059 and its own DEGRADED-state addendum. */
export type ServiceState = 'RUNNING' | 'DEGRADED' | 'DISABLED' | 'FAILED';

/** Matches the backend's ServiceStatusView. name is a stable identifier ("dht"/"peerServer"),
 * mapped to a display label/icon in shared/status-display.ts. See design_docs/0059. */
export interface ServiceStatus {
  name: string;
  state: ServiceState;
}
