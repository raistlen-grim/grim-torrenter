import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { App } from './app';
import { TorrentEventsService } from './services/torrent-events.service';

/** jsdom has no matchMedia, and ThemeService reads prefers-color-scheme through it as soon as it's
 * created. A stand-in that always answers "light" and never fires is all App's creation needs. */
function stubMatchMedia(): void {
  if (typeof globalThis.matchMedia === 'function') {
    return;
  }
  const stub = (query: string) =>
    ({
      matches: false,
      media: query,
      onchange: null,
      addEventListener: () => {},
      removeEventListener: () => {},
      addListener: () => {},
      removeListener: () => {},
      dispatchEvent: () => false,
    }) as MediaQueryList;
  // defineProperty rather than assignment: lib.dom declares matchMedia as a global function.
  Object.defineProperty(globalThis, 'matchMedia', { value: stub, configurable: true, writable: true });
}

describe('App', () => {
  beforeEach(async () => {
    stubMatchMedia();
    await TestBed.configureTestingModule({
      imports: [App],
      // App now composes AppHeader/AppFooter (design_docs/0043), which each inject
      // HttpClient via DhtService/SystemService - provideHttpClientTesting stops their
      // polling from making real network calls during this test.
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: TorrentEventsService, useValue: { connect: () => {} } },
      ],
    }).compileComponents();
  });

  it('should create the app', () => {
    const fixture = TestBed.createComponent(App);
    expect(fixture.componentInstance).toBeTruthy();
  });
});
