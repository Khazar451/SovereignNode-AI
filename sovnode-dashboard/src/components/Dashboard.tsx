import { useState, useEffect, useRef } from 'react'
import { useTelemetry } from '../hooks/useTelemetry'
import { SensorCard } from './SensorCard'
import { AiDiagnosticsPanel } from './AiDiagnosticsPanel'
import type { ConnectionStatus } from '../types'

// ─── Header ───────────────────────────────────────────────────────────────────

function LiveClock() {
  const [time, setTime] = useState(() => new Date())
  useEffect(() => {
    const t = setInterval(() => setTime(new Date()), 1000)
    return () => clearInterval(t)
  }, [])
  return (
    <span className="mono text-xs text-slate-400 tabular-nums">
      {time.toISOString().replace('T', ' ').slice(0, 19)}{' '}
      <span className="text-slate-600">UTC</span>
    </span>
  )
}

function ConnectionBadge({ status }: { status: ConnectionStatus }) {
  const config = {
    connected:  { dot: 'bg-emerald-400', text: 'text-emerald-400', label: 'Connected' },
    degraded:   { dot: 'bg-amber-400',   text: 'text-amber-400',   label: 'Degraded'  },
    error:      { dot: 'bg-red-400',     text: 'text-red-400',     label: 'Error'     },
    connecting: { dot: 'bg-cyan-400 animate-pulse', text: 'text-cyan-400', label: 'Connecting' },
  }[status]

  return (
    <div className="flex items-center gap-1.5 px-2.5 py-1 rounded-full
                    bg-slate-800 border border-slate-700">
      <span className={`w-1.5 h-1.5 rounded-full ${config.dot}`} />
      <span className={`mono text-[10px] font-medium ${config.text}`}>
        {config.label}
      </span>
    </div>
  )
}

function Header({ status, lastUpdated }: {
  status: ConnectionStatus
  lastUpdated: Date | null
}) {
  return (
    <header className="flex-shrink-0 border-b border-slate-800/80 bg-slate-950/80
                       backdrop-blur-md sticky top-0 z-20 px-6 py-3">
      <div className="max-w-[1600px] mx-auto flex items-center justify-between gap-4">

        {/* Left: Logo + brand */}
        <div className="flex items-center gap-3">
          {/* Hex logo mark */}
          <div className="w-9 h-9 rounded-xl bg-gradient-to-br from-cyan-500 via-blue-600
                          to-violet-700 flex items-center justify-center text-lg font-bold
                          shadow-lg shadow-cyan-500/20 select-none glow-cyan">
            ⬡
          </div>
          <div className="flex flex-col">
            <h1 className="text-sm font-bold tracking-tight leading-none">
              <span className="text-gradient-cyan">SovereignNode AI</span>
            </h1>
            <span className="text-[10px] text-slate-500 font-medium tracking-widest uppercase">
              Edge Monitor
            </span>
          </div>

          {/* Divider */}
          <div className="h-8 w-px bg-slate-800 mx-1 hidden sm:block" />

          {/* System label */}
          <span className="hidden md:block mono text-[11px] text-slate-500">
            Industrial IoT · Predictive Maintenance · Air-gapped
          </span>
        </div>

        {/* Right: Status + clock */}
        <div className="flex items-center gap-3">
          <LiveClock />
          <ConnectionBadge status={status} />
          {lastUpdated && (
            <span className="hidden lg:block text-[10px] text-slate-600">
              Updated {lastUpdated.toLocaleTimeString()}
            </span>
          )}
        </div>
      </div>
    </header>
  )
}

// ─── Stats Bar ────────────────────────────────────────────────────────────────

function StatsBar({
  total, critical, warning, nominal,
}: {
  total: number; critical: number; warning: number; nominal: number
}) {
  const tiles = [
    { label: 'Total',    value: total,    color: 'text-slate-300',  bg: 'bg-slate-800'      },
    { label: 'Nominal',  value: nominal,  color: 'text-emerald-400', bg: 'bg-emerald-500/10' },
    { label: 'Warning',  value: warning,  color: 'text-amber-400',   bg: 'bg-amber-500/10'   },
    { label: 'Critical', value: critical, color: 'text-red-400',     bg: 'bg-red-500/10'     },
  ]

  return (
    <div className="grid grid-cols-4 gap-3 flex-shrink-0">
      {tiles.map(({ label, value, color, bg }) => (
        <div
          key={label}
          className={`rounded-lg ${bg} border border-slate-800 px-3 py-2.5 flex flex-col gap-0.5`}
        >
          <span className="text-[10px] text-slate-500 uppercase tracking-widest font-medium">
            {label}
          </span>
          <span className={`mono text-xl font-bold tabular-nums leading-none ${color}`}>
            {value}
          </span>
        </div>
      ))}
    </div>
  )
}

// ─── Error Banner ─────────────────────────────────────────────────────────────

function ErrorBanner({ message }: { message: string }) {
  return (
    <div className="flex items-center gap-3 px-4 py-2.5 rounded-lg
                    bg-red-500/10 border border-red-500/30 flex-shrink-0">
      <span className="text-red-400 text-sm">⚠</span>
      <span className="text-xs text-red-300">{message}</span>
    </div>
  )
}

// ─── Loading skeleton ─────────────────────────────────────────────────────────

