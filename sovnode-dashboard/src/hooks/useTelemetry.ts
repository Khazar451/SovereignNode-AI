import { useState, useEffect, useRef, useCallback } from 'react'
import type {
  TelemetryState,
  SensorReading,
  AiDiagnostic,
  AiInsightRequest,
  ConnectionStatus,
  SpringPage,
} from '../types'

// ─── Constants ────────────────────────────────────────────────────────────────

const JAVA_BASE        = '/api/v1/telemetry'
const PYTHON_BASE      = '/inference'
const POLL_INTERVAL_MS = 2_000
const MAX_READINGS     = 50   // cap live feed to prevent memory growth
const MAX_DIAGNOSTICS  = 10   // keep last 10 AI insights

// Vibration threshold that triggers an AI diagnostic call (mm/s RMS)
// Must match the Java service configuration: ai.anomaly.vibration-threshold-mms
const VIBRATION_ALERT_THRESHOLD = 7.1

// ─── Helpers ──────────────────────────────────────────────────────────────────

function mergeReadings(prev: SensorReading[], next: SensorReading[]): SensorReading[] {
  const existingIds = new Set(prev.map((r) => r.id))
  const fresh = next.filter((r) => !existingIds.has(r.id))
  // Newest first, capped at MAX_READINGS
  return [...fresh, ...prev].slice(0, MAX_READINGS)
}

function buildDiagnosticQuery(reading: SensorReading): string {
  const status = reading.status.toLowerCase()
  const anomaly = reading.vibration > VIBRATION_ALERT_THRESHOLD ? 'high vibration' : 'high temperature'

  return (
    `${anomaly} alarm detected: vibration ${reading.vibration.toFixed(2)} mm/s RMS ` +
    `exceeds the ${VIBRATION_ALERT_THRESHOLD} mm/s alarm threshold. ` +
    `Temperature is ${reading.temperature.toFixed(1)} degrees Celsius. ` +
    `Equipment status is ${status}. ` +
    `Based on the maintenance manual, what are the most likely root causes ` +
    `and what immediate corrective actions should be taken for bearing inspection ` +
    `and vibration reduction?`
  )
}

// ─── Custom Hook ──────────────────────────────────────────────────────────────

/**
 * useTelemetry — Polling hook for live IoT sensor data and AI diagnostics.
 *
 * Polling strategy:
 *   Every POLL_INTERVAL_MS (2 s):
 *     1. Fetch CRITICAL readings from Java backend → detect new anomalies
 *     2. Fetch WARNING readings
 *     3. Fetch NOMINAL readings
 *     4. For each NEW anomalous reading, call Python FastAPI for an AI insight
 *        (non-blocking: the diagnostic arrives asynchronously)
 *
 * IMPORTANT: AI diagnostic requests use a SEPARATE AbortController from the
 * polling cycle. The LLM inference takes 40-80+ seconds on CPU, while the poll
 * cycle runs every 2 seconds. If they shared the same AbortController, every
 * poll would abort in-flight AI requests before they could complete.
 *
 * The hook is designed for resilience:
 *   - Network errors degrade gracefully (status → 'degraded', data retained)
 *   - Cleanup: interval is cleared on unmount; in-flight AI calls are cancelled
 *     via a dedicated unmount-only AbortController
 */
