/** Progress as a whole percentage for display. Any nonzero progress rounds up to at least 1:
 * Math.round alone sits at 0 for a long time on a large torrent, which looks the same as "hasn't
 * started" even once real data has arrived. Shared by the row and the details panel. */
export function progressPercent(progress: number): number {
  return progress <= 0 ? 0 : Math.max(1, Math.round(progress * 100));
}
