// ─── SovereignNode AI — Shared TypeScript types ───────────────────────────

/**
 * Spring Data Page wrapper returned by all paginated Java endpoints.
 * Shape matches @EnableSpringDataWebSupport(pageSerializationMode = VIA_DTO)
 * where pagination metadata is nested under a 'page' sub-object.
 */
export interface SpringPage<T> {
  content: T[]
  page: {
    size: number
    number: number        // current page (0-based)
    totalElements: number
    totalPages: number
  }
}

/** MongoDB SensorReading document returned by the Java API */
export interface SensorReading {
  id: string
  sensorId: string
  timestamp: string     // ISO-8601 UTC string
  temperature: number   // °C
  vibration: number     // mm/s RMS
  status: SensorStatus
  ingestedAt: string    // server-side wall-clock
  lastModifiedAt?: string
  version?: number
}

export type SensorStatus = 'NOMINAL' | 'WARNING' | 'CRITICAL' | string

/** AI insight returned by the Python FastAPI engine */
export interface AiInsight {
  insight: string
  confidence_score: number       // [0, 1]
  sources_referenced: string[]
  inference_time_ms: number
}

/** Enriched insight with the triggering sensor context (local state only) */
export interface AiDiagnostic extends AiInsight {
  /** Local monotonic ID for React keys */
  id: string
  sensorId: string
  anomalyType: string
  vibrationReading: number
  triggeredAt: string   // ISO timestamp when the anomaly was detected client-side
}

/** Request body sent to POST /inference/generate-insight  */
export interface AiInsightRequest {
  sensor_id: string
  anomaly_type: string
  raw_telemetry: {
    temperature: number
    vibration: number
    timestamp: string
  }
  query: string
}

/** Connection status of the backend services */
export type ConnectionStatus = 'connected' | 'degraded' | 'error' | 'connecting'

/** Aggregated state managed by useTelemetry hook */
export interface TelemetryState {
  readings: SensorReading[]
  diagnostics: AiDiagnostic[]
  connectionStatus: ConnectionStatus
  lastUpdated: Date | null
  isLoading: boolean
  errorMessage: string | null
  stats: {
    totalReadings: number
    criticalCount: number
    warningCount: number
    nominalCount: number
  }
}
