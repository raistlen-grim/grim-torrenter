import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, input, output, signal } from '@angular/core';
import { ButtonModule } from 'primeng/button';
import { DialogModule } from 'primeng/dialog';

import { Label } from '../../../models/torrent.model';
import { LabelService } from '../../../services/label.service';
import { TorrentEventsService } from '../../../services/torrent-events.service';
import { TorrentFilterService } from '../../../services/torrent-filter.service';
import { pluralTorrentCount } from '../../../shared/plural-torrent-count';

const MAX_NAME_LENGTH = 32;

/**
 * Create, rename and delete labels (design_docs/0077). Lives in the sidebar shell, outside
 * TorrentList's own <p-toast>/<p-confirmDialog> scope, so it reports errors inline and confirms
 * a delete inline (a second click on the row) rather than through those. The backend is the
 * authority on what's a valid name - this only trims and length-limits input for ergonomics and
 * words the 400/409 responses.
 */
@Component({
  selector: 'app-manage-labels-dialog',
  imports: [ButtonModule, DialogModule],
  templateUrl: './manage-labels-dialog.html',
  styleUrl: './manage-labels-dialog.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ManageLabelsDialog {
  private readonly labelService = inject(LabelService);
  private readonly events = inject(TorrentEventsService);
  private readonly filter = inject(TorrentFilterService);

  readonly visible = input.required<boolean>();
  readonly visibleChange = output<boolean>();

  readonly maxNameLength = MAX_NAME_LENGTH;
  readonly labels = this.labelService.labels;

  readonly newName = signal('');
  readonly editingId = signal<string | null>(null);
  readonly editName = signal('');
  readonly confirmingDeleteId = signal<string | null>(null);
  readonly error = signal<string | null>(null);
  readonly busy = signal(false);

  private readonly torrentCounts = computed(() => {
    const counts = new Map<string, number>();
    for (const torrent of this.events.torrents()) {
      for (const id of torrent.labelIds) {
        counts.set(id, (counts.get(id) ?? 0) + 1);
      }
    }
    return counts;
  });

  torrentCount(label: Label): number {
    return this.torrentCounts().get(label.id) ?? 0;
  }

  countText(label: Label): string {
    return pluralTorrentCount(this.torrentCount(label));
  }

  add(): void {
    const name = this.newName().trim();
    if (name === '' || this.busy()) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    this.labelService.create(name).subscribe({
      next: () => {
        this.newName.set('');
        this.busy.set(false);
      },
      error: (e: HttpErrorResponse) => this.fail(e),
    });
  }

  startEdit(label: Label): void {
    this.error.set(null);
    this.confirmingDeleteId.set(null);
    this.editingId.set(label.id);
    this.editName.set(label.name);
  }

  cancelEdit(): void {
    this.editingId.set(null);
    this.error.set(null);
  }

  saveEdit(): void {
    const id = this.editingId();
    const name = this.editName().trim();
    if (id === null || name === '' || this.busy()) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    this.labelService.rename(id, name).subscribe({
      next: () => {
        this.editingId.set(null);
        this.busy.set(false);
      },
      error: (e: HttpErrorResponse) => this.fail(e),
    });
  }

  askDelete(label: Label): void {
    this.error.set(null);
    this.editingId.set(null);
    this.confirmingDeleteId.set(label.id);
  }

  cancelDelete(): void {
    this.confirmingDeleteId.set(null);
  }

  confirmDelete(label: Label): void {
    if (this.busy()) {
      return;
    }
    this.busy.set(true);
    this.labelService.delete(label.id).subscribe({
      next: () => {
        this.filter.removeLabel(label.id);
        this.confirmingDeleteId.set(null);
        this.busy.set(false);
      },
      error: (e: HttpErrorResponse) => {
        this.confirmingDeleteId.set(null);
        this.fail(e);
      },
    });
  }

  close(): void {
    this.editingId.set(null);
    this.confirmingDeleteId.set(null);
    this.error.set(null);
    this.visibleChange.emit(false);
  }

  private fail(e: HttpErrorResponse): void {
    this.busy.set(false);
    if (e.status === 409) {
      this.error.set('A label with that name already exists.');
    } else if (e.status === 400) {
      this.error.set(`A label name must be 1-${MAX_NAME_LENGTH} characters, with no control characters.`);
    } else if (e.status === 404) {
      this.error.set('That label no longer exists.');
    } else {
      this.error.set('Something went wrong - please try again.');
    }
  }
}
