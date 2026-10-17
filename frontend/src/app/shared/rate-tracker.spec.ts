import { RateTracker } from './rate-tracker';

describe('RateTracker', () => {
  it('reports zero until there are two readings', () => {
    const tracker = new RateTracker(15_000);
    expect(tracker.record('a', 100, 0)).toEqual({ current: 0, trend: [] });
  });

  it('averages over the primary window rather than the last step', () => {
    const tracker = new RateTracker(10_000);
    tracker.record('a', 0, 0);
    tracker.record('a', 1000, 1000); // 1000/s
    const snapshot = tracker.record('a', 1000, 2000); // 0/s this step
    expect(snapshot.current).toBe(500); // 1000 bytes over 2 s
    expect(snapshot.trend).toEqual([1000, 0]);
  });

  it('clamps a decreasing value to zero', () => {
    const tracker = new RateTracker(10_000);
    tracker.record('a', 1000, 0);
    expect(tracker.record('a', 500, 1000).current).toBe(0);
  });

  it('forgets keys that retainOnly() leaves out', () => {
    const tracker = new RateTracker(10_000);
    tracker.record('a', 0, 0);
    tracker.record('b', 0, 0);
    tracker.retainOnly(new Set(['a']));
    // 'b' starts over: one reading again, so no rate yet.
    expect(tracker.record('b', 1000, 1000).current).toBe(0);
    expect(tracker.record('a', 1000, 1000).current).toBe(1000);
  });
});
