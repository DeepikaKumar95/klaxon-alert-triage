import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { SEVERITY_ORDER, Severity, TriagedAlert, severityChanged } from './models';
import { TriageService } from './triage.service';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [DatePipe, DecimalPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <header class="mast">
      <div class="brand">
        <span class="mark" aria-hidden="true"></span>
        <div>
          <h1>Klaxon</h1>
          <p>Streaming alert triage</p>
        </div>
      </div>

      <div class="status" role="status">
        <span class="dot" [class.live]="feed.connected()"></span>
        {{ feed.connected() ? 'Live' : 'Reconnecting' }}
        @if (stats(); as s) {
          <span class="sep">·</span>
          <span [class.degraded]="!s.llmEnabled" [title]="s.llmEnabled ? 'Gemini is answering' : 'No API key — rule-based triage'">
            {{ s.llmEnabled ? 'Gemini' : 'Fallback only' }}
          </span>
          @if (s.llmEnabled) {
            <span class="sep">·</span><span class="quota">{{ s.quotaUsedThisMinute }}/min</span>
          }
        }
      </div>
    </header>

    @if (stats(); as s) {
      <section class="tiles" aria-label="Triage counts by severity">
        @for (sev of order; track sev) {
          <button class="tile" [attr.data-sev]="sev"
                  [class.active]="filter() === sev"
                  (click)="toggleFilter(sev)">
            <span class="tile-n">{{ s.bySeverity[sev] ?? 0 }}</span>
            <span class="tile-l">{{ sev }}</span>
          </button>
        }
        <div class="tile meta">
          <span class="tile-n">{{ s.embeddings }}</span>
          <span class="tile-l">EMBEDDED</span>
        </div>
      </section>
    }

    <main>
      @if (visible().length === 0) {
        <p class="empty">
          No alerts yet. Start the service with <code>KLAXON_GENERATOR_ENABLED=true</code>,
          or POST one to <code>/api/v1/alerts</code>.
        </p>
      }

      @for (a of visible(); track a.alertId) {
        <article class="row" [attr.data-sev]="a.severity">
          <div class="sev">
            <span class="pill">{{ a.severity }}</span>
            @if (changed(a)) {
              <span class="was" title="Triage disagreed with the reported severity">
                was {{ a.rawSeverity }}
              </span>
            }
          </div>

          <div class="body">
            <div class="line1">
              <span class="service">{{ a.service }}</span>
              @if (a.host) { <span class="host">{{ a.host }}</span> }
              <span class="cat">{{ a.category }}</span>
            </div>

            <h2 class="title">{{ a.title }}</h2>
            <p class="summary">{{ a.summary }}</p>

            @if (a.hypothesis) {
              <p class="hyp"><span>Likely cause</span>{{ a.hypothesis }}</p>
            }

            @if (a.similarAlertId) {
              <p class="similar">
                Resembles an earlier alert
                <code>{{ a.similarAlertId.slice(0, 8) }}</code>
                <span class="score">{{ a.similarity | number:'1.0-2' }} similarity</span>
              </p>
            }
          </div>

          <div class="meta-col">
            <span class="by" [class.fallback]="a.decidedBy === 'FALLBACK'">{{ a.decidedBy }}</span>
            @if (a.confidence !== null) {
              <span class="conf">conf {{ a.confidence | number:'1.0-2' }}</span>
            }
            @if (a.latencyMs) { <span class="lat">{{ a.latencyMs }}ms</span> }
            <time [attr.datetime]="a.decidedAt">{{ a.decidedAt | date:'HH:mm:ss' }}</time>
          </div>
        </article>
      }
    </main>
  `,
})
export class AppComponent implements OnInit {
  readonly feed = inject(TriageService);
  readonly order = SEVERITY_ORDER;

  readonly stats = this.feed.stats;
  readonly filter = signal<Severity | null>(null);

  readonly visible = computed(() => {
    const f = this.filter();
    const rows = this.feed.alerts();
    return f ? rows.filter(a => a.severity === f) : rows;
  });

  changed = severityChanged;

  ngOnInit(): void {
    this.feed.loadRecent().subscribe({
      next: rows => this.feed.seed(rows),
      error: () => this.feed.seed([]),
    });
    this.feed.refreshStats();
    this.feed.connect();
  }

  toggleFilter(sev: Severity): void {
    this.filter.update(current => (current === sev ? null : sev));
  }
}
