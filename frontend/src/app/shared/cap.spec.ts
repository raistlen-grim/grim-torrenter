import { capTitle } from './cap';

describe('capTitle', () => {
  it('names the source and the limit', () => {
    expect(capTitle(1024 * 1024, 'GLOBAL')).toMatch(/^Capped by the global limit, .+\/s$/);
    expect(capTitle(500_000, 'SCHEDULED')).toMatch(/^Capped by the off-hours schedule, /);
    expect(capTitle(100_000, 'TORRENT')).toMatch(/^Capped by this torrent's limit, /);
  });

  it('still reads sensibly without a limit figure', () => {
    expect(capTitle(0, 'NONE')).toBe('Capped by a rate limit');
  });
});
