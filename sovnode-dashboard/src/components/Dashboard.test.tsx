import { render, screen } from '@testing-library/react'
import { describe, it, expect, vi } from 'vitest'
import { Dashboard } from './Dashboard'
import { useTelemetry } from '../hooks/useTelemetry'

// 1. Mock the useTelemetry custom hook
vi.mock('../hooks/useTelemetry')

describe('Dashboard Component - AI Diagnostics Integration', () => {
  it('renders a critical anomaly and the corresponding AI diagnostic insight', () => {
    // 2. Mocked data reflecting a CRITICAL anomaly with a generated insight
    const mockDiagnostics = [
      {
        id: 'diag-123',
        sensorId: 'VIB-MTR-001',
        anomalyType: 'HIGH_VIBRATION',
        vibrationReading: 9.8,
        insight: 'Warning: Possible bearing misalignment detected in Motor 001. Immediate inspection required.',
        confidence_score: 0.92,
        inference_time_ms: 1250,
        sources_referenced: ['motor_manual_v2.pdf'],
        triggeredAt: new Date().toISOString(),
      },
    ]

    const mockStats = {
      totalReadings: 1,
      criticalCount: 1,
      warningCount: 0,
      nominalCount: 0,
    }

    // 3. Set the mock return value for the hook
    vi.mocked(useTelemetry).mockReturnValue({
      readings: [],
      diagnostics: mockDiagnostics,
      connectionStatus: 'connected',
      lastUpdated: new Date(),
      isLoading: false,
      errorMessage: null,
      stats: mockStats,
    })

    // 4. Render the dashboard
    render(<Dashboard />)

    // 5. Assertions
    // Check if the AI Diagnostics panel header is present
    expect(screen.getByText(/AI Diagnostics/i)).toBeInTheDocument()

    // Check if the specific mocked insight text is rendered
    expect(
      screen.getByText(/Possible bearing misalignment detected in Motor 001/i)
    ).toBeInTheDocument()

    // Check if the confidence score is displayed
    expect(screen.getByText(/92%/i)).toBeInTheDocument()

    // Check if the source filename is displayed
    expect(screen.getByText(/motor_manual_v2.pdf/i)).toBeInTheDocument()
  })

  it('renders the "All Systems Nominal" state when no diagnostics exist', () => {
    vi.mocked(useTelemetry).mockReturnValue({
      readings: [],
      diagnostics: [],
      connectionStatus: 'connected',
      lastUpdated: new Date(),
      isLoading: false,
      errorMessage: null,
      stats: { totalReadings: 0, criticalCount: 0, warningCount: 0, nominalCount: 0 },
    })

    render(<Dashboard />)

    expect(screen.getByText(/All Systems Nominal/i)).toBeInTheDocument()
  })
})
