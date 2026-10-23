"""
Traceform Representation Layer — Multimodal metrics, normalizers, and discrete lattice.
"""

from .normalizer import MultimodalNormalizer, NormalizationParams
from .metrics import MultimodalDistanceMetric, MetricWeights
from .lattice import TopologicalKineticLattice, MicroState

__all__ = [
    "MultimodalNormalizer",
    "NormalizationParams",
    "MultimodalDistanceMetric",
    "MetricWeights",
    "TopologicalKineticLattice",
    "MicroState",
]
