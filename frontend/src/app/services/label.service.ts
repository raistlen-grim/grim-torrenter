import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';

import { Label } from '../models/torrent.model';

/**
 * The managed label list (design_docs/0077) - a torrent only carries label ids, so anything
 * that shows a label's name resolves it through `nameOf()`/`namesById` here. Loaded once over
 * REST at startup (TorrentEventsService.connect()), then kept current by the backend's
 * periodic "labels" WebSocket message (`applyServerList`), which is also how a create/rename/
 * delete made in another browser shows up. Every mutation here also applies its own result
 * locally straight away rather than waiting for the next tick.
 */
@Injectable({ providedIn: 'root' })
export class LabelService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/labels';

  private readonly state = signal<Label[]>([]);
  readonly labels = this.state.asReadonly();
  readonly namesById = computed(() => new Map(this.state().map((label) => [label.id, label.name])));

  load(): void {
    this.http.get<Label[]>(this.baseUrl).subscribe((labels) => this.applyServerList(labels));
  }

  /** The WebSocket re-sends the whole list every tick - only replace the signal when it
   * actually changed, so nothing downstream recomputes every 2 seconds for nothing. */
  applyServerList(labels: Label[]): void {
    const current = this.state();
    const same =
      current.length === labels.length &&
      current.every((label, i) => label.id === labels[i].id && label.name === labels[i].name);
    if (!same) {
      this.state.set(labels);
    }
  }

  nameOf(id: string): string | undefined {
    return this.namesById().get(id);
  }

  create(name: string): Observable<Label> {
    return this.http
      .post<Label>(this.baseUrl, { name })
      .pipe(tap((created) => this.state.update((labels) => [...labels, created])));
  }

  rename(id: string, name: string): Observable<Label> {
    return this.http
      .put<Label>(`${this.baseUrl}/${id}`, { name })
      .pipe(tap((renamed) => this.state.update((labels) => labels.map((label) => (label.id === id ? renamed : label)))));
  }

  delete(id: string): Observable<void> {
    return this.http
      .delete<void>(`${this.baseUrl}/${id}`)
      .pipe(tap(() => this.state.update((labels) => labels.filter((label) => label.id !== id))));
  }
}
