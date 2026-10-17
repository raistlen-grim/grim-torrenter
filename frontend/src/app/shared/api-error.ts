import { HttpErrorResponse } from '@angular/common/http';

/**
 * The message to show for a failed API call: the server's own reason when it gave one (every
 * error response carries `{"error": "..."}` since design_docs/0094), "Could not reach the
 * server." when the request never got an answer, otherwise `fallback`. The one place error
 * bodies are read, so every toast and inline message says the same kind of thing. See
 * design_docs/0096.
 */
export function apiErrorMessage(error: unknown, fallback: string): string {
  if (error instanceof HttpErrorResponse) {
    const body: unknown = error.error;
    if (body !== null && typeof body === 'object' && 'error' in body) {
      const reason = (body as { error: unknown }).error;
      if (typeof reason === 'string' && reason.trim() !== '') {
        return reason;
      }
    }
    if (error.status === 0) {
      return 'Could not reach the server.';
    }
  }
  return fallback;
}
