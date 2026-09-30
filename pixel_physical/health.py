"""Observation health telemetry tracking for physical modalities."""

from dataclasses import asdict, dataclass
import time
from typing import Any, Dict, Optional

MODALITIES = ["motion", "place", "environment", "connectivity", "device_relation", "audio_context"]


@dataclass
class ModalityHealth:
    name: str
    status: str  # AVAILABLE, DEGRADED, PERMISSION_DENIED, NOT_SUPPORTED, TEMPORARILY_UNAVAILABLE, ERROR
    successful_samples: int = 0
    failed_samples: int = 0
    last_success_utc: Optional[str] = None
    last_latency_ms: float = 0.0
    average_latency_ms: float = 0.0

    def record_sample(self, status: str, latency_ms: float, timestamp_utc: str) -> None:
        self.status = status
        self.last_latency_ms = round(latency_ms, 2)
        if status in ("AVAILABLE", "NOT_SAMPLED"):
            if status == "AVAILABLE":
                self.successful_samples += 1
                self.last_success_utc = timestamp_utc
        else:
            self.failed_samples += 1

        total = self.successful_samples + self.failed_samples
        if total > 0:
            self.average_latency_ms = round(
                ((self.average_latency_ms * (total - 1)) + latency_ms) / total, 2
            )


class HealthTracker:
    """Tracks cumulative health and sampling latency across all physical modalities."""

    def __init__(self):
        self.modalities: Dict[str, ModalityHealth] = {
            m: ModalityHealth(name=m, status="AVAILABLE") for m in MODALITIES
        }

    def update_from_observation(self, obs_dict: Dict[str, Any]) -> None:
        avail = obs_dict.get("availability", {})
        latencies = obs_dict.get("sample_latencies_ms", {})
        ts = obs_dict.get("timestamp_utc", time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()))

        for m in MODALITIES:
            status = avail.get(m, "UNAVAILABLE")
            lat = float(latencies.get(m, 0.0))
            self.modalities[m].record_sample(status, lat, ts)

    def to_dict(self) -> Dict[str, Any]:
        return {m: asdict(h) for m, h in self.modalities.items()}
