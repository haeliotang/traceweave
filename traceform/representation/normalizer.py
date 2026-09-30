"""
Multimodal Normalizer for Physical Observations.

Fits robust scaling parameters strictly on the Day 1–7 discovery window,
producing standardized numerical vectors and canonical topological descriptors.
"""

from __future__ import annotations

from dataclasses import asdict, dataclass
import math
import statistics
from typing import Any, Dict, List, Optional, Tuple


CONTINUOUS_FEATURE_NAMES = [
    # Motion features
    "motion_accel_magnitude",
    "motion_accel_rms",
    "motion_energy",
    "motion_stationary_likelihood",
    # Environment features
    "env_log_lux",
    "env_pressure_hpa",
    "env_device_temp_c",
    # Device state features
    "device_battery_fraction",
    "device_is_plugged",
    "device_is_charging",
    # Audio context features
    "audio_silence_likelihood",
    "audio_rms_loudness",
    "audio_spectral_entropy",
    # Connectivity metrics
    "conn_wifi_connected",
    "conn_wifi_rssi_norm",
    # Digital & Cognitive features (16-18)
    "screen_on_ratio",
    "app_switch_rate",
    "unlock_burst_count",
    # Headset & Immersion (19)
    "audio_immersion_state",
    # Circadian phase (20)
    "circadian_sin_phase",
]


@dataclass
class NormalizationParams:
    feature_names: List[str]
    medians: Dict[str, float]
    iqrs: Dict[str, float]
    fitted_observation_count: int
    fit_window_description: str

    def to_dict(self) -> Dict[str, Any]:
        return asdict(self)

    @classmethod
    def from_dict(cls, d: Dict[str, Any]) -> "NormalizationParams":
        return cls(
            feature_names=d["feature_names"],
            medians=d["medians"],
            iqrs=d["iqrs"],
            fitted_observation_count=d["fitted_observation_count"],
            fit_window_description=d["fit_window_description"],
        )


