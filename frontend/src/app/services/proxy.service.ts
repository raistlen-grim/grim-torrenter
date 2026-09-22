import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { ProxyStatus, ProxyTestResult } from '../models/proxy.model';

/** The SOCKS5 proxy's status, its write-only password, and a "test it" action (design_docs/0079).
 * Host/port/username/enabled/block live in the ordinary settings; the password is separate and
 * never comes back from any of these calls. */
@Injectable({ providedIn: 'root' })
export class ProxyService {
  private readonly http = inject(HttpClient);

  status(): Observable<ProxyStatus> {
    return this.http.get<ProxyStatus>('/api/proxy');
  }

  /** An empty password clears it. */
  setPassword(password: string): Observable<ProxyStatus> {
    return this.http.put<ProxyStatus>('/api/proxy/password', { password });
  }

  clearPassword(): Observable<ProxyStatus> {
    return this.http.delete<ProxyStatus>('/api/proxy/password');
  }

  /** Checks the currently *saved* proxy settings and password - save first, then test. */
  test(): Observable<ProxyTestResult> {
    return this.http.post<ProxyTestResult>('/api/proxy/test', null);
  }
}
