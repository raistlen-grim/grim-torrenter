import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { AppVersion, DiskUsage, HealthReport, ResourceUsage, ServiceStatus } from '../models/system.model';

@Injectable({ providedIn: 'root' })
export class SystemService {
  private readonly http = inject(HttpClient);

  diskUsage(): Observable<DiskUsage> {
    return this.http.get<DiskUsage>('/api/system/disk-usage');
  }

  resourceUsage(): Observable<ResourceUsage> {
    return this.http.get<ResourceUsage>('/api/system/resource-usage');
  }

  /** Which build the backend is - see design_docs/0084. */
  version(): Observable<AppVersion> {
    return this.http.get<AppVersion>('/api/system/version');
  }

  /** Everything the Health page shows, grouped - see design_docs/0086. */
  health(): Observable<HealthReport> {
    return this.http.get<HealthReport>('/api/system/health');
  }

  services(): Observable<ServiceStatus[]> {
    return this.http.get<ServiceStatus[]>('/api/system/services');
  }
}
