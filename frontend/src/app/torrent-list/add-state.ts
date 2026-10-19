import { Torrent } from '../models/torrent.model';
import { ParsedMagnet, normalizeMagnetCandidate, parseMagnetParams } from '../shared/magnet';

const URL_RE = /^https?:\/\/\S+$/i;

/** What the add field currently holds, which drives its tone, primary button and echo strip. */
export type AddState =
  | { kind: 'empty' }
  | { kind: 'invalid' }
  /** The design guide's "Add link" (fetch a .torrent from a URL) has no backend endpoint yet -
   * it needs new server work plus an SSRF consideration. A recognised URL is shown as
   * unsupported rather than wired to a fetch that doesn't exist. */
  | { kind: 'torrent-url' }
  | ({ kind: 'magnet'; uri: string; duplicate: Torrent | null } & ParsedMagnet)
  | { kind: 'multi-magnet'; uris: string[]; firstName: string | null };

/** Newline-separated pastes are treated as a batch only when every line parses as a
 * magnet/infohash - a mix of valid and junk lines falls through to plain 'invalid' rather than
 * silently dropping the junk. Pure; tested in add-state.spec.ts. */
export function resolveAddState(rawValue: string, torrents: readonly Torrent[]): AddState {
  const trimmed = rawValue.trim();
  if (!trimmed) {
    return { kind: 'empty' };
  }

  const lines = trimmed
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line.length > 0);

  if (lines.length > 1) {
    const uris = lines.map(normalizeMagnetCandidate);
    if (uris.every((uri): uri is string => uri !== null)) {
      const first = parseMagnetParams(uris[0]);
      return { kind: 'multi-magnet', uris, firstName: first?.displayName ?? null };
    }
    return { kind: 'invalid' };
  }

  const single = lines[0];
  const normalized = normalizeMagnetCandidate(single);
  if (normalized) {
    const parsed = parseMagnetParams(normalized);
    if (parsed) {
      const duplicate = parsed.infoHash
        ? (torrents.find((t) => t.infoHash.toLowerCase() === parsed.infoHash) ?? null)
        : null;
      return { kind: 'magnet', uri: normalized, duplicate, ...parsed };
    }
  }

  if (URL_RE.test(single)) {
    return { kind: 'torrent-url' };
  }

  return { kind: 'invalid' };
}
