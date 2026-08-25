import { Pipe, PipeTransform } from '@angular/core';

/** Whole-percent granularity is too coarse to visibly move for a large file at slow
 * speed - byte counts make real movement visible even when the rounded percent doesn't
 * change. See design_docs/0020. */
@Pipe({ name: 'formatBytes' })
export class FormatBytesPipe implements PipeTransform {
  transform(bytes: number): string {
    if (bytes < 1024) {
      return `${bytes} B`;
    }
    const units = ['KB', 'MB', 'GB', 'TB'];
    let value = bytes;
    let unitIndex = -1;
    do {
      value /= 1024;
      unitIndex++;
    } while (value >= 1024 && unitIndex < units.length - 1);
    return `${value.toFixed(1)} ${units[unitIndex]}`;
  }
}