class MultimodalNormalizer:
    def __init__(self, params: Optional[NormalizationParams] = None):
        self.params = params

    def to_dict(self) -> Dict[str, Any]:
        return self.params.to_dict() if self.params else {}

    @classmethod
    def from_dict(cls, d: Dict[str, Any]) -> "MultimodalNormalizer":
        params = NormalizationParams.from_dict(d)
        return cls(params=params)

    def extract_raw_features(self, obs: Dict[str, Any]) -> Tuple[Dict[str, float], Dict[str, str]]:
        """
        Extracts raw continuous features and topological categorical keys from an observation frame.
        """
        # 1. Motion
        mot = obs.get("motion", {})
        accel_mag = float(mot.get("accel_magnitude", 9.8))
        accel_rms = float(mot.get("accel_rms", 5.6))
        mot_energy = float(mot.get("motion_energy", 0.0))
        stat_like = float(mot.get("stationary_likelihood", 1.0))

        # 2. Environment
        env = obs.get("environment", {})
        lux_raw = env.get("ambient_light_lux")
        lux_val = float(lux_raw) if lux_raw is not None else 0.0
        log_lux = math.log(max(0.0, lux_val) + 1.0)
        pressure = float(env.get("pressure_hpa", 915.0) if env.get("pressure_hpa") is not None else 915.0)
        dev_temp = float(env.get("device_temp_c", 30.0) if env.get("device_temp_c") is not None else 30.0)

        # 3. Device Relation
        dev = obs.get("device_relation", {})
        bat_raw = dev.get("battery_percentage")
        battery_frac = (float(bat_raw) / 100.0) if bat_raw is not None else 0.5
        plugged_str = str(dev.get("plugged", "UNPLUGGED"))
        is_plugged = 0.0 if plugged_str == "UNPLUGGED" else 1.0
        status_str = str(dev.get("status", "DISCHARGING"))
        is_charging = 1.0 if status_str == "CHARGING" else 0.0

        # 4. Audio Context
        aud = obs.get("audio_context", {})
        if aud.get("status") == "NOT_SAMPLED":
            silence_like = 1.0
            rms_loudness = 0.0
            spectral_entropy = 0.0
        else:
            silence_like = float(aud.get("silence_likelihood", 1.0))
            rms_loudness = float(aud.get("rms_loudness", 0.0))
            spectral_entropy = float(aud.get("spectral_entropy", 0.0))

        # 5. Connectivity
        conn = obs.get("connectivity", {})
        wifi_conn = 1.0 if conn.get("wifi_connected") else 0.0
        wifi_rssi_raw = conn.get("wifi_rssi")
        # Normalize RSSI [-100, -30] to [0.0, 1.0]
        if wifi_rssi_raw is not None:
            rssi_val = float(wifi_rssi_raw)
            wifi_rssi_norm = max(0.0, min(1.0, (rssi_val + 100.0) / 70.0))
        else:
            wifi_rssi_norm = 0.0

        # 6. Digital & Cognitive Features (16-18)
        dig = obs.get("digital_context", {})
        screen_ratio = float(dig.get("screen_on_ratio", 0.0))
        # Fallback to device_relation.screen_state if available
        if "screen_on_ratio" not in dig:
            screen_state = dev.get("screen_state", "UNKNOWN")
            if screen_state == "ON":
                screen_ratio = 1.0
            elif screen_state == "DOZING":
                screen_ratio = 0.2
            else:
                screen_ratio = 0.0
        app_switch_rate = float(dig.get("app_switch_rate", 0.0))
        unlock_burst_count = float(dig.get("unlock_burst_count", 0.0))

        # 7. Audio Immersion State (19)
        # 1.0 if wired headset or bluetooth A2DP audio active
        is_immersed = 1.0 if (dev.get("wired_headset") or dev.get("bluetooth_a2dp")) else 0.0

        # 8. Circadian Sine Phase (20)
        # Smooth continuous mapping of daily solar cycle in [-1.0, 1.0]
        ts_str = obs.get("timestamp_utc", "")
        sin_phase = 0.0
        if ts_str:
            try:
                import datetime
                dt = datetime.datetime.fromisoformat(ts_str.replace("Z", "+00:00"))
                seconds_in_day = dt.hour * 3600 + dt.minute * 60 + dt.second
                sin_phase = math.sin(2.0 * math.pi * (seconds_in_day / 86400.0))
            except Exception:
                pass

        continuous_map: Dict[str, float] = {
            "motion_accel_magnitude": accel_mag,
            "motion_accel_rms": accel_rms,
            "motion_energy": mot_energy,
            "motion_stationary_likelihood": stat_like,
            "env_log_lux": log_lux,
            "env_pressure_hpa": pressure,
            "env_device_temp_c": dev_temp,
            "device_battery_fraction": battery_frac,
            "device_is_plugged": is_plugged,
            "device_is_charging": is_charging,
            "audio_silence_likelihood": silence_like,
            "audio_rms_loudness": rms_loudness,
            "audio_spectral_entropy": spectral_entropy,
            "conn_wifi_connected": wifi_conn,
            "conn_wifi_rssi_norm": wifi_rssi_norm,
            "screen_on_ratio": screen_ratio,
            "app_switch_rate": app_switch_rate,
            "unlock_burst_count": unlock_burst_count,
            "audio_immersion_state": is_immersed,
            "circadian_sin_phase": sin_phase,
        }

        # Topological & Categorical identifiers
        plc = obs.get("place", {})
        spatial_cell = plc.get("spatial_cell", "NO_GEO")
        wifi_fp = conn.get("wifi_fingerprint") or conn.get("wifi_environment_fingerprint") or "NO_WIFI"
        cell_fp = conn.get("cellular_cell_fingerprint") or "NO_CELL"

        topological_map: Dict[str, str] = {
            "spatial_cell": spatial_cell,
            "wifi_fingerprint": wifi_fp,
            "cellular_fingerprint": cell_fp,
            "plugged_state": plugged_str,
            "charging_status": status_str,
        }

        return continuous_map, topological_map

    def fit_discovery_window(self, observations: List[Dict[str, Any]], description: str = "Day 1–7 Discovery Window") -> NormalizationParams:
        """
        Computes median and IQR scaling parameters across observations.
        """
        if not observations:
            raise ValueError("Cannot fit normalizer on empty observation list.")

        feature_values: Dict[str, List[float]] = {name: [] for name in CONTINUOUS_FEATURE_NAMES}

        for obs in observations:
            c_map, _ = self.extract_raw_features(obs)
            for k, v in c_map.items():
                feature_values[k].append(v)

        medians: Dict[str, float] = {}
        iqrs: Dict[str, float] = {}

        for k in CONTINUOUS_FEATURE_NAMES:
            vals = sorted(feature_values[k])
            med = statistics.median(vals)
            n = len(vals)
            q25 = vals[int(n * 0.25)]
            q75 = vals[int(n * 0.75)]
            iqr = q75 - q25
            # Prevent division by zero
            if iqr < 1e-6:
                # If IQR is effectively zero, fall back to max - min or std or default 1.0
                diff = vals[-1] - vals[0]
                iqr = diff if diff > 1e-6 else 1.0

            medians[k] = round(float(med), 6)
            iqrs[k] = round(float(iqr), 6)

        self.params = NormalizationParams(
            feature_names=CONTINUOUS_FEATURE_NAMES,
            medians=medians,
            iqrs=iqrs,
            fitted_observation_count=len(observations),
            fit_window_description=description,
        )
        return self.params

    def transform(self, obs: Dict[str, Any]) -> Tuple[List[float], Dict[str, str]]:
        """
        Transforms a single observation into a standardized continuous feature vector
        and canonical topological descriptors.
        """
        if self.params is None:
            raise RuntimeError("MultimodalNormalizer must be fitted or initialized with NormalizationParams before transform.")

        c_map, topo_map = self.extract_raw_features(obs)
        vector: List[float] = []

        for k in self.params.feature_names:
            raw_val = c_map.get(k, 0.0)
            med = self.params.medians.get(k, 0.0)
            iqr = self.params.iqrs.get(k, 1.0)
            if iqr < 1e-6:
                iqr = 1.0
            # Robust scaling: (x - median) / IQR
            scaled = (raw_val - med) / iqr
            # Bound extremes to [-10.0, 10.0] to prevent outlier distortion
            bounded = max(-10.0, min(10.0, scaled))
            vector.append(round(bounded, 5))

        return vector, topo_map
