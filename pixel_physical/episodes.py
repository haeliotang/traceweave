"""Evidence-level PhysicalEpisode assembly and segmentation."""

from dataclasses import asdict, dataclass
import hashlib
import json
import time
from typing import Any, Dict, List, Optional, Tuple

from . import config
from .observation import PhysicalObservation
from .transition import PhysicalTransition


@dataclass
class PhysicalEpisode:
    protocol: str
    episode_id: str
    start_time_utc: str
    end_time_utc: str
    initial_state_ref: str
    final_state_ref: str
    transition_ids: List[str]
    dominant_modalities: List[str]
    evidence_observation_ids: List[str]
    episode_boundary_reason: str

    def to_dict(self) -> Dict[str, Any]:
        return asdict(self)

    def to_json(self) -> str:
        return json.dumps(self.to_dict(), separators=(",", ":"))

    @classmethod
    def from_dict(cls, d: Dict[str, Any]) -> "PhysicalEpisode":
        return cls(
            protocol=d.get("protocol", config.EPISODE_PROTOCOL),
            episode_id=d.get("episode_id", ""),
            start_time_utc=d.get("start_time_utc", ""),
            end_time_utc=d.get("end_time_utc", ""),
            initial_state_ref=d.get("initial_state_ref", ""),
            final_state_ref=d.get("final_state_ref", ""),
            transition_ids=d.get("transition_ids", []),
            dominant_modalities=d.get("dominant_modalities", []),
            evidence_observation_ids=d.get("evidence_observation_ids", []),
            episode_boundary_reason=d.get("episode_boundary_reason", "UNKNOWN"),
        )


class EpisodeAssembler:
    """
    Manages active episode temporal grouping and boundary segmentation.
    """

    def __init__(self, stability_timeout_s: float = config.EPISODE_STABILITY_TIMEOUT_SECONDS):
        self.stability_timeout_s = stability_timeout_s
        self.active_episode: Optional[PhysicalEpisode] = None
        self.last_transition_time_s: float = time.time()
        self.last_obs_time_s: float = time.time()

    def process_observation(
        self,
        obs: PhysicalObservation,
        transition: Optional[PhysicalTransition] = None,
    ) -> Tuple[Optional[PhysicalEpisode], PhysicalEpisode]:
        """
        Processes a new observation and optional transition.
        Returns (completed_closed_episode, active_episode).
        """
        now_s = time.time()
        closed_episode: Optional[PhysicalEpisode] = None

        # If no active episode exists, bootstrap initial episode
        if not self.active_episode:
            self.active_episode = self._start_new_episode(
                obs=obs,
                boundary_reason="INITIAL_BOOTSTRAP",
            )
            self.last_obs_time_s = now_s
            self.last_transition_time_s = now_s
            if transition:
                self._append_transition(self.active_episode, transition)
            return None, self.active_episode

        # Check for episode boundary trigger
        close_reason: Optional[str] = None

        if transition is not None:
            # Significant multimodal or major state change triggers new episode boundary
            if "place" in transition.dominant_changes:
                close_reason = "PLACE_TRANSITION"
            elif "connectivity" in transition.dominant_changes:
                close_reason = "CONNECTIVITY_CONTEXT_CHANGE"
            elif len(transition.dominant_changes) >= 2:
                close_reason = "MULTIMODAL_CHANGE"
            elif "motion" in transition.dominant_changes and transition.transition_score >= 1.2:
                close_reason = "MOTION_REGIME_CHANGE"

        elif (now_s - self.last_transition_time_s) >= self.stability_timeout_s and len(self.active_episode.evidence_observation_ids) >= 5:
            # Extended stability timeout closes active episode
            close_reason = "STABILITY_TIMEOUT"

        if close_reason:
            # Close active episode
            self.active_episode.end_time_utc = obs.timestamp_utc
            self.active_episode.final_state_ref = obs.observation_id
            self.active_episode.episode_boundary_reason = close_reason
            closed_episode = self.active_episode

            # Start fresh episode
            self.active_episode = self._start_new_episode(
                obs=obs,
                boundary_reason="ACTIVE",
            )
            if transition:
                self._append_transition(self.active_episode, transition)
            self.last_transition_time_s = now_s
        else:
            # Continue active episode
            self.active_episode.end_time_utc = obs.timestamp_utc
            self.active_episode.final_state_ref = obs.observation_id
            if obs.observation_id not in self.active_episode.evidence_observation_ids:
                self.active_episode.evidence_observation_ids.append(obs.observation_id)
            if transition:
                self._append_transition(self.active_episode, transition)
                self.last_transition_time_s = now_s

        self.last_obs_time_s = now_s
        return closed_episode, self.active_episode

    def _start_new_episode(self, obs: PhysicalObservation, boundary_reason: str) -> PhysicalEpisode:
        ts_utc = obs.timestamp_utc
        h_payload = f"{ts_utc}:{obs.observation_id}"
        e_hash = hashlib.sha256(h_payload.encode("utf-8")).hexdigest()[:12]
        ep_id = f"ep-{int(time.time() * 1000)}-{e_hash}"

        return PhysicalEpisode(
            protocol=config.EPISODE_PROTOCOL,
            episode_id=ep_id,
            start_time_utc=ts_utc,
            end_time_utc=ts_utc,
            initial_state_ref=obs.observation_id,
            final_state_ref=obs.observation_id,
            transition_ids=[],
            dominant_modalities=[],
            evidence_observation_ids=[obs.observation_id],
            episode_boundary_reason=boundary_reason,
        )

    def _append_transition(self, ep: PhysicalEpisode, trans: PhysicalTransition) -> None:
        if trans.transition_id not in ep.transition_ids:
            ep.transition_ids.append(trans.transition_id)
        for dom in trans.dominant_changes:
            if dom not in ep.dominant_modalities:
                ep.dominant_modalities.append(dom)
