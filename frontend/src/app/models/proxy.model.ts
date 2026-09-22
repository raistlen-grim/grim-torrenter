/** Matches the backend's TorrentEngine.ProxyStatus (GET /api/proxy). `active` is the saved
 * setting; `blockingNow` is whether this run of the backend actually turned DHT/uTP/LSD/inbound
 * off; `restartRequired` is true when the saved settings would block them but this run didn't (or
 * the reverse) - until then those still use the real address. `hasPassword` says only whether one
 * is stored; the password itself is never returned. See design_docs/0079. */
export interface ProxyStatus {
  active: boolean;
  host: string;
  port: number;
  hasPassword: boolean;
  blockUnsupported: boolean;
  blockingNow: boolean;
  restartRequired: boolean;
}

/** Matches Socks5.TestResult (POST /api/proxy/test). */
export interface ProxyTestResult {
  reachable: boolean;
  udpSupported: boolean;
  message: string;
}
