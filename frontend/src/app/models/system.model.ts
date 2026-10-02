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
  /** Set only for a DISABLED service that a proxy turned off (design_docs/0079), null otherwise. */
  reason: string | null;
}

/** GET /api/system/version - the backend's own name and build version (design_docs/0084). */
export interface AppVersion {
  name: string;
  version: string;
}

/** Matches the backend's HealthView.State. OK - working. INFO - a fact, neither good nor bad.
 * WARNING - working, but worth a look. FAILED - not working (the only state the sidebar badge
 * counts). DISABLED - switched off. See design_docs/0086. */
export type HealthState = 'OK' | 'INFO' | 'WARNING' | 'FAILED' | 'DISABLED';

/** One row of the Health page. name is a stable identifier mapped to a label/icon in
 * shared/status-display.ts; message is ready-to-show text from the backend. */
export interface HealthCheck {
  name: string;
  state: HealthState;
  message: string;
}

export interface HealthGroup {
  name: string;
  checks: HealthCheck[];
}

/** GET /api/system/health. status is the worst state present. */
export interface HealthReport {
  status: 'OK' | 'WARNING' | 'FAILED';
  groups: HealthGroup[];
}
