import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import {
  AddTorrentResponse,
  Peer,
  PiecesResponse,
  SeedingLimitOverride,
  Torrent,
  TorrentFile,
  Tracker,
} from '../models/torrent.model';

@Injectable({ providedIn: 'root' })
export class TorrentService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/torrents';

  list(): Observable<Torrent[]> {
    return this.http.get<Torrent[]>(this.baseUrl);
  }

  /** Self-contained detail endpoints - not part of the list snapshot, fetched only while
   * a torrent's detail view is open. See design_docs/0031. */
  pieces(infoHash: string): Observable<PiecesResponse> {
    return this.http.get<PiecesResponse>(`${this.baseUrl}/${infoHash}/pieces`);
  }

  files(infoHash: string): Observable<TorrentFile[]> {
    return this.http.get<TorrentFile[]>(`${this.baseUrl}/${infoHash}/files`);
  }

  peers(infoHash: string): Observable<Peer[]> {
    return this.http.get<Peer[]>(`${this.baseUrl}/${infoHash}/peers`);
  }

  trackers(infoHash: string): Observable<Tracker[]> {
    return this.http.get<Tracker[]>(`${this.baseUrl}/${infoHash}/trackers`);
  }

  upload(file: File): Observable<AddTorrentResponse> {
    const formData = new FormData();
    formData.append('file', file, file.name);
    return this.http.post<AddTorrentResponse>(this.baseUrl, formData);
  }

  /** No Torrent in the response - metadata fetch happens asynchronously server-side
   * (see design_docs/0028), so there's nothing to return yet. */
  addMagnet(magnetUri: string): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/magnet`, magnetUri, {
      headers: { 'Content-Type': 'text/plain' },
    });
  }

  remove(infoHash: string, deleteData = false): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${infoHash}`, { params: { deleteData } });
  }

  pause(infoHash: string): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/${infoHash}/pause`, null);
  }

  resume(infoHash: string): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/${infoHash}/resume`, null);
  }

  /** See design_docs/0054 - a torrent's override of the global seeding-limit defaults. */
  seedingLimits(infoHash: string): Observable<SeedingLimitOverride> {
    return this.http.get<SeedingLimitOverride>(`${this.baseUrl}/${infoHash}/seeding-limits`);
  }

  updateSeedingLimits(infoHash: string, override: SeedingLimitOverride): Observable<SeedingLimitOverride> {
    return this.http.put<SeedingLimitOverride>(`${this.baseUrl}/${infoHash}/seeding-limits`, override);
  }
}
