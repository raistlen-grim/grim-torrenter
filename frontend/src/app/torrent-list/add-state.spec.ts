import { Torrent } from '../models/torrent.model';
import { resolveAddState } from './add-state';

const HEX = '0123456789abcdef0123456789abcdef01234567';
const OTHER_HEX = 'fedcba9876543210fedcba9876543210fedcba98';

function torrent(infoHash: string, name: string): Torrent {
  return { infoHash, name } as Torrent;
}

describe('resolveAddState', () => {
  it('is empty for blank input', () => {
    expect(resolveAddState('', []).kind).toBe('empty');
    expect(resolveAddState('   \n ', []).kind).toBe('empty');
  });

  it('recognises a single magnet', () => {
    const state = resolveAddState(`magnet:?xt=urn:btih:${HEX}&dn=Name`, []);
    expect(state).toMatchObject({ kind: 'magnet', infoHash: HEX, displayName: 'Name', duplicate: null });
  });

  it('flags a magnet already in the list, whatever the case of either hash', () => {
    const existing = torrent(HEX.toUpperCase(), 'Existing');
    const state = resolveAddState(`magnet:?xt=urn:btih:${HEX}`, [existing]);
    expect(state).toMatchObject({ kind: 'magnet', duplicate: existing });
  });

  it('treats several lines as a batch only when every line is a magnet or info hash', () => {
    const batch = resolveAddState(`magnet:?xt=urn:btih:${HEX}&dn=First\n${OTHER_HEX}`, []);
    expect(batch).toMatchObject({ kind: 'multi-magnet', firstName: 'First' });
    expect(batch.kind === 'multi-magnet' && batch.uris.length).toBe(2);

    expect(resolveAddState(`magnet:?xt=urn:btih:${HEX}\nnot a magnet`, []).kind).toBe('invalid');
  });

  it('marks an http(s) URL as unsupported and anything else as invalid', () => {
    expect(resolveAddState('https://example.com/a.torrent', []).kind).toBe('torrent-url');
    expect(resolveAddState('hello', []).kind).toBe('invalid');
  });

  it('does not throw on a malformed %-escape', () => {
    expect(resolveAddState(`magnet:?xt=urn:btih:${HEX}&dn=100%`, [])).toMatchObject({
      kind: 'magnet',
      displayName: '100%',
    });
  });
});
