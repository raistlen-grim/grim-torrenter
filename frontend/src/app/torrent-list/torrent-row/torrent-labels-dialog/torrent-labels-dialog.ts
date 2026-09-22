import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, effect, inject, input, output, signal, untracked } from '@angular/core';
import { MessageService } from 'primeng/api';
import { ButtonModule } from 'primeng/button';
import { DialogModule } from 'primeng/dialog';

import { LabelService } from '../../../services/label.service';
import { TorrentEventsService } from '../../../services/torrent-events.service';
import { TorrentService } from '../../../services/torrent.service';

const MAX_NAME_LENGTH = 32;

/**
 * Pick which labels a torrent carries (design_docs/0077). A checkbox per managed label, plus an
 * inline "new label" field that creates the label and ticks it in one step so there's no detour
 * through the sidebar's manage dialog. Save sends the complete id list; the response (the real,
 * updated torrent) goes straight into TorrentEventsService so the row updates without waiting
 * for the next snapshot. Renaming and deleting labels live in the sidebar's manage dialog, not
 * here.
 */
@Component({
  selector: 'app-torrent-labels-dialog',
  imports: [ButtonModule, DialogModule],
  templateUrl: './torrent-labels-dialog.html',
  styleUrl: './torrent-labels-dialog.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TorrentLabelsDialog {
  private readonly labelService = inject(LabelService);
  private readonly torrentService = inject(TorrentService);
  private readonly events = inject(TorrentEventsService);
  private readonly messageService = inject(MessageService);

  readonly infoHash = input.required<string>();
  readonly torrentName = input.required<string>();
  readonly visible = input.required<boolean>();
  readonly visibleChange = output<boolean>();

  readonly labels = this.labelService.labels;
  readonly maxNameLength = MAX_NAME_LENGTH;

  readonly selected = signal<ReadonlySet<string>>(new Set());
  readonly newName = signal('');
  readonly error = signal<string | null>(null);
  readonly saving = signal(false);
  readonly creating = signal(false);

  constructor() {
    // Re-seeded from the torrent's current labels each time the dialog opens, so an earlier,
    // abandoned selection never carries over.
    effect(() => {
      if (this.visible()) {
        untracked(() => {
          const current = this.events.torrents().find((t) => t.infoHash === this.infoHash());
          this.selected.set(new Set(current?.labelIds ?? []));
          this.newName.set('');
          this.error.set(null);
        });
      }
    });
  }

  toggle(id: string): void {
    this.selected.update((set) => {
      const next = new Set(set);
      if (next.has(id)) {
        next.delete(id);
      } else {
        next.add(id);
      }
      return next;
    });
  }

  createAndSelect(): void {
    const name = this.newName().trim();
    if (name === '' || this.creating()) {
      return;
    }
    this.creating.set(true);
    this.error.set(null);
    this.labelService.create(name).subscribe({
      next: (created) => {
        this.selected.update((set) => new Set(set).add(created.id));
        this.newName.set('');
        this.creating.set(false);
      },
      error: (e: HttpErrorResponse) => {
        this.creating.set(false);
        this.error.set(
          e.status === 409
            ? 'A label with that name already exists.'
            : e.status === 400
              ? `A label name must be 1-${MAX_NAME_LENGTH} characters.`
              : 'Could not create the label - please try again.',
        );
      },
    });
  }

  save(): void {
    if (this.saving()) {
      return;
    }
    // In the managed list's own order (not click order), and only ids that still exist.
    const ids = this.labels()
      .map((label) => label.id)
      .filter((id) => this.selected().has(id));
    this.saving.set(true);
    this.torrentService.setLabels(this.infoHash(), ids).subscribe({
      next: (updated) => {
        this.events.upsert(updated);
        this.saving.set(false);
        this.close();
      },
      error: () => {
        this.saving.set(false);
        this.messageService.add({
          severity: 'error',
          summary: 'Could not save labels',
          detail: `"${this.torrentName()}" - please try again.`,
        });
      },
    });
  }

  close(): void {
    this.visibleChange.emit(false);
  }
}
