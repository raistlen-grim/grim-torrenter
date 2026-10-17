import { Observable, of, throwError } from 'rxjs';

import { pollEvery } from './poll';

describe('pollEvery', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  /** One failure used to end the poll for good - design_docs/0095. */
  it('skips a failed fetch and keeps polling', () => {
    const responses: Observable<number>[] = [of(1), throwError(() => new Error('down')), of(3)];
    let call = 0;
    const seen: number[] = [];
    let errored = false;

    const subscription = pollEvery(1000, () => responses[call++] ?? of(-1)).subscribe({
      next: (value) => seen.push(value),
      error: () => (errored = true),
    });
    vi.advanceTimersByTime(2000);
    subscription.unsubscribe();

    expect(seen).toEqual([1, 3]);
    expect(errored).toBe(false);
  });
});
