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
