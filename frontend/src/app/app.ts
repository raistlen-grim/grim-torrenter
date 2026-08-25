import { Component, inject } from '@angular/core';
import { RouterOutlet } from '@angular/router';

import { TorrentEventsService } from './services/torrent-events.service';
import { AppFooter } from './shell/app-footer/app-footer';
import { AppHeader } from './shell/app-header/app-header';
import { AppSidebar } from './shell/app-sidebar/app-sidebar';

@Component({
  selector: 'app-root',
  imports: [AppFooter, AppHeader, AppSidebar, RouterOutlet],
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App {
  constructor() {
    inject(TorrentEventsService).connect();
  }
}
