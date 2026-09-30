"""
Attractor Landscape and Critical Slowing Down (CSD) Auditor.

Reconstructs the empirical quasi-potential energy surface U(u, v) = -ln P(u, v)
over the 2D dynamical manifold grid. Tracks longitudinal attractor drift and detects
critical slowing down (variance surge + lag-1 autocorrelation spike) as early warning
signals for habit disintegration and dynamical phase transitions.
"""

from __future__ import annotations

from dataclasses import asdict, dataclass
import math
import statistics
from typing import Any, Dict, List, Optional, Tuple


@dataclass
class AttractorBasin:
    basin_id: str
    grid_coord: Tuple[int, int]
    state_token: str
    potential_depth: float
    stationary_probability: float

    def to_dict(self) -> Dict[str, Any]:
        return {
            "basin_id": self.basin_id,
            "grid_coord": list(self.grid_coord),
            "state_token": self.state_token,
            "potential_depth": round(self.potential_depth, 4),
            "stationary_probability": round(self.stationary_probability, 4),
        }


@dataclass
class CSDAlert:
    window_index: int
    timestamp_utc: str
    lag1_autocorrelation: float
    spatial_variance: float
    csd_composite_score: float
    warning_level: str  # NOTICE, ELEVATED, CRITICAL
    message: str

    def to_dict(self) -> Dict[str, Any]:
        return asdict(self)


