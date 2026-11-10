# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

"""
Ambient Resonance and Dynamic Context Trigger Engine.

Provides declarative, non-invasive hooks based strictly on unsemantic
dynamical structures (micro-states Sk, macroscopic regimes Rj, motif disruption).
Emits structured intervention events without semantic pre-labeling.
"""

from __future__ import annotations

from dataclasses import asdict, dataclass
import enum
from typing import Any, Dict, Optional


class ResonanceTriggerType(str, enum.Enum):
    REGIME_PROLONGED_DWELL = "REGIME_PROLONGED_DWELL"
    HIGH_ENTROPY_FLUX = "HIGH_ENTROPY_FLUX"
    MOTIF_DISRUPTION = "MOTIF_DISRUPTION"
    ATTRACTOR_ESCAPE = "ATTRACTOR_ESCAPE"


@dataclass
class ResonanceEvent:
    event_id: str
    timestamp_utc: str
    trigger_type: ResonanceTriggerType
    current_state_token: str
    current_regime_token: str
    consecutive_dwell_steps: int
    severity: str  # INFO, NOTICE, ADAPTATION
    payload: Dict[str, Any]

    def to_dict(self) -> Dict[str, Any]:
        d = asdict(self)
        d["trigger_type"] = self.trigger_type.value
        return d


class AmbientResonanceEngine:
    def __init__(
        self,
        max_regime_dwell_steps: int = 120,  # ~1 hour
        entropy_spike_threshold: float = 2.0,
    ):
        self.max_dwell_steps = max_regime_dwell_steps
        self.entropy_threshold = entropy_spike_threshold

        self.current_state: str = ""
        self.current_regime: str = "R01"
        self.dwell_steps: int = 0
        self.event_counter: int = 0

    def process_step(
        self,
        step_index: int,
        timestamp_utc: str,
        state_token: str,
        regime_token: str = "R01",
        sliding_entropy: float = 0.5,
    ) -> Optional[ResonanceEvent]:
        """
        Processes a dynamic observation step and fires resonance events when structural criteria are met.
        """
        if state_token == self.current_state and regime_token == self.current_regime:
            self.dwell_steps += 1
        else:
            self.current_state = state_token
            self.current_regime = regime_token
            self.dwell_steps = 1

        # Rule 1: Prolonged Regime Locking / Dwell
        if self.dwell_steps == self.max_dwell_steps:
            self.event_counter += 1
            return ResonanceEvent(
                event_id=f"evt-{self.event_counter:04d}",
                timestamp_utc=timestamp_utc,
                trigger_type=ResonanceTriggerType.REGIME_PROLONGED_DWELL,
                current_state_token=state_token,
                current_regime_token=regime_token,
                consecutive_dwell_steps=self.dwell_steps,
                severity="NOTICE",
                payload={
                    "message": f"Attractor basin prolonged dwell reached ({self.dwell_steps} frames)",
                    "recommendation": "Ambient pause or physical movement opportunity",
                },
            )

        # Rule 2: Sudden High Entropy Flux (Rapid disorganization)
        if sliding_entropy >= self.entropy_threshold:
            self.event_counter += 1
            return ResonanceEvent(
                event_id=f"evt-{self.event_counter:04d}",
                timestamp_utc=timestamp_utc,
                trigger_type=ResonanceTriggerType.HIGH_ENTROPY_FLUX,
                current_state_token=state_token,
                current_regime_token=regime_token,
                consecutive_dwell_steps=self.dwell_steps,
                severity="ADAPTATION",
                payload={
                    "message": f"High dynamical entropy surge detected ({sliding_entropy:.2f} bits)",
                    "recommendation": "Protect cognitive bandwidth / suppress ambient notifications",
                },
            )

        return None
