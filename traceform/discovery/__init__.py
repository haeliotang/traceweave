"""
Trajectory Structure Discovery Engine.
"""

from .recurrence import compute_recurrence_matrix, compute_rqa_metrics, RQAMetrics
from .regimes import detect_dynamic_regimes, Regime
from .motifs import extract_trajectory_motifs, TrajectoryMotif
from .transition_graph import build_transition_graph, TransitionGraphMetrics

__all__ = [
    "compute_recurrence_matrix",
    "compute_rqa_metrics",
    "RQAMetrics",
    "detect_dynamic_regimes",
    "Regime",
    "extract_trajectory_motifs",
    "TrajectoryMotif",
    "build_transition_graph",
    "TransitionGraphMetrics",
]
