import type { AiDiagnostic } from '../types'

// ─── Confidence Bar ───────────────────────────────────────────────────────────

function ConfidenceBar({ score }: { score: number }) {
  const pct = Math.round(score * 100)
  const color =
    pct >= 80 ? 'from-emerald-500 to-cyan-500' :
    pct >= 50 ? 'from-amber-500 to-yellow-400' :
                'from-red-600 to-orange-500'

  return (
    <div className="flex items-center gap-3">
      <div className="flex-1 h-1.5 bg-slate-800 rounded-full overflow-hidden">
        <div
          className={`h-full bg-gradient-to-r ${color} rounded-full transition-all duration-700 ease-out`}
          style={{ width: `${pct}%` }}
        />
      </div>
      <span className={`mono text-xs font-semibold tabular-nums ${
        pct >= 80 ? 'text-emerald-400' : pct >= 50 ? 'text-amber-400' : 'text-red-400'
      }`}>
        {pct}%
      </span>
    </div>
  )
}

// ─── Single Diagnostic Card ───────────────────────────────────────────────────

function DiagnosticCard({ diagnostic }: { diagnostic: AiDiagnostic }) {
  const triggeredAt = (() => {
    try {
      return new Date(diagnostic.triggeredAt).toLocaleTimeString()
    } catch {
      return '—'
    }
  })()

  return (
    <div className="border border-slate-800 rounded-xl p-4 bg-slate-900/60 animate-fade-in
                    hover:border-slate-700 transition-all duration-300 space-y-3">

      {/* Card header */}
      <div className="flex items-start justify-between gap-2">
        <div className="flex flex-col gap-0.5 min-w-0">
          <div className="flex items-center gap-2">
            <span className="text-red-400 text-sm">✦</span>
            <span className="mono text-sm font-semibold text-slate-200 truncate">
              {diagnostic.sensorId}
            </span>
          </div>
          <span className="mono text-[10px] text-red-400/80 uppercase tracking-wider">
            {diagnostic.anomalyType} — {diagnostic.vibrationReading.toFixed(2)} mm/s
          </span>
        </div>
        <div className="flex flex-col items-end gap-0.5 flex-shrink-0">
          <span className="mono text-[10px] text-slate-500">{triggeredAt}</span>
          <span className="mono text-[10px] text-slate-600">
            {Math.round(diagnostic.inference_time_ms)} ms
          </span>
        </div>
      </div>

      {/* Confidence bar */}
      <div>
        <div className="flex items-center justify-between mb-1.5">
          <span className="text-[10px] font-medium text-slate-500 uppercase tracking-widest">
            RAG Confidence
          </span>
        </div>
        <ConfidenceBar score={diagnostic.confidence_score} />
      </div>

      {/* Insight text */}
      <div className="relative">
        <div
          className="text-sm text-slate-300 leading-relaxed max-h-40 overflow-y-auto pr-1
                     border-l-2 border-cyan-500/40 pl-3"
        >
          {diagnostic.insight}
        </div>
        {/* Fade-out gradient at the bottom */}
        <div className="absolute bottom-0 left-0 right-0 h-6 bg-gradient-to-t
                        from-slate-900/80 to-transparent pointer-events-none rounded-b" />
      </div>

      {/* Sources referenced */}
      {diagnostic.sources_referenced.length > 0 && (
        <div>
          <span className="text-[10px] font-medium text-slate-500 uppercase tracking-widest block mb-1">
            Sources Referenced
          </span>
          <div className="flex flex-wrap gap-1.5">
            {diagnostic.sources_referenced.map((src) => (
              <span
                key={src}
                className="mono text-[10px] px-2 py-0.5 rounded bg-cyan-500/10
                           text-cyan-400 border border-cyan-500/20"
              >
                📄 {src}
              </span>
            ))}
          </div>
        </div>
      )}
    </div>
  )
}

// ─── AiDiagnosticsPanel ───────────────────────────────────────────────────────

interface AiDiagnosticsPanelProps {
  diagnostics: AiDiagnostic[]
}

export function AiDiagnosticsPanel({ diagnostics }: AiDiagnosticsPanelProps) {
  return (
    <div className="flex flex-col h-full">
      {/* Panel header */}
      <div className="flex items-center justify-between mb-4 flex-shrink-0">
        <div className="flex items-center gap-3">
          <div className="relative">
            <div className="w-8 h-8 rounded-lg bg-gradient-to-br from-violet-600 to-cyan-600
                            flex items-center justify-center text-sm shadow-lg shadow-cyan-500/20">
              ⬢
            </div>
          </div>
          <div>
            <h2 className="text-sm font-semibold text-slate-100">AI Diagnostics</h2>
            <p className="text-[10px] text-slate-500">
              Local SLM · RAG-grounded analysis
            </p>
          </div>
        </div>

        <div className="flex items-center gap-2">
          {diagnostics.length > 0 && (
            <span className="mono text-[10px] px-2 py-0.5 rounded-full
                             bg-violet-500/15 text-violet-400 border border-violet-500/25">
              {diagnostics.length} insight{diagnostics.length !== 1 ? 's' : ''}
            </span>
          )}
        </div>
      </div>

      {/* Content */}
      <div className="flex-1 overflow-y-auto space-y-3 pr-0.5">
        {diagnostics.length === 0 ? (
          <EmptyDiagnosticsState />
        ) : (
          diagnostics.map((d) => <DiagnosticCard key={d.id} diagnostic={d} />)
        )}
      </div>
    </div>
  )
}

// ─── Empty state ──────────────────────────────────────────────────────────────

function EmptyDiagnosticsState() {
  return (
    <div className="flex flex-col items-center justify-center py-16 gap-4 text-center">
      {/* Animated hexagon */}
      <div className="relative">
        <div className="w-16 h-16 rounded-2xl bg-gradient-to-br from-slate-800 to-slate-900
                        border border-slate-700 flex items-center justify-center text-3xl
                        shadow-inner">
          ⬡
        </div>
        <div className="absolute -bottom-1 -right-1 w-5 h-5 rounded-full bg-emerald-500/20
                        border border-emerald-500/40 flex items-center justify-center">
          <span className="text-[8px] text-emerald-400">✓</span>
        </div>
      </div>

      <div className="space-y-1.5">
        <p className="text-sm font-medium text-slate-400">All Systems Nominal</p>
        <p className="text-xs text-slate-600 max-w-[220px] leading-relaxed">
          AI diagnostic insights appear here automatically when sensor anomalies
          exceed the vibration threshold (7.1 mm/s RMS).
        </p>
      </div>

      {/* Monitoring indicator */}
      <div className="flex items-center gap-2 px-3 py-1.5 rounded-full
                      bg-slate-800 border border-slate-700">
        <span className="relative flex h-2 w-2">
          <span className="animate-ping absolute inline-flex h-full w-full rounded-full
                           bg-emerald-400 opacity-60" />
          <span className="relative inline-flex h-2 w-2 rounded-full bg-emerald-400" />
        </span>
        <span className="text-[10px] text-slate-500 mono">
          Monitoring active · polling every 2s
        </span>
      </div>
    </div>
  )
}
