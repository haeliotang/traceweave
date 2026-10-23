"""V2 acquisition fork: v1 sensing policy with rolling persistence.

Frozen pixel_physical source remains unchanged; this fork owns its lifecycle.
"""

import time
from typing import Any, Dict, Optional, Tuple

from pixel_physical import config
from traceform.streaming import physical_ledger_v2 as ledger
from pixel_physical.episodes import EpisodeAssembler, PhysicalEpisode
from pixel_physical.health import HealthTracker
from pixel_physical.observation import PhysicalObservation
from traceform.streaming.physical_observation_v2 import acquire_observation_frame
from pixel_physical.transition import PhysicalTransition, detect_transition


class RollingPhysicalAcquisitionEngine:
    """
    Coordinates multi-rate sampling, transition detection, episode assembly,
    and ledger persistence.
    """

    def __init__(self, ledger_dir=config.DEFAULT_LEDGER_DIR):
        self.ledger_dir = ledger_dir
        self.health_tracker = HealthTracker()
        self.episode_assembler = EpisodeAssembler()
        self.prior_observation: Optional[PhysicalObservation] = None
        self.last_location_coords: Optional[Tuple[float, float]] = None
        self.last_audio_sample_time: float = 0.0
        self.last_location_sample_time: float = 0.0
        self.last_location_attempt_monotonic: Optional[float] = None

        # Load prior state if available from ledger
        recent_obs = ledger.read_recent_observations(limit=1, ledger_path=ledger_dir / "physical_observations.jsonl")
        if recent_obs:
            self.prior_observation = recent_obs[-1]
            p = self.prior_observation.place
            if "latitude" in p and "longitude" in p:
                self.last_location_coords = (float(p["latitude"]), float(p["longitude"]))

        # Restore active episode from live state snapshot if present
        live_state = ledger.read_live_state(state_path=ledger_dir / "physical_live_state.json")
        if live_state and live_state.get("active_episode"):
            self.episode_assembler.active_episode = PhysicalEpisode.from_dict(live_state["active_episode"])

    def sample_once(
        self,
        force_audio: bool = True,
        force_location: bool = True,
    ) -> Tuple[PhysicalObservation, Optional[PhysicalTransition], PhysicalEpisode]:
        """
        Acquires one complete multimodal frame, detects transitions, updates episodes,
        and durably appends to ledgers.
        """
        now_s = time.time()
        location_now = time.monotonic()

        # Adaptive policy: determine if audio / location should be acquired
        include_audio = force_audio
        include_location = force_location

        if not force_audio:
            # Sample audio at most once every 3 minutes, unless stationary dropped
            if (now_s - self.last_audio_sample_time) >= 180.0:
                include_audio = True

        if not force_location:
            # Sample location at most once every 2 minutes when stationary, 30s when moving
            interval = 45.0
            if self.prior_observation and self.prior_observation.motion.get("stationary_likelihood", 1.0) > 0.8:
                interval = 120.0
            if self.last_location_attempt_monotonic is None or location_now - self.last_location_attempt_monotonic >= interval:
                include_location = True

        if include_location:
            # Failed or interrupted attempts also consume the retry interval.
            self.last_location_attempt_monotonic = location_now

        # Acquire frame
        obs = acquire_observation_frame(
            include_audio=include_audio,
            include_location=include_location,
            last_coords=self.last_location_coords,
        )

        if include_audio and obs.availability.get("audio_context") == "AVAILABLE":
            self.last_audio_sample_time = now_s

        if include_location and obs.availability.get("place") == "AVAILABLE":
            self.last_location_sample_time = now_s
            lat = obs.place.get("latitude")
            lon = obs.place.get("longitude")
            if lat is not None and lon is not None:
                self.last_location_coords = (float(lat), float(lon))

        # Update health tracking
        self.health_tracker.update_from_observation(obs.to_dict())

        # Durably append observation
        ledger.append_observation(obs, ledger_path=self.ledger_dir / "physical_observations.jsonl")

        # Detect transition against prior observation
        transition: Optional[PhysicalTransition] = None
        if self.prior_observation:
            transition = detect_transition(self.prior_observation, obs)
            if transition:
                ledger.append_transition(transition, ledger_path=self.ledger_dir / "physical_transitions.jsonl")

        # Update episode assembly
        closed_ep, active_ep = self.episode_assembler.process_observation(obs, transition=transition)
        if closed_ep:
            ledger.append_episode(closed_ep, ledger_path=self.ledger_dir / "physical_episodes.jsonl")

        # Update live state snapshot for CLI fast read
        live_snapshot = {
            "protocol": config.PROTOCOL,
            "updated_at_utc": obs.timestamp_utc,
            "latest_observation": obs.to_dict(),
            "latest_transition": transition.to_dict() if transition else None,
            "active_episode": active_ep.to_dict(),
            "health": self.health_tracker.to_dict(),
        }
        ledger.update_live_state(live_snapshot, state_path=self.ledger_dir / "physical_live_state.json")

        self.prior_observation = obs
        return obs, transition, active_ep
