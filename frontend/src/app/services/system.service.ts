import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { DiskUsage } from '../models/system.model';

@Injectable({ providedIn: 'root' })
export class SystemService {
  private readonly http = inject(HttpClient);

  diskUsage(): Observable<DiskUsage> {
    return this.http.get<DiskUsage>('/api/system/disk-usage');
  }
}
