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
    # Estimated SoC computational power dissipation in Watts (36)
    "compute_power_dissipation_watts",
    # Battery dynamic electrochemical internal impedance in mOhm (37)
    "battery_internal_impedance_miliohm",
    # 3D rotational kinetic energy in rad^2/s^2 (38)
    "rotational_kinetic_energy",
    # Kinematic jerk vector derivative in m/s^3 (39)
    "kinematic_jerk_metric",
    # Dual-band RF 2.4GHz vs 5GHz propagation ratio (40)
    "rf_spectral_penetration_ratio",
    # Micro-spatial BLE RSSI variance in dB^2 (41)
    "ble_rssi_spatial_variance",
    # Acoustic spectral centroid brightness in kHz (42)
    "acoustic_spectral_centroid_khz",
    # Acoustic crest factor peak-to-RMS ratio (43)
    "acoustic_crest_factor",
    # Current macro-state residence persistence in hours (44)
    "state_residence_persistence_hours",
    # Physiological micro-tremor to motion spectral ratio (45)
    "neuromotor_tremor_spectral_ratio",
    # Screen plane gravity projection alignment cosine (46)
    "device_screen_gravity_alignment",
    # Rotational angular jerk vector derivative in rad/s^2 (47)
    "rotational_angular_jerk",
    # Acoustic spectral roll-off 85% quantile in kHz (48)
    "acoustic_spectral_rolloff_khz",
    # Acoustic time-domain zero crossing rate (49)
    "acoustic_zero_crossing_rate",
    # Second-order thermal conduction acceleration in degC/s^2 (50)
    "thermal_flux_acceleration_dt2",
    # Ambient Wi-Fi spatial multi-path Shannon entropy in nats (51)
    "wifi_spatial_shannon_entropy",
    # Continuous touch engagement decay factor [0, 1] (52)
    "touch_engagement_decay_factor",
    # Rolling phase-space recurrence density (53)
    "phase_space_recurrence_density",
    # Geomagnetic 3D dip inclination angle in degrees [-90, 90] (54)
    "geomagnetic_field_dip_angle_deg",
    # Kinematic 3D trajectory Frenet curvature (55)
    "kinematic_trajectory_curvature",
    # Barometric high-frequency pressure turbulence variance in hPa^2 (56)
    "barometric_turbulence_variance",
    # Photometric ambient dynamic contrast ratio (57)
    "photometric_contrast_ratio",
    # Acoustic second-order spectral bandwidth in kHz (58)
    "acoustic_spectral_bandwidth_khz",
    # Wi-Fi multi-AP RSSI distribution skewness (59)
    "wifi_rssi_distribution_skewness",
    # Battery internal electrochemical Joule heat loss in Watts (60)
    "thermodynamic_joule_heat_loss_watts",
    # Multi-modal phase space kinetic migration velocity in s^-1 (61)
    "phase_space_kinetic_velocity",
    # Neuromotor touch tap recoil deceleration intensity (62)
    "neuromotor_tap_recoil_intensity",
    # Kinematic 3D trajectory Frenet torsion in m^-1 (63)
    "kinematic_trajectory_torsion",
    # Phase-space Grassberger-Procaccia correlation dimension estimate (64)
    "phase_space_correlation_dimension",
    # Photometric ambient dynamic excess kurtosis (65)
    "photometric_flicker_kurtosis",
    # Acoustic spectral flatness measure / Wiener entropy (66)
    "acoustic_spectral_flatness",
    # Wi-Fi multi-AP RSSI distribution excess kurtosis (67)
    "wifi_rssi_distribution_kurtosis",
    # Geomagnetic total field intensity flux anomaly in uT (68)
    "geomagnetic_total_field_flux",
    # Thermodynamic thermal-to-electrical dissipation ratio (69)
    "thermodynamic_dissipation_ratio",
    # Neuromotor touch micro-slip rotational angular velocity in rad/s (70)
    "neuromotor_microslip_angular_velocity",
    # Circadian phase disruption / chrono-entropy index (71)
    "circadian_phase_disruption_index",
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
        self._last_accel_raw: Optional[List[float]] = None
        self._last_voltage_mv: Optional[float] = None
        self._last_current_ua: Optional[float] = None
        self._residence_regime: Optional[str] = None
        self._residence_start_ts: Optional[float] = None
        self._last_gyro_raw: Optional[List[float]] = None
        self._last_conduction_rate: Optional[float] = None
        self._last_touch_ts: Optional[float] = None
        self._recent_coords: deque[Tuple[float, Tuple[float, ...]]] = deque(maxlen=20)
        self._pressure_window: deque[float] = deque(maxlen=6)
        self._lux_window: deque[float] = deque(maxlen=10)
        self._last_vec_summary: Optional[Tuple[float, ...]] = None
        self._last_vec_ts: Optional[float] = None
        self._last_az: Optional[float] = None
        self._last_jerk_raw: Optional[List[float]] = None

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
        self._last_accel_raw = None
        self._last_voltage_mv = None
        self._last_current_ua = None
        self._residence_regime = None
        self._residence_start_ts = None
        self._last_gyro_raw = None
        self._last_conduction_rate = None
        self._last_touch_ts = None
        self._recent_coords.clear()
        self._pressure_window.clear()
        self._lux_window.clear()
        self._last_vec_summary = None
        self._last_vec_ts = None
        self._last_az = None
        self._last_jerk_raw = None

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

        # 19. Estimated SoC computational power dissipation in Watts (36)
        comp_power_direct = dev.get("compute_power_w")
        if comp_power_direct is not None:
            compute_power_w = safe_float(comp_power_direct, 0.5)
        else:
            base_idle = 0.35
            screen_pwr = 0.85 * screen_ratio
            audio_pwr = 0.25 * is_immersed
            switch_pwr = 0.20 * min(1.0, app_switch_rate / 8.0)
            compute_power_w = round(min(15.0, max(0.1, base_idle + screen_pwr + audio_pwr + switch_pwr)), 3)

        # 20. Battery dynamic electrochemical internal impedance in mOhm (37)
        r_int_direct = dev.get("internal_resistance_miliohm")
        voltage_mv = safe_float(dev.get("voltage_mv"), 4000.0)
        if r_int_direct is not None:
            battery_impedance = safe_float(r_int_direct, 180.0)
        else:
            battery_impedance = 180.0
            if self._last_voltage_mv is not None and self._last_current_ua is not None:
                d_i_ua = abs(battery_current_ua - self._last_current_ua)
                d_v_mv = abs(voltage_mv - self._last_voltage_mv)
                if d_i_ua >= 40000.0:
                    calc_r = (d_v_mv / d_i_ua) * 1000000.0
                    battery_impedance = round(max(50.0, min(800.0, calc_r)), 1)
                else:
                    battery_impedance = round(max(80.0, 160.0 + max(0.0, 30.0 - dev_temp) * 6.0), 1)
            else:
                battery_impedance = round(max(80.0, 160.0 + max(0.0, 30.0 - dev_temp) * 6.0), 1)

        # 21. 3D rotational kinetic energy in rad^2/s^2 (38)
        gyro_raw = mot.get("gyro_raw")
        if gyro_raw and isinstance(gyro_raw, (list, tuple)) and len(gyro_raw) >= 3:
            gx = safe_float(gyro_raw[0], 0.0)
            gy = safe_float(gyro_raw[1], 0.0)
            gz = safe_float(gyro_raw[2], 0.0)
            rot_energy = round(min(50.0, 0.5 * (gx * gx + gy * gy + gz * gz)), 5)
        else:
            g_energy = safe_float(mot.get("gyro_energy"), 0.0)
            rot_energy = round(min(50.0, g_energy), 5)

        # 22. Kinematic jerk vector derivative in m/s^3 (39)
        jerk_metric = 0.0
        accel_raw = mot.get("accel_raw")
        if accel_raw and isinstance(accel_raw, (list, tuple)) and len(accel_raw) >= 3:
            current_accel = [
                safe_float(accel_raw[0], 0.0),
                safe_float(accel_raw[1], 0.0),
                safe_float(accel_raw[2], 9.8),
            ]
            if self._last_accel_raw is not None and self._last_ts_seconds is not None and current_ts_seconds is not None:
                dt_s = current_ts_seconds - self._last_ts_seconds
                if 2.0 <= dt_s <= 300.0:
                    d_ax = current_accel[0] - self._last_accel_raw[0]
                    d_ay = current_accel[1] - self._last_accel_raw[1]
                    d_az = current_accel[2] - self._last_accel_raw[2]
                    jerk_calc = math.sqrt(d_ax * d_ax + d_ay * d_ay + d_az * d_az) / dt_s
                    jerk_metric = round(min(20.0, jerk_calc), 4)

        # 23. Dual-band RF 2.4GHz vs 5GHz propagation ratio (40)
        wifi_freq = safe_float(conn.get("wifi_frequency_mhz"), 2412.0)
        if wifi_conn > 0.0:
            if wifi_freq >= 4900.0:
                rf_penetration = round(max(0.1, min(2.0, 0.6 + wifi_rssi_norm * 0.8)), 3)
            else:
                rf_penetration = round(max(0.1, min(2.0, 1.1 + wifi_rssi_norm * 0.7)), 3)
        else:
            rf_penetration = 0.0

        # 24. Micro-spatial BLE RSSI variance in dB^2 (41)
        ble_var_direct = dig.get("ble_rssi_spatial_variance")
        if ble_var_direct is not None:
            ble_variance = safe_float(ble_var_direct, 0.0)
        else:
            if ambient_ble > 1.0:
                ble_variance = round(min(25.0, ambient_ble * 1.6), 2)
            else:
                ble_variance = 0.0

        # 25. Acoustic spectral centroid brightness in kHz (42)
        aud_cent_direct = aud.get("spectral_centroid_hz")
        if aud_cent_direct is not None:
            acoustic_centroid_khz = round(min(10.0, safe_float(aud_cent_direct, 300.0) / 1000.0), 3)
        else:
            acoustic_centroid_khz = round(min(10.0, 0.3 + 3.5 * spectral_entropy), 3)

        # 26. Acoustic crest factor peak-to-RMS ratio (43)
        peak_level = safe_float(aud.get("peak_level"), 0.0)
        if silence_like >= 0.95 or rms_loudness <= 0.0005:
            acoustic_crest = 1.0
        else:
            raw_crest = peak_level / max(0.001, rms_loudness)
            acoustic_crest = round(max(1.0, min(25.0, raw_crest)), 2)

        # 27. Current macro-state residence persistence in hours (44)
        current_regime = "STATIONARY" if stat_like >= 0.8 else "DYNAMIC"
        if current_ts_seconds is not None:
            if self._residence_regime == current_regime and self._residence_start_ts is not None:
                dwell_sec = max(0.0, current_ts_seconds - self._residence_start_ts)
                residence_persistence_hours = round(min(24.0, dwell_sec / 3600.0), 3)
            else:
                if self._last_ts_seconds is None or current_ts_seconds != self._last_ts_seconds:
                    self._residence_regime = current_regime
                    self._residence_start_ts = current_ts_seconds
                residence_persistence_hours = 0.0
        else:
            residence_persistence_hours = 0.0

        # 28. Physiological micro-tremor to motion spectral ratio (45)
        tremor_ratio_direct = dig.get("neuromotor_tremor_spectral_ratio") or mot.get("neuromotor_tremor_spectral_ratio")
        if tremor_ratio_direct is not None:
            neuromotor_ratio = safe_float(tremor_ratio_direct, 0.35)
        else:
            neuromotor_ratio = round(min(5.0, physio_tremor / max(0.015, mot_energy * 0.4 + 0.02)), 3)

        # 29. Screen plane gravity projection alignment cosine (46)
        if accel_raw and isinstance(accel_raw, (list, tuple)) and len(accel_raw) >= 3:
            ax_g = safe_float(accel_raw[0], 0.0)
            ay_g = safe_float(accel_raw[1], 0.0)
            az_g = safe_float(accel_raw[2], 9.8)
            norm_g = math.sqrt(ax_g * ax_g + ay_g * ay_g + az_g * az_g)
            if norm_g > 0.1:
                screen_alignment = round(max(-1.0, min(1.0, az_g / norm_g)), 3)
            else:
                screen_alignment = 0.0
        else:
            screen_alignment = 0.0

        # 30. Rotational angular jerk vector derivative in rad/s^2 (47)
        rot_jerk = 0.0
        if gyro_raw and isinstance(gyro_raw, (list, tuple)) and len(gyro_raw) >= 3:
            current_gyro = [
                safe_float(gyro_raw[0], 0.0),
                safe_float(gyro_raw[1], 0.0),
                safe_float(gyro_raw[2], 0.0),
            ]
            if self._last_gyro_raw is not None and self._last_ts_seconds is not None and current_ts_seconds is not None:
                dt_s = current_ts_seconds - self._last_ts_seconds
                if 2.0 <= dt_s <= 300.0:
                    d_gx = current_gyro[0] - self._last_gyro_raw[0]
                    d_gy = current_gyro[1] - self._last_gyro_raw[1]
                    d_gz = current_gyro[2] - self._last_gyro_raw[2]
                    jerk_g_calc = math.sqrt(d_gx * d_gx + d_gy * d_gy + d_gz * d_gz) / dt_s
                    rot_jerk = round(min(25.0, jerk_g_calc), 4)
            if self._last_ts_seconds is None or current_ts_seconds != self._last_ts_seconds:
                self._last_gyro_raw = current_gyro
        else:
            if self._last_ts_seconds is None or current_ts_seconds != self._last_ts_seconds:
                self._last_gyro_raw = None

        # 31. Acoustic spectral roll-off 85% quantile in kHz (48)
        aud_rolloff_direct = aud.get("spectral_rolloff_hz") or aud.get("rolloff_khz")
        if aud_rolloff_direct is not None:
            rolloff_val = safe_float(aud_rolloff_direct, 1800.0)
            rolloff_khz = round(min(12.0, max(0.1, rolloff_val / 1000.0 if rolloff_val > 50.0 else rolloff_val)), 3)
        else:
            rolloff_khz = round(min(12.0, max(0.2, acoustic_centroid_khz * (1.2 + 0.8 * spectral_entropy))), 3)

        # 32. Acoustic time-domain zero crossing rate (49)
        aud_zcr_direct = aud.get("zero_crossing_rate") or aud.get("zcr")
        if aud_zcr_direct is not None:
            acoustic_zcr = round(min(1.0, max(0.0, safe_float(aud_zcr_direct, 0.08))), 3)
        else:
            if silence_like >= 0.95 or rms_loudness <= 0.0005:
                acoustic_zcr = 0.01
            else:
                raw_zcr = 0.04 + 0.03 * min(5.0, acoustic_centroid_khz) + 0.15 * min(1.0, rms_loudness * 8.0)
                acoustic_zcr = round(min(0.9, max(0.01, raw_zcr)), 3)

        # 33. Second-order thermal conduction acceleration in (deg C / hr) / s (50)
        thermal_accel_dt2 = 0.0
        if self._last_conduction_rate is not None and self._last_ts_seconds is not None and current_ts_seconds is not None:
            dt_s = current_ts_seconds - self._last_ts_seconds
            if 2.0 <= dt_s <= 300.0:
                d_rate = conduction_rate - self._last_conduction_rate
                thermal_accel_dt2 = round(max(-2.0, min(2.0, d_rate / dt_s)), 5)
        if self._last_ts_seconds is None or current_ts_seconds != self._last_ts_seconds:
            if self._last_temp is not None:
                self._last_conduction_rate = conduction_rate
            else:
                self._last_conduction_rate = None

        # 34. Ambient Wi-Fi spatial multi-path Shannon entropy in nats (51)
        wifi_ent_direct = conn.get("wifi_spatial_shannon_entropy") or dig.get("wifi_spatial_shannon_entropy")
        if wifi_ent_direct is not None:
            wifi_shannon_entropy = round(max(0.0, min(6.0, safe_float(wifi_ent_direct, 1.2))), 3)
        else:
            wifi_ap_num = safe_float(conn.get("wifi_ap_count"), 0.0)
            if wifi_conn > 0.0:
                calc_wifi_ent = min(5.0, ambient_rf_entropy * 0.85 + 0.2 * math.log(1.0 + max(0.0, wifi_ap_num)))
                wifi_shannon_entropy = round(max(0.0, calc_wifi_ent), 3)
            else:
                wifi_shannon_entropy = 0.0

        # 35. Continuous touch engagement decay factor [0, 1] (52)
        has_touch_interaction = (touch_velocity > 0.0 or app_switch_rate > 0.0 or unlock_burst_count > 0.0)
        if has_touch_interaction:
            if current_ts_seconds is not None and (self._last_ts_seconds is None or current_ts_seconds != self._last_ts_seconds):
                self._last_touch_ts = current_ts_seconds
            touch_engagement = 1.0
        else:
            if self._last_touch_ts is not None and current_ts_seconds is not None and current_ts_seconds >= self._last_touch_ts:
                idle_sec = current_ts_seconds - self._last_touch_ts
                touch_engagement = round(math.exp(-idle_sec / 600.0), 3)
            else:
                touch_engagement = 0.5 if screen_ratio > 0.5 else 0.0

        # 36. Rolling phase-space recurrence density (53)
        coord_summary = (
            round(accel_mag, 2),
            round(dev_temp, 1),
            round(battery_frac, 2),
            round(log_lux, 2),
            round(stat_like, 2),
        )
        t_entry = current_ts_seconds if current_ts_seconds is not None else 0.0
        if self._recent_coords and self._recent_coords[-1][0] == t_entry:
            self._recent_coords[-1] = (t_entry, coord_summary)
        else:
            self._recent_coords.append((t_entry, coord_summary))

        rec_count = sum(
            1 for _, prev_c in self._recent_coords
            if sum((c1 - c2) ** 2 for c1, c2 in zip(coord_summary, prev_c)) <= 4.0
        )
        recurrence_density = round(float(rec_count) / float(len(self._recent_coords)), 3)

        # 37. Geomagnetic 3D dip inclination angle in degrees [-90, 90] (54)
        mag_dip_direct = env.get("geomagnetic_dip_angle_deg") or mot.get("geomagnetic_dip_angle_deg")
        if mag_dip_direct is not None:
            geomag_dip_angle = round(max(-90.0, min(90.0, safe_float(mag_dip_direct, 45.0))), 2)
        else:
            if mag_raw and isinstance(mag_raw, (list, tuple)) and len(mag_raw) >= 3:
                bx = safe_float(mag_raw[0], 0.0)
                by = safe_float(mag_raw[1], 0.0)
                bz = safe_float(mag_raw[2], 45.0)
                bh = math.sqrt(bx * bx + by * by)
                if bh > 0.1:
                    geomag_dip_angle = round(math.degrees(math.atan2(bz, bh)), 2)
                else:
                    geomag_dip_angle = 90.0 if bz >= 0 else -90.0
            else:
                geomag_dip_angle = 45.0

        # 38. Kinematic 3D trajectory Frenet curvature (55) & Torsion (63)
        trajectory_curvature = 0.0
        trajectory_torsion = 0.0
        current_jerk: Optional[List[float]] = None
        torsion_direct = mot.get("trajectory_torsion") or mot.get("kinematic_trajectory_torsion")
        if torsion_direct is not None:
            trajectory_torsion = round(max(-10.0, min(10.0, safe_float(torsion_direct, 0.0))), 4)

        if accel_raw and isinstance(accel_raw, (list, tuple)) and len(accel_raw) >= 3:
            curr_a = [safe_float(accel_raw[0], 0.0), safe_float(accel_raw[1], 0.0), safe_float(accel_raw[2], 9.8)]
            if self._last_accel_raw is not None and self._last_ts_seconds is not None and current_ts_seconds is not None:
                dt_s = current_ts_seconds - self._last_ts_seconds
                if 2.0 <= dt_s <= 300.0:
                    d_ax = (curr_a[0] - self._last_accel_raw[0]) / dt_s
                    d_ay = (curr_a[1] - self._last_accel_raw[1]) / dt_s
                    d_az = (curr_a[2] - self._last_accel_raw[2]) / dt_s
                    current_jerk = [d_ax, d_ay, d_az]
                    cx = curr_a[1] * d_az - curr_a[2] * d_ay
                    cy = curr_a[2] * d_ax - curr_a[0] * d_az
                    cz = curr_a[0] * d_ay - curr_a[1] * d_ax
                    cross_mag = math.sqrt(cx * cx + cy * cy + cz * cz)
                    a_mag_cubed = (curr_a[0]**2 + curr_a[1]**2 + curr_a[2]**2) ** 1.5
                    trajectory_curvature = round(min(5.0, cross_mag / max(0.1, a_mag_cubed)), 4)

                    if torsion_direct is None and self._last_jerk_raw is not None:
                        s_x = (d_ax - self._last_jerk_raw[0]) / dt_s
                        s_y = (d_ay - self._last_jerk_raw[1]) / dt_s
                        s_z = (d_az - self._last_jerk_raw[2]) / dt_s
                        dot_c_s = cx * s_x + cy * s_y + cz * s_z
                        cross_sq = cross_mag * cross_mag
                        if cross_sq > 0.001:
                            raw_torsion = dot_c_s / cross_sq
                            trajectory_torsion = round(max(-10.0, min(10.0, raw_torsion)), 4)

        # 39. Barometric high-frequency pressure turbulence variance in hPa^2 (56)
        if pressure > 0.0:
            if self._last_ts_seconds is None or current_ts_seconds != self._last_ts_seconds:
                self._pressure_window.append(pressure)
            if len(self._pressure_window) >= 2:
                p_mean = sum(self._pressure_window) / len(self._pressure_window)
                p_var = sum((x - p_mean) ** 2 for x in self._pressure_window) / len(self._pressure_window)
                baro_turbulence = round(min(10.0, p_var), 4)
            else:
                baro_turbulence = 0.0
        else:
            baro_turbulence = 0.0

        # 40. Photometric ambient dynamic contrast ratio (57)
        if self._last_ts_seconds is None or current_ts_seconds != self._last_ts_seconds:
            self._lux_window.append(lux_val)
        if len(self._lux_window) >= 2:
            lux_mean = sum(self._lux_window) / len(self._lux_window)
            contrast_val = math.log(1.0 + abs(lux_val - lux_mean) / (lux_mean + 1.0))
            photo_contrast = round(min(6.0, max(0.0, contrast_val)), 3)
        else:
            photo_contrast = 0.0

        # 41. Acoustic second-order spectral bandwidth in kHz (58)
        aud_bw_direct = aud.get("spectral_bandwidth_hz") or aud.get("bandwidth_khz")
        if aud_bw_direct is not None:
            bw_val = safe_float(aud_bw_direct, 1200.0)
            spectral_bandwidth_khz = round(min(8.0, max(0.1, bw_val / 1000.0 if bw_val > 20.0 else bw_val)), 3)
        else:
            spectral_bandwidth_khz = round(min(8.0, max(0.1, 0.4 * acoustic_centroid_khz + 1.8 * spectral_entropy)), 3)

        # 42. Wi-Fi multi-AP RSSI distribution skewness (59)
        wifi_skew_direct = conn.get("wifi_rssi_distribution_skewness") or dig.get("wifi_rssi_distribution_skewness")
        if wifi_skew_direct is not None:
            wifi_skewness = round(max(-3.0, min(3.0, safe_float(wifi_skew_direct, 0.0))), 3)
        else:
            if wifi_conn > 0.0:
                wifi_skewness = round(max(-2.5, min(2.5, (0.5 - wifi_rssi_norm) * 3.0)), 3)
            else:
                wifi_skewness = 0.0

        # 43. Battery internal electrochemical Joule heat loss in Watts (60)
        i_amp = abs(battery_current_ua) * 1e-6
        r_ohm = battery_impedance * 1e-3
        joule_heat_w = round(min(5.0, (i_amp ** 2) * r_ohm), 5)

        # 44. Multi-modal phase space kinetic migration velocity in s^-1 (61)
        phase_velocity = 0.0
        summary_5d = (accel_mag, dev_temp, battery_frac, log_lux, stat_like)
        if self._last_vec_summary is not None and self._last_vec_ts is not None and current_ts_seconds is not None:
            dt_s = current_ts_seconds - self._last_vec_ts
            if 2.0 <= dt_s <= 300.0:
                dist_sum = math.sqrt(sum((c1 - c2) ** 2 for c1, c2 in zip(summary_5d, self._last_vec_summary)))
                phase_velocity = round(min(5.0, dist_sum / dt_s), 4)
        if self._last_ts_seconds is None or current_ts_seconds != self._last_ts_seconds:
            self._last_vec_summary = summary_5d
            self._last_vec_ts = current_ts_seconds

        # 45. Neuromotor touch tap recoil deceleration intensity (62)
        tap_recoil = 0.0
        if accel_raw and isinstance(accel_raw, (list, tuple)) and len(accel_raw) >= 3:
            az_raw_val = safe_float(accel_raw[2], 9.8)
            if self._last_az is not None:
                d_az = abs(az_raw_val - self._last_az)
                tap_recoil = round(min(8.0, d_az / (1.0 + touch_velocity * 0.5)), 3)
            if self._last_ts_seconds is None or current_ts_seconds != self._last_ts_seconds:
                self._last_az = az_raw_val
        else:
            self._last_az = None

        # 46. Kinematic 3D trajectory Frenet torsion (63) is computed above as trajectory_torsion

        # 47. Phase-space Grassberger-Procaccia correlation dimension estimate (64)
        corr_dim_direct = mot.get("phase_space_correlation_dimension") or dig.get("correlation_dimension")
        if corr_dim_direct is not None:
            corr_dimension = round(max(0.0, min(5.0, safe_float(corr_dim_direct, 1.2))), 3)
        else:
            if len(self._recent_coords) >= 6:
                dists = [
                    math.sqrt(sum((c1 - c2) ** 2 for c1, c2 in zip(c_i, c_j)))
                    for i, (_, c_i) in enumerate(self._recent_coords)
                    for j, (_, c_j) in enumerate(self._recent_coords)
                    if i < j
                ]
                n1 = sum(1 for d in dists if d < 1.0)
                n2 = sum(1 for d in dists if d < 3.0)
                if n1 > 0 and n2 > n1:
                    raw_d2 = math.log(float(n2) / float(n1)) / math.log(3.0)
                    corr_dimension = round(min(5.0, max(0.0, raw_d2)), 3)
                else:
                    corr_dimension = 0.15 if stat_like >= 0.8 else round(min(4.0, 1.2 + 1.8 * min(1.0, mot_energy)), 3)
            else:
                corr_dimension = 0.15 if stat_like >= 0.8 else round(min(4.0, 1.2 + 1.8 * min(1.0, mot_energy)), 3)

        # 48. Photometric ambient dynamic excess kurtosis (65)
        lux_kurt_direct = env.get("photometric_flicker_kurtosis") or env.get("lux_kurtosis")
        if lux_kurt_direct is not None:
            flicker_kurtosis = round(max(-2.0, min(10.0, safe_float(lux_kurt_direct, 0.0))), 3)
        else:
            if len(self._lux_window) >= 4:
                n_lux = len(self._lux_window)
                m_lux = sum(self._lux_window) / n_lux
                var_lux = sum((x - m_lux) ** 2 for x in self._lux_window) / n_lux
                if var_lux > 1e-4:
                    m4_lux = sum((x - m_lux) ** 4 for x in self._lux_window) / n_lux
                    kurt_val = (m4_lux / (var_lux ** 2)) - 3.0
                    flicker_kurtosis = round(max(-2.0, min(10.0, kurt_val)), 3)
                else:
                    flicker_kurtosis = 0.0
            else:
                flicker_kurtosis = 0.0

        # 49. Acoustic spectral flatness measure / Wiener entropy (66)
        aud_flat_direct = aud.get("spectral_flatness") or aud.get("wiener_entropy")
        if aud_flat_direct is not None:
            spectral_flatness = round(max(0.0, min(1.0, safe_float(aud_flat_direct, 0.1))), 3)
        else:
            if silence_like >= 0.95 or rms_loudness <= 0.0005:
                spectral_flatness = 0.05
            else:
                raw_flatness = 0.12 + 0.72 * spectral_entropy - 0.15 * min(1.0, rms_loudness * 10.0)
                spectral_flatness = round(min(1.0, max(0.01, raw_flatness)), 3)

        # 50. Wi-Fi multi-AP RSSI distribution excess kurtosis (67)
        wifi_kurt_direct = conn.get("wifi_rssi_distribution_kurtosis") or conn.get("wifi_kurtosis")
        if wifi_kurt_direct is not None:
            wifi_kurtosis = round(max(-2.0, min(8.0, safe_float(wifi_kurt_direct, 0.0))), 3)
        else:
            if wifi_conn > 0.0:
                calc_kurt = max(-2.0, min(8.0, (wifi_skewness ** 2) - 1.2))
                wifi_kurtosis = round(calc_kurt, 3)
            else:
                wifi_kurtosis = 0.0

        # 51. Geomagnetic total field intensity flux anomaly in uT (68)
        mag_flux_direct = env.get("geomagnetic_total_field_flux") or env.get("mag_flux_anomaly")
        if mag_flux_direct is not None:
            mag_total_flux = round(max(0.0, min(100.0, safe_float(mag_flux_direct, 5.0))), 2)
        else:
            if mag_raw and isinstance(mag_raw, (list, tuple)) and len(mag_raw) >= 3:
                bx = safe_float(mag_raw[0], 0.0)
                by = safe_float(mag_raw[1], 0.0)
                bz = safe_float(mag_raw[2], 50.0)
                b_mag = math.sqrt(bx * bx + by * by + bz * bz)
                mag_total_flux = round(min(100.0, max(0.0, abs(b_mag - 50.0))), 2)
            else:
                mag_total_flux = round(min(100.0, mag_divergence * 6.0), 2)

        # 52. Thermodynamic thermal-to-electrical dissipation ratio (69)
        dissip_direct = dig.get("thermodynamic_dissipation_ratio") or env.get("dissipation_ratio")
        if dissip_direct is not None:
            thermo_dissipation_ratio = round(max(0.0, min(5.0, safe_float(dissip_direct, 0.5))), 4)
        else:
            p_elec = (voltage_mv * 1e-3) * (abs(battery_current_ua) * 1e-6)
            p_thermal = 80.0 * (max(0.0, conduction_rate) / 3600.0)
            ratio_calc = p_thermal / max(0.05, p_elec + compute_power_w)
            thermo_dissipation_ratio = round(min(5.0, max(0.0, ratio_calc)), 4)

        # 53. Neuromotor touch micro-slip rotational angular velocity in rad/s (70)
        slip_direct = dig.get("neuromotor_microslip_angular_velocity") or mot.get("microslip_ang_vel")
        if slip_direct is not None:
            microslip_vel = round(max(0.0, min(10.0, safe_float(slip_direct, 0.05))), 3)
        else:
            if gyro_raw and isinstance(gyro_raw, (list, tuple)) and len(gyro_raw) >= 3:
                gx = safe_float(gyro_raw[0], 0.0)
                gy = safe_float(gyro_raw[1], 0.0)
                gz = safe_float(gyro_raw[2], 0.0)
                norm_gyro = math.sqrt(gx * gx + gy * gy + gz * gz)
                if has_touch_interaction or touch_velocity > 0.0:
                    raw_slip = norm_gyro * min(1.0, 0.4 + touch_velocity * 0.3)
                else:
                    raw_slip = 0.05 * norm_gyro
                microslip_vel = round(min(10.0, max(0.0, raw_slip)), 3)
            else:
                microslip_vel = 0.0

        # 54. Circadian phase disruption / chrono-entropy index (71)
        chrono_direct = dig.get("circadian_phase_disruption_index") or dig.get("chrono_entropy")
        if chrono_direct is not None:
            circadian_disruption = round(max(0.0, min(1.0, safe_float(chrono_direct, 0.05))), 3)
        else:
            is_night = (cos_phase > 0.6)
            if is_night:
                disrupt_val = 0.45 * screen_ratio + 0.35 * min(1.0, log_lux / 2.5) + 0.20 * min(1.0, mot_energy / 0.4)
            else:
                is_midday = (cos_phase < -0.5)
                if is_midday and log_lux <= 0.1 and stat_like >= 0.9 and screen_ratio <= 0.05:
                    disrupt_val = 0.5 * stat_like
                else:
                    disrupt_val = 0.02
            circadian_disruption = round(min(1.0, max(0.0, disrupt_val)), 3)

        # Update persistent state across frames
        if current_ts_seconds is not None and (self._last_ts_seconds is None or current_ts_seconds != self._last_ts_seconds):
            self._last_ts_seconds = current_ts_seconds
            self._last_pressure = pressure
            self._last_temp = dev_temp
            self._last_voltage_mv = voltage_mv
            self._last_current_ua = battery_current_ua
            if accel_raw and isinstance(accel_raw, (list, tuple)) and len(accel_raw) >= 3:
                self._last_accel_raw = [
                    safe_float(accel_raw[0], 0.0),
                    safe_float(accel_raw[1], 0.0),
                    safe_float(accel_raw[2], 9.8),
                ]
            else:
                self._last_accel_raw = None
            self._last_jerk_raw = current_jerk

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
            "compute_power_dissipation_watts": compute_power_w,
            "battery_internal_impedance_miliohm": battery_impedance,
            "rotational_kinetic_energy": rot_energy,
            "kinematic_jerk_metric": jerk_metric,
            "rf_spectral_penetration_ratio": rf_penetration,
            "ble_rssi_spatial_variance": ble_variance,
            "acoustic_spectral_centroid_khz": acoustic_centroid_khz,
            "acoustic_crest_factor": acoustic_crest,
            "state_residence_persistence_hours": residence_persistence_hours,
            "neuromotor_tremor_spectral_ratio": neuromotor_ratio,
            "device_screen_gravity_alignment": screen_alignment,
            "rotational_angular_jerk": rot_jerk,
            "acoustic_spectral_rolloff_khz": rolloff_khz,
            "acoustic_zero_crossing_rate": acoustic_zcr,
            "thermal_flux_acceleration_dt2": thermal_accel_dt2,
            "wifi_spatial_shannon_entropy": wifi_shannon_entropy,
            "touch_engagement_decay_factor": touch_engagement,
            "phase_space_recurrence_density": recurrence_density,
            "geomagnetic_field_dip_angle_deg": geomag_dip_angle,
            "kinematic_trajectory_curvature": trajectory_curvature,
            "barometric_turbulence_variance": baro_turbulence,
            "photometric_contrast_ratio": photo_contrast,
            "acoustic_spectral_bandwidth_khz": spectral_bandwidth_khz,
            "wifi_rssi_distribution_skewness": wifi_skewness,
            "thermodynamic_joule_heat_loss_watts": joule_heat_w,
            "phase_space_kinetic_velocity": phase_velocity,
            "neuromotor_tap_recoil_intensity": tap_recoil,
            "kinematic_trajectory_torsion": trajectory_torsion,
            "phase_space_correlation_dimension": corr_dimension,
            "photometric_flicker_kurtosis": flicker_kurtosis,
            "acoustic_spectral_flatness": spectral_flatness,
            "wifi_rssi_distribution_kurtosis": wifi_kurtosis,
            "geomagnetic_total_field_flux": mag_total_flux,
            "thermodynamic_dissipation_ratio": thermo_dissipation_ratio,
            "neuromotor_microslip_angular_velocity": microslip_vel,
            "circadian_phase_disruption_index": circadian_disruption,
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
