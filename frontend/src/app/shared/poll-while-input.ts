import { Signal } from '@angular/core';
import { toObservable } from '@angular/core/rxjs-interop';
import { Observable, interval, startWith, switchMap } from 'rxjs';

/**
 * Polls fetch(value) every intervalMs, restarting whenever input's value changes. Built on
 * toObservable specifically to defer the first read until Angular has actually set the
 * input - reading a required input signal synchronously (e.g. inside interval(...).pipe(
 * switchMap(() => fetch(someInputSignal()))) directly) throws NG0950, since that runs
 * during the component's field-initializer/construction phase, before input binding has
 * happened. See design_docs/0031's detail-view polling pattern.
 */
export function pollWhileInput<T>(
  input: Signal<string>,
  intervalMs: number,
  fetch: (value: string) => Observable<T>,
): Observable<T> {
  return toObservable(input).pipe(
    switchMap((value) => interval(intervalMs).pipe(startWith(0), switchMap(() => fetch(value)))),
  );
}
