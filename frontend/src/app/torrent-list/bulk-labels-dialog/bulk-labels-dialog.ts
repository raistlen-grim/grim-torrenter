import { HttpErrorResponse } from '@angular/common/http';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';
import { MessageService } from 'primeng/api';
import { ButtonModule } from 'primeng/button';
import { DialogModule } from 'primeng/dialog';
import { catchError, forkJoin, of } from 'rxjs';

import { Torrent } from '../../models/torrent.model';
import { LabelService } from '../../services/label.service';
import { TorrentEventsService } from '../../services/torrent-events.service';
import { TorrentService } from '../../services/torrent.service';
import { pluralTorrentCount } from '../../shared/plural-torrent-count';

const MAX_NAME_LENGTH = 32;

/** all = every target has the label, none = no target has it, some = only part of them. */
type LabelState = 'all' | 'some' | 'none';

/**
 * Labels for several torrents at once - the selection bar's Label action (design_docs/0083's
 * addendum). The same list-plus-"new label" shape as the per-torrent TorrentLabelsDialog, but
 * each checkbox has three states, because the selected torrents rarely agree: ticked (all of
 * them have it), a dash (only some do) or empty (none do). Ticking adds the label to every
 * torrent, clearing removes it from every torrent, and a dash left alone changes nothing for
 * that label. Save sends each changed torrent its own complete id list through the existing
 * per-torrent endpoint; torrents whose labels wouldn't change are not sent at all.
 */
@Component({
  selector: 'app-bulk-labels-dialog',
  imports: [ButtonModule, DialogModule],
  templateUrl: './bulk-labels-dialog.html',
  styleUrl: './bulk-labels-dialog.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BulkLabelsDialog {
  private readonly labelService = inject(LabelService);
  private readonly torrentService = inject(TorrentService);
  private readonly events = inject(TorrentEventsService);
  private readonly messageService = inject(MessageService);

  /** The current selection. Snapshotted when the dialog opens (see `targets`), so the list
   * changing underneath an open dialog doesn't change what Save applies to. */
  readonly torrents = input.required<Torrent[]>();
  readonly visible = input.required<boolean>();
  readonly visibleChange = output<boolean>();

  readonly labels = this.labelService.labels;
  readonly maxNameLength = MAX_NAME_LENGTH;

  private readonly targets = signal<Torrent[]>([]);
  /** Only the labels the user has explicitly set to all/none in this dialog; anything absent
   * keeps whatever each torrent already has. */
  private readonly choices = signal<ReadonlyMap<string, 'all' | 'none'>>(new Map());

  readonly newName = signal('');
  readonly error = signal<string | null>(null);
  readonly saving = signal(false);
  readonly creating = signal(false);

  readonly targetCountLabel = computed(() => pluralTorrentCount(this.targets().length));

  /** Where each label stands across the targets before any change made here. */
  private readonly initialStates = computed(() => {
    const targets = this.targets();
    const states = new Map<string, LabelState>();
    for (const label of this.labels()) {
      const count = targets.filter((t) => t.labelIds.includes(label.id)).length;
      states.set(label.id, count === 0 ? 'none' : count === targets.length ? 'all' : 'some');
    }
    return states;
  });

  /** What each checkbox shows: the user's choice where there is one, else the initial state. */
  readonly states = computed(() => {
    const choices = this.choices();
    const states = new Map<string, LabelState>();
    for (const [id, initial] of this.initialStates()) {
      states.set(id, choices.get(id) ?? initial);
    }
    return states;
  });

  readonly hasMixed = computed(() => [...this.states().values()].includes('some'));

  constructor() {
    // Re-seeded each time the dialog opens, so an abandoned set of choices never carries over.
    effect(() => {
      if (this.visible()) {
        untracked(() => {
          this.targets.set(this.torrents());
          this.choices.set(new Map());
          this.newName.set('');
          this.error.set(null);
        });
      }
    });
  }

  /** all -> none -> all for a label the targets agreed on; some -> all -> none -> some for one
   * they didn't, so "leave it as it was" can always be got back to. The checkbox's own DOM
   * state is set here too: a native checkbox flips itself on click, and a binding whose value
   * didn't change would not put it back. */
  toggle(id: string, event: Event): void {
    const current = this.states().get(id) ?? 'none';
    const initial = this.initialStates().get(id) ?? 'none';
    const next: LabelState = current === 'some' ? 'all' : current === 'all' ? 'none' : initial === 'some' ? 'some' : 'all';
    this.choices.update((choices) => {
      const updated = new Map(choices);
      if (next === 'some') {
        updated.delete(id);
      } else {
        updated.set(id, next);
      }
      return updated;
    });
    const checkbox = event.target as HTMLInputElement;
    checkbox.checked = next === 'all';
    checkbox.indeterminate = next === 'some';
  }

  /** Creates the label and marks it for every target, in one step. */
  createAndSelect(): void {
    const name = this.newName().trim();
    if (name === '' || this.creating()) {
      return;
    }
    this.creating.set(true);
    this.error.set(null);
    this.labelService.create(name).subscribe({
      next: (created) => {
        this.choices.update((choices) => new Map(choices).set(created.id, 'all'));
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

  onNewNameInput(event: Event): void {
    this.newName.set((event.target as HTMLInputElement).value);
  }

  save(): void {
    if (this.saving()) {
      return;
    }
    const choices = this.choices();
    // Each torrent's new list, in the managed list's own order and only ids that still exist -
    // same rule as the per-torrent dialog. Unchanged torrents are dropped.
    const changes = this.targets().flatMap((torrent) => {
      const ids = this.labels()
        .map((label) => label.id)
        .filter((id) => {
          const choice = choices.get(id);
          return choice === 'all' || (choice === undefined && torrent.labelIds.includes(id));
        });
      const unchanged = ids.length === torrent.labelIds.length && ids.every((id) => torrent.labelIds.includes(id));
      return unchanged ? [] : [{ infoHash: torrent.infoHash, ids }];
    });
    if (changes.length === 0) {
      this.close();
      return;
    }
    this.saving.set(true);
    forkJoin(
      changes.map((change) => this.torrentService.setLabels(change.infoHash, change.ids).pipe(catchError(() => of(null)))),
    ).subscribe((results) => {
      let failed = 0;
      for (const updated of results) {
        if (updated === null) {
          failed++;
        } else {
          this.events.upsert(updated);
        }
      }
      this.saving.set(false);
      this.close();
      if (failed > 0) {
        this.messageService.add({
          severity: 'error',
          summary: `Could not save labels for ${pluralTorrentCount(failed)}`,
          detail: 'Please try again.',
        });
      }
    });
  }

  close(): void {
    this.visibleChange.emit(false);
  }
}
