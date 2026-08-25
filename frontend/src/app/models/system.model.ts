/** Matches the backend's DhtStatusView. See design_docs/0028's DHT status addendum. */
export interface DhtStatus {
  enabled: boolean;
  nodeCount: number;
}

/** Matches the backend's DiskUsageView. See design_docs/0043. */
export interface DiskUsage {
  freeBytes: number;
}
