"""Evidence-level PhysicalTransition detection and scoring."""

from dataclasses import asdict, dataclass
import hashlib
import json
import math
import time
from typing import Any, Dict, List, Optional, Tuple

from . import config, location
from .observation import PhysicalObservation


@dataclass
class PhysicalTransition:
    protocol: str
    transition_id: str
    timestamp_utc: str
    prior_observation_id: str
    subsequent_observation_id: str
    transition_score: float
    dominant_changes: List[str]
    modality_evidence: Dict[str, Any]
    transition_summary: str

    def to_dict(self) -> Dict[str, Any]:
        return asdict(self)

    def to_json(self) -> str:
        return json.dumps(self.to_dict(), separators=(",", ":"))

    @classmethod
    def from_dict(cls, d: Dict[str, Any]) -> "PhysicalTransition":
        return cls(
            protocol=d.get("protocol", config.TRANSITION_PROTOCOL),
            transition_id=d.get("transition_id", ""),
            timestamp_utc=d.get("timestamp_utc", ""),
            prior_observation_id=d.get("prior_observation_id", ""),
            subsequent_observation_id=d.get("subsequent_observation_id", ""),
            transition_score=float(d.get("transition_score", 0.0)),
            dominant_changes=d.get("dominant_changes", []),
            modality_evidence=d.get("modality_evidence", {}),
            transition_summary=d.get("transition_summary", ""),
        )


def compute_effective_noise_radius(
    prior_accuracy_m: float,
    current_accuracy_m: float,
    floor_threshold_m: float = 50.0,
) -> float:
    """
    Computes the uncertainty noise envelope from prior and current reported accuracies.
    Displacements below this envelope cannot be distinguished from GPS drift or provider handover.
    """
    acc_combined = float(prior_accuracy_m) + float(current_accuracy_m)
    return max(acc_combined * 1.2, floor_threshold_m)


