/** Humanizes a duration as the largest two relevant units (e.g. "4h 12m", "18m 4s"), dropping
 * to one unit only at the seconds tier - shared by FormatEtaPipe and FormatDurationPipe, which
 * each wrap this with their own floor/edge-case behavior (an ETA's "Stalled"/em-dash cases
 * don't apply to a plain elapsed duration, and vice versa - see design_docs/0064). */
export function humanizeDuration(totalSeconds: number): string {
  const days = Math.floor(totalSeconds / 86_400);
  const hours = Math.floor((totalSeconds % 86_400) / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;

  if (days > 0) {
    return `${days}d ${hours}h`;
  }
  if (hours > 0) {
    return `${hours}h ${minutes}m`;
  }
  if (minutes > 0) {
    return `${minutes}m ${seconds}s`;
  }
  return `${seconds}s`;
}
