import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { catchError, map, of } from 'rxjs';

import { AuthService } from './auth.service';

/**
 * Redirects to /login when auth is required and no token is held yet - a UX convenience, not
 * the real security boundary (that's entirely server-side, AuthenticationFilter). Fails open
 * on a status-check error (e.g. the backend is briefly unreachable) rather than locking the
 * user out client-side on a hunch - if auth genuinely is required, the very next real API call
 * 401s and authInterceptor redirects then anyway. See design_docs/0061.
 */
export const authGuard: CanActivateFn = () => {
  const authService = inject(AuthService);
  const router = inject(Router);

  return authService.status().pipe(
    map((status) => !status.authEnabled || !!authService.token() || router.createUrlTree(['/login'])),
    catchError(() => of(true)),
  );
};
