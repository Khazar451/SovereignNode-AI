#!/usr/bin/env python3
"""
telemetry_simulator.py — SovereignNode AI Live Data Simulator
==============================================================
Continuously pumps realistic sensor readings into the Java ingestion
service so the dashboard always has fresh, diverse telemetry to display.

Usage:
    python3 telemetry_simulator.py

Requirements:
    pip install requests
"""

import random
import time
from datetime import datetime, timezone

import requests

# ── Configuration ─────────────────────────────────────────────────────────────

TELEMETRY_URL = "http://localhost:18080/api/v1/telemetry"
POLL_INTERVAL_SEC = 2

SENSOR_IDS = [
    "VIB-MTR-001",   # Primary motor (already has historical data)
    "PUMP-001",      # Coolant pump
    "PUMP-002",      # Hydraulic pump
    "MOTOR-002",     # Conveyor drive motor
    "COMP-003",      # Air compressor
    "COMP-004",      # Backup compressor
    "GEARBOX-001",   # Main gearbox
    "BEARING-007",   # Rotor bearing assembly
]

# Vibration thresholds (mm/s RMS) — must match Java + dashboard config
VIBRATION_NOMINAL_MAX  = 4.0
VIBRATION_WARNING_MAX  = 7.1   # above this → CRITICAL

# Temperature operating ranges per sensor profile (°C)
TEMP_RANGE = {
    "VIB-MTR-001":  (60.0,  90.0),
    "PUMP-001":     (35.0,  55.0),
    "PUMP-002":     (38.0,  60.0),
    "MOTOR-002":    (50.0,  80.0),
    "COMP-003":     (70.0, 110.0),
    "COMP-004":     (65.0, 105.0),
    "GEARBOX-001":  (55.0,  85.0),
    "BEARING-007":  (45.0,  75.0),
}

# ANSI colour codes for terminal output
_RED    = "\033[91m"
_YELLOW = "\033[93m"
_GREEN  = "\033[92m"
_CYAN   = "\033[96m"
_GREY   = "\033[90m"
_RESET  = "\033[0m"
_BOLD   = "\033[1m"


# ── Helpers ───────────────────────────────────────────────────────────────────

def _status_color(status: str) -> str:
    return {
        "CRITICAL": _RED,
        "WARNING":  _YELLOW,
        "NOMINAL":  _GREEN,
    }.get(status, _RESET)


def generate_reading(sensor_id: str) -> dict:
    """
    Build a realistic sensor payload.

    Vibration distribution is intentionally biased so the demo produces a
    compelling mix: ~55% NOMINAL, ~25% WARNING, ~20% CRITICAL.
    """
    roll = random.random()
    if roll < 0.55:
        # NOMINAL — low vibration, healthy operation
        vibration = round(random.uniform(0.5, VIBRATION_NOMINAL_MAX - 0.1), 2)
        status = "NOMINAL"
    elif roll < 0.80:
        # WARNING — elevated but sub-critical vibration
        vibration = round(random.uniform(VIBRATION_NOMINAL_MAX, VIBRATION_WARNING_MAX - 0.05), 2)
        status = "WARNING"
    else:
        # CRITICAL — anomalous vibration event
        vibration = round(random.uniform(VIBRATION_WARNING_MAX + 0.05, 15.0), 2)
        status = "CRITICAL"

    temp_min, temp_max = TEMP_RANGE.get(sensor_id, (40.0, 100.0))
    # Temperature skews higher during WARNING/CRITICAL events
    temp_bias = 1.0 if status == "NOMINAL" else 1.15 if status == "WARNING" else 1.30
    raw_temp  = random.uniform(temp_min, min(temp_max, temp_max * temp_bias))
    temperature = round(raw_temp, 1)

    timestamp = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.000Z")

    return {
        "sensor_id":   sensor_id,
        "timestamp":   timestamp,
        "temperature": temperature,
        "vibration":   vibration,
        "status":      status,
    }


# ── Main Loop ─────────────────────────────────────────────────────────────────

def main() -> None:
    print(f"\n{_BOLD}{_CYAN}━━━  SovereignNode AI — Telemetry Simulator  ━━━{_RESET}")
    print(f"{_GREY}Target : {TELEMETRY_URL}")
    print(f"Sensors: {', '.join(SENSOR_IDS)}")
    print(f"Interval: {POLL_INTERVAL_SEC}s{_RESET}\n")
    print(f"{'Time':<12} {'Sensor':<16} {'Status':<10} {'Vibration':>12} {'Temp':>8}  Result")
    print("─" * 72)

    session = requests.Session()   # reuse TCP connection

    while True:
        sensor_id = random.choice(SENSOR_IDS)
        payload   = generate_reading(sensor_id)
        now       = datetime.now().strftime("%H:%M:%S")
        sc        = _status_color(payload["status"])

        try:
            resp = session.post(TELEMETRY_URL, json=payload, timeout=5)
            resp.raise_for_status()

            print(
                f"{_GREY}{now}{_RESET}  "
                f"{_BOLD}{sensor_id:<16}{_RESET} "
                f"{sc}{payload['status']:<10}{_RESET} "
                f"{_BOLD}{payload['vibration']:>9.2f} mm/s{_RESET}  "
                f"{payload['temperature']:>6.1f} °C  "
                f"{_GREEN}✓ {resp.status_code}{_RESET}"
            )

        except requests.exceptions.ConnectionError:
            print(
                f"{_GREY}{now}{_RESET}  "
                f"{sensor_id:<16} "
                f"{'—':<10} "
                f"{'—':>12}  {'—':>8}  "
                f"{_RED}✗ Connection refused — is the telemetry service running?{_RESET}"
            )
        except requests.exceptions.Timeout:
            print(
                f"{_GREY}{now}{_RESET}  "
                f"{sensor_id:<16} "
                f"{'—':<10} "
                f"{'—':>12}  {'—':>8}  "
                f"{_YELLOW}⚠ Timeout (>5s){_RESET}"
            )
        except requests.exceptions.HTTPError as exc:
            print(
                f"{_GREY}{now}{_RESET}  "
                f"{sensor_id:<16} "
                f"{payload['status']:<10} "
                f"{payload['vibration']:>9.2f} mm/s  "
                f"{payload['temperature']:>6.1f} °C  "
                f"{_RED}✗ HTTP {exc.response.status_code}: {exc.response.text[:60]}{_RESET}"
            )

        time.sleep(POLL_INTERVAL_SEC)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print(f"\n{_GREY}Simulator stopped.{_RESET}\n")
