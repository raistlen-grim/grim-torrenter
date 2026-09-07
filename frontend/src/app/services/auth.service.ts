import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Observable, map, tap } from 'rxjs';

const TOKEN_STORAGE_KEY = 'grimtorrenter.auth.token';

export interface AuthStatus {
  authEnabled: boolean;
  passwordSet: boolean;
}

interface LoginResponse {
  token: string;
}

/**
 * Holds the current bearer token (design_docs/0061) - a single shared password, no username,
 * since this app has exactly one torrent list per deployment. The token is kept in
 * localStorage (not sessionStorage) so a page refresh doesn't force a re-login; it's private
 * to this browser/origin either way. authInterceptor attaches it to every outgoing request
 * and clears it on a 401; authGuard redirects to /login when auth is required and no token is
 * held yet - both are UX conveniences, not the actual security boundary, which is entirely
 * server-side (AuthenticationFilter).
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);

  private readonly _token = signal<string | null>(readStoredToken());
  readonly token = this._token.asReadonly();

  status(): Observable<AuthStatus> {
    return this.http.get<AuthStatus>('/api/auth/status');
  }

  login(password: string): Observable<void> {
    return this.http.post<LoginResponse>('/api/auth/login', { password }).pipe(
      tap((response) => this.setToken(response.token)),
      map(() => undefined),
    );
  }

  logout(): Observable<void> {
    return this.http.post<void>('/api/auth/logout', null).pipe(tap(() => this.clearToken()));
  }

  /** Public (unlike setToken) - authInterceptor also calls this on a 401, since that means
   * whatever token was held is dead (expired/revoked) and shouldn't be resent. */
  clearToken(): void {
    this._token.set(null);
    try {
      localStorage.removeItem(TOKEN_STORAGE_KEY);
    } catch {
      // See setToken() below.
    }
  }

  /** currentPassword is omitted (undefined, not sent as an empty string) while no password
   * exists yet - see AuthResource.changePassword()'s own bootstrap-vs-change distinction. */
  setPassword(newPassword: string, currentPassword?: string): Observable<void> {
    return this.http.put<void>('/api/auth/password', { currentPassword, newPassword });
  }

  private setToken(token: string): void {
    this._token.set(token);
    try {
      localStorage.setItem(TOKEN_STORAGE_KEY, token);
    } catch {
      // Private browsing / storage disabled - the token still works for this page load,
      // just won't survive a refresh. Not worth surfacing to the user.
    }
  }
}

function readStoredToken(): string | null {
  try {
    return localStorage.getItem(TOKEN_STORAGE_KEY);
  } catch {
    return null;
  }
}