export function useTelemetry(): TelemetryState {
  const [readings, setReadings]             = useState<SensorReading[]>([])
  const [diagnostics, setDiagnostics]       = useState<AiDiagnostic[]>([])
  const [connectionStatus, setStatus]       = useState<ConnectionStatus>('connecting')
  const [lastUpdated, setLastUpdated]       = useState<Date | null>(null)
  const [isLoading, setIsLoading]           = useState(true)
  const [errorMessage, setErrorMessage]     = useState<string | null>(null)
  const [pendingDiagnostics, setPending]    = useState(0)

  // Track which sensor IDs we've already sent for AI analysis (session-scoped)
  const analysedSensorIds = useRef<Set<string>>(new Set())

  // AbortController for POLLING requests only (recycled each poll cycle)
  const pollAbortRef = useRef<AbortController | null>(null)

  // AbortController for AI DIAGNOSTIC requests (only aborted on unmount)
  // This is critical: LLM inference takes 40-80s on CPU, so AI requests must
  // NOT be cancelled by the 2-second poll cycle.
  const aiAbortRef = useRef<AbortController>(new AbortController())

  // Track whether the component is still mounted
  const mountedRef = useRef(true)

  // ── fetchStatus ─────────────────────────────────────────────────────────────
  const fetchByStatus = useCallback(
    async (status: string, signal: AbortSignal): Promise<SensorReading[]> => {
      const res = await fetch(
        `${JAVA_BASE}/status/${status}?page=0&size=20&sort=timestamp,desc`,
        { signal }
      )
      if (!res.ok) throw new Error(`Java API ${status}: HTTP ${res.status}`)
      const page: SpringPage<SensorReading> = await res.json()
      return page.content
    },
    []
  )

  // ── requestAiDiagnostic ─────────────────────────────────────────────────────
  // Uses the AI-specific AbortController (aiAbortRef), NOT the poll controller.
  const requestAiDiagnostic = useCallback(
    async (reading: SensorReading): Promise<void> => {
      // Deduplicate: only call once per sensor reading per session
      const key = `${reading.sensorId}::${reading.id}`
      if (analysedSensorIds.current.has(key)) {
        return
      }
      analysedSensorIds.current.add(key)

      console.log(
        `[AI Diagnostic] Requesting insight for ${reading.sensorId} ` +
        `(vibration=${reading.vibration.toFixed(2)} mm/s, threshold=${VIBRATION_ALERT_THRESHOLD})`
      )

      const body: AiInsightRequest = {
        sensor_id:    reading.sensorId,
        anomaly_type: reading.vibration > VIBRATION_ALERT_THRESHOLD ? 'HIGH_VIBRATION' : 'HIGH_TEMP',
        raw_telemetry: {
          temperature: reading.temperature,
          vibration:   reading.vibration,
          timestamp:   reading.timestamp,
        },
        query: buildDiagnosticQuery(reading),
      }

      // Track pending count for UI feedback
      setPending((n) => n + 1)

      try {
        const res = await fetch(`${PYTHON_BASE}/generate-insight`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(body),
          signal: aiAbortRef.current.signal,   // ← AI-specific controller
        })

        if (!res.ok) {
          console.warn(`AI inference returned HTTP ${res.status} for ${reading.sensorId}`)
          return
        }

        const insight = await res.json()

        console.log(`[AI Diagnostic] ✅ Insight received for ${reading.sensorId}`, insight)

        // Only update state if still mounted
        if (!mountedRef.current) return

        const diagnostic: AiDiagnostic = {
          ...insight,
          id:               `${reading.id}-${Date.now()}`,
          sensorId:         reading.sensorId,
          anomalyType:      body.anomaly_type,
          vibrationReading: reading.vibration,
          triggeredAt:      new Date().toISOString(),
        }

        setDiagnostics((prev) => [diagnostic, ...prev].slice(0, MAX_DIAGNOSTICS))
      } catch (err) {
        if ((err as Error).name !== 'AbortError') {
          console.warn(`AI insight failed for ${reading.sensorId}:`, err)
        }
      } finally {
        if (mountedRef.current) {
          setPending((n) => Math.max(0, n - 1))
        }
      }
    },
    []
  )

  // ── poll ─────────────────────────────────────────────────────────────────────
  const poll = useCallback(async () => {
    // Abort ONLY the previous poll fetch, NOT any AI requests
    pollAbortRef.current?.abort()
    const controller = new AbortController()
    pollAbortRef.current = controller
    const { signal } = controller

    try {
      // Fetch all three status tiers in parallel
      const [criticals, warnings, nominals] = await Promise.all([
        fetchByStatus('CRITICAL', signal),
        fetchByStatus('WARNING', signal),
        fetchByStatus('NOMINAL', signal),
      ])

      const newBatch = [...criticals, ...warnings, ...nominals]

      setReadings((prev) => mergeReadings(prev, newBatch))
      setStatus('connected')
      setErrorMessage(null)
      setLastUpdated(new Date())
      setIsLoading(false)

      // Trigger async AI diagnostics for anomalous readings
      // These use their own AbortController and won't be cancelled by the next poll
      const anomalies = [...criticals, ...warnings].filter(
        (r) => r.vibration > VIBRATION_ALERT_THRESHOLD
      )
      anomalies.forEach((r) => {
        // Non-blocking: don't await — let diagnostics arrive asynchronously
        void requestAiDiagnostic(r)
      })
    } catch (err) {
      if ((err as Error).name === 'AbortError') return
      const msg = err instanceof Error ? err.message : 'Unknown error'
      console.error('Poll error:', msg)
      setStatus((prev) => prev === 'connecting' ? 'error' : 'degraded')
      setErrorMessage(msg)
      setIsLoading(false)
    }
  }, [fetchByStatus, requestAiDiagnostic])

  // ── Effect: Start polling ─────────────────────────────────────────────────
  useEffect(() => {
    mountedRef.current = true
    // Create a fresh AbortController for AI requests on each mount.
    // Without this, after HMR or React strict-mode remounts, the previous
    // controller would already be aborted, causing all AI fetches to fail immediately.
    aiAbortRef.current = new AbortController()

    console.log('[useTelemetry] Mounted — starting poll cycle')
    void poll()                                     // immediate first fetch
    const timer = setInterval(poll, POLL_INTERVAL_MS)
    return () => {
      console.log('[useTelemetry] Unmounting — aborting AI requests')
      mountedRef.current = false
      clearInterval(timer)
      pollAbortRef.current?.abort()    // cancel pending poll fetches
      aiAbortRef.current.abort()       // cancel in-flight AI requests on unmount
    }
  }, [poll])

  // ── Derived stats ─────────────────────────────────────────────────────────
  const stats = {
    totalReadings: readings.length,
    criticalCount: readings.filter((r) => r.status === 'CRITICAL').length,
    warningCount:  readings.filter((r) => r.status === 'WARNING').length,
    nominalCount:  readings.filter((r) => r.status === 'NOMINAL').length,
  }

  return {
    readings,
    diagnostics,
    connectionStatus,
    lastUpdated,
    isLoading,
    errorMessage,
    stats,
    pendingDiagnostics,
  }
}
