import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { DiskUsage, ResourceUsage } from '../models/system.model';

@Injectable({ providedIn: 'root' })
export class SystemService {
  private readonly http = inject(HttpClient);

  diskUsage(): Observable<DiskUsage> {
    return this.http.get<DiskUsage>('/api/system/disk-usage');
  }

  resourceUsage(): Observable<ResourceUsage> {
    return this.http.get<ResourceUsage>('/api/system/resource-usage');
  }
}
