---
title: SovNode Dashboard
tags: [sovereignnode, react, typescript, vite, tailwind, frontend]
created: 2026-05-04
updated: 2026-05-04
type: engineering-note
related: ["[[AI Inference Engine]]", "[[IoT Telemetry Service]]", "[[SovereignNode AI — Index]]"]
---

# 🖥️ SovNode Dashboard

> [!NOTE] What Is This?
> The **sovnode-dashboard** is the React frontend of SovereignNode — a real-time industrial monitoring UI built with **React 18 + TypeScript + Vite + Tailwind CSS**. It polls the Java backend every 2 seconds for live sensor readings, visually classifies them by severity, and automatically calls the Python AI engine to display diagnostic insights whenever a vibration anomaly is detected.

**Location:** `SovereignNode-AI/sovnode-dashboard/`
**Language:** TypeScript 5.5
**Framework:** React 18 + Vite 5
**Styling:** Tailwind CSS 3.4
**Dev server port:** `5173`
**Polls:** [[IoT Telemetry Service]] on port `18080`
**Calls:** [[AI Inference Engine]] on port `8000`

---

## Tech Stack

| Tool | Version | Purpose |
|---|---|---|
| React | 18.3 | UI rendering and state management |
| TypeScript | 5.5 | Full type safety across all components and API contracts |
| Vite | 5.4 | Dev server with HMR + proxy + production build |
| Tailwind CSS | 3.4 | Utility-first styling |
| JetBrains Mono | — | Monospace font for all sensor readouts |

> [!TIP] Zero Runtime Dependencies
> Beyond React itself, there are no runtime libraries — no Redux, no React Query, no Axios, no chart library. Everything is built on native `fetch`, `useState`, `useEffect`, and `useRef`. This keeps the bundle minimal and the dependency surface tiny.

---

## File Structure

```
src/
  main.tsx                        Entry point — mounts <App> in StrictMode
  App.tsx                         Root — renders <Dashboard> directly
  index.css                       Global styles, custom animations, design tokens
  types/index.ts                  All shared TypeScript interfaces
  hooks/
    useTelemetry.ts               Core polling + AI trigger hook (owns all state)
  components/
    Dashboard.tsx                 Main layout orchestrator + sub-components
    SensorCard.tsx                Individual sensor reading card
    AiDiagnosticsPanel.tsx        Right-panel AI insights display
    Dashboard.test.tsx            Component tests
```

---

## `types/index.ts` — Shared Type System

All data shapes are defined centrally here, mirroring the Java and Python API contracts exactly. TypeScript ensures the frontend and backend stay in sync.

### Key Interfaces

**`SensorReading`** — maps to the Java MongoDB document:

```typescript
interface SensorReading {
  id: string
  sensorId: string
  timestamp: string       // ISO-8601 UTC
  temperature: number     // °C
  vibration: number       // mm/s RMS
  status: SensorStatus    // 'NOMINAL' | 'WARNING' | 'CRITICAL' | string
  ingestedAt: string      // server-side wall-clock
}
```

**`SpringPage<T>`** — matches Spring Boot's paginated JSON envelope (produced by `@EnableSpringDataWebSupport` with `VIA_DTO` mode):

```typescript
interface SpringPage<T> {
  content: T[]
  page: {
    size: number
    number: number        // 0-based
    totalElements: number
    totalPages: number
  }
}
```

**`AiInsight`** — maps to the Python `InsightResponse`:

```typescript
interface AiInsight {
  insight: string
  confidence_score: number       // [0, 1]
  sources_referenced: string[]   // manual filenames used
  inference_time_ms: number
}
```

**`AiDiagnostic`** — extends `AiInsight` with local UI-only context:

```typescript
interface AiDiagnostic extends AiInsight {
  id: string                // local React key
  sensorId: string
  anomalyType: string
  vibrationReading: number
  triggeredAt: string       // client-side ISO timestamp
}
```

**`TelemetryState`** — the complete shape returned by `useTelemetry`:

