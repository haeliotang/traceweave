"""
Multiscale Renormalization & Anomalous Diffusion Analysis.

Quantifies trajectory diffusion regimes:
1. Mean Squared Displacement (MSD) <(Delta r(tau))^2> over multiple temporal lags.
2. Hurst exponent H estimation to differentiate sub-diffusion (potential well confinement)
   from Brownian random walk and super-diffusive exploration.
3. Temporal coarse-graining and transition determinism across scaling factors.
"""

from __future__ import annotations

from dataclasses import asdict, dataclass
import math
from typing import Any, Dict, List, Optional, Tuple


@dataclass
class DiffusionRegime:
    hurst_exponent: float
    r_squared: float
    regime_type: str  # SUB_DIFFUSIVE, BROWNIAN, SUPER_DIFFUSIVE
    interpretation: str
    msd_by_lag: Dict[int, float]

    def to_dict(self) -> Dict[str, Any]:
        return {
            "hurst_exponent": round(self.hurst_exponent, 4),
            "r_squared": round(self.r_squared, 4),
            "regime_type": self.regime_type,
            "interpretation": self.interpretation,
            "msd_by_lag": {k: round(v, 4) for k, v in self.msd_by_lag.items()},
        }


@dataclass
class CoarseGrainedScale:
    scale_factor: int
    scale_duration_seconds: float
    state_vocabulary_size: int
    transition_determinism: float
    mean_transition_entropy: float

    def to_dict(self) -> Dict[str, Any]:
        return {
            "scale_factor": self.scale_factor,
            "scale_duration_seconds": self.scale_duration_seconds,
            "state_vocabulary_size": self.state_vocabulary_size,
            "transition_determinism": round(self.transition_determinism, 4),
            "mean_transition_entropy": round(self.mean_transition_entropy, 4),
        }


