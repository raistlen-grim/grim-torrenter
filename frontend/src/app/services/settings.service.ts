import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { Settings } from '../models/settings.model';

@Injectable({ providedIn: 'root' })
export class SettingsService {
  private readonly http = inject(HttpClient);

  current(): Observable<Settings> {
    return this.http.get<Settings>('/api/settings');
  }

  update(settings: Settings): Observable<Settings> {
    return this.http.put<Settings>('/api/settings', settings);
  }
}