```typescript
interface TelemetryState {
  readings: SensorReading[]
  diagnostics: AiDiagnostic[]
  connectionStatus: ConnectionStatus   // 'connected' | 'degraded' | 'error' | 'connecting'
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
```

---

## `vite.config.ts` — Dev Server Proxy

Two proxy rules eliminate CORS issues in development. In production, an nginx reverse proxy handles the same routing.

| Frontend request | Proxied to | Notes |
|---|---|---|
| `/api/*` | `http://localhost:18080` | Java Spring Boot |
| `/inference/*` | `http://localhost:8000` | Python FastAPI — `/inference` prefix is stripped |

The browser always thinks it's talking to the same origin as the Vite dev server.

---

## `useTelemetry.ts` — The Core Data Hook

This is the most important file in the frontend. A single custom hook owns **all** data-fetching, polling, AI triggering, deduplication, and state management. No state lives in components — they only receive props.

### Constants

| Constant | Value | Purpose |
|---|---|---|
| `POLL_INTERVAL_MS` | `2000` ms | Data refresh frequency |
| `MAX_READINGS` | `50` | Cap on live feed to prevent memory growth |
| `MAX_DIAGNOSTICS` | `10` | Max AI insight cards retained |
| `VIBRATION_ALERT_THRESHOLD` | `7.1` mm/s | **Must match** Java's `ai.anomaly.vibration-threshold-mms` |

### The Polling Loop

Every 2 seconds, `poll()` fires three `fetch` requests simultaneously using `Promise.all`:

```
GET /api/v1/telemetry/status/CRITICAL?page=0&size=20&sort=timestamp,desc
GET /api/v1/telemetry/status/WARNING?page=0&size=20&sort=timestamp,desc
GET /api/v1/telemetry/status/NOMINAL?page=0&size=20&sort=timestamp,desc
```

Results from all three are merged by `mergeReadings()` — a deduplication function that:
- Filters out IDs already in the existing state
- Prepends fresh readings (newest first)
- Slices the result to `MAX_READINGS` (50)

### AI Diagnostic Triggering

After merging readings, any reading from CRITICAL or WARNING with `vibration > 7.1` triggers `requestAiDiagnostic()` — called with `void` (no await). This is deliberately **fire-and-forget**:

```typescript
anomalies.forEach((r) => {
  void requestAiDiagnostic(r, signal)   // non-blocking
})
```

The poll resolves and the UI updates immediately. The AI insight card appears asynchronously when the Python LLM finishes.

### AI Call Deduplication

A `useRef<Set<string>>` named `analysedSensorIds` tracks every `sensorId::readingId` key sent to the AI engine. This prevents re-calling the Python engine for the same reading on every subsequent 2-second poll. Scoped to the browser session.

### AbortController Management

Each poll cycle cancels the **previous** cycle's in-flight requests before starting new ones:

```typescript
abortRef.current?.abort()
const controller = new AbortController()
abortRef.current = controller
```

`AbortError` exceptions are caught and silently ignored in all error handlers. This prevents stale slow-poll responses from overwriting newer data.

### Connection Status Logic

| Situation | `connectionStatus` | Data |
|---|---|---|
| First load | `'connecting'` | Empty |
| First successful poll | `'connected'` | Populated |
| First poll fails | `'error'` | Empty |
| Subsequent poll fails | `'degraded'` | Previous data retained |

### Derived Stats

Computed on every render from the `readings` array — no extra state needed:

```typescript
stats = {
  totalReadings: readings.length,
  criticalCount: readings.filter(r => r.status === 'CRITICAL').length,
  warningCount:  readings.filter(r => r.status === 'WARNING').length,
  nominalCount:  readings.filter(r => r.status === 'NOMINAL').length,
}
```

---

## `Dashboard.tsx` — Layout Orchestrator

Calls `useTelemetry()` and distributes state to child components. Contains several small inline sub-components:

### `LiveClock`
A `useEffect` with `setInterval(1000)` that updates a `Date` state every second. Displays the current UTC time in `YYYY-MM-DD HH:MM:SS UTC` format in the sticky header.

