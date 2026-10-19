import { agoLabel, linePath, seriesMax, xTickIndexes, yTicks } from './chart-geometry';

describe('yTicks', () => {
  it('uses round steps that cover the maximum', () => {
    expect(yTicks(950)).toEqual([0, 500, 1000]);
    expect(yTicks(3_800_000)).toEqual([0, 1_000_000, 2_000_000, 3_000_000, 4_000_000]);
  });

  it('still draws an axis for an all-zero window', () => {
    const ticks = yTicks(0);
    expect(ticks[0]).toBe(0);
    expect(ticks[ticks.length - 1]).toBeGreaterThan(0);
  });
});

describe('linePath', () => {
  const x = (index: number) => index * 10;
  const y = (value: number) => 100 - value;

  it('draws one continuous line through every point', () => {
    expect(linePath([0, 10, 20], x, y)).toBe('M 0 100 L 10 90 L 20 80');
  });

  it('breaks at gaps and draws a lone point as a dot', () => {
    expect(linePath([5, null, 10, 20, null], x, y)).toBe('M 0 95 L 0 95 M 20 90 L 30 80');
  });

  it('is empty when there is nothing to draw', () => {
    expect(linePath([null, null], x, y)).toBe('');
  });
});

describe('xTickIndexes', () => {
  it('counts back from the newest interval so Now is always labelled', () => {
    expect(xTickIndexes(60, 15)).toEqual([14, 29, 44, 59]);
    expect(xTickIndexes(168, 24)).toEqual([23, 47, 71, 95, 119, 143, 167]);
  });
});

describe('agoLabel', () => {
  it('uses the unit that reads naturally', () => {
    expect(agoLabel(0)).toBe('Now');
    expect(agoLabel(45)).toBe('45 s ago');
    expect(agoLabel(1800)).toBe('30 min ago');
    expect(agoLabel(4 * 3600)).toBe('4 h ago');
    expect(agoLabel(3 * 86_400)).toBe('3 d ago');
  });
});

describe('seriesMax', () => {
  it('ignores gaps', () => {
    expect(seriesMax([1, null, 7], [null, 3])).toBe(7);
    expect(seriesMax([null])).toBe(0);
  });
});
