import { Torrent } from '../models/torrent.model';
import { matchesLabelFilter, matchesSearchText, matchesStatusFilter } from './torrent-filter.service';

function torrent(overrides: Partial<Torrent>): Torrent {
  return { name: 'Ubuntu ISO', state: 'DOWNLOADING', totalPieces: 10, completedPieces: 3, labelIds: [], ...overrides } as Torrent;
}

describe('matchesStatusFilter', () => {
  it('maps each filter to its state', () => {
    expect(matchesStatusFilter(torrent({ state: 'STOPPED' }), 'paused')).toBe(true);
    expect(matchesStatusFilter(torrent({ state: 'SEEDING' }), 'downloading')).toBe(false);
    expect(matchesStatusFilter(torrent({}), 'all')).toBe(true);
  });

  it('counts a fully downloaded torrent as harvested even while paused', () => {
    expect(matchesStatusFilter(torrent({ state: 'STOPPED', completedPieces: 10 }), 'harvest')).toBe(true);
    expect(matchesStatusFilter(torrent({ completedPieces: 9 }), 'harvest')).toBe(false);
    expect(matchesStatusFilter(torrent({ totalPieces: 0, completedPieces: 0 }), 'harvest')).toBe(false);
  });
});

describe('matchesSearchText', () => {
  it('matches the name or a label name, case-insensitively, ignoring surrounding space', () => {
    expect(matchesSearchText(torrent({}), '  ubuntu ')).toBe(true);
    expect(matchesSearchText(torrent({}), 'linux', ['Linux ISOs'])).toBe(true);
    expect(matchesSearchText(torrent({}), 'debian')).toBe(false);
    expect(matchesSearchText(torrent({}), '')).toBe(true);
  });
});

describe('matchesLabelFilter', () => {
  const tagged = torrent({ labelIds: ['a', 'b'] });

  it('matches everything with no labels selected', () => {
    expect(matchesLabelFilter(torrent({}), [], 'all')).toBe(true);
  });

  it('needs one selected label for "any" and every one for "all"', () => {
    expect(matchesLabelFilter(tagged, ['a', 'c'], 'any')).toBe(true);
    expect(matchesLabelFilter(tagged, ['a', 'c'], 'all')).toBe(false);
    expect(matchesLabelFilter(tagged, ['a', 'b'], 'all')).toBe(true);
  });
});