function SkeletonCard() {
  return (
    <div className="rounded-xl border border-l-4 border-slate-800 border-l-slate-700
                    p-4 bg-slate-900/40 animate-pulse">
      <div className="flex items-center justify-between mb-3">
        <div className="h-4 w-32 bg-slate-800 rounded" />
        <div className="h-5 w-20 bg-slate-800 rounded-full" />
      </div>
      <div className="grid grid-cols-2 gap-3 mb-3">
        <div className="space-y-1.5">
          <div className="h-2.5 w-16 bg-slate-800 rounded" />
          <div className="h-6 w-12 bg-slate-800 rounded" />
        </div>
        <div className="space-y-1.5">
          <div className="h-2.5 w-16 bg-slate-800 rounded" />
          <div className="h-6 w-12 bg-slate-800 rounded" />
        </div>
      </div>
      <div className="border-t border-slate-800 pt-2 flex justify-between">
        <div className="h-2.5 w-40 bg-slate-800 rounded" />
        <div className="h-2.5 w-12 bg-slate-800 rounded" />
      </div>
    </div>
  )
}

// ─── Main Dashboard Component ─────────────────────────────────────────────────

export function Dashboard() {
  const {
    readings,
    diagnostics,
    connectionStatus,
    lastUpdated,
    isLoading,
    errorMessage,
    stats,
  } = useTelemetry()

  // Track which readings are "new" to trigger the highlight animation
  const [newIds, setNewIds] = useState<Set<string>>(new Set())
  const prevIdsRef = useRef<Set<string>>(new Set())

  useEffect(() => {
    if (readings.length === 0) return
    const incoming = new Set(readings.map((r) => r.id))
    const fresh = [...incoming].filter((id) => !prevIdsRef.current.has(id))
    if (fresh.length > 0) {
      setNewIds(new Set(fresh))
      fresh.forEach((id) => prevIdsRef.current.add(id))
      // Remove highlight after 2 s
      const t = setTimeout(() => setNewIds(new Set()), 2000)
      return () => clearTimeout(t)
    }
  }, [readings, prevIdsRef])

  return (
    <div className="min-h-screen bg-[#030712] bg-grid flex flex-col">
      {/* Scanline overlay effect */}
      <div className="scanline" />

      {/* Sticky navbar */}
      <Header status={connectionStatus} lastUpdated={lastUpdated} />

      {/* Main content */}
      <main className="flex-1 px-4 sm:px-6 py-5 max-w-[1600px] mx-auto w-full
                       flex flex-col gap-4">

        {/* Stats bar */}
        <StatsBar
          total={stats.totalReadings}
          critical={stats.criticalCount}
          warning={stats.warningCount}
          nominal={stats.nominalCount}
        />

        {/* Error banner */}
        {errorMessage && <ErrorBanner message={errorMessage} />}

        {/* Two-column layout */}
        <div className="flex-1 grid grid-cols-1 xl:grid-cols-[1fr_420px] gap-5 min-h-0">

          {/* ── Left Column: Live Sensor Feed ─────────────────────────── */}
          <section className="flex flex-col gap-4 min-h-0">
            <div className="flex items-center justify-between flex-shrink-0">
              <div className="flex items-center gap-2">
                <span className="relative flex h-2 w-2">
                  <span className="animate-ping absolute inline-flex h-full w-full
                                   rounded-full bg-cyan-400 opacity-60" />
                  <span className="relative inline-flex h-2 w-2 rounded-full bg-cyan-400" />
                </span>
                <h2 className="text-sm font-semibold text-slate-200">Live Sensor Feed</h2>
              </div>
              <span className="mono text-[10px] text-slate-500">
                {stats.totalReadings} readings · newest first
              </span>
            </div>

            <div className="flex-1 overflow-y-auto">
              {isLoading ? (
                <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3">
                  {Array.from({ length: 6 }).map((_, i) => (
                    <SkeletonCard key={i} />
                  ))}
                </div>
              ) : readings.length === 0 ? (
                <div className="flex flex-col items-center justify-center py-20 gap-3">
                  <span className="text-3xl opacity-30">📡</span>
                  <p className="text-sm text-slate-500">
                    Waiting for telemetry from{' '}
                    <span className="mono text-cyan-600">localhost:18080</span> …
                  </p>
                  <p className="text-xs text-slate-600">
                    Send a POST to /api/v1/telemetry to see data here
                  </p>
                </div>
              ) : (
                <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3 pb-4">
                  {readings.map((r) => (
                    <SensorCard key={r.id} reading={r} isNew={newIds.has(r.id)} />
                  ))}
                </div>
              )}
            </div>
          </section>

          {/* ── Right Column: AI Diagnostics ──────────────────────────── */}
          <aside
            className="flex flex-col min-h-0 rounded-2xl border border-slate-800
                       bg-slate-950/60 backdrop-blur-sm p-5 xl:sticky xl:top-[61px]
                       xl:h-[calc(100vh-61px-40px)] overflow-hidden"
          >
            {/* Top glow accent */}
            <div className="absolute top-0 left-1/2 -translate-x-1/2 w-32 h-px
                            bg-gradient-to-r from-transparent via-violet-500/60 to-transparent" />
            <AiDiagnosticsPanel diagnostics={diagnostics} />
          </aside>
        </div>
      </main>

      {/* Footer */}
      <footer className="flex-shrink-0 border-t border-slate-900 px-6 py-2.5
                         flex items-center justify-between">
        <span className="mono text-[10px] text-slate-700">
          SovereignNode AI v1.0.0 · Air-gapped · Zero data egress
        </span>
        <span className="mono text-[10px] text-slate-700">
          Java :18080 · Python :8000 · MongoDB :27017 · RabbitMQ :5672
        </span>
      </footer>
    </div>
  )
}
