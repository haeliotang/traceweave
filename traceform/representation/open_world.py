"""
Open-World Continuous Representation Lattice Manager.

Maintains stability-plasticity balance across longitudinal observation (30~180+ days).
Preserves locked Tier-1 core states while enabling bounded, adaptive Tier-2 state recruitment
for novel physical environments without catastrophic forgetting or vocabulary explosion.
"""

from __future__ import annotations

from collections import Counter
from dataclasses import asdict, dataclass
from typing import Any, Dict, List, Optional, Set, Tuple

from traceform.representation.lattice import TopologicalKineticLattice, MicroState


@dataclass
class OpenWorldStats:
    tier1_locked_count: int
    tier2_adaptive_count: int
    pending_candidates_count: int
    total_active_vocabulary: int
    max_capacity_limit: int

    def to_dict(self) -> Dict[str, Any]:
        return asdict(self)


class OpenWorldLatticeManager:
    def __init__(
        self,
        base_lattice: TopologicalKineticLattice,
        tier1_core_states: Set[str],
        promotion_threshold: int = 5,
        max_vocab_size: int = 80,
    ):
        self.lattice = base_lattice
        self.tier1_core_states = set(tier1_core_states)  # Signatures of locked core states
        self.promotion_threshold = promotion_threshold
        self.max_vocab_size = max_vocab_size

        self.candidate_counts: Counter[str] = Counter()
        self.tier2_states: Dict[str, MicroState] = {}

        # Register existing non-core states as tier2
        for sig, ms in self.lattice.vocabulary.items():
            if sig not in self.tier1_core_states:
                self.tier2_states[sig] = ms

    def process_observation(
        self,
        obs: Dict[str, Any],
    ) -> Tuple[str, str, bool]:
        """
        Quantizes an observation under open-world stability-plasticity constraints.

        Returns:
            (state_token, signature, is_novel_tier2)
        """
        sig, topo, kin, pow_m, lit = self.lattice.compute_signature(obs)

        # 1. Tier-1 Core State Match (Fast path)
        if sig in self.tier1_core_states:
            ms = self.lattice.vocabulary[sig]
            return ms.state_token, sig, False

        # 2. Tier-2 Existing Adaptive State Match
        if sig in self.tier2_states:
            ms = self.tier2_states[sig]
            return ms.state_token, sig, True

        # 3. Novel Signature Candidate Handling
        self.candidate_counts[sig] += 1
        curr_count = self.candidate_counts[sig]

        # Check for promotion into Tier-2
        if curr_count >= self.promotion_threshold:
            # Capacity guard
            if len(self.lattice.vocabulary) >= self.max_vocab_size:
                self._prune_lowest_frequency_tier2()

            # Create new state in lattice
            ms = self.lattice.quantize(obs, allow_new_states=True)
            self.tier2_states[sig] = ms
            return ms.state_token, sig, True

        # If not yet promoted, assign to nearest existing fallback or temporary transient
        return "S_TRANSIENT", sig, True

    def _prune_lowest_frequency_tier2(self) -> None:
        """
        Prunes the lowest-frequency Tier-2 state to prevent vocabulary explosion.
        Never touches Tier-1 locked core states.
        """
        if not self.tier2_states:
            return
        # Find least frequent tier-2 signature
        least_sig = min(self.tier2_states.keys(), key=lambda s: self.candidate_counts.get(s, 0))
        del self.tier2_states[least_sig]
        if least_sig in self.lattice.vocabulary:
            del self.lattice.vocabulary[least_sig]

    def get_stats(self) -> OpenWorldStats:
        return OpenWorldStats(
            tier1_locked_count=len(self.tier1_core_states),
            tier2_adaptive_count=len(self.tier2_states),
            pending_candidates_count=len([s for s, c in self.candidate_counts.items() if s not in self.lattice.vocabulary]),
            total_active_vocabulary=len(self.lattice.vocabulary),
            max_capacity_limit=self.max_vocab_size,
        )