class MultiscaleDiffusionAuditor:
    """
    Audits multiscale temporal dynamics, anomalous diffusion, and renormalization
    properties of human trajectory streams.
    """

    def __init__(self, step_duration_seconds: float = 30.0) -> None:
        self.step_seconds = step_duration_seconds

    def compute_msd(
        self,
        coords: List[Tuple[float, float]],
        lags: Optional[List[int]] = None,
    ) -> Dict[int, float]:
        """
        Computes Mean Squared Displacement MSD(tau) = < ||r(t+tau) - r(t)||^2 >.
        """
        n = len(coords)
        if n < 2:
            return {}

        if lags is None:
            lags = [1, 2, 4, 8, 16, 32, 64, 128, 256, 512]

        msd_map: Dict[int, float] = {}

        for tau in lags:
            if tau >= n:
                continue

            sq_dists = []
            # Calculate displacement across all t
            for t in range(n - tau):
                u1, v1 = coords[t]
                u2, v2 = coords[t + tau]
                d_sq = (u2 - u1) ** 2 + (v2 - v1) ** 2
                sq_dists.append(d_sq)

            if sq_dists:
                msd_map[tau] = sum(sq_dists) / len(sq_dists)

        return msd_map

    def estimate_hurst_exponent(
        self,
        msd_curve: Dict[int, float],
        min_lag: int = 1,
        max_lag: int = 256,
    ) -> DiffusionRegime:
        """
        Estimates Hurst Exponent H via linear regression on ln(MSD) vs ln(tau).
        ln(MSD) = 2H * ln(tau) + C => H = slope / 2.
        """
        # Filter valid positive pairs
        points: List[Tuple[float, float]] = []
        for tau, msd in sorted(msd_curve.items()):
            if min_lag <= tau <= max_lag and msd > 1e-8:
                points.append((math.log(tau), math.log(msd)))

        if len(points) < 2:
            return DiffusionRegime(
                hurst_exponent=0.5,
                r_squared=0.0,
                regime_type="BROWNIAN",
                interpretation="Insufficient points for regression",
                msd_by_lag=msd_curve,
            )

        n = len(points)
        x_vals = [p[0] for p in points]
        y_vals = [p[1] for p in points]

        mean_x = sum(x_vals) / n
        mean_y = sum(y_vals) / n

        ss_xx = sum((x - mean_x) ** 2 for x in x_vals)
        ss_yy = sum((y - mean_y) ** 2 for y in y_vals)
        ss_xy = sum((x - mean_x) * (y - mean_y) for x, y in zip(x_vals, y_vals))

        if ss_xx < 1e-9:
            slope = 0.0
            r_sq = 0.0
        else:
            slope = ss_xy / ss_xx
            r_sq = (ss_xy ** 2) / (ss_xx * ss_yy) if ss_yy > 1e-9 else 0.0

        # Hurst exponent H = slope / 2
        h = max(0.0, slope / 2.0)

        if h < 0.45:
            regime = "SUB_DIFFUSIVE"
            interp = f"Sub-diffusive trapping (H={h:.3f} < 0.5): Trajectory is strongly confined inside potential well attractors."
        elif h > 0.55:
            regime = "SUPER_DIFFUSIVE"
            interp = f"Super-diffusive persistence (H={h:.3f} > 0.5): Trajectory exhibits active exploration / long-range jumps."
        else:
            regime = "BROWNIAN"
            interp = f"Normal Brownian motion (H={h:.3f} ~ 0.5): Trajectory resembles memoryless uncorrelated random walk."

        return DiffusionRegime(
            hurst_exponent=h,
            r_squared=r_sq,
            regime_type=regime,
            interpretation=interp,
            msd_by_lag=msd_curve,
        )

    def compute_coarse_grained_determinism(
        self,
        tokens: List[str],
        scales: Optional[List[int]] = None,
    ) -> List[CoarseGrainedScale]:
        """
        Computes transition determinism and entropy across temporal coarse-graining scales.
        """
        if scales is None:
            scales = [1, 2, 4, 8, 16, 32]

        n = len(tokens)
        results: List[CoarseGrainedScale] = []

        for s in scales:
            if s > n // 2:
                continue

            # Coarse-grain sequence by taking the mode / most frequent token in each block
            coarse_tokens: List[str] = []
            for b in range(0, n - s + 1, s):
                block = tokens[b : b + s]
                counts: Dict[str, int] = {}
                for tok in block:
                    counts[tok] = counts.get(tok, 0) + 1
                mode_tok = max(counts.items(), key=lambda kv: kv[1])[0]
                coarse_tokens.append(mode_tok)

            # Build transition matrix
            trans_counts: Dict[str, Dict[str, int]] = {}
            vocab = set(coarse_tokens)

            for i in range(len(coarse_tokens) - 1):
                src = coarse_tokens[i]
                dst = coarse_tokens[i + 1]
                if src not in trans_counts:
                    trans_counts[src] = {}
                trans_counts[src][dst] = trans_counts[src].get(dst, 0) + 1

            # Determinism: mean of max(P(j | i))
            # Entropy: mean of sum -P log2 P
            determinisms = []
            entropies = []

            for src, dst_map in trans_counts.items():
                tot = sum(dst_map.values())
                probs = [cnt / tot for cnt in dst_map.values()]
                determinisms.append(max(probs))
                h = -sum(p * math.log2(p) for p in probs if p > 0)
                entropies.append(h)

            mean_det = sum(determinisms) / len(determinisms) if determinisms else 0.0
            mean_ent = sum(entropies) / len(entropies) if entropies else 0.0

            results.append(
                CoarseGrainedScale(
                    scale_factor=s,
                    scale_duration_seconds=s * self.step_seconds,
                    state_vocabulary_size=len(vocab),
                    transition_determinism=mean_det,
                    mean_transition_entropy=mean_ent,
                )
            )

        return results

    def audit_multiscale_dynamics(
        self,
        records: List[Dict[str, Any]],
        lags: Optional[List[int]] = None,
        scales: Optional[List[int]] = None,
    ) -> Dict[str, Any]:
        """
        Executes complete multiscale diffusion and coarse-graining audit.
        """
        coords: List[Tuple[float, float]] = []
        tokens: List[str] = []

        for r in records:
            c = r.get("grid_coord")
            if c and len(c) >= 2:
                coords.append((float(c[0]), float(c[1])))
            else:
                coords.append((0.0, 0.0))
            tokens.append(r.get("state_token", "M01"))

        # 1. Compute MSD
        msd = self.compute_msd(coords, lags)

        # 2. Estimate Hurst exponent
        regime = self.estimate_hurst_exponent(msd)

        # 3. Coarse-graining
        cg_scales = self.compute_coarse_grained_determinism(tokens, scales)

        # Find optimal scale maximizing determinism / vocab retention
        optimal_scale = max(cg_scales, key=lambda s: s.transition_determinism) if cg_scales else None

        return {
            "record_count": len(records),
            "diffusion_regime": regime.to_dict(),
            "coarse_grained_scales": [s.to_dict() for s in cg_scales],
            "optimal_scale": optimal_scale.to_dict() if optimal_scale else None,
        }
