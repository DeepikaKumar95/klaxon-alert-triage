export type Severity = 'INFO' | 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
export type DecidedBy = 'LLM' | 'FALLBACK';

export interface TriagedAlert {
  alertId: string;
  service: string;
  host: string | null;
  title: string;
  rawSeverity: string | null;
  severity: Severity;
  category: string;
  summary: string;
  hypothesis: string | null;
  confidence: number | null;
  decidedBy: DecidedBy;
  model: string | null;
  latencyMs: number | null;
  similarAlertId: string | null;
  similarity: number | null;
  occurredAt: string;
  decidedAt: string;
}

export interface Stats {
  /** Partial on purpose: the API fills every level today, but the UI should not break if it stops. */
  bySeverity: Partial<Record<Severity, number>>;
  total: number;
  embeddings: number;
  llmEnabled: boolean;
  quotaUsedThisMinute: number;
  subscribers: number;
}

export const SEVERITY_ORDER: Severity[] = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'INFO'];

/**
 * True when triage disagreed with the severity the sender claimed. These are the
 * rows worth looking at, so the UI marks them.
 */
export function severityChanged(a: TriagedAlert): boolean {
  if (!a.rawSeverity) return false;
  const claimed = a.rawSeverity.trim().toUpperCase();
  const known: string[] = ['INFO', 'LOW', 'MEDIUM', 'HIGH', 'CRITICAL'];
  // Senders use their own vocabulary; map the common ones onto ours.
  const alias: Record<string, Severity> = {
    WARN: 'MEDIUM', WARNING: 'MEDIUM', ERROR: 'HIGH', PAGE: 'HIGH',
    CRIT: 'CRITICAL', FATAL: 'CRITICAL', NOTICE: 'INFO', DEBUG: 'INFO',
  };
  const normalised = known.includes(claimed) ? (claimed as Severity) : alias[claimed];
  return !!normalised && normalised !== a.severity;
}