### `ConnectionBadge`
A color-coded pill that maps `ConnectionStatus` to visual config:

| Status | Color | Dot Animation |
|---|---|---|
| `connected` | Emerald | Static |
| `degraded` | Amber | Static |
| `error` | Red | Static |
| `connecting` | Cyan | `animate-pulse` |

### `StatsBar`
Four count tiles (Total, Nominal, Warning, Critical) with color-coded backgrounds and large monospaced numbers. Data comes directly from `stats`.

### `SkeletonCard`
Shown in a 6-card grid while `isLoading` is true. Uses Tailwind's `animate-pulse` to produce a shimmer loading placeholder — no third-party library needed.

### New Reading Highlight Animation

The Dashboard tracks which reading IDs existed in the previous render using `useRef<Set<string>>`:

1. On each `readings` update, newly arrived IDs are added to a `newIds` Set in state
2. `SensorCard` receives `isNew` prop and applies the `.sensor-card-new` class
3. The `highlightNew` keyframe plays: card background flashes cyan → fades to transparent over 2 seconds
4. A `setTimeout` after 2 seconds clears `newIds` back to an empty Set

### Page Layout

Two-column grid (responsive):
- **Left column** (flexible): Live sensor feed — 1/2/3 column grid of `SensorCard` components depending on viewport width
- **Right column** (fixed 420px, sticky): `AiDiagnosticsPanel` — sticks below the header and scrolls independently from the sensor feed

---

## `SensorCard.tsx` — Sensor Reading Display

Each card represents one `SensorReading`. All styling is driven by a `STATUS_CONFIG` lookup map — no conditional class logic scattered through JSX.

### Status Config Map

| Status | Left border | Badge | Dot | CRITICAL extra |
|---|---|---|---|---|
| `NOMINAL` | Emerald | Emerald ring pill | Emerald pulsing | — |
| `WARNING` | Amber | Amber ring pill | Amber pulsing | — |
| `CRITICAL` | Red | Red ring pill | Red pulsing | `animate-pulse-slow` on entire card |
| Unknown | Slate | Slate ring pill | Slate pulsing | — |

### Card Contents

1. **Pulsing live dot** — `animate-ping` ring + solid inner circle, color-matched to status
2. **Sensor ID** — monospaced, truncated with `title` tooltip for long hardware IDs
3. **Status pill** — rounded badge with icon: `●` (nominal), `▲` (warning), `✦` (critical)
4. **Temperature cell** — value highlighted in red if `> 100 °C`
5. **Vibration cell** — value highlighted in red if `> 7.1 mm/s` (matching the anomaly threshold)
6. **Footer** — UTC observation timestamp + relative age string

### Relative Time Helper (`formatRelativeTime`)

| Age | Output |
|---|---|
| < 5 seconds | "just now" |
| < 60 seconds | "Xs ago" |
| < 60 minutes | "Xm ago" |
| 60+ minutes | "Xh ago" |

---

## `AiDiagnosticsPanel.tsx` — AI Insights Panel

The sticky right-side panel. Two states:

### Empty State (`EmptyDiagnosticsState`)
Shown when no anomalies have triggered AI analysis yet:
- Hex icon with a green checkmark overlay
- "All Systems Nominal" heading
- Description of what triggers insights (vibration > 7.1 mm/s)
- Animated pulsing green dot: "Monitoring active · polling every 2s"

### Populated State
A scrollable list of `DiagnosticCard` components, newest first, capped at 10.

### `DiagnosticCard` Contents

**Header row:**
- Sensor ID (monospaced) + `✦` critical icon
- Anomaly type + vibration reading that triggered the call
- Local time the anomaly was detected + inference latency in ms

**RAG Confidence Bar (`ConfidenceBar`):**
A gradient progress bar that changes colour by confidence level:

| Score | Gradient | Label colour |
|---|---|---|
| ≥ 80% | Emerald → Cyan | Emerald |
| ≥ 50% | Amber → Yellow | Amber |
| < 50% | Red → Orange | Red |

