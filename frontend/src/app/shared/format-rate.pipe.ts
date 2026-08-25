import { Pipe, PipeTransform } from '@angular/core';

import { FormatBytesPipe } from './format-bytes.pipe';

@Pipe({ name: 'formatRate' })
export class FormatRatePipe implements PipeTransform {
  private readonly formatBytes = new FormatBytesPipe();

  transform(bytesPerSecond: number): string {
    return `${this.formatBytes.transform(bytesPerSecond)}/s`;
  }
}
