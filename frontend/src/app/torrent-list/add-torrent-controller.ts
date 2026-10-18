import { DOCUMENT } from '@angular/common';
import { DestroyRef, Injectable, computed, effect, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { MessageService } from 'primeng/api';

import { TorrentEventsService } from '../services/torrent-events.service';
import { TorrentService } from '../services/torrent.service';
import { apiErrorMessage } from '../shared/api-error';
import { FormatBytesPipe } from '../shared/format-bytes.pipe';
import { generateLocalId } from '../shared/local-id';
import { magnetDisplayName, normalizeMagnetCandidate, shortInfoHash } from '../shared/magnet';
import { AddState, resolveAddState } from './add-state';

/** A brief "request in flight" placeholder row, for an upload or a magnet add. Both resolve
 * synchronously (design_docs/0070), so this only covers request latency. See
 * design_docs/0029/0070. */
export interface PendingUpload {
  id: string;
  fileName: string;
}

export interface EchoState {
  tone: 'accent' | 'alarm';
  icon: string;
  name: string;
  meta: string;
  hint: string;
}

export interface AddPrimary {
  icon: string;
  label: string;
  disabled: boolean;
}

/**
 * The add control's state and behaviour: the bonded paste field and primary button, the echo
 * strip under the toolbar, page-wide paste and drag/drop, the pending placeholder rows, and the
 * toasts for failed adds. Its markup stays in TorrentList's template, because its pieces sit in
 * three places there (the toolbar, the strip under it, the table's first rows) and moving them
 * would move their layout too. Provided by TorrentList, so it shares that component's lifetime
 * and its MessageService. See the add-control guide (design_docs/0032) and design_docs/0101.
 */
@Injectable()
export class AddTorrentController {
  private readonly torrentService = inject(TorrentService);
  private readonly messageService = inject(MessageService);
  private readonly events = inject(TorrentEventsService);
  private readonly router = inject(Router);
  private readonly document = inject(DOCUMENT);
  private readonly formatBytes = new FormatBytesPipe();

  private fileInput: () => HTMLInputElement | undefined = () => undefined;
  private field: () => HTMLInputElement | undefined = () => undefined;

  readonly value = signal('');
  readonly pending = signal(false);
  readonly error = signal<string | null>(null);
  readonly isDraggingFile = signal(false);

  private readonly pendingUploadList = signal<PendingUpload[]>([]);
  readonly pendingUploads = this.pendingUploadList.asReadonly();

  readonly state = computed<AddState>(() => resolveAddState(this.value(), this.events.torrents()));

  readonly fieldTone = computed<'idle' | 'accent' | 'alarm'>(() => {
    switch (this.state().kind) {
      case 'empty':
        return 'idle';
      case 'magnet':
      case 'multi-magnet':
        return 'accent';
      case 'invalid':
      case 'torrent-url':
        return 'alarm';
    }
  });

  readonly primary = computed<AddPrimary>(() => {
    const state = this.state();
    if (this.pending()) {
      const label =
        state.kind === 'multi-magnet' ? `Add ${state.uris.length} magnets` : state.kind === 'magnet' ? 'Add magnet' : 'Add file…';
      return { icon: 'pi pi-spinner pi-spin', label, disabled: true };
    }
    switch (state.kind) {
      case 'empty':
        return { icon: 'pi pi-file-plus', label: 'Add file…', disabled: false };
      case 'magnet':
        return state.duplicate
          ? { icon: 'pi pi-plus', label: 'Show existing', disabled: false }
          : { icon: 'pi pi-plus', label: 'Add magnet', disabled: false };
      case 'multi-magnet':
        return { icon: 'pi pi-plus', label: `Add ${state.uris.length} magnets`, disabled: false };
      case 'torrent-url':
        return { icon: 'pi pi-plus', label: 'Add link', disabled: true };
      case 'invalid':
        return { icon: 'pi pi-plus', label: 'Add magnet', disabled: true };
    }
  });

  readonly echo = computed<EchoState | null>(() => {
    const error = this.error();
    if (error) {
      return { tone: 'alarm', icon: 'pi pi-exclamation-triangle', name: error, meta: '', hint: 'Enter to retry · Esc to clear' };
    }

    const state = this.state();
    switch (state.kind) {
      case 'empty':
        return null;
      case 'invalid':
        return {
          tone: 'alarm',
          icon: 'pi pi-exclamation-triangle',
          name: "That isn't a magnet link or a .torrent file.",
          meta: '',
          hint: 'Paste starts magnet:?xt=urn:btih:…',
        };
      case 'torrent-url':
        return {
          tone: 'alarm',
          icon: 'pi pi-exclamation-triangle',
          name: "Adding by URL isn't supported yet.",
          meta: '',
          hint: 'Save the .torrent file and use Add file… instead',
        };
      case 'magnet': {
        if (state.duplicate) {
          return {
            tone: 'alarm',
            icon: 'pi pi-exclamation-triangle',
            name: `Already in the list — ${state.duplicate.name}`,
            meta: '',
            hint: 'Enter to open · Esc to clear',
          };
        }
        const name =
          state.displayName ?? (state.infoHash ? shortInfoHash(state.infoHash) : 'Unnamed magnet link');
        const metaParts: string[] = [];
        if (state.lengthBytes !== null) {
          metaParts.push(this.formatBytes.transform(state.lengthBytes));
        }
        if (state.trackerCount > 0) {
          metaParts.push(`${state.trackerCount} tracker${state.trackerCount === 1 ? '' : 's'}`);
        }
        return { tone: 'accent', icon: 'pi pi-check', name, meta: metaParts.join(' · '), hint: 'Enter to add · Esc to clear' };
      }
      case 'multi-magnet':
        return {
          tone: 'accent',
          icon: 'pi pi-check',
          name: `${state.firstName ?? 'Magnet link'} and ${state.uris.length - 1} more`,
          meta: `${state.uris.length} magnet links`,
          hint: 'Enter to add · Esc to clear',
        };
    }
  });

  /** A magnet's row is a real FETCHING_METADATA torrent (design_docs/0070), so when the
   * background fetch gives up the backend just drops it - the MAGNET_ADD_FAILED library event is
   * the only signal. Each one is toasted live (design_docs/0060). The cursor starts at the newest
   * event already buffered, so re-creating the list (navigating back to it) doesn't replay old
   * failures. Timestamps are compared as times, not strings: the server's ISO strings drop
   * trailing fractional zeros, so `…:00.55Z` sorts before `…:00.5Z` as text. */
  private lastToastedEventTime = eventTime(this.events.libraryEvents()[0]?.timestamp);
  private readonly magnetFailureToastEffect = effect(() => {
    const events = this.events.libraryEvents();
    const since = this.lastToastedEventTime;
    const fresh = events.filter((e) => e.type === 'MAGNET_ADD_FAILED' && eventTime(e.timestamp) > since);
    this.lastToastedEventTime = Math.max(since, eventTime(events[0]?.timestamp));
    for (const event of fresh.reverse()) {
      this.messageService.add({
        severity: 'error',
        summary: 'Magnet add failed',
        detail: event.message ?? event.infoHash ?? undefined,
        life: 8000,
      });
    }
  });

  constructor() {
    const pasteHandler = (event: ClipboardEvent) => this.onGlobalPaste(event);
    const dragOverHandler = (event: DragEvent) => this.onWindowDragOver(event);
    const dragLeaveHandler = (event: DragEvent) => this.onWindowDragLeave(event);
    const dropHandler = (event: DragEvent) => this.onWindowDrop(event);

    // Global rather than scoped to the add field, per the add-control guide: "Cmd/Ctrl-V anywhere
    // on the page ... This is the single most important interaction in the control." Paste and
    // drop aimed at another text input, textarea or contenteditable (the filter field, a dialog)
    // are left alone.
    this.document.addEventListener('paste', pasteHandler);
    this.document.defaultView?.addEventListener('dragover', dragOverHandler);
    this.document.defaultView?.addEventListener('dragleave', dragLeaveHandler);
    this.document.defaultView?.addEventListener('drop', dropHandler);
    inject(DestroyRef).onDestroy(() => {
      this.document.removeEventListener('paste', pasteHandler);
      this.document.defaultView?.removeEventListener('dragover', dragOverHandler);
      this.document.defaultView?.removeEventListener('dragleave', dragLeaveHandler);
      this.document.defaultView?.removeEventListener('drop', dropHandler);
    });
  }

  /** The host's hidden file input and the paste field, which Add file… and a page-wide paste
   * need to reach. */
  attachElements(fileInput: () => HTMLInputElement | undefined, field: () => HTMLInputElement | undefined): void {
    this.fileInput = fileInput;
    this.field = field;
  }

  onInput(event: Event): void {
    this.value.set((event.target as HTMLInputElement).value);
    this.error.set(null);
  }

  clear(): void {
    this.value.set('');
    this.error.set(null);
  }

  /** The primary button, Enter in the field, and the empty list's Add torrent button. */
  submit(): void {
    if (this.pending() || this.primary().disabled) {
      return;
    }
    const state = this.state();
    switch (state.kind) {
      case 'empty':
        this.fileInput()?.click();
        break;
      case 'magnet':
        if (state.duplicate) {
          this.router.navigate(['/torrents', state.duplicate.infoHash]);
        } else {
          this.submitMagnet(state.uri);
        }
        break;
      case 'multi-magnet':
        this.submitMultipleMagnets(state.uris);
        break;
      case 'torrent-url':
      case 'invalid':
        break;
    }
  }

  onFilesPicked(event: Event): void {
    const input = event.target as HTMLInputElement;
    const files = input.files ? Array.from(input.files) : [];
    files.forEach((file) => this.uploadFile(file));
    input.value = '';
  }

  private uploadFile(file: File): void {
    const pendingId = this.addPendingUpload(file.name);
    this.torrentService.upload(file).subscribe({
      next: (response) => {
        this.removePendingUpload(pendingId);
        // Applied at once rather than waiting for the next snapshot - the point of the
        // placeholder row.
        this.events.upsert(response.torrent);
        if (response.alreadyExisted) {
          this.messageService.add({
            severity: 'info',
            summary: 'Already added',
            detail: `"${response.torrent.name}" is already in your list.`,
          });
        }
      },
      error: (error: unknown) => {
        this.removePendingUpload(pendingId);
        this.messageService.add({
          severity: 'error',
          summary: 'Upload failed',
          detail: apiErrorMessage(error, 'Could not add torrent'),
        });
      },
    });
  }

  /** The response is a real, insertable torrent (FETCHING_METADATA until it resolves, or the
   * existing torrent if this info hash was already added) - design_docs/0070. No success toast:
   * per the add-control guide, "the new row appearing *is* the confirmation." An error response
   * (no usable tracker and DHT off, or a malformed URI) keeps the echo strip open in its alarm
   * state, naming the reason, instead of a toast. */
  private submitMagnet(uri: string): void {
    const pendingId = this.addPendingUpload(magnetDisplayName(uri));
    this.pending.set(true);
    this.torrentService.addMagnet(uri).subscribe({
      next: (response) => {
        this.removePendingUpload(pendingId);
        this.pending.set(false);
        this.value.set('');
        this.events.upsert(response.torrent);
      },
      error: (error: unknown) => {
        this.removePendingUpload(pendingId);
        this.pending.set(false);
        this.error.set(apiErrorMessage(error, 'Invalid magnet link'));
      },
    });
  }

  /** submitMagnet() per uri, with one summary in the echo strip if any fail. */
  private submitMultipleMagnets(uris: string[]): void {
    this.pending.set(true);
    let remaining = uris.length;
    let failed = 0;
    const settle = () => {
      remaining--;
      if (remaining === 0) {
        this.finishMultiAdd(failed);
      }
    };
    uris.forEach((uri) => {
      const pendingId = this.addPendingUpload(magnetDisplayName(uri));
      this.torrentService.addMagnet(uri).subscribe({
        next: (response) => {
          this.removePendingUpload(pendingId);
          this.events.upsert(response.torrent);
          settle();
        },
        error: () => {
          this.removePendingUpload(pendingId);
          failed++;
          settle();
        },
      });
    });
  }

  private finishMultiAdd(failed: number): void {
    this.pending.set(false);
    if (failed > 0) {
      this.error.set(`${failed} of the pasted links couldn't be added.`);
    } else {
      this.value.set('');
    }
  }

  private onGlobalPaste(event: ClipboardEvent): void {
    const target = event.target as HTMLElement | null;
    if (target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.isContentEditable)) {
      return;
    }
    const text = event.clipboardData?.getData('text/plain');
    if (!text?.trim()) {
      return;
    }
    event.preventDefault();
    this.value.set(text);
    this.error.set(null);
    this.field()?.focus();
  }

  private onWindowDragOver(event: DragEvent): void {
    if (!event.dataTransfer?.types.includes('Files')) {
      return;
    }
    event.preventDefault();
    this.isDraggingFile.set(true);
  }

  private onWindowDragLeave(event: DragEvent): void {
    if (event.relatedTarget === null) {
      this.isDraggingFile.set(false);
    }
  }

  private onWindowDrop(event: DragEvent): void {
    if (event.dataTransfer?.types.includes('Files')) {
      event.preventDefault();
    }
    this.isDraggingFile.set(false);

    const files = event.dataTransfer?.files;
    if (files && files.length > 0) {
      Array.from(files)
        .filter((file) => file.name.toLowerCase().endsWith('.torrent'))
        .forEach((file) => this.uploadFile(file));
      return;
    }

    // Dropped text that looks like a magnet fills the field, so the echo strip can confirm it
    // before anything is added.
    const text = event.dataTransfer?.getData('text/plain');
    if (text && normalizeMagnetCandidate(text.trim())) {
      event.preventDefault();
      this.value.set(text.trim());
      this.error.set(null);
    }
  }

  private addPendingUpload(fileName: string): string {
    const id = generateLocalId();
    this.pendingUploadList.update((uploads) => [...uploads, { id, fileName }]);
    return id;
  }

  private removePendingUpload(id: string): void {
    this.pendingUploadList.update((uploads) => uploads.filter((u) => u.id !== id));
  }
}

/** An event timestamp as epoch millis; 0 (older than any real event) when absent or unparseable. */
function eventTime(timestamp: string | undefined): number {
  const millis = timestamp === undefined ? NaN : Date.parse(timestamp);
  return Number.isNaN(millis) ? 0 : millis;
}
