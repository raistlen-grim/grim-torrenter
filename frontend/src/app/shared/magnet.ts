/**
 * Client-side reading of a pasted magnet link - only to preview and validate it before it's
 * sent (the server parses it again for real, design_docs/0071's "input validation/preview"
 * allowance). Pure functions, so they're unit-tested in magnet.spec.ts. Moved out of
 * TorrentList in design_docs/0096.
 */

/** Requires the actual btih infohash, not just the magnet: scheme - a bare `magnet:?dn=foo`
 * must fail validation. */
const MAGNET_PREFIX_RE = /^magnet:\?/i;
const BTIH_RE = /xt=urn:btih:([0-9a-fA-F]{40}|[A-Za-z2-7]{32})/i;
const HEX_INFOHASH_RE = /^[0-9a-fA-F]{40}$/;

export interface ParsedMagnet {
  /** Only populated for a 40-char hex btih (the base32 form isn't converted client-side, so a
   * base32 magnet skips duplicate detection rather than mis-comparing). */
  infoHash: string | null;
  displayName: string | null;
  lengthBytes: number | null;
  trackerCount: number;
}

/** A magnet URI (quotes/angle brackets around a paste stripped), or a bare 40-char hex info hash
 * turned into one; null for anything else. */
export function normalizeMagnetCandidate(value: string): string | null {
  const stripped = value.trim().replace(/^[<'"]+/, '').replace(/[>'"]+$/, '');
  if (MAGNET_PREFIX_RE.test(stripped)) {
    return stripped;
  }
  if (HEX_INFOHASH_RE.test(stripped)) {
    return `magnet:?xt=urn:btih:${stripped}`;
  }
  return null;
}

/** Null without a btih. Never throws: a malformed %-escape (a literal "50%" in a name is common)
 * keeps the raw text rather than throwing URIError - this runs inside a computed the list's
 * template reads, so a throw broke the whole list view while the text was in the field. `+`
 * decodes to a space, the form-encoding most magnet generators use. See design_docs/0096. */
export function parseMagnetParams(uri: string): ParsedMagnet | null {
  const match = BTIH_RE.exec(uri);
  if (!match) {
    return null;
  }
  const infoHash = match[1].length === 40 ? match[1].toLowerCase() : null;
  let displayName: string | null = null;
  let lengthBytes: number | null = null;
  let trackerCount = 0;
  const query = uri.slice(uri.indexOf('?') + 1);
  for (const part of query.split('&')) {
    const eqIndex = part.indexOf('=');
    if (eqIndex === -1) {
      continue;
    }
    const key = part.slice(0, eqIndex);
    const value = decodeParam(part.slice(eqIndex + 1));
    if (key === 'dn' && displayName === null) {
      displayName = value;
    } else if (key === 'xl' && lengthBytes === null) {
      const parsedLength = Number(value);
      lengthBytes = Number.isFinite(parsedLength) ? parsedLength : null;
    } else if (key === 'tr') {
      trackerCount++;
    }
  }
  return { infoHash, displayName, lengthBytes, trackerCount };
}

function decodeParam(raw: string): string {
  const spaced = raw.replace(/\+/g, ' ');
  try {
    return decodeURIComponent(spaced);
  } catch {
    return spaced;
  }
}

/** The display name, else a shortened `btih:abcd…wxyz`, else a generic label - never null, so a
 * pending row always has something to show. */
export function magnetDisplayName(uri: string): string {
  const parsed = parseMagnetParams(uri);
  if (parsed?.displayName) {
    return parsed.displayName;
  }
  return parsed?.infoHash ? shortInfoHash(parsed.infoHash) : 'Magnet link';
}

export function shortInfoHash(infoHash: string): string {
  return `btih:${infoHash.slice(0, 4)}…${infoHash.slice(-4)}`;
}
