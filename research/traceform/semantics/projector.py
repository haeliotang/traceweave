# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

"""
Candidate Semantic Projector.

Performs physical reverse projection of discovered latent structures
(micro-states Sk, macro-regimes Rj, motifs Mm) into objective physical observables
and constructs quarantined post-hoc candidate semantic hypotheses.
"""

from __future__ import annotations

from collections import Counter
from dataclasses import asdict, dataclass
import datetime
import statistics
from typing import Any, Dict, List, Tuple


@dataclass
class PhysicalObservableProfile:
    state_token: str
    observation_count: int
    total_duration_hours: float
    stationary_ratio: float
    mean_motion_energy: float
    mean_accel_rms: float
    median_light_lux: float
    darkness_ratio: float
    mean_pressure_hpa: float
    plugged_ac_ratio: float
    plugged_usb_ratio: float
    unplugged_ratio: float
    charging_ratio: float
    mean_battery_percentage: float
    mean_battery_temp_c: float
    top_wifi_ap: str
    wifi_concentration_ratio: float
    night_ratio: float
    day_ratio: float
    peak_hours_utc: List[int]
    physical_signature: str
    candidate_semantic_hypothesis: str
    hypothesis_confidence: float
    epistemic_quarantine: str = (
        "HYPOTHESIS_ONLY: Inferred post-hoc from objective observables; never used in structure discovery."
    )

    def to_dict(self) -> Dict[str, Any]:
        return asdict(self)


