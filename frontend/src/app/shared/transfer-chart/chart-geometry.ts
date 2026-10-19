/** Pure geometry for TransferChart - scales, ticks and line paths - kept out of the component
 * so it can be unit-tested. See design_docs/0102. */

/** Round y-axis ticks from 0 up to just above max: steps of 1, 2 or 5 x 10^n, about four of
 * them. A flat-zero window still gets an axis (0 to 1 KiB/s) rather than dividing by zero. */
export function yTicks(max: number, target = 4): number[] {
  const top = max > 0 ? max : 1024;
  const rough = top / target;
  const magnitude = 10 ** Math.floor(Math.log10(rough));
  const step = [1, 2, 5, 10].map((m) => m * magnitude).find((candidate) => candidate >= rough) ?? 10 * magnitude;
  const ticks: number[] = [];
  for (let value = 0; value < top + step; value += step) {
    ticks.push(value);
    if (value >= top) {
      break;
    }
  }
  return ticks;
}

/** An SVG path through the points, broken at every null so a gap (the app wasn't running) shows
 * as a gap rather than a line drawn across it. A lone point between gaps gets a zero-length
 * segment, which the round cap draws as a dot. */
export function linePath(values: readonly (number | null)[], x: (index: number) => number, y: (value: number) => number): string {
  let path = '';
  let drawing = false;
  values.forEach((value, index) => {
    if (value === null) {
      drawing = false;
      return;
    }
    const point = `${round(x(index))} ${round(y(value))}`;
    if (drawing) {
      path += ` L ${point}`;
    } else {
      const next = values[index + 1];
      path += `${path ? ' ' : ''}M ${point}`;
      if (next === null || next === undefined) {
        path += ` L ${point}`;
      }
      drawing = true;
    }
  });
  return path;
}

/** Where to put x-axis labels: counting back from the newest interval in steps of
 * tickIntervals, so "Now" is always labelled and the others land on round ages. Oldest first. */
export function xTickIndexes(count: number, tickIntervals: number): number[] {
  const indexes: number[] = [];
  for (let index = count - 1; index >= 0; index -= tickIntervals) {
    indexes.unshift(index);
  }
  return indexes;
}

/** "Now", "30 s ago", "45 min ago", "2 h ago", "3 d ago" - the age of an interval's end. */
export function agoLabel(seconds: number): string {
  if (seconds <= 0) {
    return 'Now';
  }
  if (seconds < 120) {
    return `${seconds} s ago`;
  }
  if (seconds < 2 * 3600) {
    return `${Math.round(seconds / 60)} min ago`;
  }
  if (seconds < 2 * 86_400) {
    return `${Math.round(seconds / 3600)} h ago`;
  }
  return `${Math.round(seconds / 86_400)} d ago`;
}

/** The largest value in either series, ignoring gaps. */
export function seriesMax(...series: readonly (readonly (number | null)[])[]): number {
  let max = 0;
  for (const values of series) {
    for (const value of values) {
      if (value !== null && value > max) {
        max = value;
      }
    }
  }
  return max;
}

function round(value: number): number {
  return Math.round(value * 10) / 10;
}