The bar width is set with `style={{ width: '${pct}%' }}` and transitions with `duration-700 ease-out` for a smooth animated fill.

**Insight Text:**
The raw LLM-generated diagnostic answer. Rendered in a max-40-line scrollable container with:
- Left cyan border — visually marks AI-generated content
- Fade-out gradient overlay at the bottom — hints at scrollable content below

**Sources Referenced:**
Pill-shaped cyan tags for each manual filename used as RAG context:
```
📄 pump_manual.pdf    📄 turbine_maintenance.pdf
```

Each `DiagnosticCard` has `animate-fade-in` so new insights smoothly appear.

---

## `index.css` — Custom Design System

Beyond standard Tailwind utilities, several custom rules define the dashboard's visual identity:

### Custom Classes

| Class | Effect |
|---|---|
| `.mono` | Applies JetBrains Mono / Fira Code / system monospace |
| `.status-pill` | Base style for all status badge pills |
| `.glow-cyan` | Box-shadow halo in cyan (used on the brand logo mark) |
| `.glow-red` | Box-shadow halo in red |
| `.glow-amber` | Box-shadow halo in amber |
| `.text-gradient-cyan` | Clips a cyan → blue gradient as text fill (brand name) |
| `.bg-grid` | Subtle SVG dot-grid background at 3% cyan opacity (full page) |

### Custom Animations

**`.scanline`** — A fixed-position 2px semi-transparent cyan line that slides top → bottom over 8 seconds in an infinite loop. Produces a CRT monitor / oscilloscope aesthetic. `pointer-events: none` so it never blocks interaction.

**`.sensor-card-new`** — The `highlightNew` keyframe: card background flashes cyan at 12% opacity and border turns cyan, then fades to transparent over 2 seconds with `ease-out`. Fired on every newly arrived reading.

### Typography
- Body font: `Inter` (Google Fonts) via `font-family` in the base layer
- Numeric/code font: `JetBrains Mono` via `.mono` class
- Background colour: `#030712` (near-black, slightly blue-tinted)

---

## `main.tsx` — Entry Point

```typescript
createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>
)
```

`StrictMode` causes all effects to run twice in development (React 18 behaviour) — a useful safety net that catches effects that don't clean up properly. The `useTelemetry` cleanup function (clears `setInterval`, calls `abort()`) is validated by this.

---

## `package.json` — Scripts

| Script | Command | Purpose |
|---|---|---|
| `dev` | `vite --host 0.0.0.0 --port 5173` | Start dev server with HMR |
| `build` | `tsc -b && vite build` | Type-check then bundle for production |
| `preview` | `vite preview` | Preview the production build locally |

---

## How Data Flows

```
  Every 2 seconds — useTelemetry.poll()
  |
  +── fetch /api/v1/telemetry/status/CRITICAL  (Java :18080)
  +── fetch /api/v1/telemetry/status/WARNING
  +── fetch /api/v1/telemetry/status/NOMINAL
  |
  v
  mergeReadings()  — deduplicate by ID, cap at 50, newest first
  |
  v
  setReadings()    — triggers re-render of all SensorCards
  |
  v
  For each new CRITICAL/WARNING reading where vibration > 7.1 mm/s:
  |
  +── requestAiDiagnostic()  [fire-and-forget, no await]
          |
          v
      POST /inference/generate-insight  (Python :8000)
          |
          v
      setDiagnostics()  — prepends AiDiagnostic, cap at 10
          |
          v
      DiagnosticCard appears in right panel
```

The sensor feed and AI panel are **intentionally decoupled** — sensor cards update on a fixed 2-second schedule, while AI cards arrive asynchronously whenever the LLM finishes. The user never waits.

---

## Related Notes

- [[IoT Telemetry Service]] — polled by this dashboard every 2 seconds for sensor data
- [[AI Inference Engine]] — called directly by this dashboard on vibration anomaly detection
- [[SovereignNode AI — Index]] — platform overview

---

#sovereignnode #react #typescript #vite #tailwind #frontend #dashboard #real-time
