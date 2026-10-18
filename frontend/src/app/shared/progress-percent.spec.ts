import { progressPercent } from './progress-percent';

describe('progressPercent', () => {
  it('is 0 only for no progress at all, and never rounds real progress down to 0', () => {
    expect(progressPercent(0)).toBe(0);
    expect(progressPercent(0.0001)).toBe(1);
    expect(progressPercent(0.5)).toBe(50);
    expect(progressPercent(1)).toBe(100);
  });
});
