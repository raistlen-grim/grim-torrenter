import { magnetDisplayName, magnetLinkFor, normalizeMagnetCandidate, parseMagnetParams } from './magnet';

const HEX = '0123456789abcdef0123456789abcdef01234567';
const BASE32 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';

describe('normalizeMagnetCandidate', () => {
  it('accepts a magnet URI, stripping quotes and angle brackets around a paste', () => {
    expect(normalizeMagnetCandidate(`  <magnet:?xt=urn:btih:${HEX}>  `)).toBe(`magnet:?xt=urn:btih:${HEX}`);
    expect(normalizeMagnetCandidate(`"magnet:?xt=urn:btih:${HEX}"`)).toBe(`magnet:?xt=urn:btih:${HEX}`);
  });

  it('turns a bare 40-character hex info hash into a magnet URI', () => {
    expect(normalizeMagnetCandidate(HEX)).toBe(`magnet:?xt=urn:btih:${HEX}`);
  });

  it('rejects anything else', () => {
    expect(normalizeMagnetCandidate('hello')).toBeNull();
    expect(normalizeMagnetCandidate('0123')).toBeNull();
    expect(normalizeMagnetCandidate('https://example.com/a.torrent')).toBeNull();
  });
});

describe('parseMagnetParams', () => {
  it('needs a btih', () => {
    expect(parseMagnetParams('magnet:?dn=foo')).toBeNull();
  });

  it('reads the info hash, name, length and tracker count', () => {
    const parsed = parseMagnetParams(
      `magnet:?xt=urn:btih:${HEX.toUpperCase()}&dn=Some%20Name&xl=1024&tr=udp%3A%2F%2Fa&tr=udp%3A%2F%2Fb`,
    );
    expect(parsed).toEqual({ infoHash: HEX, displayName: 'Some Name', lengthBytes: 1024, trackerCount: 2 });
  });

  it('leaves the info hash null for a base32 btih, rather than mis-comparing it', () => {
    expect(parseMagnetParams(`magnet:?xt=urn:btih:${BASE32}`)?.infoHash).toBeNull();
  });

  it('decodes + as a space', () => {
    expect(parseMagnetParams(`magnet:?xt=urn:btih:${HEX}&dn=Some+Name`)?.displayName).toBe('Some Name');
  });

  /** A malformed escape used to throw URIError inside the add field's computed - design_docs/0096. */
  it('keeps a malformed %-escape as raw text instead of throwing', () => {
    expect(parseMagnetParams(`magnet:?xt=urn:btih:${HEX}&dn=50%`)?.displayName).toBe('50%');
    expect(parseMagnetParams(`magnet:?xt=urn:btih:${HEX}&dn=%zz`)?.displayName).toBe('%zz');
  });

  it('ignores a non-numeric length', () => {
    expect(parseMagnetParams(`magnet:?xt=urn:btih:${HEX}&xl=lots`)?.lengthBytes).toBeNull();
  });
});

describe('magnetDisplayName', () => {
  it('prefers the dn name, then a shortened hash, then a generic label', () => {
    expect(magnetDisplayName(`magnet:?xt=urn:btih:${HEX}&dn=Name`)).toBe('Name');
    expect(magnetDisplayName(`magnet:?xt=urn:btih:${HEX}`)).toBe('btih:0123…4567');
    expect(magnetDisplayName(`magnet:?xt=urn:btih:${BASE32}`)).toBe('Magnet link');
  });
});

describe('magnetLinkFor', () => {
  it('builds a link the add field reads back to the same hash and name', () => {
    const link = magnetLinkFor(HEX, 'A & B 50%');
    expect(link).toBe(`magnet:?xt=urn:btih:${HEX}&dn=A%20%26%20B%2050%25`);
    expect(parseMagnetParams(link)?.displayName).toBe('A & B 50%');
    expect(parseMagnetParams(link)?.infoHash).toBe(HEX);
  });
});
