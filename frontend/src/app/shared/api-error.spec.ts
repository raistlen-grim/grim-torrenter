import { HttpErrorResponse } from '@angular/common/http';

import { apiErrorMessage } from './api-error';

describe('apiErrorMessage', () => {
  it("uses the server's reason when the body has one", () => {
    const error = new HttpErrorResponse({ status: 400, error: { error: 'magnetFetchConcurrencyLimit must be between 1 and 1024' } });
    expect(apiErrorMessage(error, 'fallback')).toBe('magnetFetchConcurrencyLimit must be between 1 and 1024');
  });

  it('says the server could not be reached when there was no response', () => {
    expect(apiErrorMessage(new HttpErrorResponse({ status: 0 }), 'fallback')).toBe('Could not reach the server.');
  });

  it('falls back for a body without a reason, or for anything that is not an HTTP error', () => {
    expect(apiErrorMessage(new HttpErrorResponse({ status: 500, error: 'oops' }), 'fallback')).toBe('fallback');
    expect(apiErrorMessage(new HttpErrorResponse({ status: 500, error: { error: '  ' } }), 'fallback')).toBe('fallback');
    expect(apiErrorMessage(new Error('boom'), 'fallback')).toBe('fallback');
  });
});