class CandidateSemanticProjector:
    def __init__(self):
        pass

    @staticmethod
    def _parse_utc_hour(iso_ts: str) -> int:
        s = iso_ts.strip().replace("Z", "+00:00")
        try:
            dt = datetime.datetime.fromisoformat(s)
            return dt.hour
        except Exception:
            return 0

    def compute_state_physical_profile(
        self,
        state_token: str,
        state_records: List[Dict[str, Any]],
        raw_obs_map: Dict[str, Dict[str, Any]],
    ) -> PhysicalObservableProfile:
        """
        Aggregates empirical physical sensor readings for all frames associated with state_token.
        """
        obs_count = len(state_records)
        if obs_count == 0:
            raise ValueError(f"No records provided for state {state_token}")

        stationary_count = 0
        motion_energies: List[float] = []
        accel_rmss: List[float] = []
        lux_values: List[float] = []
        dark_count = 0
        pressures: List[float] = []
        battery_pcts: List[float] = []
        battery_temps: List[float] = []
        plugged_ac_count = 0
        plugged_usb_count = 0
        unplugged_count = 0
        charging_count = 0
        wifi_aps: List[str] = []
        hours_utc: List[int] = []

        def _safe_float(val: Any, default: float) -> float:
            if val is None:
                return default
            try:
                return float(val)
            except (ValueError, TypeError):
                return default

        for r in state_records:
            obs_id = r["observation_id"]
            raw = raw_obs_map.get(obs_id, {})

            # Motion
            motion = raw.get("motion", {})
            st_lik = _safe_float(motion.get("stationary_likelihood"), 1.0)
            if st_lik >= 0.8:
                stationary_count += 1
            motion_energies.append(_safe_float(motion.get("motion_energy"), 0.0))
            accel_rmss.append(_safe_float(motion.get("accel_rms"), 0.0))

            # Environment
            env = raw.get("environment", {})
            lux = _safe_float(env.get("ambient_light_lux"), 0.0)
            lux_values.append(lux)
            if lux <= 2.0:
                dark_count += 1
            pressures.append(_safe_float(env.get("pressure_hpa"), 1013.25))

            # Device / Power
            dev = raw.get("device_relation", {})
            plug = str(dev.get("plugged") or "UNPLUGGED").upper()
            if "AC" in plug:
                plugged_ac_count += 1
            elif "USB" in plug:
                plugged_usb_count += 1
            else:
                unplugged_count += 1

            status = str(dev.get("status") or "DISCHARGING").upper()
            if "CHARGING" in status and "NOT" not in status and "DIS" not in status:
                charging_count += 1

            battery_pcts.append(_safe_float(dev.get("battery_percentage"), 50.0))
            battery_temps.append(_safe_float(dev.get("battery_temp_c"), 25.0))

            # Connectivity
            conn = raw.get("connectivity", {})
            wf = (
                conn.get("wifi_fingerprint")
                or conn.get("wifi_environment_fingerprint")
                or "NO_WIFI"
            )
            wifi_aps.append(str(wf)[:10])

            # Diurnal Timing
            ts_str = r.get("timestamp_utc") or raw.get("timestamp_utc", "")
            if ts_str:
                hours_utc.append(self._parse_utc_hour(ts_str))

        # Statistical summaries
        stat_ratio = round(stationary_count / obs_count, 4)
        mean_energy = round(
            statistics.mean(motion_energies) if motion_energies else 0.0, 4
        )
        mean_accel = round(statistics.mean(accel_rmss) if accel_rmss else 0.0, 4)
        med_lux = round(statistics.median(lux_values) if lux_values else 0.0, 2)
        dark_ratio = round(dark_count / obs_count, 4)
        mean_p = round(statistics.mean(pressures) if pressures else 1013.25, 2)
        ac_ratio = round(plugged_ac_count / obs_count, 4)
        usb_ratio = round(plugged_usb_count / obs_count, 4)
        unplug_ratio = round(unplugged_count / obs_count, 4)
        chg_ratio = round(charging_count / obs_count, 4)
        mean_bat = round(statistics.mean(battery_pcts) if battery_pcts else 50.0, 1)
        mean_temp = round(statistics.mean(battery_temps) if battery_temps else 25.0, 1)

        # WiFi concentration
        wifi_counts = Counter(wifi_aps)
        top_wifi, top_wifi_freq = (
            wifi_counts.most_common(1)[0] if wifi_counts else ("UNKNOWN", 0)
        )
        wifi_conc = round(top_wifi_freq / obs_count, 4)

        # Diurnal distribution
        # Define night as 21:00 UTC to 06:00 UTC (9 hours) and day as 06:00 to 21:00 (15 hours)
        night_hours_count = sum(1 for h in hours_utc if h >= 21 or h < 6)
        day_hours_count = obs_count - night_hours_count
        night_ratio = round(night_hours_count / obs_count, 4)
        day_ratio = round(day_hours_count / obs_count, 4)

        hour_counts = Counter(hours_utc)
        peak_hours = [h for h, _ in hour_counts.most_common(3)]

        # Physical Signature construction
        kinematic_tag = (
            "STATIONARY"
            if stat_ratio >= 0.80
            else ("LOW_MOTION" if stat_ratio >= 0.50 else "MOBILE")
        )
        power_tag = (
            "AC_PLUGGED"
            if ac_ratio >= 0.50
            else ("USB_PLUGGED" if usb_ratio >= 0.50 else "UNPLUGGED")
        )
        light_tag = (
            "DARK" if dark_ratio >= 0.60 else ("DIM" if med_lux < 25.0 else "BRIGHT")
        )
        diurnal_tag = (
            "NOCTURNAL"
            if night_ratio >= 0.60
            else ("DIURNAL" if day_ratio >= 0.60 else "BIMODAL")
        )
        rf_tag = f"RF_{top_wifi}"

        phys_sig = f"{diurnal_tag}_{kinematic_tag}_{power_tag}_{light_tag}_{rf_tag}"

        # Candidate Semantic Hypothesis generation (Strictly post-hoc rule synthesis)
        hypothesis, conf = self._synthesize_hypothesis(
            stat_ratio=stat_ratio,
            ac_ratio=ac_ratio,
            usb_ratio=usb_ratio,
            unplug_ratio=unplug_ratio,
            dark_ratio=dark_ratio,
            night_ratio=night_ratio,
            day_ratio=day_ratio,
            wifi_conc=wifi_conc,
            mean_energy=mean_energy,
        )

        # Estimate duration in hours: ~30 seconds nominal per observation frame
        duration_hours = round(obs_count * 30.0 / 3600.0, 2)

        return PhysicalObservableProfile(
            state_token=state_token,
            observation_count=obs_count,
            total_duration_hours=duration_hours,
            stationary_ratio=stat_ratio,
            mean_motion_energy=mean_energy,
            mean_accel_rms=mean_accel,
            median_light_lux=med_lux,
            darkness_ratio=dark_ratio,
            mean_pressure_hpa=mean_p,
            plugged_ac_ratio=ac_ratio,
            plugged_usb_ratio=usb_ratio,
            unplugged_ratio=unplug_ratio,
            charging_ratio=chg_ratio,
            mean_battery_percentage=mean_bat,
            mean_battery_temp_c=mean_temp,
            top_wifi_ap=top_wifi,
            wifi_concentration_ratio=wifi_conc,
            night_ratio=night_ratio,
            day_ratio=day_ratio,
            peak_hours_utc=peak_hours,
            physical_signature=phys_sig,
            candidate_semantic_hypothesis=hypothesis,
            hypothesis_confidence=conf,
        )

    @staticmethod
    def _synthesize_hypothesis(
        stat_ratio: float,
        ac_ratio: float,
        usb_ratio: float,
        unplug_ratio: float,
        dark_ratio: float,
        night_ratio: float,
        day_ratio: float,
        wifi_conc: float,
        mean_energy: float,
    ) -> Tuple[str, float]:
        """
        Synthesizes objective behavioral hypothesis with explicit confidence score.
        """
        # Nocturnal sleep / rest anchor
        if (
            stat_ratio >= 0.85
            and (ac_ratio >= 0.50 or dark_ratio >= 0.70)
            and night_ratio >= 0.55
        ):
            conf = min(
                0.95, round(stat_ratio * 0.4 + dark_ratio * 0.3 + night_ratio * 0.3, 2)
            )
            return (
                "Prolonged Nocturnal Rest / In-bed Charging (Sleep Anchor Candidate)",
                conf,
            )

        # Diurnal stationary work / desk session
        if (
            stat_ratio >= 0.80
            and unplug_ratio >= 0.60
            and day_ratio >= 0.60
            and wifi_conc >= 0.70
        ):
            conf = min(
                0.90, round(stat_ratio * 0.4 + day_ratio * 0.3 + wifi_conc * 0.3, 2)
            )
            return (
                "Diurnal Stationary Indoor Activity / Desk Work (Unplugged Office/Home)",
                conf,
            )

        # Diurnal powered desk / charging workstation
        if (
            stat_ratio >= 0.80
            and (ac_ratio >= 0.50 or usb_ratio >= 0.50)
            and day_ratio >= 0.60
        ):
            conf = min(
                0.90,
                round(
                    stat_ratio * 0.4 + day_ratio * 0.3 + (ac_ratio + usb_ratio) * 0.3, 2
                ),
            )
            return (
                "Diurnal Stationary Powered Operation (Workstation / Charging Desk)",
                conf,
            )

        # Active physical locomotion / commute
        if stat_ratio < 0.65 or mean_energy > 0.15:
            conf = min(
                0.85,
                round((1.0 - stat_ratio) * 0.5 + min(1.0, mean_energy * 3.0) * 0.5, 2),
            )
            return (
                "Active Physical Locomotion / Kinematic Transit (Walking or Commute Candidate)",
                conf,
            )

        # Stationary transient / pause
        if stat_ratio >= 0.75:
            return "Quasi-Stationary Intermediate Pause / Ambient Holding State", 0.70

        return "Unclassified Dynamic State / Ambient Physical Flux", 0.50

    def project_macro_regimes(
        self,
        regimes_list: List[Dict[str, Any]],
        state_profiles: Dict[str, PhysicalObservableProfile],
    ) -> List[Dict[str, Any]]:
        """
        Projects macro-regimes (R01..RJ) into temporal and behavioral context.
        """
        projected = []
        for reg in regimes_list:
            r_token = reg["regime_token"]
            dominant_states = reg.get("dominant_states", [])
            dur_frames = reg.get("duration_frames", 0)
            dur_hours = round(dur_frames * 30.0 / 3600.0, 2)

            top_state = dominant_states[0][0] if dominant_states else "UNKNOWN"
            top_state_share = dominant_states[0][1] if dominant_states else 0.0
            prof = state_profiles.get(top_state)

            if prof:
                regime_hypothesis = f"Sustained {prof.candidate_semantic_hypothesis} (Dominant: {top_state} {top_state_share*100:.1f}%)"
                regime_physical_type = prof.physical_signature
            else:
                regime_hypothesis = f"Composite Regime dominated by {top_state}"
                regime_physical_type = "UNKNOWN"

            projected.append(
                {
                    "regime_token": r_token,
                    "start_utc": reg.get("start_utc"),
                    "end_utc": reg.get("end_utc"),
                    "duration_hours": dur_hours,
                    "dominant_state": top_state,
                    "dominant_share": top_state_share,
                    "physical_signature": regime_physical_type,
                    "candidate_regime_interpretation": regime_hypothesis,
                }
            )
        return projected

    def project_motifs(
        self,
        motifs_list: List[Dict[str, Any]],
        state_profiles: Dict[str, PhysicalObservableProfile],
    ) -> List[Dict[str, Any]]:
        """
        Projects trajectory motifs (M01..MM) into behavioral sequence interpretations.
        """
        projected = []
        for m in motifs_list:
            m_token = m.get("motif_token", "")
            pattern = m.get("pattern", [])
            support = m.get("support", 0)

            # Summarize transition dynamics in pattern
            unique_states = list(dict.fromkeys(pattern))
            state_descs = [
                (
                    f"{st} ({state_profiles[st].candidate_semantic_hypothesis.split('(')[0].strip()})"
                    if st in state_profiles
                    else st
                )
                for st in unique_states
            ]
            seq_meaning = " \u2192 ".join(state_descs)

            projected.append(
                {
                    "motif_token": m_token,
                    "pattern": " -> ".join(pattern),
                    "support": support,
                    "unique_state_count": len(unique_states),
                    "candidate_sequence_interpretation": seq_meaning,
                }
            )
        return projected