def detect_transition(
    prior: PhysicalObservation,
    curr: PhysicalObservation,
    settled_cell_history: Optional[List[str]] = None,
) -> Optional[PhysicalTransition]:
    """
    Evaluates evidence of physical state change between two consecutive observations.
    Returns a PhysicalTransition if significant evidence exists, otherwise None.
    """
    dominant_changes: List[str] = []
    modality_evidence: Dict[str, Any] = {}
    total_score = 0.0
    summary_parts: List[str] = []

    # 1. Motion Change
    m_prior = prior.motion
    m_curr = curr.motion
    if "motion_energy" in m_prior and "motion_energy" in m_curr:
        e_prior = float(m_prior.get("motion_energy", 0.0))
        e_curr = float(m_curr.get("motion_energy", 0.0))
        stat_prior = float(m_prior.get("stationary_likelihood", 1.0))
        stat_curr = float(m_curr.get("stationary_likelihood", 1.0))

        d_energy = abs(e_curr - e_prior)
        d_stat = abs(stat_curr - stat_prior)
        motion_score = min(2.0, d_energy + d_stat)

        step_p = m_prior.get("step_count")
        step_c = m_curr.get("step_count")
        d_steps = (step_c - step_p) if (step_p is not None and step_c is not None) else 0

        evidence_motion = {
            "energy_delta": round(d_energy, 4),
            "stationary_likelihood_delta": round(d_stat, 3),
            "step_delta": d_steps,
            "motion_score": round(motion_score, 3),
        }
        modality_evidence["motion"] = evidence_motion

        if motion_score >= 0.6 or abs(d_stat) >= 0.4 or d_steps >= 10:
            dominant_changes.append("motion")
            total_score += motion_score
            if stat_prior > 0.7 and stat_curr < 0.3:
                summary_parts.append("STATIONARY_TO_ACTIVE")
            elif stat_prior < 0.3 and stat_curr > 0.7:
                summary_parts.append("ACTIVE_TO_STATIONARY")
            else:
                summary_parts.append(f"MOTION_REGIME_SHIFT(score={round(motion_score, 2)})")

    # 2. Place Change with Accuracy-Aware Spatial Hysteresis
    p_prior = prior.place
    p_curr = curr.place
    if "latitude" in p_prior and "latitude" in p_curr:
        lat1, lon1 = float(p_prior["latitude"]), float(p_prior["longitude"])
        lat2, lon2 = float(p_curr["latitude"]), float(p_curr["longitude"])
        acc1 = float(p_prior.get("accuracy_m", 30.0))
        acc2 = float(p_curr.get("accuracy_m", 30.0))
        dist_m = location.calculate_haversine_distance_m(lat1, lon1, lat2, lon2)
        cell1 = p_prior.get("spatial_cell", "")
        cell2 = p_curr.get("spatial_cell", "")

        noise_envelope = compute_effective_noise_radius(acc1, acc2, floor_threshold_m=50.0)

        # Strict hysteresis:
        # A spatial cell boundary change is only a true transition if displacement materially
        # exceeds the combined uncertainty envelope (dist_m > noise_envelope * 1.1)
        is_place_transition = (cell1 != cell2) and (dist_m > noise_envelope * 1.1)

        place_score = min(2.5, (dist_m - noise_envelope) / 50.0 + 1.0) if is_place_transition else 0.0
        modality_evidence["place"] = {
            "distance_delta_m": round(dist_m, 2),
            "prior_cell": cell1,
            "current_cell": cell2,
            "prior_accuracy_m": acc1,
            "current_accuracy_m": acc2,
            "noise_envelope_m": round(noise_envelope, 2),
            "significant_displacement": is_place_transition,
            "place_score": round(place_score, 3),
        }

        if is_place_transition:
            dominant_changes.append("place")
            total_score += place_score
            summary_parts.append(f"SPATIAL_CELL_DISPLACEMENT({cell1}->{cell2},{round(dist_m, 1)}m)")

    # 3. Connectivity Change
    c_prior = prior.connectivity
    c_curr = curr.connectivity
    if "wifi_connected" in c_prior and "wifi_connected" in c_curr:
        w_conn_prior = c_prior.get("wifi_connected")
        w_conn_curr = c_curr.get("wifi_connected")
        w_fp_prior = c_prior.get("wifi_fingerprint")
        w_fp_curr = c_curr.get("wifi_fingerprint")
        cell_fp_prior = c_prior.get("cellular_cell_fingerprint")
        cell_fp_curr = c_curr.get("cellular_cell_fingerprint")

        conn_score = 0.0
        conn_changes: List[str] = []

        if w_conn_prior != w_conn_curr:
            conn_score += 1.5
            conn_changes.append("WIFI_CONNECTIVITY_TOGGLE")
        elif w_fp_prior != w_fp_curr and (w_fp_prior or w_fp_curr):
            conn_score += 1.2
            conn_changes.append(f"WIFI_FINGERPRINT_SHIFT({w_fp_prior}->{w_fp_curr})")

        if cell_fp_prior != cell_fp_curr and (cell_fp_prior or cell_fp_curr):
            conn_score += 0.8
            conn_changes.append("CELLULAR_TOWER_HANDOVER")

        modality_evidence["connectivity"] = {
            "connectivity_score": round(conn_score, 3),
            "wifi_connected_prior": w_conn_prior,
            "wifi_connected_curr": w_conn_curr,
            "wifi_fingerprint_prior": w_fp_prior,
            "wifi_fingerprint_curr": w_fp_curr,
            "cellular_cell_prior": cell_fp_prior,
            "cellular_cell_curr": cell_fp_curr,
        }

        if conn_score >= 0.8:
            dominant_changes.append("connectivity")
            total_score += conn_score
            summary_parts.extend(conn_changes)

    # 4. Device Relation Change
    d_prior = prior.device_relation
    d_curr = curr.device_relation
    if "plugged" in d_prior and "plugged" in d_curr:
        plug_prior = d_prior.get("plugged")
        plug_curr = d_curr.get("plugged")
        stat_p = d_prior.get("status")
        stat_c = d_curr.get("status")
        head_p = d_prior.get("wired_headset")
        head_c = d_curr.get("wired_headset")

        dev_score = 0.0
        dev_changes: List[str] = []

        if plug_prior != plug_curr:
            dev_score += 1.5
            dev_changes.append(f"CHARGING_STATE({plug_prior}->{plug_curr})")
        if head_p != head_c:
            dev_score += 1.0
            dev_changes.append(f"WIRED_HEADSET_TOGGLE({head_p}->{head_c})")

        modality_evidence["device_relation"] = {
            "device_score": round(dev_score, 3),
            "plugged_prior": plug_prior,
            "plugged_curr": plug_curr,
            "status_prior": stat_p,
            "status_curr": stat_c,
        }

        if dev_score > 0.0:
            dominant_changes.append("device_relation")
            total_score += dev_score
            summary_parts.extend(dev_changes)

    # 5. Environment Change
    env_prior = prior.environment
    env_curr = curr.environment
    if "ambient_light_lux" in env_prior and "ambient_light_lux" in env_curr:
        lux_p = env_prior.get("ambient_light_lux")
        lux_c = env_curr.get("ambient_light_lux")
        press_p = env_prior.get("pressure_hpa")
        press_c = env_curr.get("pressure_hpa")

        env_score = 0.0
        if lux_p is not None and lux_c is not None:
            # Logarithmic lux ratio
            log_ratio = abs(math.log(float(lux_c) + 1.0) - math.log(float(lux_p) + 1.0))
            if log_ratio >= 1.5:
                env_score += min(1.2, log_ratio * 0.4)
                summary_parts.append(f"LIGHT_SHIFT({round(lux_p, 1)}lx->{round(lux_c, 1)}lx)")

        if press_p is not None and press_c is not None:
            press_diff = abs(float(press_c) - float(press_p))
            if press_diff >= 1.5:  # ~12m vertical movement
                env_score += min(1.0, press_diff * 0.3)
                summary_parts.append(f"BAROMETRIC_ALTITUDE_SHIFT(dp={round(press_diff, 2)}hPa)")

        modality_evidence["environment"] = {
            "env_score": round(env_score, 3),
            "lux_prior": lux_p,
            "lux_curr": lux_c,
            "pressure_prior": press_p,
            "pressure_curr": press_c,
        }

        if env_score >= 0.6:
            dominant_changes.append("environment")
            total_score += env_score

    # 6. Audio Context Change
    a_prior = prior.audio_context
    a_curr = curr.audio_context
    if "silence_likelihood" in a_prior and "silence_likelihood" in a_curr:
        sil_p = float(a_prior.get("silence_likelihood", 1.0))
        sil_c = float(a_curr.get("silence_likelihood", 1.0))
        rms_p = float(a_prior.get("rms_loudness", 0.0))
        rms_c = float(a_curr.get("rms_loudness", 0.0))

        d_sil = abs(sil_c - sil_p)
        audio_score = min(1.5, d_sil * 1.2 + abs(rms_c - rms_p) * 20.0)

        modality_evidence["audio_context"] = {
            "audio_score": round(audio_score, 3),
            "silence_delta": round(d_sil, 3),
            "rms_delta": round(abs(rms_c - rms_p), 5),
        }

        if audio_score >= 0.7 or d_sil >= 0.5:
            dominant_changes.append("audio_context")
            total_score += audio_score
            if sil_p > 0.7 and sil_c < 0.3:
                summary_parts.append("ACOUSTIC_ACTIVITY_ONSET")
            elif sil_p < 0.3 and sil_c > 0.7:
                summary_parts.append("ACOUSTIC_ACTIVITY_CESSATION")

    total_score = round(total_score, 3)

    # Threshold evaluation
    if total_score >= config.TRANSITION_SCORE_THRESHOLD or len(dominant_changes) > 0:
        ts_utc = curr.timestamp_utc
        h_payload = f"{prior.observation_id}:{curr.observation_id}:{total_score}:{':'.join(dominant_changes)}"
        t_hash = hashlib.sha256(h_payload.encode("utf-8")).hexdigest()[:12]
        trans_id = f"trans-{int(time.time() * 1000)}-{t_hash}"
        summary_str = " + ".join(summary_parts) if summary_parts else f"TRANSITION_SCORE_{total_score}"

        return PhysicalTransition(
            protocol=config.TRANSITION_PROTOCOL,
            transition_id=trans_id,
            timestamp_utc=ts_utc,
            prior_observation_id=prior.observation_id,
            subsequent_observation_id=curr.observation_id,
            transition_score=total_score,
            dominant_changes=dominant_changes,
            modality_evidence=modality_evidence,
            transition_summary=summary_str,
        )

    return None
