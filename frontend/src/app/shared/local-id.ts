/** A locally-unique id for tracking an in-flight UI item (e.g. a pending upload row) -
 * never sent to the backend or compared across sessions, so it doesn't need real
 * cryptographic randomness. Deliberately not crypto.randomUUID(): that API is only defined in
 * secure contexts (HTTPS, or http://localhost) - GrimTorrenter is typically reached over plain
 * HTTP on a LAN IP/hostname when self-hosted, where crypto.randomUUID is undefined and throws. */
export function generateLocalId(): string {
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;
}
