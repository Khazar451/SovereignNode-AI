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
  return (
    `Sensor '${reading.sensorId}' reported vibration of ${reading.vibration.toFixed(2)} mm/s RMS, ` +
    `significantly above the alarm threshold of ${VIBRATION_ALERT_THRESHOLD} mm/s. ` +
    `Temperature at the time was ${reading.temperature.toFixed(1)} degrees Celsius and ` +
    `operational status was '${reading.status}'. ` +
    `Based on the maintenance manual, what are the most likely root causes ` +
    `and what immediate corrective actions should be taken?`
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
 *        (non-blocking: the diagnostic arrives on the next tick)
 *
 * The hook is designed for resilience:
 *   - Network errors degrade gracefully (status → 'degraded', data retained)
 *   - Cleanup: interval is cleared on unmount; in-flight AI calls are ignored
 *     after the component unmounts (via abortController)
 */
export function useTelemetry(): TelemetryState {
  const [readings, setReadings]             = useState<SensorReading[]>([])
  const [diagnostics, setDiagnostics]       = useState<AiDiagnostic[]>([])
  const [connectionStatus, setStatus]       = useState<ConnectionStatus>('connecting')
  const [lastUpdated, setLastUpdated]       = useState<Date | null>(null)
  const [isLoading, setIsLoading]           = useState(true)
  const [errorMessage, setErrorMessage]     = useState<string | null>(null)

  // Track which sensor IDs we've already sent for AI analysis (session-scoped)
  const analysedSensorIds = useRef<Set<string>>(new Set())
  // AbortController for in-flight requests
  const abortRef = useRef<AbortController | null>(null)

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
  const requestAiDiagnostic = useCallback(
    async (reading: SensorReading, signal: AbortSignal): Promise<void> => {
      // Deduplicate: only call once per sensor ID per session
      const key = `${reading.sensorId}::${reading.id}`
      if (analysedSensorIds.current.has(key)) return
      analysedSensorIds.current.add(key)

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

      try {
        const res = await fetch(`${PYTHON_BASE}/generate-insight`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(body),
          signal,
        })

        if (!res.ok) {
          console.warn(`AI inference returned HTTP ${res.status} for ${reading.sensorId}`)
          return
        }

        const insight = await res.json()
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
      }
    },
    []
  )

  // ── poll ─────────────────────────────────────────────────────────────────────
  const poll = useCallback(async () => {
    abortRef.current?.abort()
    const controller = new AbortController()
    abortRef.current = controller
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
      const anomalies = [...criticals, ...warnings].filter(
        (r) => r.vibration > VIBRATION_ALERT_THRESHOLD
      )
      anomalies.forEach((r) => {
        // Non-blocking: don't await — let diagnostics arrive asynchronously
        void requestAiDiagnostic(r, signal)
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
    void poll()                                     // immediate first fetch
    const timer = setInterval(poll, POLL_INTERVAL_MS)
    return () => {
      clearInterval(timer)
      abortRef.current?.abort()
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
  }
}
