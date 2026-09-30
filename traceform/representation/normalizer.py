"""
Multimodal Normalizer for Physical Observations.

Fits robust scaling parameters strictly on the Day 1–7 discovery window,
producing standardized numerical vectors and canonical topological descriptors.
"""

from __future__ import annotations

from collections import deque
from dataclasses import asdict, dataclass
import math
import statistics
from typing import Any, Dict, List, Optional, Tuple


CONTINUOUS_FEATURE_NAMES = [
    # Motion features (1-4)
    "motion_accel_magnitude",
    "motion_accel_rms",
    "motion_energy",
    "motion_stationary_likelihood",
    # Environment features (5-7)
    "env_log_lux",
    "env_pressure_hpa",
    "env_device_temp_c",
    # Device state features (8-10)
    "device_battery_fraction",
    "device_is_plugged",
    "device_is_charging",
    # Audio context features (11-13)
    "audio_silence_likelihood",
    "audio_rms_loudness",
    "audio_spectral_entropy",
    # Connectivity metrics (14-15)
    "conn_wifi_connected",
    "conn_wifi_rssi_norm",
    # Digital & Cognitive features (16-18)
    "screen_on_ratio",
    "app_switch_rate",
    "unlock_burst_count",
    # Headset & Immersion (19)
    "audio_immersion_state",
    # Circadian 24-hr sine phase (20)
    "circadian_sin_phase",
    # Circadian 24-hr cosine phase (21)
    "circadian_cos_phase",
    # Infradian 7-day weekly phase (22)
    "infradian_weekly_phase",
    # Battery instantaneous current in microamperes (23)
    "battery_current_ua",
    # Spatial radius of gyration in km (24)
    "spatial_radius_of_gyration",
    # Touch interaction motor-cadence velocity (25)
    "touch_interaction_velocity",
    # Ambient social BLE / RF density (26)
    "ambient_social_ble_density",
    # Physiological micro-tremor & ballistocardiogram (27)
    "physio_ballisto_tremor_energy",
    # Net thermal conduction gradient rate (28)
    "thermal_conduction_rate_dt",
    # 3D gravity tilt zenith angle (29)
    "device_zenith_tilt_angle",
    # Geomagnetic indoor structural anomaly flux (30)
    "geomagnetic_flux_divergence",
    # Barometric vertical ascent/descent velocity (31)
    "barometric_vertical_velocity",
    # Ambient RF Shannon information entropy (32)
    "ambient_rf_shannon_entropy",
    # Cellular base station handover churn rate (33)
    "cellular_tower_churn_rate",
    # Touch motor interval jitter / CV (34)
    "touch_interval_jitter",
    # Multitask attention fragmentation entropy (35)
    "attention_fragmentation_entropy",
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


def safe_float(val: Any, default: float) -> float:
    """Safely converts arbitrary input to bounded float without raising TypeError or ValueError."""
    if val is None:
        return default
    try:
        f = float(val)
        return default if math.isnan(f) or math.isinf(f) else f
    except (ValueError, TypeError):
        return default


class MultimodalNormalizer:
    def __init__(self, params: Optional[NormalizationParams] = None):
        self.params = params
        self._geo_window: deque[Tuple[float, float]] = deque(maxlen=60)
        self._last_pressure: Optional[float] = None
        self._last_temp: Optional[float] = None
        self._last_ts_seconds: Optional[float] = None
        self._cell_history: deque[Tuple[float, str]] = deque(maxlen=100)

    def to_dict(self) -> Dict[str, Any]:
        return self.params.to_dict() if self.params else {}

    @classmethod
    def from_dict(cls, d: Dict[str, Any]) -> "MultimodalNormalizer":
        params = NormalizationParams.from_dict(d)
        return cls(params=params)

    def reset_geo_window(self) -> None:
        """Resets the sliding spatial coordinate buffer for radius of gyration."""
        self._geo_window.clear()

    def reset_state(self) -> None:
        """Resets all sliding buffers and derivative states across reboots or discontinuous sessions."""
        self._geo_window.clear()
        self._last_pressure = None
        self._last_temp = None
        self._last_ts_seconds = None
        self._cell_history.clear()

    @staticmethod
    def haversine_km(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
        """Computes great-circle distance between two GPS coordinates in kilometers."""
        phi1, phi2 = math.radians(lat1), math.radians(lat2)
        dphi = math.radians(lat2 - lat1)
        dlambda = math.radians(lon2 - lon1)
        a = math.sin(dphi / 2.0) ** 2 + math.cos(phi1) * math.cos(phi2) * math.sin(dlambda / 2.0) ** 2
        return 2.0 * 6371.0 * math.asin(math.sqrt(min(1.0, max(0.0, a))))

    def extract_raw_features(self, obs: Dict[str, Any]) -> Tuple[Dict[str, float], Dict[str, str]]:
        """
        Extracts raw continuous features and topological categorical keys from an observation frame.
        """
        # 1. Motion
        mot = obs.get("motion", {})
        accel_mag = safe_float(mot.get("accel_magnitude"), 9.8)
        accel_rms = safe_float(mot.get("accel_rms"), 5.6)
        mot_energy = safe_float(mot.get("motion_energy"), 0.0)
        stat_like = safe_float(mot.get("stationary_likelihood"), 1.0)

        # 2. Environment
        env = obs.get("environment", {})
        lux_val = safe_float(env.get("ambient_light_lux"), 0.0)
        log_lux = math.log(max(0.0, lux_val) + 1.0)
        pressure = safe_float(env.get("pressure_hpa"), 915.0)
        dev_temp = safe_float(env.get("device_temp_c"), 30.0)

        # 3. Device Relation
        dev = obs.get("device_relation", {})
        bat_raw = dev.get("battery_percentage")
        battery_frac = (safe_float(bat_raw, 50.0) / 100.0) if bat_raw is not None else 0.5
        plugged_str = str(dev.get("plugged", "UNPLUGGED"))
        is_plugged = 0.0 if plugged_str == "UNPLUGGED" else 1.0
        status_str = str(dev.get("status", "DISCHARGING"))
        is_charging = 1.0 if status_str == "CHARGING" else 0.0

        # Microampere current (23) - signed physical rate of discharge/charge
        battery_current_ua = safe_float(dev.get("current_ua"), 0.0)

        # 4. Audio Context
        aud = obs.get("audio_context", {})
        if aud.get("status") == "NOT_SAMPLED":
            silence_like = 1.0
            rms_loudness = 0.0
            spectral_entropy = 0.0
        else:
            silence_like = safe_float(aud.get("silence_likelihood"), 1.0)
            rms_loudness = safe_float(aud.get("rms_loudness"), 0.0)
            spectral_entropy = safe_float(aud.get("spectral_entropy"), 0.0)

        # 5. Connectivity
        conn = obs.get("connectivity", {})
        wifi_conn = 1.0 if conn.get("wifi_connected") else 0.0
        wifi_rssi_raw = conn.get("wifi_rssi")
        # Normalize RSSI [-100, -30] to [0.0, 1.0]
        if wifi_rssi_raw is not None:
            rssi_val = safe_float(wifi_rssi_raw, -100.0)
            wifi_rssi_norm = max(0.0, min(1.0, (rssi_val + 100.0) / 70.0))
        else:
            wifi_rssi_norm = 0.0

        # 6. Digital & Cognitive Features (16-18, 25-26)
        dig = obs.get("digital_context", {})
        screen_ratio = safe_float(dig.get("screen_on_ratio"), 0.0)
        # Fallback to device_relation.screen_state if available
        if "screen_on_ratio" not in dig:
            screen_state = dev.get("screen_state", "UNKNOWN")
            if screen_state == "ON":
                screen_ratio = 1.0
            elif screen_state == "DOZING":
                screen_ratio = 0.2
            else:
                screen_ratio = 0.0
        app_switch_rate = safe_float(dig.get("app_switch_rate"), 0.0)
        unlock_burst_count = safe_float(dig.get("unlock_burst_count"), 0.0)

        # Touch interaction velocity (25)
        touch_velocity = safe_float(dig.get("touch_interaction_velocity"), 0.0)
        if "touch_interaction_velocity" not in dig:
            if screen_ratio > 0.0:
                touch_velocity = round(min(10.0, 1.0 + app_switch_rate * 0.4 + unlock_burst_count * 0.3), 3)
            else:
                touch_velocity = 0.0

        # Ambient social BLE / RF density (26)
        ambient_ble = safe_float(dig.get("ambient_social_ble_density"), 0.0)
        if "ambient_social_ble_density" not in dig:
            wifi_cnt = safe_float(conn.get("wifi_ap_count"), 0.0)
            if wifi_cnt > 0:
                ambient_ble = round(min(30.0, wifi_cnt / 3.0), 2)
            else:
                ambient_ble = 0.0

        # 7. Audio Immersion State (19)
        # 1.0 if wired headset or bluetooth A2DP audio active
        is_immersed = 1.0 if (dev.get("wired_headset") or dev.get("bluetooth_a2dp")) else 0.0

        # 8. Circadian & Infradian Temporal Phases (20-22)
        # Smooth continuous mapping of daily solar cycle and 7-day infradian cycle
        ts_str = obs.get("timestamp_utc", "")
        sin_phase = 0.0
        cos_phase = 1.0
        weekly_phase = 0.0
        current_ts_seconds: Optional[float] = None
        if ts_str:
            try:
                import datetime
                dt = datetime.datetime.fromisoformat(ts_str.replace("Z", "+00:00"))
                seconds_in_day = dt.hour * 3600 + dt.minute * 60 + dt.second + dt.microsecond / 1e6
                daily_fraction = seconds_in_day / 86400.0
                sin_phase = math.sin(2.0 * math.pi * daily_fraction)
                cos_phase = math.cos(2.0 * math.pi * daily_fraction)
                weekly_fraction = (dt.weekday() + daily_fraction) / 7.0
                weekly_phase = math.sin(2.0 * math.pi * weekly_fraction)
                current_ts_seconds = dt.timestamp()
            except Exception:
                pass

        # 9. Spatial Radius of Gyration Rg in km (24)
        plc = obs.get("place", {})
        rg_direct = plc.get("radius_of_gyration")
        if rg_direct is not None:
            radius_of_gyration = safe_float(rg_direct, 0.05)
        else:
            lat = plc.get("latitude")
            lon = plc.get("longitude")
            if lat is not None and lon is not None:
                try:
                    flat = float(lat)
                    flon = float(lon)
                    if not (math.isnan(flat) or math.isnan(flon)):
                        self._geo_window.append((flat, flon))
                except (ValueError, TypeError):
                    pass

            if len(self._geo_window) >= 2:
                mean_lat = sum(p[0] for p in self._geo_window) / len(self._geo_window)
                mean_lon = sum(p[1] for p in self._geo_window) / len(self._geo_window)
                sq_dists = [
                    self.haversine_km(p[0], p[1], mean_lat, mean_lon) ** 2
                    for p in self._geo_window
                ]
                radius_of_gyration = round(math.sqrt(sum(sq_dists) / len(sq_dists)), 4)
            else:
                # Nominal indoor micro-oscillation fallback (Defect 1)
                radius_of_gyration = 0.05

        # 10. Physiological micro-tremor & ballistocardiogram (27)
        physio_direct = mot.get("ballisto_energy")
        if physio_direct is not None:
            physio_tremor = safe_float(physio_direct, 0.0)
        else:
            gyro_energy = safe_float(mot.get("gyro_energy"), 0.0)
            if stat_like >= 0.8:
                physio_tremor = round(min(1.0, mot_energy * stat_like * max(0.0, 1.0 - 50.0 * gyro_energy)), 4)
            else:
                physio_tremor = 0.0

        # 11. Net thermal conduction gradient rate (28) in deg C / hour
        conduction_rate = 0.0
        if self._last_temp is not None and self._last_ts_seconds is not None and current_ts_seconds is not None:
            dt_s = current_ts_seconds - self._last_ts_seconds
            if 5.0 <= dt_s <= 3600.0:
                raw_rate = ((dev_temp - self._last_temp) / dt_s) * 3600.0
                internal_bias = (1.5 if is_charging > 0.0 else 0.0) + (0.8 if screen_ratio > 0.0 else 0.0)
                conduction_rate = round(max(-10.0, min(10.0, raw_rate - internal_bias)), 3)

        # 12. 3D gravity tilt zenith angle (29) in degrees [0, 180]
        accel_raw = mot.get("accel_raw")
        if accel_raw and isinstance(accel_raw, (list, tuple)) and len(accel_raw) >= 3:
            ax = safe_float(accel_raw[0], 0.0)
            ay = safe_float(accel_raw[1], 0.0)
            az = safe_float(accel_raw[2], 9.8)
            norm = math.sqrt(ax * ax + ay * ay + az * az)
            if norm > 0.1:
                cos_zenith = max(-1.0, min(1.0, az / norm))
                tilt_angle = round(math.degrees(math.acos(cos_zenith)), 2)
            else:
                tilt_angle = 0.0
        else:
            tilt_angle = 0.0

        # 13. Geomagnetic indoor structural anomaly flux (30) in uT
        mag_raw = mot.get("mag_raw") or env.get("magnetic_field_ut")
        if mag_raw is not None:
            if isinstance(mag_raw, (list, tuple)) and len(mag_raw) >= 3:
                bx = safe_float(mag_raw[0], 0.0)
                by = safe_float(mag_raw[1], 0.0)
                bz = safe_float(mag_raw[2], 45.0)
                b_mag = math.sqrt(bx * bx + by * by + bz * bz)
                mag_divergence = round(abs(b_mag - 45.0), 2)
            else:
                mag_divergence = round(abs(safe_float(mag_raw, 45.0) - 45.0), 2)
        else:
            wifi_cnt = safe_float(conn.get("wifi_ap_count"), 0.0)
            mag_divergence = round(min(50.0, math.log(1.0 + wifi_cnt) * 6.5), 2)

        # 14. Barometric vertical ascent/descent velocity (31) in m/s
        vertical_velocity = 0.0
        if self._last_pressure is not None and self._last_ts_seconds is not None and current_ts_seconds is not None:
            dt_s = current_ts_seconds - self._last_ts_seconds
            if 5.0 <= dt_s <= 600.0:
                dp = pressure - self._last_pressure
                vz_calc = -8.43 * (dp / dt_s)
                vertical_velocity = round(max(-5.0, min(5.0, vz_calc)), 3)

        # 15. Ambient RF Shannon information entropy (32) in bits [0, 5]
        rf_ent_direct = dig.get("ambient_rf_shannon_entropy")
        if rf_ent_direct is not None:
            ambient_rf_entropy = safe_float(rf_ent_direct, 0.0)
        else:
            wifi_cnt = safe_float(conn.get("wifi_ap_count"), 0.0)
            ambient_rf_entropy = round(min(5.0, math.log2(wifi_cnt + 1.0) * 0.85), 3) if wifi_cnt > 0 else 0.0

        # 16. Cellular base station handover churn rate (33) in handovers/hr
        cell_fp = conn.get("cellular_cell_fingerprint") or "NO_CELL"
        if current_ts_seconds is not None:
            self._cell_history.append((current_ts_seconds, str(cell_fp)))
            cutoff = current_ts_seconds - 3600.0
            recent_cells = [c for t, c in self._cell_history if t >= cutoff and c != "NO_CELL"]
            if len(recent_cells) >= 2:
                handovers = sum(1 for i in range(1, len(recent_cells)) if recent_cells[i] != recent_cells[i-1])
                dt_hr = max(0.1, (current_ts_seconds - cutoff) / 3600.0)
                cellular_churn = round(min(30.0, float(handovers) / dt_hr), 2)
            else:
                cellular_churn = 0.0
        else:
            cellular_churn = 0.0

        # 17. Touch motor interval jitter / CV (34) [0, 2]
        touch_jitter_direct = dig.get("touch_interval_jitter")
        if touch_jitter_direct is not None:
            touch_interval_jitter = safe_float(touch_jitter_direct, 0.0)
        else:
            if screen_ratio > 0.0:
                touch_interval_jitter = round(min(2.0, (app_switch_rate * 0.25 + unlock_burst_count * 0.2) / (1.0 + touch_velocity * 0.2)), 3)
            else:
                touch_interval_jitter = 0.0

        # 18. Multitask attention fragmentation entropy (35) in bits [0, 4]
        attention_ent_direct = dig.get("attention_fragmentation_entropy")
        if attention_ent_direct is not None:
            attention_frag_entropy = safe_float(attention_ent_direct, 0.0)
        else:
            if app_switch_rate > 1.0:
                attention_frag_entropy = round(min(4.0, math.log2(min(16.0, app_switch_rate))), 3)
            else:
                attention_frag_entropy = 0.0

        # Update persistent state across frames
        if current_ts_seconds is not None:
            self._last_ts_seconds = current_ts_seconds
            self._last_pressure = pressure
            self._last_temp = dev_temp

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
            "circadian_cos_phase": cos_phase,
            "infradian_weekly_phase": weekly_phase,
            "battery_current_ua": battery_current_ua,
            "spatial_radius_of_gyration": radius_of_gyration,
            "touch_interaction_velocity": touch_velocity,
            "ambient_social_ble_density": ambient_ble,
            "physio_ballisto_tremor_energy": physio_tremor,
            "thermal_conduction_rate_dt": conduction_rate,
            "device_zenith_tilt_angle": tilt_angle,
            "geomagnetic_flux_divergence": mag_divergence,
            "barometric_vertical_velocity": vertical_velocity,
            "ambient_rf_shannon_entropy": ambient_rf_entropy,
            "cellular_tower_churn_rate": cellular_churn,
            "touch_interval_jitter": touch_interval_jitter,
            "attention_fragmentation_entropy": attention_frag_entropy,
        }

        # Topological & Categorical identifiers
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

        self.reset_state()
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
