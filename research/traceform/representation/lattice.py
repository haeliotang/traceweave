# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

"""
Topological-Kinetic State Lattice for Micro-State Quantization.

Maps multimodal observations to deterministic, discrete, and semantic-free
micro-states (S1, S2, ..., SK) via orthogonal topological and kinetic binning.
"""

from __future__ import annotations

from dataclasses import asdict, dataclass
import math
from typing import Any, Dict, List, Optional, Tuple


@dataclass(frozen=True)
class MicroState:
    state_id: int
    state_token: str
    topological_anchor: str
    kinetic_mode: str  # K0 (Stationary), K1 (Moderate), K2 (Dynamic)
    power_mode: str  # P0 (Unplugged), P1 (Charging)
    light_mode: str  # L0 (Dark), L1 (Ambient), L2 (Bright)
    signature: str

    def to_dict(self) -> Dict[str, Any]:
        return asdict(self)

    @classmethod
    def from_dict(cls, d: Dict[str, Any]) -> "MicroState":
        return cls(
            state_id=int(d["state_id"]),
            state_token=d["state_token"],
            topological_anchor=d["topological_anchor"],
            kinetic_mode=d["kinetic_mode"],
            power_mode=d["power_mode"],
            light_mode=d["light_mode"],
            signature=d["signature"],
        )


def safe_float(val: Any, default: float) -> float:
    if val is None:
        return default
    try:
        f = float(val)
        return default if math.isnan(f) or math.isinf(f) else f
    except (ValueError, TypeError):
        return default


class TopologicalKineticLattice:
    def __init__(self, vocabulary: Optional[Dict[str, MicroState]] = None):
        # Maps signature string -> MicroState
        self.vocabulary: Dict[str, MicroState] = vocabulary or {}
        # Counter for new states
        self._next_id = len(self.vocabulary) + 1

    def compute_signature(self, obs: Dict[str, Any]) -> Tuple[str, str, str, str, str]:
        """
        Derives the deterministic topological and kinetic discrete anchors from an observation.
        """
        # 1. Topological Anchor (WiFi -> Cell -> Geo)
        conn = obs.get("connectivity", {})
        plc = obs.get("place", {})

        wifi_fp = conn.get("wifi_fingerprint")
        cell_fp = conn.get("cellular_cell_fingerprint")
        geo_cell = plc.get("spatial_cell")

        if wifi_fp and wifi_fp not in ("NO_WIFI", ""):
            topo_anchor = f"WIFI_{wifi_fp[:10]}"
        elif cell_fp and cell_fp not in ("NO_CELL", ""):
            topo_anchor = f"CELL_{cell_fp[:10]}"
        elif geo_cell and geo_cell not in ("NO_GEO", ""):
            topo_anchor = f"GEO_{geo_cell}"
        else:
            topo_anchor = "TOPO_DISCONNECTED"

        # 2. Kinetic Mode (from stationary likelihood & energy)
        mot = obs.get("motion", {})
        stat_like = safe_float(mot.get("stationary_likelihood"), 1.0)
        mot_energy = safe_float(mot.get("motion_energy"), 0.0)

        if stat_like >= 0.85 and mot_energy < 0.2:
            kin_mode = "K0_STATIONARY"
        elif stat_like < 0.35 or mot_energy >= 0.5:
            kin_mode = "K2_DYNAMIC"
        else:
            kin_mode = "K1_MODERATE"

        # 3. Power Mode
        dev = obs.get("device_relation", {})
        status = str(dev.get("status", "DISCHARGING"))
        plugged = str(dev.get("plugged", "UNPLUGGED"))
        if status == "CHARGING" or plugged != "UNPLUGGED":
            power_mode = "P1_CHARGING"
        else:
            power_mode = "P0_UNPLUGGED"

        # 4. Light Mode (Log-lux based)
        env = obs.get("environment", {})
        lux_val = safe_float(env.get("ambient_light_lux"), 0.0)
        if lux_val <= 3.0:
            light_mode = "L0_DARK"
        elif lux_val <= 60.0:
            light_mode = "L1_AMBIENT"
        else:
            light_mode = "L2_BRIGHT"

        signature = f"{topo_anchor}|{kin_mode}|{power_mode}|{light_mode}"
        return signature, topo_anchor, kin_mode, power_mode, light_mode

    def fit_discovery_window(
        self, observations: List[Dict[str, Any]]
    ) -> Dict[str, MicroState]:
        """
        Builds the canonical micro-state vocabulary from the Day 1–7 discovery window.
        States are indexed canonically by frequency of occurrence (S1 is the most frequent).
        """
        signature_counts: Dict[str, int] = {}
        signature_anchors: Dict[str, Tuple[str, str, str, str]] = {}

        for obs in observations:
            sig, topo, kin, pow_m, lit = self.compute_signature(obs)
            signature_counts[sig] = signature_counts.get(sig, 0) + 1
            if sig not in signature_anchors:
                signature_anchors[sig] = (topo, kin, pow_m, lit)

        # Sort signatures by frequency descending
        sorted_sigs = sorted(
            signature_counts.keys(), key=lambda s: signature_counts[s], reverse=True
        )

        self.vocabulary.clear()
        for idx, sig in enumerate(sorted_sigs, 1):
            topo, kin, pow_m, lit = signature_anchors[sig]
            state = MicroState(
                state_id=idx,
                state_token=f"S{idx:02d}",
                topological_anchor=topo,
                kinetic_mode=kin,
                power_mode=pow_m,
                light_mode=lit,
                signature=sig,
            )
            self.vocabulary[sig] = state

        self._next_id = len(self.vocabulary) + 1
        return self.vocabulary

    def quantize(
        self, obs: Dict[str, Any], allow_new_states: bool = True
    ) -> MicroState:
        """
        Quantizes an observation into a MicroState token.
        If allow_new_states is True and a previously unseen signature occurs (e.g. in validation),
        assigns a deterministic subsequent ID (e.g. S99+).
        """
        sig, topo, kin, pow_m, lit = self.compute_signature(obs)
        if sig in self.vocabulary:
            return self.vocabulary[sig]

        if not allow_new_states:
            raise KeyError(f"Signature {sig} not found in frozen vocabulary.")

        new_id = self._next_id
        self._next_id += 1
        new_state = MicroState(
            state_id=new_id,
            state_token=f"S{new_id:02d}",
            topological_anchor=topo,
            kinetic_mode=kin,
            power_mode=pow_m,
            light_mode=lit,
            signature=sig,
        )
        self.vocabulary[sig] = new_state
        return new_state

    def to_dict(self) -> Dict[str, Any]:
        return {
            "total_micro_states": len(self.vocabulary),
            "states": {sig: state.to_dict() for sig, state in self.vocabulary.items()},
        }

    @classmethod
    def from_dict(cls, d: Dict[str, Any]) -> "TopologicalKineticLattice":
        vocab = {
            sig: MicroState.from_dict(s_dict)
            for sig, s_dict in d.get("states", {}).items()
        }
        return cls(vocabulary=vocab)
