# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

"""
Edge Stream Quantizer.

Lightweight, sub-millisecond on-device state quantizer for GrapheneOS Termux runtime.
Loads frozen normalizer and lattice parameters from representation_spec.json
and performs streaming quantization on live raw physical observation frames.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any, Dict, Tuple

from traceform.representation.normalizer import (
    MultimodalNormalizer,
    NormalizationParams,
)
from traceform.representation.lattice import TopologicalKineticLattice, MicroState


class EdgeStreamQuantizer:
    """
    Sub-millisecond on-device streaming quantizer.
    Can be initialized from representation_spec.json dict or file path.
    """

    def __init__(self, spec: Dict[str, Any]):
        self.spec = spec

        # Reconstruct normalizer
        norm_dict = spec.get("normalization_parameters", {})
        norm_params = NormalizationParams(
            feature_names=norm_dict["feature_names"],
            medians=norm_dict["medians"],
            iqrs=norm_dict["iqrs"],
            fitted_observation_count=norm_dict.get("fitted_observation_count", 3147),
            fit_window_description=norm_dict.get(
                "fit_window_description", "Loaded from frozen spec"
            ),
        )
        self.normalizer = MultimodalNormalizer(params=norm_params)

        # Reconstruct lattice
        self.lattice = TopologicalKineticLattice()
        state_dict = spec.get("micro_state_lattice", {}).get("state_dictionary", {})
        for item in sorted(state_dict.values(), key=lambda s: s["state_id"]):
            ms = MicroState.from_dict(item)
            self.lattice.vocabulary[ms.signature] = ms

        self.lattice._next_id = (
            max([s["state_id"] for s in state_dict.values()], default=0) + 1
        )
        self.w1_vocab_size = spec.get("micro_state_lattice", {}).get(
            "total_vocabulary_size_w1", 33
        )

    @classmethod
    def from_spec_file(cls, spec_file_path: Path) -> EdgeStreamQuantizer:
        with open(spec_file_path, "r", encoding="utf-8") as f:
            data = json.load(f)
        return cls(data)

    def process_raw_frame(
        self,
        raw_obs: Dict[str, Any],
        allow_new_states: bool = True,
    ) -> Tuple[str, str, Dict[str, Any]]:
        """
        Quantizes a single raw observation frame into an abstract state token.

        Returns:
            (state_token, signature, topological_descriptors)
            e.g. ("S01", "WIFI_d6c2cf9dba|K0_STATIONARY|P1_AC|L0_DARK", {...})
        """
        try:
            norm_vec, topo_desc = self.normalizer.transform(raw_obs)
            micro_state = self.lattice.quantize(
                raw_obs, allow_new_states=allow_new_states
            )
            return micro_state.state_token, micro_state.signature, topo_desc
        except Exception:
            return "ERR", "ERROR_FRAME", {"error": True}
