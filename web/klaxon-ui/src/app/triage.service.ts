import { HttpClient } from '@angular/common/http';
import { Injectable, NgZone, inject, signal } from '@angular/core';
import { Observable } from 'rxjs';
import { Stats, TriagedAlert } from './models';

@Injectable({ providedIn: 'root' })
export class TriageService {
  private http = inject(HttpClient);
  private zone = inject(NgZone);

  /** Live verdicts, newest first. Capped so a long-running tab cannot grow without bound. */
  readonly alerts = signal<TriagedAlert[]>([]);
  readonly connected = signal(false);
  readonly stats = signal<Stats | null>(null);

  private static readonly MAX_ROWS = 200;
  private source?: EventSource;

  loadRecent(): Observable<TriagedAlert[]> {
    return this.http.get<TriagedAlert[]>('/api/v1/alerts', { params: { limit: 50 } });
  }

  refreshStats(): void {
    this.http.get<Stats>('/api/v1/stats').subscribe({
      next: s => this.stats.set(s),
      error: () => {},
    });
  }

  seed(rows: TriagedAlert[]): void {
    this.alerts.set(rows.slice(0, TriageService.MAX_ROWS));
  }

  /**
   * SSE rather than WebSocket: one-way traffic, and the browser reconnects on its
   * own. EventSource callbacks fire outside Angular, so signals are set inside
   * zone.run to keep change detection honest.
   */
  connect(): void {
    if (this.source) return;

    const source = new EventSource('/api/v1/stream');
    this.source = source;

    source.addEventListener('hello', () => this.zone.run(() => this.connected.set(true)));

    source.addEventListener('triaged', (e: MessageEvent) => {
      const alert: TriagedAlert = JSON.parse(e.data);
      this.zone.run(() => {
        this.alerts.update(current => [alert, ...current].slice(0, TriageService.MAX_ROWS));
        this.refreshStats();
      });
    });

    source.onerror = () => {
      // EventSource retries on its own; reflect the gap rather than tearing it down.
      this.zone.run(() => this.connected.set(false));
    };
  }

  disconnect(): void {
    this.source?.close();
    this.source = undefined;
    this.connected.set(false);
  }
}
