"""
Multimodal Distance Metric for Physical Observations.

Defines a bounded, symmetric, and metric-invariant distance function D(x_i, x_j) in [0, 1]
combining continuous robust-scaled kinematics/environment with discrete network/spatial topology.
"""

from __future__ import annotations

from dataclasses import asdict, dataclass
import math
from typing import Dict, List, Optional


@dataclass
class MetricWeights:
    weight_topology: float = 0.30
    weight_kinetics: float = 0.25
    weight_device: float = 0.15
    weight_environment: float = 0.12
    weight_audio: float = 0.08
    weight_connectivity: float = 0.10

    def to_dict(self) -> Dict[str, float]:
        return asdict(self)

    @classmethod
    def from_dict(cls, d: Dict[str, float]) -> "MetricWeights":
        return cls(
            weight_topology=float(d.get("weight_topology", 0.30)),
            weight_kinetics=float(d.get("weight_kinetics", 0.25)),
            weight_device=float(d.get("weight_device", 0.15)),
            weight_environment=float(d.get("weight_environment", 0.12)),
            weight_audio=float(d.get("weight_audio", 0.08)),
            weight_connectivity=float(d.get("weight_connectivity", 0.10)),
        )


class MultimodalDistanceMetric:
    def __init__(self, weights: Optional[MetricWeights] = None):
        self.weights = weights or MetricWeights()

    @staticmethod
    def _euclidean_dist(v1: List[float], v2: List[float]) -> float:
        s = sum((a - b) ** 2 for a, b in zip(v1, v2))
        return math.sqrt(s)

    def continuous_subvector_distance(self, v1: List[float], v2: List[float]) -> float:
        """
        Computes bounded non-linear distance in [0, 1] via hyperbolic tangent:
        d = tanh(0.5 * ||v1 - v2||_2)
        """
        euc = self._euclidean_dist(v1, v2)
        return math.tanh(0.5 * euc)

    def topological_distance(
        self, topo1: Dict[str, str], topo2: Dict[str, str]
    ) -> float:
        """
        Computes discrete topological distance in [0, 1] based on WiFi fingerprint,
        cellular cell fingerprint, and spatial geocell matching.
        """
        # 1. WiFi Fingerprint (0.50 weight in topo)
        w1 = topo1.get("wifi_fingerprint", "NO_WIFI")
        w2 = topo2.get("wifi_fingerprint", "NO_WIFI")
        if w1 == w2:
            d_wifi = 0.0
        elif w1 == "NO_WIFI" or w2 == "NO_WIFI":
            d_wifi = 0.5
        else:
            d_wifi = 1.0

        # 2. Spatial Geocell (0.30 weight in topo)
        g1 = topo1.get("spatial_cell", "NO_GEO")
        g2 = topo2.get("spatial_cell", "NO_GEO")
        if g1 == g2:
            d_geo = 0.0
        elif g1 == "NO_GEO" or g2 == "NO_GEO":
            d_geo = 0.5
        else:
            # Geohash common prefix length gives hierarchical proximity
            common_prefix_len = 0
            for c1, c2 in zip(g1, g2):
                if c1 == c2:
                    common_prefix_len += 1
                else:
                    break
            # Typical geohash length is 7 chars; floor at 0.05 for unequal strings
            d_geo = max(0.05, 1.0 - (common_prefix_len / 7.0))

        # 3. Cellular Cell ID (0.20 weight in topo)
        c1 = topo1.get("cellular_fingerprint", "NO_CELL")
        c2 = topo2.get("cellular_fingerprint", "NO_CELL")
        if c1 == c2:
            d_cell = 0.0
        elif c1 == "NO_CELL" or c2 == "NO_CELL":
            d_cell = 0.5
        else:
            d_cell = 1.0

        d_topo = (0.50 * d_wifi) + (0.30 * d_geo) + (0.20 * d_cell)
        return min(1.0, max(0.0, d_topo))

    def compute_distance(
        self,
        vec1: List[float],
        topo1: Dict[str, str],
        vec2: List[float],
        topo2: Dict[str, str],
    ) -> float:
        """
        Computes the overall composite multimodal distance D(x_1, x_2) in [0.0, 1.0].
        Index slices of continuous vector (15 dimensions):
          - Kinetic:     [0:4]  (accel_mag, accel_rms, motion_energy, stationary_like)
          - Environment: [4:7]  (log_lux, pressure, temp)
          - Device:      [7:10] (battery, is_plugged, is_charging)
          - Audio:       [10:13](silence, rms, entropy)
          - Conn metrics:[13:15](wifi_conn, rssi_norm)
        """
        # Sub-vectors
        d_kin = self.continuous_subvector_distance(vec1[0:4], vec2[0:4])
        d_env = self.continuous_subvector_distance(vec1[4:7], vec2[4:7])
        d_dev = self.continuous_subvector_distance(vec1[7:10], vec2[7:10])
        d_aud = self.continuous_subvector_distance(vec1[10:13], vec2[10:13])
        d_conn = self.continuous_subvector_distance(vec1[13:15], vec2[13:15])
        d_topo = self.topological_distance(topo1, topo2)

        composite = (
            self.weights.weight_topology * d_topo
            + self.weights.weight_kinetics * d_kin
            + self.weights.weight_device * d_dev
            + self.weights.weight_environment * d_env
            + self.weights.weight_audio * d_aud
            + self.weights.weight_connectivity * d_conn
        )

        return round(float(min(1.0, max(0.0, composite))), 6)
