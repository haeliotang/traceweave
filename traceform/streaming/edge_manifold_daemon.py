"""
Traceform Edge Manifold Daemon (DETM & Online CSD Tipping Detector).

Processes continuous physical sensor observations in real-time on-device (Pixel 6a / Termux):
1. Projects continuous 15-dim feature vectors with phase velocity v(t) = dx/dt onto
   the 2D Dynamical Energy Manifold (DETM) using Riemannian energy geodesics (< 0.1 ms).
2. Maintains an online sliding ring-buffer to track spatial coordinate variance
   and lag-1 autocorrelation AR(1).
3. Fires real-time Critical Slowing Down (CSD) phase transition alerts and
   prolonged attractor dwell alerts.
"""

from __future__ import annotations

from collections import deque
from dataclasses import asdict, dataclass
import datetime
import json
import math
from pathlib import Path
import statistics
import time
from typing import Any, Deque, Dict, List, Optional, Tuple

from traceform.representation.normalizer import MultimodalNormalizer
from traceform.representation.manifold_lattice import DynamicalEnergyManifold


@dataclass
class EdgeDaemonEvent:
    event_type: str  # CSD_TIPPING, PROLONGED_DWELL, HIGH_VELOCITY_BURST
    warning_level: str  # NOTICE, ELEVATED, CRITICAL
    timestamp_utc: str
    current_token: str
    grid_coord: Tuple[int, int]
    metrics: Dict[str, float]
    message: str

    def to_dict(self) -> Dict[str, Any]:
        return {
            "event_type": self.event_type,
            "warning_level": self.warning_level,
            "timestamp_utc": self.timestamp_utc,
            "current_token": self.current_token,
            "grid_coord": list(self.grid_coord),
            "metrics": {k: round(v, 4) for k, v in self.metrics.items()},
            "message": self.message,
        }


