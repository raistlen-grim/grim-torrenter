import { Signal } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { EMPTY, Observable, catchError, interval, startWith, switchMap } from 'rxjs';

/**
 * Calls fetch() now and then every intervalMs, emitting each result. A failed fetch (the server
 * restarting for an upgrade, a network blip, a 404 for a torrent removed elsewhere) just skips
 * that tick: the stream never errors, so a toSignal() over it keeps its last good value and the
 * next tick tries again. Without the per-tick catchError, one failure ended the poll for good and
 * made the signal throw on every read. Every poll in the app goes through this. See
 * design_docs/0095.
 */
export function pollEvery<T>(intervalMs: number, fetch: () => Observable<T>): Observable<T> {
  return interval(intervalMs).pipe(
    startWith(0),
    switchMap(() => fetch().pipe(catchError(() => EMPTY))),
  );
}

/** pollEvery() while visible is true, stopping (no request in flight) the moment it turns false -
 * for a dialog that only needs live data while it's open. pollWhileInput() is the other gated
 * form, keyed to a changing value instead. Must be called in an injection context (a field
 * initializer). See design_docs/0073. */
export function pollWhileVisible<T>(
  visible: Signal<boolean>,
  intervalMs: number,
  fetch: () => Observable<T>,
  initialValue: T,
): Signal<T> {
  return toSignal(
    toObservable(visible).pipe(switchMap((isVisible) => (isVisible ? pollEvery(intervalMs, fetch) : EMPTY))),
    { initialValue },
  );
}
