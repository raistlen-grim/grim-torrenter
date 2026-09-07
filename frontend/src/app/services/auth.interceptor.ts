import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';

import { AuthService } from './auth.service';

/** Never carry a bearer token (irrelevant for /login, and a stale/expired token on /status
 * would defeat its whole purpose of being safely callable before any token is known good). */
const UNAUTHENTICATED_PATHS = ['/api/auth/login', '/api/auth/status'];

/**
 * Attaches the current bearer token (design_docs/0061) to every outgoing request, and on a
 * 401 clears it and sends the user to /login - this is the client-side convenience half of
 * auth; the actual boundary is AuthenticationFilter on the server, so a bypassed/broken
 * interceptor can never itself grant access to anything.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const authService = inject(AuthService);
  const router = inject(Router);

  const isUnauthenticatedPath = UNAUTHENTICATED_PATHS.some((path) => req.url.startsWith(path));
  const token = authService.token();
  const authorizedReq =
    token && !isUnauthenticatedPath ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }) : req;

  return next(authorizedReq).pipe(
    catchError((error: unknown) => {
      if (error instanceof HttpErrorResponse && error.status === 401 && !isUnauthenticatedPath) {
        authService.clearToken();
        router.navigateByUrl('/login');
      }
      return throwError(() => error);
    }),
  );
};