class EdgeManifoldDaemon:
    """
    Sub-millisecond on-device edge daemon executing DETM quantization,
    online CSD tipping detection, and attractor dwell resonance triggers.
    """

    def __init__(
        self,
        normalizer: MultimodalNormalizer,
        manifold: DynamicalEnergyManifold,
        ring_buffer_size: int = 300,
        dwell_threshold_steps: int = 60,
        csd_threshold_sigma: float = 2.5,
    ) -> None:
        self.normalizer = normalizer
        self.manifold = manifold
        self.buffer_size = ring_buffer_size
        self.dwell_threshold = dwell_threshold_steps
        self.csd_threshold_sigma = csd_threshold_sigma

        # Ring buffer storing recent (u, v, proj_dist)
        self.coord_buffer: Deque[Tuple[float, float, float]] = deque(maxlen=ring_buffer_size)
        self.timestamps: Deque[str] = deque(maxlen=ring_buffer_size)

        # Baseline running statistics for z-score standardization
        self.var_history: Deque[float] = deque(maxlen=300)
        self.ar1_history: Deque[float] = deque(maxlen=300)

        # Dwell tracking
        self.current_token: Optional[str] = None
        self.consecutive_dwell_steps: int = 0

    def process_observation(
        self,
        obs: Dict[str, Any],
    ) -> Tuple[Dict[str, Any], List[EdgeDaemonEvent]]:
        """
        Processes a single incoming physical observation frame in real time.
        Returns:
            quantized_payload: Dict containing token, coordinates, and energy distance.
            events: List of any triggered EdgeDaemonEvents.
        """
        t0 = time.perf_counter()
        token, coord, dist = self.manifold.quantize(obs, self.normalizer)
        u, v = float(coord[0]), float(coord[1])
        proj = math.sqrt(u * u + v * v)
        ts = obs.get("timestamp_utc", datetime.datetime.now(datetime.timezone.utc).isoformat())

        self.coord_buffer.append((u, v, proj))
        self.timestamps.append(ts)

        # 1. Update Dwell Tracking
        events: List[EdgeDaemonEvent] = []
        if token == self.current_token:
            self.consecutive_dwell_steps += 1
        else:
            self.current_token = token
            self.consecutive_dwell_steps = 1

        if self.consecutive_dwell_steps == self.dwell_threshold:
            events.append(
                EdgeDaemonEvent(
                    event_type="PROLONGED_DWELL",
                    warning_level="NOTICE",
                    timestamp_utc=ts,
                    current_token=token,
                    grid_coord=(int(u), int(v)),
                    metrics={"dwell_steps": float(self.consecutive_dwell_steps)},
                    message=f"Prolonged attractor dwell: {self.consecutive_dwell_steps} consecutive steps in basin {token}.",
                )
            )

        # 2. Online CSD Tipping Evaluation with Linear Detrending (Fixing Non-stationarity)
        if len(self.coord_buffer) >= 20:
            u_vals = [pt[0] for pt in self.coord_buffer]
            v_vals = [pt[1] for pt in self.coord_buffer]
            proj_vals = [pt[2] for pt in self.coord_buffer]

            n_pts = len(self.coord_buffer)
            x_vals = list(range(n_pts))
            mean_x = sum(x_vals) / n_pts
            ss_xx = sum((x - mean_x) ** 2 for x in x_vals)

            def get_residuals(y_vals: List[float]) -> List[float]:
                if ss_xx < 1e-9:
                    return y_vals
                mean_y = sum(y_vals) / n_pts
                ss_xy = sum((x - mean_x) * (y - mean_y) for x, y in zip(x_vals, y_vals))
                slope = ss_xy / ss_xx
                intercept = mean_y - slope * mean_x
                return [y - (slope * x + intercept) for x, y in zip(x_vals, y_vals)]

            detrended_u = get_residuals(u_vals)
            detrended_v = get_residuals(v_vals)
            detrended_proj = get_residuals(proj_vals)

            var_u = sum(x**2 for x in detrended_u) / n_pts if n_pts > 1 else 0.0
            var_v = sum(x**2 for x in detrended_v) / n_pts if n_pts > 1 else 0.0
            tot_var = var_u + var_v

            # Lag-1 Autocorrelation AR(1) on detrended residuals
            # Since residuals have mean ~ 0, we can just use dot products
            cov_0 = sum(x**2 for x in detrended_proj) / n_pts
            cov_1 = (
                sum(detrended_proj[i] * detrended_proj[i + 1] for i in range(n_pts - 1))
                / (n_pts - 1)
                if n_pts > 1
                else 0.0
            )
            ar1 = max(-1.0, min(1.0, cov_1 / max(1e-6, cov_0)))

            self.var_history.append(tot_var)
            self.ar1_history.append(ar1)

            # Compute standardization
            if len(self.var_history) >= 10:
                mean_var = statistics.mean(self.var_history)
                std_var = statistics.stdev(self.var_history) if len(self.var_history) > 1 else 1e-4

                mean_ar1 = statistics.mean(self.ar1_history)
                std_ar1 = statistics.stdev(self.ar1_history) if len(self.ar1_history) > 1 else 1e-4

                z_var = (tot_var - mean_var) / max(1e-4, std_var)
                z_ar1 = (ar1 - mean_ar1) / max(1e-4, std_ar1)
                composite = z_var + z_ar1

                if composite >= self.csd_threshold_sigma:
                    level = "CRITICAL" if composite >= (self.csd_threshold_sigma + 1.5) else "ELEVATED"
                    events.append(
                        EdgeDaemonEvent(
                            event_type="CSD_TIPPING",
                            warning_level=level,
                            timestamp_utc=ts,
                            current_token=token,
                            grid_coord=(int(u), int(v)),
                            metrics={
                                "lag1_ar": ar1,
                                "spatial_variance": tot_var,
                                "composite_score": composite,
                            },
                            message=f"Critical Slowing Down tipping alert ({level}): AR(1)={ar1:.3f}, Var={tot_var:.2f}, Composite={composite:.2f}σ.",
                        )
                    )

        inference_ms = (time.perf_counter() - t0) * 1000.0

        quantized_payload = {
            "observation_id": obs.get("observation_id", ""),
            "timestamp_utc": ts,
            "state_token": token,
            "grid_coord": [int(u), int(v)],
            "energy_distance": round(dist, 4),
            "dwell_steps": self.consecutive_dwell_steps,
            "inference_ms": round(inference_ms, 4),
        }

        return quantized_payload, events