class AttractorLandscapeAuditor:
    """
    Analyzes non-linear dynamical attractor landscapes and phase transition warnings
    on the 2D topological manifold.
    """

    def __init__(self, grid_height: int = 4, grid_width: int = 4) -> None:
        self.height = grid_height
        self.width = grid_width

    def compute_potential_surface(
        self,
        records: List[Dict[str, Any]],
        alpha_smooth: float = 1.0,
    ) -> List[List[float]]:
        """
        Reconstructs the 2D quasi-potential energy landscape U(u, v) = -ln P(u, v)
        with Laplace smoothing.
        """
        counts = [[0 for _ in range(self.width)] for _ in range(self.height)]
        total = len(records)

        for r in records:
            coord = r.get("grid_coord")
            if coord and len(coord) >= 2:
                u, v = int(coord[0]), int(coord[1])
                if 0 <= u < self.height and 0 <= v < self.width:
                    counts[u][v] += 1

        denom = total + alpha_smooth * (self.height * self.width)
        potentials: List[List[float]] = []

        for u in range(self.height):
            row = []
            for v in range(self.width):
                prob = (counts[u][v] + alpha_smooth) / max(1.0, denom)
                u_val = -math.log(prob)
                row.append(round(u_val, 4))
            potentials.append(row)

        return potentials

    def detect_attractor_basins(
        self,
        potential_matrix: List[List[float]],
        token_map: Optional[Dict[Tuple[int, int], str]] = None,
    ) -> List[AttractorBasin]:
        """
        Identifies local potential energy minima (attractor holding basins) on 2D grid.
        Excludes flat unvisited plateaus where barrier is zero.
        """
        basins: List[AttractorBasin] = []
        basin_idx = 1
        min_global_val = float("inf")
        min_global_coord = (0, 0)

        for u in range(self.height):
            for v in range(self.width):
                val = potential_matrix[u][v]
                if val < min_global_val:
                    min_global_val = val
                    min_global_coord = (u, v)

                # Check 4-connected neighbors
                neighbors = []
                for du, dv in [(-1, 0), (1, 0), (0, -1), (0, 1)]:
                    nu, nv = u + du, v + dv
                    if 0 <= nu < self.height and 0 <= nv < self.width:
                        neighbors.append(potential_matrix[nu][nv])

                # Must be strictly lower than neighbors or plateau-escaping
                if neighbors:
                    is_local_min = all(n >= val for n in neighbors) and any(n > val for n in neighbors)
                    if is_local_min:
                        higher_neighbors = [n for n in neighbors if n > val]
                        barrier = (min(higher_neighbors) - val) if higher_neighbors else 0.0
                        if barrier > 1e-4:
                            prob = math.exp(-val)
                            token = (
                                token_map.get((u, v), f"M({u},{v})")
                                if token_map
                                else f"M({u},{v})"
                            )
                            basins.append(
                                AttractorBasin(
                                    basin_id=f"B{basin_idx:02d}",
                                    grid_coord=(u, v),
                                    state_token=token,
                                    potential_depth=barrier,
                                    stationary_probability=prob,
                                )
                            )
                            basin_idx += 1

        # Fallback: if no strict local minimum found, designate the global minimum
        if not basins:
            u_g, v_g = min_global_coord
            token = token_map.get((u_g, v_g), f"M({u_g},{v_g})") if token_map else f"M({u_g},{v_g})"
            basins.append(
                AttractorBasin(
                    basin_id="B01",
                    grid_coord=(u_g, v_g),
                    state_token=token,
                    potential_depth=0.1,
                    stationary_probability=math.exp(-min_global_val),
                )
            )

        # Sort by depth descending (deepest attractor first)
        basins.sort(key=lambda b: b.potential_depth, reverse=True)
        return basins

    def track_attractor_drift(
        self,
        w1_records: List[Dict[str, Any]],
        w2_records: List[Dict[str, Any]],
        token_map: Optional[Dict[Tuple[int, int], str]] = None,
    ) -> Dict[str, Any]:
        """
        Measures the spatial and energetic drift of dominant attractors between W1 and W2.
        """
        pot_w1 = self.compute_potential_surface(w1_records)
        pot_w2 = self.compute_potential_surface(w2_records)

        basins_w1 = self.detect_attractor_basins(pot_w1, token_map)
        basins_w2 = self.detect_attractor_basins(pot_w2, token_map)

        drift_details: List[Dict[str, Any]] = []
        total_drift_dist = 0.0

        for b1 in basins_w1:
            u1, v1 = b1.grid_coord
            # Match nearest basin in W2
            if basins_w2:
                best_match = min(
                    basins_w2,
                    key=lambda b2: (b2.grid_coord[0] - u1) ** 2
                    + (b2.grid_coord[1] - v1) ** 2,
                )
                u2, v2 = best_match.grid_coord
                dist = math.sqrt((u2 - u1) ** 2 + (v2 - v1) ** 2)
                d_energy = best_match.potential_depth - b1.potential_depth
            else:
                best_match = None
                dist = 0.0
                d_energy = 0.0

            total_drift_dist += dist
            drift_details.append(
                {
                    "w1_basin": b1.to_dict(),
                    "matched_w2_basin": best_match.to_dict() if best_match else None,
                    "grid_drift_distance": round(dist, 2),
                    "potential_depth_change": round(d_energy, 4),
                }
            )

        mean_drift = (
            total_drift_dist / len(basins_w1) if basins_w1 else 0.0
        )

        primary_drift = 0.0
        if basins_w1 and basins_w2:
            p1 = basins_w1[0].grid_coord
            p2 = basins_w2[0].grid_coord
            primary_drift = round(math.sqrt((p2[0] - p1[0]) ** 2 + (p2[1] - p1[1]) ** 2), 3)

        return {
            "w1_basin_count": len(basins_w1),
            "w2_basin_count": len(basins_w2),
            "primary_attractor_drift_distance": primary_drift,
            "mean_grid_drift_distance": round(mean_drift, 3),
            "attractor_drift_details": drift_details,
            "landscape_drift_velocity_steps_per_week": round(mean_drift, 3),
            "potential_surface_w1": pot_w1,
            "potential_surface_w2": pot_w2,
        }

    def compute_critical_slowing_down(
        self,
        records: List[Dict[str, Any]],
        window_size: int = 80,
        step_size: int = 20,
    ) -> List[CSDAlert]:
        """
        Sliding-window Critical Slowing Down (CSD) detector.
        Detects phase transition warnings via lag-1 autocorrelation surges and variance explosion.
        """
        n = len(records)
        if n < window_size:
            return []

        # Extract grid coordinates time series (u(t), v(t))
        coords: List[Tuple[float, float]] = []
        timestamps: List[str] = []
        for r in records:
            c = r.get("grid_coord", (0, 0))
            coords.append((float(c[0]), float(c[1])))
            timestamps.append(r.get("timestamp_utc", ""))

        window_metrics: List[Dict[str, float]] = []

        for start in range(0, n - window_size + 1, step_size):
            sub = coords[start : start + window_size]
            u_vals = [pt[0] for pt in sub]
            v_vals = [pt[1] for pt in sub]

            # Spatial variance
            var_u = statistics.variance(u_vals) if len(u_vals) > 1 else 0.0
            var_v = statistics.variance(v_vals) if len(v_vals) > 1 else 0.0
            tot_var = var_u + var_v

            # Lag-1 Autocorrelation AR(1) on 1D projected grid distance
            proj = [math.sqrt(u * u + v * v) for u, v in sub]
            p_mean = statistics.mean(proj)
            cov_0 = sum((x - p_mean) ** 2 for x in proj) / len(proj)
            cov_1 = (
                sum((proj[i] - p_mean) * (proj[i + 1] - p_mean) for i in range(len(proj) - 1))
                / (len(proj) - 1)
                if len(proj) > 1
                else 0.0
            )
            ar1 = cov_1 / max(1e-6, cov_0)
            ar1 = max(-1.0, min(1.0, ar1))

            window_metrics.append(
                {
                    "start": float(start),
                    "mid_ts_idx": start + window_size // 2,
                    "var": tot_var,
                    "ar1": ar1,
                }
            )

        if not window_metrics:
            return []

        mean_var = statistics.mean(m["var"] for m in window_metrics)
        std_var = statistics.stdev(m["var"] for m in window_metrics) if len(window_metrics) > 1 else 1e-4

        mean_ar1 = statistics.mean(m["ar1"] for m in window_metrics)
        std_ar1 = statistics.stdev(m["ar1"] for m in window_metrics) if len(window_metrics) > 1 else 1e-4

        alerts: List[CSDAlert] = []
        for i, m in enumerate(window_metrics):
            z_var = (m["var"] - mean_var) / max(1e-4, std_var)
            z_ar1 = (m["ar1"] - mean_ar1) / max(1e-4, std_ar1)
            composite = z_var + z_ar1

            ts = timestamps[int(m["mid_ts_idx"])] if int(m["mid_ts_idx"]) < len(timestamps) else ""

            if composite >= 2.5:
                level = "CRITICAL" if composite >= 4.0 else "ELEVATED"
                alerts.append(
                    CSDAlert(
                        window_index=i,
                        timestamp_utc=ts,
                        lag1_autocorrelation=round(m["ar1"], 4),
                        spatial_variance=round(m["var"], 4),
                        csd_composite_score=round(composite, 2),
                        warning_level=level,
                        message=f"Critical Slowing Down warning ({level}): AR(1)={m['ar1']:.3f}, Var={m['var']:.2f}, Composite={composite:.2f}σ",
                    )
                )

        return alerts
