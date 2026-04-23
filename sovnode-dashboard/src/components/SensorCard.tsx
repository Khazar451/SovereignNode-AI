import type { SensorReading, SensorStatus } from '../types'

// ─── Status config map ────────────────────────────────────────────────────────

interface StatusConfig {
  dot:        string   // pulsing indicator color
  badge:      string   // pill background + text
  border:     string   // card left-border accent
  glow:       string   // card box shadow on hover
  icon:       string   // emoji / symbol
  label:      string   // display label
}

const STATUS_CONFIG: Record<string, StatusConfig> = {
  NOMINAL: {
    dot:    'bg-emerald-400',
    badge:  'bg-emerald-500/15 text-emerald-400 ring-1 ring-emerald-500/30',
    border: 'border-l-emerald-500/60',
    glow:   'hover:shadow-emerald-500/10',
    icon:   '●',
    label:  'NOMINAL',
  },
  WARNING: {
    dot:    'bg-amber-400',
    badge:  'bg-amber-500/15 text-amber-400 ring-1 ring-amber-500/30',
    border: 'border-l-amber-500/60',
    glow:   'hover:shadow-amber-500/10',
    icon:   '▲',
    label:  'WARNING',
  },
  CRITICAL: {
    dot:    'bg-red-400',
    badge:  'bg-red-500/15 text-red-400 ring-1 ring-red-500/30',
    border: 'border-l-red-500/60',
    glow:   'hover:shadow-red-500/10',
    icon:   '✦',
    label:  'CRITICAL',
  },
}

function getStatusConfig(status: SensorStatus): StatusConfig {
  return STATUS_CONFIG[status] ?? {
    dot:    'bg-slate-400',
    badge:  'bg-slate-500/15 text-slate-400 ring-1 ring-slate-500/30',
    border: 'border-l-slate-500/60',
    glow:   'hover:shadow-slate-500/10',
    icon:   '◌',
    label:  status,
  }
}

// ─── Formatting helpers ───────────────────────────────────────────────────────

function formatRelativeTime(isoString: string): string {
  try {
    const diff = Date.now() - new Date(isoString).getTime()
    const s = Math.floor(diff / 1000)
    if (s < 5)  return 'just now'
    if (s < 60) return `${s}s ago`
    const m = Math.floor(s / 60)
    if (m < 60) return `${m}m ago`
    return `${Math.floor(m / 60)}h ago`
  } catch {
    return '—'
  }
}

// ─── Metric cell ─────────────────────────────────────────────────────────────

function MetricCell({
  label,
  value,
  unit,
  highlight,
}: {
  label: string
  value: string
  unit: string
  highlight?: boolean
}) {
  return (
    <div className="flex flex-col gap-0.5">
      <span className="text-[10px] font-medium text-slate-500 uppercase tracking-widest">
        {label}
      </span>
      <div className="flex items-baseline gap-1">
        <span
          className={`mono text-lg font-semibold leading-none ${
            highlight ? 'text-red-400' : 'text-slate-100'
          }`}
        >
          {value}
        </span>
        <span className="text-[10px] text-slate-500">{unit}</span>
      </div>
    </div>
  )
}

// ─── SensorCard ───────────────────────────────────────────────────────────────

interface SensorCardProps {
  reading: SensorReading
  isNew?: boolean
}

export function SensorCard({ reading, isNew }: SensorCardProps) {
  const cfg = getStatusConfig(reading.status)
  const isCritical = reading.status === 'CRITICAL'

  return (
    <div
      className={[
        'relative rounded-xl border border-l-4 p-4 transition-all duration-300',
        'bg-slate-900/80 backdrop-blur-sm border-slate-800',
        cfg.border,
        'hover:shadow-lg hover:-translate-y-0.5',
        cfg.glow,
        isCritical ? 'animate-pulse-slow' : '',
        isNew ? 'sensor-card-new' : '',
      ]
        .filter(Boolean)
        .join(' ')}
    >
      {/* Header row */}
      <div className="flex items-center justify-between mb-3">
        <div className="flex items-center gap-2 min-w-0">
          {/* Pulsing live dot */}
          <span className="relative flex h-2.5 w-2.5 flex-shrink-0">
            <span
              className={`absolute inline-flex h-full w-full animate-ping rounded-full opacity-60 ${cfg.dot}`}
            />
            <span className={`relative inline-flex h-2.5 w-2.5 rounded-full ${cfg.dot}`} />
          </span>

          {/* Sensor ID */}
          <span
            className="mono text-sm font-semibold text-slate-200 truncate"
            title={reading.sensorId}
          >
            {reading.sensorId}
          </span>
        </div>

        {/* Status pill */}
        <span className={`status-pill ${cfg.badge} flex-shrink-0`}>
          {cfg.icon} {cfg.label}
        </span>
      </div>

      {/* Metrics grid */}
      <div className="grid grid-cols-2 gap-3 mb-3">
        <MetricCell
          label="Temperature"
          value={reading.temperature.toFixed(1)}
          unit="°C"
          highlight={reading.temperature > 100}
        />
        <MetricCell
          label="Vibration"
          value={reading.vibration.toFixed(2)}
          unit="mm/s"
          highlight={reading.vibration > 7.1}
        />
      </div>

      {/* Footer */}
      <div className="flex items-center justify-between border-t border-slate-800 pt-2 mt-1">
        <span className="mono text-[10px] text-slate-600 truncate">
          {new Date(reading.timestamp).toISOString().replace('T', ' ').slice(0, 19)} UTC
        </span>
        <span className="text-[10px] text-slate-500">
          {formatRelativeTime(reading.ingestedAt ?? reading.timestamp)}
        </span>
      </div>
    </div>
  )
}
