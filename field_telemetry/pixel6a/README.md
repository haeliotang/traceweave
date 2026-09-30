# Field Telemetry: Dedicated Pixel 6a Tester

This directory stores autonomous telemetry, recalibrated dynamic models, and health states synchronized over-the-air (OTA) from the dedicated field tester Pixel 6a (`[REDACTED_DEVICE_SERIAL]`).

## Architecture: Scheme 1 + Scheme 2
- **Scheme 1 (On-Device Autonomous Evolution)**:
  The Pixel 6a runs `traceform/evolution/autonomous_learner.py` locally. When topological strain/drift is detected (or on a weekly schedule while charging), the Google Tensor chip recalibrates its 2D Dynamical Energy Manifold (DETM) in ~2 seconds without requiring physical USB connection.
- **Scheme 2 (OTA Git Synchronization)**:
  The device uses an authenticated SSH Deploy Key to push its latest telemetry, event ledgers, and recalibrated models to `field_telemetry/pixel6a/` and pull PC-side configuration updates.

## Directory Structure
- `models/`: Self-calibrated DETM manifold lattice models and normalization parameters.
- `events/`: Critical Slowing Down (CSD) tipping and prolonged dwell alert logs.
- `daily_summaries/`: Daily macro-state occupancy, Hurst diffusion exponent, and drift velocity summaries.
- `health/`: Device battery status, sensor availability, and edge quantization latency stats.
