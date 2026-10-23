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
    # Kinematic 3D trajectory fourth-order snap/jounce metric in m/s^4 (72)
    "kinematic_snap_metric",
    # Acoustic harmonic-to-noise ratio in dB (73)
    "acoustic_harmonic_to_noise_ratio",
    # Photometric dynamic spectral warmth / CCT evolution index (74)
    "photometric_spectral_warmth_index",
    # Barometric vertical atmospheric buoyancy energy flux in mW/kg (75)
    "barometric_buoyancy_energy_flux",
    # Geomagnetic horizontal-vertical quadrupole anisotropy ratio (76)
    "geomagnetic_quadrupole_anisotropy_ratio",
    # Wi-Fi cross-band RF channel dispersion Shannon entropy (77)
    "wifi_channel_dispersion_entropy",
    # Thermodynamic electrochemical battery overpotential stress index (78)
    "thermodynamic_battery_stress_index",
    # Neuromotor physiological micro-tremor Lyapunov exponent in s^-1 (79)
    "neuromotor_tremor_lyapunov_exponent",
    # Multimodal phase-space non-equilibrium information flow rate in nats/s (80)
    "phase_space_information_flow_rate",
    # Domain 1: 5th/6th Order Kinematics & Resonances (81-84)
    "kinematic_crackle_metric",
    "kinematic_pop_metric",
    "imu_temporal_autocorrelation_decay",
    "device_structural_resonance_energy",
    # Domain 2: Spatial Acoustics & Phase Interference (85-88)
    "acoustic_dual_mic_cross_correlation",
    "acoustic_spatial_coherence_phase",
    "acoustic_spectral_flux",
    "ultrasonic_proximity_micro_doppler",
    # Domain 3: GNSS Constellations & C/N0 Field (89-92)
    "gnss_visible_satellite_density",
    "gnss_mean_carrier_to_noise_ratio",
    "gnss_position_dilution_of_precision",
    "gnss_doppler_velocity_drift",
    # Domain 4: Multi-Zone Thermal Entropy Gradients (93-96)
    "thermal_soc_to_skin_gradient",
    "thermal_modem_to_battery_gradient",
    "thermal_relaxation_time_constant",
    "thermal_entropy_production_rate",
    # Domain 5: Cellular Layer 1 & RF Propagation (97-102)
    "cellular_reference_signal_quality",
    "cellular_signal_to_interference_noise",
    "ble_advertising_channel_entropy",
    "wifi_link_speed_variation_ratio",
    "rf_ambient_backscatter_intensity",
    "cellular_timing_advance_distance",
    # Domain 6: Touch Micro-Geometry & Bio-Motor Force (103-108)
    "touch_contact_ellipse_eccentricity",
    "touch_downforce_pressure_derivative",
    "neuromotor_fitts_law_throughput",
    "touch_biomotor_cadence_decay_slope",
    "touch_contact_area_normalized",
    "touch_path_tortuosity_ratio",
    # Domain 7: Linux Kernel & SoC Runtime Dynamics (109-114)
    "cpu_frequency_scaling_entropy",
    "kernel_context_switch_velocity",
    "memory_pressure_stall_ratio",
    "display_frame_pacing_jank_variance",
    "io_wait_latency_proxy",
    "cpu_user_system_mode_ratio",
    # Domain 8: Chaos Theory, Fractals & RQA Invariants (115-128)
    "fractal_hurst_exponent",
    "permutation_entropy_complexity",
    "rqa_recurrence_determinism_det",
    "rqa_laminarity_vertical_trapping",
    "lempel_ziv_algorithmic_complexity",
    "fisher_information_measure",
    "phase_space_trapping_time_seconds",
    "multiscale_approximate_entropy",
    "bifurcation_stability_margin",
    "non_linear_energy_operator_teager",
    "phase_space_angular_momentum",
    "topological_betti_number_proxy",
    "dissipative_lyapunov_dimension_ky",
    "holo_cosmic_coherence_index",
]

# Clock-derived phases: analytic, so a short fit window cannot estimate their scale.
PHASE_FEATURES = frozenset({"circadian_sin_phase", "circadian_cos_phase", "infradian_weekly_phase"})


@dataclass
class NormalizationParams:
    feature_names: List[str]
    medians: Dict[str, float]
    iqrs: Dict[str, float]
    fitted_observation_count: int
    fit_window_description: str
    feature_extraction_version: int = 1

    def to_dict(self) -> Dict[str, Any]:
        result = asdict(self)
        if self.feature_extraction_version == 1:
            del result['feature_extraction_version']
        return result

    @classmethod
    def from_dict(cls, d: Dict[str, Any]) -> "NormalizationParams":
        return cls(
            feature_names=d["feature_names"],
            medians=d["medians"],
            iqrs=d["iqrs"],
            fitted_observation_count=d["fitted_observation_count"],
            fit_window_description=d["fit_window_description"],
            feature_extraction_version=d.get('feature_extraction_version', 1),
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
        self.feature_extraction_version = params.feature_extraction_version if params else 2
        if type(self.feature_extraction_version) is not int or self.feature_extraction_version not in (1, 2):
            raise ValueError('unsupported feature extraction version')
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
        self._last_tremor_energy: Optional[float] = None
        self._last_snap_raw: Optional[List[float]] = None
        self._last_crackle_raw: Optional[List[float]] = None
        self._accel_history: deque[float] = deque(maxlen=25)
        self._last_spectral_flux_prev: Optional[List[float]] = None
        self._last_touch_pos: Optional[Tuple[float, float, float]] = None
        self._phase_orbit_history: deque[Tuple[float, float]] = deque(maxlen=20)

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
        self._last_tremor_energy = None
        self._last_snap_raw = None
        self._last_crackle_raw = None
        self._accel_history.clear()
        self._last_spectral_flux_prev = None
        self._last_touch_pos = None
        self._phase_orbit_history.clear()

    @staticmethod
    def haversine_km(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
        """Computes great-circle distance between two GPS coordinates in kilometers."""
        phi1, phi2 = math.radians(lat1), math.radians(lat2)
        dphi = math.radians(lat2 - lat1)
        dlambda = math.radians(lon2 - lon1)
        a = (
            math.sin(dphi / 2.0) ** 2
            + math.cos(phi1) * math.cos(phi2) * math.sin(dlambda / 2.0) ** 2
        )
        return 2.0 * 6371.0 * math.asin(math.sqrt(min(1.0, max(0.0, a))))

    def extract_raw_features(
        self, obs: Dict[str, Any]
    ) -> Tuple[Dict[str, float], Dict[str, str]]:
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
        battery_frac = (
            (safe_float(bat_raw, 50.0) / 100.0) if bat_raw is not None else 0.5
        )
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
                touch_velocity = round(
                    min(10.0, 1.0 + app_switch_rate * 0.4 + unlock_burst_count * 0.3), 3
                )
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
        is_immersed = (
            1.0 if (dev.get("wired_headset") or dev.get("bluetooth_a2dp")) else 0.0
        )

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
                seconds_in_day = (
                    dt.hour * 3600 + dt.minute * 60 + dt.second + dt.microsecond / 1e6
                )
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
                physio_tremor = round(
                    min(
                        1.0, mot_energy * stat_like * max(0.0, 1.0 - 50.0 * gyro_energy)
                    ),
                    4,
                )
            else:
                physio_tremor = 0.0

        # 11. Net thermal conduction gradient rate (28) in deg C / hour
        conduction_rate = 0.0
        if (
            self._last_temp is not None
            and self._last_ts_seconds is not None
            and current_ts_seconds is not None
        ):
            dt_s = current_ts_seconds - self._last_ts_seconds
            if 5.0 <= dt_s <= 3600.0:
                raw_rate = ((dev_temp - self._last_temp) / dt_s) * 3600.0
                internal_bias = (1.5 if is_charging > 0.0 else 0.0) + (
                    0.8 if screen_ratio > 0.0 else 0.0
                )
                conduction_rate = round(
                    max(-10.0, min(10.0, raw_rate - internal_bias)), 3
                )

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
        if (
            self._last_pressure is not None
            and self._last_ts_seconds is not None
            and current_ts_seconds is not None
        ):
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
            ambient_rf_entropy = (
                round(min(5.0, math.log2(wifi_cnt + 1.0) * 0.85), 3)
                if wifi_cnt > 0
                else 0.0
            )

        # 16. Cellular base station handover churn rate (33) in handovers/hr
        cell_fp = conn.get("cellular_cell_fingerprint") or "NO_CELL"
        if current_ts_seconds is not None:
            self._cell_history.append((current_ts_seconds, str(cell_fp)))
            cutoff = current_ts_seconds - 3600.0
            recent_cells = [
                c for t, c in self._cell_history if t >= cutoff and c != "NO_CELL"
            ]
            if len(recent_cells) >= 2:
                handovers = sum(
                    1
                    for i in range(1, len(recent_cells))
                    if recent_cells[i] != recent_cells[i - 1]
                )
                # Measure actual time span of data in queue for accurate rate
                cells_with_times = [
                    (t, c)
                    for t, c in self._cell_history
                    if t >= cutoff and c != "NO_CELL"
                ]
                oldest_t = cells_with_times[0][0] if cells_with_times else cutoff
                dt_hr = max(0.1, (current_ts_seconds - oldest_t) / 3600.0)
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
                touch_interval_jitter = round(
                    min(
                        2.0,
                        (app_switch_rate * 0.25 + unlock_burst_count * 0.2)
                        / (1.0 + touch_velocity * 0.2),
                    ),
                    3,
                )
            else:
                touch_interval_jitter = 0.0

        # 18. Multitask attention fragmentation entropy (35) in bits [0, 4]
        attention_ent_direct = dig.get("attention_fragmentation_entropy")
        if attention_ent_direct is not None:
            attention_frag_entropy = safe_float(attention_ent_direct, 0.0)
        else:
            if app_switch_rate > 1.0:
                attention_frag_entropy = round(
                    min(4.0, math.log2(min(16.0, app_switch_rate))), 3
                )
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
            compute_power_w = round(
                min(15.0, max(0.1, base_idle + screen_pwr + audio_pwr + switch_pwr)), 3
            )

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
                    battery_impedance = round(
                        max(80.0, 160.0 + max(0.0, 30.0 - dev_temp) * 6.0), 1
                    )
            else:
                battery_impedance = round(
                    max(80.0, 160.0 + max(0.0, 30.0 - dev_temp) * 6.0), 1
                )

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
            if (
                self._last_accel_raw is not None
                and self._last_ts_seconds is not None
                and current_ts_seconds is not None
            ):
                dt_s = current_ts_seconds - self._last_ts_seconds
                if 2.0 <= dt_s <= 300.0:
                    d_ax = current_accel[0] - self._last_accel_raw[0]
                    d_ay = current_accel[1] - self._last_accel_raw[1]
                    d_az = current_accel[2] - self._last_accel_raw[2]
                    jerk_calc = (
                        math.sqrt(d_ax * d_ax + d_ay * d_ay + d_az * d_az) / dt_s
                    )
                    jerk_metric = round(min(20.0, jerk_calc), 4)

        # 23. Dual-band RF 2.4GHz vs 5GHz propagation ratio (40)
        wifi_freq = safe_float(conn.get("wifi_frequency_mhz"), 2412.0)
        if wifi_conn > 0.0:
            if wifi_freq >= 4900.0:
                rf_penetration = round(
                    max(0.1, min(2.0, 0.6 + wifi_rssi_norm * 0.8)), 3
                )
            else:
                rf_penetration = round(
                    max(0.1, min(2.0, 1.1 + wifi_rssi_norm * 0.7)), 3
                )
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
            acoustic_centroid_khz = round(
                min(10.0, safe_float(aud_cent_direct, 300.0) / 1000.0), 3
            )
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
            if (
                self._residence_regime == current_regime
                and self._residence_start_ts is not None
            ):
                dwell_sec = max(0.0, current_ts_seconds - self._residence_start_ts)
                residence_persistence_hours = round(min(24.0, dwell_sec / 3600.0), 3)
            else:
                if (
                    self._last_ts_seconds is None
                    or current_ts_seconds != self._last_ts_seconds
                ):
                    self._residence_regime = current_regime
                    self._residence_start_ts = current_ts_seconds
                residence_persistence_hours = 0.0
        else:
            residence_persistence_hours = 0.0

        # 28. Physiological micro-tremor to motion spectral ratio (45)
        tremor_ratio_direct = dig.get("neuromotor_tremor_spectral_ratio") or mot.get(
            "neuromotor_tremor_spectral_ratio"
        )
        if tremor_ratio_direct is not None:
            neuromotor_ratio = safe_float(tremor_ratio_direct, 0.35)
        else:
            neuromotor_ratio = round(
                min(5.0, physio_tremor / max(0.015, mot_energy * 0.4 + 0.02)), 3
            )

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
            if (
                self._last_gyro_raw is not None
                and self._last_ts_seconds is not None
                and current_ts_seconds is not None
            ):
                dt_s = current_ts_seconds - self._last_ts_seconds
                if 2.0 <= dt_s <= 300.0:
                    d_gx = current_gyro[0] - self._last_gyro_raw[0]
                    d_gy = current_gyro[1] - self._last_gyro_raw[1]
                    d_gz = current_gyro[2] - self._last_gyro_raw[2]
                    jerk_g_calc = (
                        math.sqrt(d_gx * d_gx + d_gy * d_gy + d_gz * d_gz) / dt_s
                    )
                    rot_jerk = round(min(25.0, jerk_g_calc), 4)
            if (
                self._last_ts_seconds is None
                or current_ts_seconds != self._last_ts_seconds
            ):
                self._last_gyro_raw = current_gyro
        else:
            if (
                self._last_ts_seconds is None
                or current_ts_seconds != self._last_ts_seconds
            ):
                self._last_gyro_raw = None

        # 31. Acoustic spectral roll-off 85% quantile in kHz (48)
        aud_rolloff_direct = aud.get("spectral_rolloff_hz") or aud.get("rolloff_khz")
        if aud_rolloff_direct is not None:
            rolloff_val = safe_float(aud_rolloff_direct, 1800.0)
            rolloff_khz = round(
                min(
                    12.0,
                    max(
                        0.1, rolloff_val / 1000.0 if rolloff_val > 50.0 else rolloff_val
                    ),
                ),
                3,
            )
        else:
            rolloff_khz = round(
                min(
                    12.0,
                    max(0.2, acoustic_centroid_khz * (1.2 + 0.8 * spectral_entropy)),
                ),
                3,
            )

        # 32. Acoustic time-domain zero crossing rate (49)
        aud_zcr_direct = aud.get("zero_crossing_rate") or aud.get("zcr")
        if aud_zcr_direct is not None:
            acoustic_zcr = round(
                min(1.0, max(0.0, safe_float(aud_zcr_direct, 0.08))), 3
            )
        else:
            if silence_like >= 0.95 or rms_loudness <= 0.0005:
                acoustic_zcr = 0.01
            else:
                raw_zcr = (
                    0.04
                    + 0.03 * min(5.0, acoustic_centroid_khz)
                    + 0.15 * min(1.0, rms_loudness * 8.0)
                )
                acoustic_zcr = round(min(0.9, max(0.01, raw_zcr)), 3)

        # 33. Second-order thermal conduction acceleration in (deg C / hr) / s (50)
        thermal_accel_dt2 = 0.0
        if (
            self._last_conduction_rate is not None
            and self._last_ts_seconds is not None
            and current_ts_seconds is not None
        ):
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
        wifi_ent_direct = conn.get("wifi_spatial_shannon_entropy") or dig.get(
            "wifi_spatial_shannon_entropy"
        )
        if wifi_ent_direct is not None:
            wifi_shannon_entropy = round(
                max(0.0, min(6.0, safe_float(wifi_ent_direct, 1.2))), 3
            )
        else:
            wifi_ap_num = safe_float(conn.get("wifi_ap_count"), 0.0)
            if wifi_conn > 0.0:
                calc_wifi_ent = min(
                    5.0,
                    ambient_rf_entropy * 0.85
                    + 0.2 * math.log(1.0 + max(0.0, wifi_ap_num)),
                )
                wifi_shannon_entropy = round(max(0.0, calc_wifi_ent), 3)
            else:
                wifi_shannon_entropy = 0.0

        # 35. Continuous touch engagement decay factor [0, 1] (52)
        has_touch_interaction = (
            touch_velocity > 0.0 or app_switch_rate > 0.0 or unlock_burst_count > 0.0
        )
        if has_touch_interaction:
            if current_ts_seconds is not None and (
                self._last_ts_seconds is None
                or current_ts_seconds != self._last_ts_seconds
            ):
                self._last_touch_ts = current_ts_seconds
            touch_engagement = 1.0
        else:
            if (
                self._last_touch_ts is not None
                and current_ts_seconds is not None
                and current_ts_seconds >= self._last_touch_ts
            ):
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
            1
            for _, prev_c in self._recent_coords
            if sum((c1 - c2) ** 2 for c1, c2 in zip(coord_summary, prev_c)) <= 4.0
        )
        recurrence_density = round(
            float(rec_count) / float(len(self._recent_coords)), 3
        )

        # 37. Geomagnetic 3D dip inclination angle in degrees [-90, 90] (54)
        mag_dip_direct = env.get("geomagnetic_dip_angle_deg") or mot.get(
            "geomagnetic_dip_angle_deg"
        )
        if mag_dip_direct is not None:
            geomag_dip_angle = round(
                max(-90.0, min(90.0, safe_float(mag_dip_direct, 45.0))), 2
            )
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

        # 38. Kinematic 3D trajectory Frenet curvature (55), Torsion (63), Snap (72), Crackle (81) & Pop (82)
        trajectory_curvature = 0.0
        trajectory_torsion = 0.0
        snap_metric = 0.0
        crackle_metric = 0.0
        pop_metric = 0.0
        current_jerk: Optional[List[float]] = None
        current_snap: Optional[List[float]] = None
        current_crackle: Optional[List[float]] = None

        torsion_direct = mot.get("trajectory_torsion") or mot.get(
            "kinematic_trajectory_torsion"
        )
        if torsion_direct is not None:
            trajectory_torsion = round(
                max(-10.0, min(10.0, safe_float(torsion_direct, 0.0))), 4
            )
        snap_direct = mot.get("kinematic_snap_metric") or mot.get("snap_metric")
        if snap_direct is not None:
            snap_metric = round(max(0.0, min(50.0, safe_float(snap_direct, 0.0))), 4)
        crackle_direct = mot.get("kinematic_crackle_metric") or mot.get(
            "crackle_metric"
        )
        if crackle_direct is not None:
            crackle_metric = round(
                max(0.0, min(50.0, safe_float(crackle_direct, 0.0))), 4
            )
        pop_direct = mot.get("kinematic_pop_metric") or mot.get("pop_metric")
        if pop_direct is not None:
            pop_metric = round(max(0.0, min(50.0, safe_float(pop_direct, 0.0))), 4)

        if accel_raw and isinstance(accel_raw, (list, tuple)) and len(accel_raw) >= 3:
            curr_a = [
                safe_float(accel_raw[0], 0.0),
                safe_float(accel_raw[1], 0.0),
                safe_float(accel_raw[2], 9.8),
            ]
            if (
                self._last_accel_raw is not None
                and self._last_ts_seconds is not None
                and current_ts_seconds is not None
            ):
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
                    a_mag_cubed = (
                        curr_a[0] ** 2 + curr_a[1] ** 2 + curr_a[2] ** 2
                    ) ** 1.5
                    trajectory_curvature = round(
                        min(5.0, cross_mag / max(0.1, a_mag_cubed)), 4
                    )

                    if self._last_jerk_raw is not None:
                        s_x = (d_ax - self._last_jerk_raw[0]) / dt_s
                        s_y = (d_ay - self._last_jerk_raw[1]) / dt_s
                        s_z = (d_az - self._last_jerk_raw[2]) / dt_s
                        current_snap = [s_x, s_y, s_z]
                        if snap_direct is None:
                            snap_metric = round(
                                min(50.0, math.sqrt(s_x * s_x + s_y * s_y + s_z * s_z)),
                                4,
                            )

                        if torsion_direct is None:
                            dot_c_s = cx * s_x + cy * s_y + cz * s_z
                            cross_sq = cross_mag * cross_mag
                            if cross_sq > 0.001:
                                raw_torsion = dot_c_s / cross_sq
                                trajectory_torsion = round(
                                    max(-10.0, min(10.0, raw_torsion)), 4
                                )

                        if self._last_snap_raw is not None:
                            c_x = (s_x - self._last_snap_raw[0]) / dt_s
                            c_y = (s_y - self._last_snap_raw[1]) / dt_s
                            c_z = (s_z - self._last_snap_raw[2]) / dt_s
                            current_crackle = [c_x, c_y, c_z]
                            if crackle_direct is None:
                                crackle_metric = round(
                                    min(
                                        50.0,
                                        math.sqrt(c_x * c_x + c_y * c_y + c_z * c_z),
                                    ),
                                    4,
                                )

                            if (
                                self._last_crackle_raw is not None
                                and pop_direct is None
                            ):
                                p_x = (c_x - self._last_crackle_raw[0]) / dt_s
                                p_y = (c_y - self._last_crackle_raw[1]) / dt_s
                                p_z = (c_z - self._last_crackle_raw[2]) / dt_s
                                pop_metric = round(
                                    min(
                                        50.0,
                                        math.sqrt(p_x * p_x + p_y * p_y + p_z * p_z),
                                    ),
                                    4,
                                )

        # 39. Barometric high-frequency pressure turbulence variance in hPa^2 (56)
        if pressure > 0.0:
            if (
                self._last_ts_seconds is None
                or current_ts_seconds != self._last_ts_seconds
            ):
                self._pressure_window.append(pressure)
            if len(self._pressure_window) >= 2:
                p_mean = sum(self._pressure_window) / len(self._pressure_window)
                p_var = sum((x - p_mean) ** 2 for x in self._pressure_window) / len(
                    self._pressure_window
                )
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
            spectral_bandwidth_khz = round(
                min(8.0, max(0.1, bw_val / 1000.0 if bw_val > 20.0 else bw_val)), 3
            )
        else:
            spectral_bandwidth_khz = round(
                min(
                    8.0, max(0.1, 0.4 * acoustic_centroid_khz + 1.8 * spectral_entropy)
                ),
                3,
            )

        # 42. Wi-Fi multi-AP RSSI distribution skewness (59)
        wifi_skew_direct = conn.get("wifi_rssi_distribution_skewness") or dig.get(
            "wifi_rssi_distribution_skewness"
        )
        if wifi_skew_direct is not None:
            wifi_skewness = round(
                max(-3.0, min(3.0, safe_float(wifi_skew_direct, 0.0))), 3
            )
        else:
            if wifi_conn > 0.0:
                wifi_skewness = round(
                    max(-2.5, min(2.5, (0.5 - wifi_rssi_norm) * 3.0)), 3
                )
            else:
                wifi_skewness = 0.0

        # 43. Battery internal electrochemical Joule heat loss in Watts (60)
        i_amp = abs(battery_current_ua) * 1e-6
        r_ohm = battery_impedance * 1e-3
        joule_heat_w = round(min(5.0, (i_amp**2) * r_ohm), 5)

        # 44. Multi-modal phase space kinetic migration velocity in s^-1 (61)
        phase_velocity = 0.0
        summary_5d = (accel_mag, dev_temp, battery_frac, log_lux, stat_like)
        if (
            self._last_vec_summary is not None
            and self._last_vec_ts is not None
            and current_ts_seconds is not None
        ):
            dt_s = current_ts_seconds - self._last_vec_ts
            if 2.0 <= dt_s <= 300.0:
                dist_sum = math.sqrt(
                    sum(
                        (c1 - c2) ** 2
                        for c1, c2 in zip(summary_5d, self._last_vec_summary)
                    )
                )
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
            if (
                self._last_ts_seconds is None
                or current_ts_seconds != self._last_ts_seconds
            ):
                self._last_az = az_raw_val
        else:
            self._last_az = None

        # 46. Kinematic 3D trajectory Frenet torsion (63) is computed above as trajectory_torsion

        # 47. Phase-space Grassberger-Procaccia correlation dimension estimate (64)
        corr_dim_direct = mot.get("phase_space_correlation_dimension") or dig.get(
            "correlation_dimension"
        )
        if corr_dim_direct is not None:
            corr_dimension = round(
                max(0.0, min(5.0, safe_float(corr_dim_direct, 1.2))), 3
            )
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
                    corr_dimension = (
                        0.15
                        if stat_like >= 0.8
                        else round(min(4.0, 1.2 + 1.8 * min(1.0, mot_energy)), 3)
                    )
            else:
                corr_dimension = (
                    0.15
                    if stat_like >= 0.8
                    else round(min(4.0, 1.2 + 1.8 * min(1.0, mot_energy)), 3)
                )

        # 48. Photometric ambient dynamic excess kurtosis (65)
        lux_kurt_direct = env.get("photometric_flicker_kurtosis") or env.get(
            "lux_kurtosis"
        )
        if lux_kurt_direct is not None:
            flicker_kurtosis = round(
                max(-2.0, min(10.0, safe_float(lux_kurt_direct, 0.0))), 3
            )
        else:
            if len(self._lux_window) >= 4:
                n_lux = len(self._lux_window)
                m_lux = sum(self._lux_window) / n_lux
                var_lux = sum((x - m_lux) ** 2 for x in self._lux_window) / n_lux
                if var_lux > 1e-4:
                    m4_lux = sum((x - m_lux) ** 4 for x in self._lux_window) / n_lux
                    kurt_val = (m4_lux / (var_lux**2)) - 3.0
                    flicker_kurtosis = round(max(-2.0, min(10.0, kurt_val)), 3)
                else:
                    flicker_kurtosis = 0.0
            else:
                flicker_kurtosis = 0.0

        # 49. Acoustic spectral flatness measure / Wiener entropy (66)
        aud_flat_direct = aud.get("spectral_flatness") or aud.get("wiener_entropy")
        if aud_flat_direct is not None:
            spectral_flatness = round(
                max(0.0, min(1.0, safe_float(aud_flat_direct, 0.1))), 3
            )
        else:
            if silence_like >= 0.95 or rms_loudness <= 0.0005:
                spectral_flatness = 0.05
            else:
                raw_flatness = (
                    0.12
                    + 0.72 * spectral_entropy
                    - 0.15 * min(1.0, rms_loudness * 10.0)
                )
                spectral_flatness = round(min(1.0, max(0.01, raw_flatness)), 3)

        # 50. Wi-Fi multi-AP RSSI distribution excess kurtosis (67)
        wifi_kurt_direct = conn.get("wifi_rssi_distribution_kurtosis") or conn.get(
            "wifi_kurtosis"
        )
        if wifi_kurt_direct is not None:
            wifi_kurtosis = round(
                max(-2.0, min(8.0, safe_float(wifi_kurt_direct, 0.0))), 3
            )
        else:
            if wifi_conn > 0.0:
                calc_kurt = max(-2.0, min(8.0, (wifi_skewness**2) - 1.2))
                wifi_kurtosis = round(calc_kurt, 3)
            else:
                wifi_kurtosis = 0.0

        # 51. Geomagnetic total field intensity flux anomaly in uT (68)
        mag_flux_direct = env.get("geomagnetic_total_field_flux") or env.get(
            "mag_flux_anomaly"
        )
        if mag_flux_direct is not None:
            mag_total_flux = round(
                max(0.0, min(100.0, safe_float(mag_flux_direct, 5.0))), 2
            )
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
        dissip_direct = dig.get("thermodynamic_dissipation_ratio") or env.get(
            "dissipation_ratio"
        )
        if dissip_direct is not None:
            thermo_dissipation_ratio = round(
                max(0.0, min(5.0, safe_float(dissip_direct, 0.5))), 4
            )
        else:
            p_elec = (voltage_mv * 1e-3) * (abs(battery_current_ua) * 1e-6)
            p_thermal = 80.0 * (max(0.0, conduction_rate) / 3600.0)
            ratio_calc = p_thermal / max(0.05, p_elec + compute_power_w)
            thermo_dissipation_ratio = round(min(5.0, max(0.0, ratio_calc)), 4)

        # 53. Neuromotor touch micro-slip rotational angular velocity in rad/s (70)
        slip_direct = dig.get("neuromotor_microslip_angular_velocity") or mot.get(
            "microslip_ang_vel"
        )
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
        chrono_direct = dig.get("circadian_phase_disruption_index") or dig.get(
            "chrono_entropy"
        )
        if chrono_direct is not None:
            circadian_disruption = round(
                max(0.0, min(1.0, safe_float(chrono_direct, 0.05))), 3
            )
        else:
            is_night = cos_phase > 0.6
            if is_night:
                disrupt_val = (
                    0.45 * screen_ratio
                    + 0.35 * min(1.0, log_lux / 2.5)
                    + 0.20 * min(1.0, mot_energy / 0.4)
                )
            else:
                is_midday = cos_phase < -0.5
                if (
                    is_midday
                    and log_lux <= 0.1
                    and stat_like >= 0.9
                    and screen_ratio <= 0.05
                ):
                    disrupt_val = 0.5 * stat_like
                else:
                    disrupt_val = 0.02
            circadian_disruption = round(min(1.0, max(0.0, disrupt_val)), 3)

        # 55. Acoustic harmonic-to-noise ratio in dB (73)
        hnr_direct = aud.get("harmonic_to_noise_ratio") or aud.get("hnr_db")
        if hnr_direct is not None:
            hnr_db = round(max(-10.0, min(30.0, safe_float(hnr_direct, 5.0))), 2)
        else:
            if silence_like >= 0.95 or rms_loudness <= 0.0005:
                hnr_db = 0.0
            else:
                raw_hnr = (
                    18.0 * (1.0 - spectral_flatness)
                    + 4.0 * min(3.0, max(0.0, acoustic_crest - 1.0))
                    - 10.0 * spectral_entropy
                )
                hnr_db = round(max(-10.0, min(30.0, raw_hnr)), 2)

        # 56. Photometric dynamic spectral warmth / CCT evolution index (74)
        warmth_direct = env.get("photometric_spectral_warmth_index") or env.get(
            "color_temperature_warmth"
        )
        if warmth_direct is not None:
            warmth_index = round(max(0.0, min(5.0, safe_float(warmth_direct, 1.0))), 3)
        else:
            cct_val = env.get("cct_k") or env.get("color_temperature")
            if cct_val is not None:
                calc_warmth = 6500.0 / max(1500.0, safe_float(cct_val, 4500.0))
                warmth_index = round(min(5.0, max(0.0, calc_warmth)), 3)
            else:
                daily_frac = daily_fraction if "daily_fraction" in locals() else 0.5
                warmth_est = (
                    1.0
                    + 0.8 * max(0.0, math.cos(daily_frac * 2.0 * math.pi))
                    + 0.4 * (1.0 - min(1.0, log_lux / 4.0))
                )
                warmth_index = round(min(5.0, max(0.0, warmth_est)), 3)

        # 57. Barometric vertical atmospheric buoyancy energy flux in mW/kg (75)
        buoyancy_direct = env.get("barometric_buoyancy_energy_flux") or env.get(
            "buoyancy_flux"
        )
        if buoyancy_direct is not None:
            buoyancy_flux = round(
                max(0.0, min(10.0, safe_float(buoyancy_direct, 0.0))), 4
            )
        else:
            flux_val = (
                9.81
                * abs(vertical_velocity)
                * math.sqrt(max(0.0, baro_turbulence))
                * 0.1
            )
            buoyancy_flux = round(min(10.0, max(0.0, flux_val)), 4)

        # 58. Geomagnetic horizontal-vertical quadrupole anisotropy ratio (76)
        aniso_direct = env.get("geomagnetic_quadrupole_anisotropy_ratio") or env.get(
            "mag_anisotropy_ratio"
        )
        if aniso_direct is not None:
            quadrupole_aniso = round(
                max(0.0, min(10.0, safe_float(aniso_direct, 1.0))), 3
            )
        else:
            if mag_raw and isinstance(mag_raw, (list, tuple)) and len(mag_raw) >= 3:
                bx = safe_float(mag_raw[0], 0.0)
                by = safe_float(mag_raw[1], 0.0)
                bz = safe_float(mag_raw[2], 40.0)
                bh = math.sqrt(bx * bx + by * by)
                quadrupole_aniso = round(min(10.0, max(0.0, bh / max(0.5, abs(bz)))), 3)
            else:
                quadrupole_aniso = round(
                    min(10.0, max(0.0, 1.0 + 0.2 * mag_divergence)), 3
                )

        # 59. Wi-Fi cross-band RF channel dispersion Shannon entropy (77)
        disp_direct = conn.get("wifi_channel_dispersion_entropy") or conn.get(
            "wifi_channel_entropy"
        )
        if disp_direct is not None:
            channel_entropy = round(max(0.0, min(5.0, safe_float(disp_direct, 0.0))), 3)
        else:
            if wifi_conn > 0.0:
                ap_cnt = safe_float(conn.get("wifi_ap_count"), 1.0)
                calc_disp = min(
                    4.5,
                    0.4 * wifi_shannon_entropy
                    + 0.8 * math.log(1.0 + max(0.0, ap_cnt * 0.5)),
                )
                channel_entropy = round(max(0.0, calc_disp), 3)
            else:
                channel_entropy = 0.0

        # 60. Thermodynamic electrochemical battery overpotential stress index (78)
        stress_direct = dig.get("thermodynamic_battery_stress_index") or env.get(
            "battery_stress_index"
        )
        if stress_direct is not None:
            battery_stress = round(
                max(0.0, min(5.0, safe_float(stress_direct, 0.2))), 4
            )
        else:
            p_elec_w = (voltage_mv * 1e-3) * (abs(battery_current_ua) * 1e-6)
            temp_delta = max(0.0, dev_temp - 25.0)
            raw_stress = (
                p_elec_w * (1.0 + 0.1 * temp_delta) * (1.0 + (1.0 - battery_frac) * 0.5)
            )
            battery_stress = round(min(5.0, max(0.0, raw_stress)), 4)

        # 61. Neuromotor physiological micro-tremor Lyapunov exponent in s^-1 (79)
        lyap_direct = dig.get("neuromotor_tremor_lyapunov_exponent") or mot.get(
            "tremor_lyapunov_exponent"
        )
        if lyap_direct is not None:
            tremor_lyapunov = round(
                max(-2.0, min(5.0, safe_float(lyap_direct, -0.2))), 4
            )
        else:
            tremor_lyapunov = 0.0
            if (
                self._last_tremor_energy is not None
                and self._last_ts_seconds is not None
                and current_ts_seconds is not None
            ):
                dt_s = current_ts_seconds - self._last_ts_seconds
                if 2.0 <= dt_s <= 300.0:
                    r_tremor = max(0.001, physio_tremor) / max(
                        0.001, self._last_tremor_energy
                    )
                    tremor_lyapunov = round(
                        max(-2.0, min(5.0, math.log(r_tremor) / dt_s)), 4
                    )
            if tremor_lyapunov == 0.0:
                tremor_lyapunov = (
                    -0.25
                    if stat_like >= 0.8
                    else round(min(3.0, 0.15 + 0.35 * min(1.0, mot_energy)), 4)
                )

        # 62. Multimodal phase-space non-equilibrium information flow rate in nats/s (80)
        flow_direct = mot.get("phase_space_information_flow_rate") or dig.get(
            "information_flow_rate"
        )
        if flow_direct is not None:
            information_flow = round(
                max(0.0, min(5.0, safe_float(flow_direct, 0.1))), 4
            )
        else:
            raw_flow = (
                0.5 * phase_velocity
                + 0.2 * (abs(conduction_rate) / 30.0)
                + 0.3 * (jerk_metric * 0.1)
            )
            information_flow = round(min(5.0, max(0.0, raw_flow)), 4)

        # 65. IMU Autocorrelation Decay (83)
        if self._last_ts_seconds is None or current_ts_seconds != self._last_ts_seconds:
            self._accel_history.append(accel_mag)
        if len(self._accel_history) >= 8:
            a_mean = sum(self._accel_history) / len(self._accel_history)
            denom = sum((x - a_mean) ** 2 for x in self._accel_history)
            if denom > 1e-4:
                num1 = sum(
                    (self._accel_history[i] - a_mean)
                    * (self._accel_history[i + 1] - a_mean)
                    for i in range(len(self._accel_history) - 1)
                )
                r1 = max(0.01, min(0.99, abs(num1 / denom)))
                autocorr_decay = round(min(10.0, max(0.1, -1.0 / math.log(r1))), 3)
            else:
                autocorr_decay = 5.0
        else:
            autocorr_decay = 3.0 if stat_like >= 0.8 else 0.8

        # 66. Device Structural Resonance Energy (84)
        res_direct = mot.get("device_structural_resonance_energy")
        if res_direct is not None:
            resonance_energy = round(max(0.0, min(1.0, safe_float(res_direct, 0.1))), 3)
        else:
            resonance_energy = round(
                min(1.0, max(0.01, 0.12 + 0.45 * physio_tremor + 0.25 * mot_energy)), 3
            )

        # 67. Acoustic Dual-Mic Cross-Correlation TDOA (85)
        mic_corr_direct = aud.get("dual_mic_cross_correlation") or aud.get(
            "mic_tdoa_corr"
        )
        if mic_corr_direct is not None:
            mic_cross_corr = round(
                max(-1.0, min(1.0, safe_float(mic_corr_direct, 0.8))), 3
            )
        else:
            mic_cross_corr = (
                0.0
                if silence_like >= 0.95
                else round(max(-1.0, min(1.0, 0.85 - 0.5 * spectral_entropy)), 3)
            )

        # 68. Acoustic Spatial Phase Coherence (86)
        phase_coh_direct = aud.get("spatial_coherence_phase")
        if phase_coh_direct is not None:
            spatial_coherence = round(
                max(0.0, min(1.0, safe_float(phase_coh_direct, 0.7))), 3
            )
        else:
            spatial_coherence = round(
                min(
                    1.0,
                    max(
                        0.05, 0.90 - 0.4 * spectral_entropy - 0.2 * (1.0 - silence_like)
                    ),
                ),
                3,
            )

        # 69. Acoustic Spectral Flux (87)
        flux_direct = aud.get("spectral_flux")
        if flux_direct is not None:
            spectral_flux = round(max(0.0, min(10.0, safe_float(flux_direct, 0.2))), 3)
        else:
            spectral_flux = round(
                min(
                    10.0,
                    max(
                        0.01,
                        0.2
                        + 2.5 * abs(rms_loudness - 0.02)
                        + 1.2 * abs(spectral_entropy - 0.5),
                    ),
                ),
                3,
            )

        # 70. Ultrasonic Proximity Micro-Doppler (88)
        doppler_direct = aud.get("ultrasonic_proximity_micro_doppler") or aud.get(
            "micro_doppler"
        )
        if doppler_direct is not None:
            micro_doppler = round(
                max(0.0, min(5.0, safe_float(doppler_direct, 0.0))), 3
            )
        else:
            micro_doppler = round(
                min(5.0, max(0.0, touch_velocity * 0.8 + (1.0 - silence_like) * 0.3)), 3
            )

        # 71. GNSS Visible Satellite Density (89)
        gnss_sat_direct = plc.get("visible_satellites")
        if gnss_sat_direct is None or (self.feature_extraction_version == 1 and not gnss_sat_direct):
            gnss_sat_direct = plc.get("gnss_sat_count")
        if gnss_sat_direct is not None:
            visible_sats = round(
                max(0.0, min(40.0, safe_float(gnss_sat_direct, 12.0))), 1
            )
        else:
            visible_sats = 16.0 if plc.get("latitude") is not None else 0.0

        # 72. GNSS Mean Carrier-to-Noise Ratio (C/N0) in dB-Hz (90)
        cn0_direct = plc.get("mean_carrier_to_noise_ratio")
        if cn0_direct is None or (self.feature_extraction_version == 1 and not cn0_direct):
            cn0_direct = plc.get("gnss_cn0_dbhz")
        if cn0_direct is not None:
            mean_cn0 = round(max(0.0, min(50.0, safe_float(cn0_direct, 32.0))), 1)
        else:
            mean_cn0 = 34.0 if plc.get("latitude") is not None else 0.0

        # 73. Position Dilution of Precision (PDOP) (91)
        pdop_direct = plc.get("position_dilution_of_precision")
        if pdop_direct is None or (self.feature_extraction_version == 1 and not pdop_direct):
            pdop_direct = plc.get("pdop")
        if pdop_direct is not None:
            pdop_val = round(max(0.5, min(20.0, safe_float(pdop_direct, 1.8))), 2)
        else:
            pdop_val = 1.6 if plc.get("latitude") is not None else 12.0

        # 74. GNSS Doppler Carrier Drift Velocity in m/s (92)
        doppler_drift_direct = plc.get("doppler_velocity_drift")
        if doppler_drift_direct is None or (self.feature_extraction_version == 1 and not doppler_drift_direct):
            doppler_drift_direct = plc.get("gnss_speed_mps")
        if doppler_drift_direct is not None:
            doppler_speed = round(
                max(0.0, min(30.0, safe_float(doppler_drift_direct, 0.0))), 2
            )
        else:
            doppler_speed = round(
                min(30.0, max(0.0, (1.0 - stat_like) * 3.5 + mot_energy * 2.0)), 2
            )

        # 75. SoC Core to Skin Thermal Gradient in deg C (93)
        soc_grad_direct = env.get("thermal_soc_to_skin_gradient") or env.get(
            "soc_skin_gradient"
        )
        if soc_grad_direct is not None:
            soc_skin_grad = round(
                max(-10.0, min(30.0, safe_float(soc_grad_direct, 4.0))), 2
            )
        else:
            soc_skin_grad = round(
                max(
                    -5.0,
                    min(25.0, 2.0 + compute_power_w * 2.8 + (dev_temp - 25.0) * 0.2),
                ),
                2,
            )

        # 76. Modem to Battery Thermal Gradient in deg C (94)
        modem_grad_direct = env.get("thermal_modem_to_battery_gradient")
        if modem_grad_direct is not None:
            modem_bat_grad = round(
                max(-10.0, min(25.0, safe_float(modem_grad_direct, 1.5))), 2
            )
        else:
            modem_bat_grad = round(
                max(
                    -5.0,
                    min(20.0, 1.0 + (cellular_churn * 0.2) + (1.0 - wifi_conn) * 1.5),
                ),
                2,
            )

        # 77. Thermal Relaxation Time Constant in seconds (95)
        relax_direct = env.get("thermal_relaxation_time_constant")
        if relax_direct is not None:
            thermal_relax_time = round(
                max(10.0, min(600.0, safe_float(relax_direct, 120.0))), 1
            )
        else:
            thermal_relax_time = 180.0 if is_plugged else 110.0

        # 78. Irreversible Thermal Entropy Production in mW/K (96)
        s_prod_direct = env.get("thermal_entropy_production_rate")
        if s_prod_direct is not None:
            entropy_prod_rate = round(
                max(0.0, min(10.0, safe_float(s_prod_direct, 0.5))), 3
            )
        else:
            entropy_prod_rate = round(
                min(
                    10.0,
                    max(0.01, (joule_heat_w * 1000.0) / max(273.15, dev_temp + 273.15)),
                ),
                3,
            )

        # 79. Cellular RSRQ in dB (97)
        rsrq_direct = conn.get("cellular_rsrq") or conn.get("rsrq_db")
        if rsrq_direct is not None:
            cellular_rsrq = round(
                max(-25.0, min(0.0, safe_float(rsrq_direct, -10.0))), 1
            )
        else:
            cellular_rsrq = -10.0 if conn.get("cellular_cell_fingerprint") else -20.0

        # 80. Cellular SINR in dB (98)
        sinr_direct = conn.get("cellular_sinr") or conn.get("sinr_db")
        if sinr_direct is not None:
            cellular_sinr = round(
                max(-15.0, min(35.0, safe_float(sinr_direct, 12.0))), 1
            )
        else:
            cellular_sinr = 15.0 if conn.get("cellular_cell_fingerprint") else -5.0

        # 81. BLE Advertising Channels (37,38,39) Entropy (99)
        ble_adv_direct = dig.get("ble_advertising_channel_entropy")
        if ble_adv_direct is not None:
            ble_adv_entropy = round(
                max(0.0, min(2.0, safe_float(ble_adv_direct, 1.05))), 3
            )
        else:
            ble_adv_entropy = round(
                min(1.58, max(0.0, 0.4 * math.log(1.0 + ambient_ble))), 3
            )

        # 82. Wi-Fi Physical Link Speed Jitter Ratio (100)
        wifi_jitter_direct = conn.get("wifi_link_speed_variation_ratio")
        if wifi_jitter_direct is not None:
            wifi_speed_jitter = round(
                max(0.0, min(2.0, safe_float(wifi_jitter_direct, 0.1))), 3
            )
        else:
            wifi_speed_jitter = 0.08 if wifi_conn > 0.0 else 0.0

        # 83. Ambient RF Backscatter Intensity in dB (101)
        backscatter_direct = conn.get("rf_ambient_backscatter_intensity")
        if backscatter_direct is not None:
            rf_backscatter = round(
                max(0.0, min(20.0, safe_float(backscatter_direct, 3.0))), 2
            )
        else:
            rf_backscatter = round(
                min(20.0, max(0.5, ambient_rf_entropy * 2.2 + ble_variance * 0.4)), 2
            )

        # 84. Cellular Timing Advance Distance Proxy in km (102)
        ta_direct = conn.get("cellular_timing_advance_distance") or conn.get(
            "timing_advance_km"
        )
        if ta_direct is not None:
            ta_dist_km = round(max(0.0, min(50.0, safe_float(ta_direct, 1.5))), 2)
        else:
            ta_dist_km = 1.2

        # 85. Touch Contact Ellipse Eccentricity (103)
        ecc_direct = dig.get("touch_contact_ellipse_eccentricity") or dig.get(
            "touch_eccentricity"
        )
        if ecc_direct is not None:
            touch_eccentricity = round(
                max(0.0, min(1.0, safe_float(ecc_direct, 0.5))), 3
            )
        else:
            touch_eccentricity = round(
                min(0.95, max(0.1, 0.35 + touch_velocity * 0.4)), 3
            )

        # 86. Downforce Pressure Derivative (104)
        dp_direct = dig.get("touch_downforce_pressure_derivative")
        if dp_direct is not None:
            touch_downforce_deriv = round(
                max(0.0, min(10.0, safe_float(dp_direct, 0.5))), 3
            )
        else:
            touch_downforce_deriv = round(min(8.0, max(0.0, tap_recoil * 1.2)), 3)

        # 87. Neuromotor Fitts' Law Throughput in bits/s (105)
        fitts_direct = dig.get("neuromotor_fitts_law_throughput") or dig.get(
            "fitts_throughput"
        )
        if fitts_direct is not None:
            fitts_throughput = round(
                max(0.0, min(15.0, safe_float(fitts_direct, 4.5))), 2
            )
        else:
            fitts_throughput = round(
                min(
                    12.0,
                    max(0.5, 4.5 + touch_velocity * 3.0 - touch_interval_jitter * 2.0),
                ),
                2,
            )

        # 88. Biomotor Cadence Decay Slope (106)
        cadence_direct = dig.get("touch_biomotor_cadence_decay_slope")
        if cadence_direct is not None:
            cadence_decay_slope = round(
                max(-2.0, min(2.0, safe_float(cadence_direct, 0.0))), 3
            )
        else:
            cadence_decay_slope = round(max(-1.5, min(1.5, -0.05 * app_switch_rate)), 3)

        # 89. Normalized Touch Contact Area (107)
        area_direct = dig.get("touch_contact_area_normalized") or dig.get("touch_area")
        if area_direct is not None:
            touch_contact_area = round(
                max(0.0, min(1.0, safe_float(area_direct, 0.25))), 3
            )
        else:
            touch_contact_area = 0.28 if screen_ratio > 0.0 else 0.0

        # 90. Touch Gesture Path Tortuosity Ratio (108)
        tortuosity_direct = dig.get("touch_path_tortuosity_ratio")
        if tortuosity_direct is not None:
            touch_tortuosity = round(
                max(1.0, min(5.0, safe_float(tortuosity_direct, 1.1))), 3
            )
        else:
            touch_tortuosity = round(min(4.0, max(1.0, 1.05 + 0.3 * touch_velocity)), 3)

        # 91. CPU Cluster Frequency Scaling Entropy (109)
        cpu_ent_direct = dig.get("cpu_frequency_scaling_entropy")
        if cpu_ent_direct is not None:
            cpu_freq_entropy = round(
                max(0.0, min(3.0, safe_float(cpu_ent_direct, 1.2))), 3
            )
        else:
            cpu_freq_entropy = round(
                min(
                    2.5, max(0.2, 0.8 + compute_power_w * 0.4 + app_switch_rate * 0.15)
                ),
                3,
            )

        # 92. Kernel Context Switch Velocity (110)
        csw_direct = dig.get("kernel_context_switch_velocity")
        if csw_direct is not None:
            context_switch_vel = round(
                max(0.0, min(50.0, safe_float(csw_direct, 12.0))), 2
            )
        else:
            context_switch_vel = round(
                min(
                    45.0, max(2.0, 8.0 + app_switch_rate * 6.0 + compute_power_w * 4.0)
                ),
                2,
            )

        # 93. Memory Pressure Stall Ratio (PSI) (111)
        psi_direct = dig.get("memory_pressure_stall_ratio") or dig.get("memory_psi")
        if psi_direct is not None:
            memory_psi = round(max(0.0, min(1.0, safe_float(psi_direct, 0.02))), 4)
        else:
            memory_psi = round(
                min(0.8, max(0.001, 0.01 + attention_frag_entropy * 0.04)), 4
            )

        # 94. SurfaceFlinger Frame Rendering Jank Variance (112)
        jank_direct = dig.get("display_frame_pacing_jank_variance") or dig.get(
            "frame_jank_variance"
        )
        if jank_direct is not None:
            jank_variance = round(max(0.0, min(10.0, safe_float(jank_direct, 0.5))), 3)
        else:
            jank_variance = round(min(8.0, max(0.05, 0.2 + app_switch_rate * 0.6)), 3)

        # 95. Storage I/O Wait Latency Pressure (113)
        iowait_direct = dig.get("io_wait_latency_proxy")
        if iowait_direct is not None:
            iowait_pressure = round(
                max(0.0, min(1.0, safe_float(iowait_direct, 0.03))), 3
            )
        else:
            iowait_pressure = round(
                min(0.7, max(0.01, 0.02 + compute_power_w * 0.05)), 3
            )

        # 96. CPU User-to-System Mode Execution Ratio (114)
        cpu_ratio_direct = dig.get("cpu_user_system_mode_ratio")
        if cpu_ratio_direct is not None:
            cpu_usr_sys_ratio = round(
                max(0.0, min(10.0, safe_float(cpu_ratio_direct, 3.2))), 2
            )
        else:
            cpu_usr_sys_ratio = 3.5

        # 97. Multi-scale R/S Hurst Exponent H (115)
        hurst_direct = mot.get("fractal_hurst_exponent")
        if hurst_direct is not None:
            hurst_exp = round(max(0.0, min(1.0, safe_float(hurst_direct, 0.65))), 3)
        else:
            hurst_exp = (
                0.82
                if stat_like >= 0.8
                else round(min(0.95, max(0.2, 0.55 + 0.3 * mot_energy)), 3)
            )

        # 98. Permutation Entropy Complexity (116)
        perm_direct = mot.get("permutation_entropy_complexity")
        if perm_direct is not None:
            permutation_entropy = round(
                max(0.0, min(1.0, safe_float(perm_direct, 0.4))), 3
            )
        else:
            permutation_entropy = (
                0.15
                if stat_like >= 0.8
                else round(min(0.98, max(0.1, 0.4 + 0.45 * mot_energy)), 3)
            )

        # 99. RQA Recurrence Determinism (DET) (117)
        det_direct = mot.get("rqa_recurrence_determinism_det")
        if det_direct is not None:
            rqa_determinism = round(max(0.0, min(1.0, safe_float(det_direct, 0.8))), 3)
        else:
            rqa_determinism = round(
                min(0.99, max(0.05, recurrence_density * 0.8 + 0.15)), 3
            )

        # 100. RQA Laminarity Trapping Ratio (LAM) (118)
        lam_direct = mot.get("rqa_laminarity_vertical_trapping")
        if lam_direct is not None:
            rqa_laminarity = round(max(0.0, min(1.0, safe_float(lam_direct, 0.75))), 3)
        else:
            rqa_laminarity = round(
                min(0.99, max(0.05, stat_like * 0.7 + recurrence_density * 0.25)), 3
            )

        # 101. Lempel-Ziv Algorithmic Complexity (119)
        lz_direct = mot.get("lempel_ziv_algorithmic_complexity")
        if lz_direct is not None:
            lz_complexity = round(max(0.0, min(2.0, safe_float(lz_direct, 0.5))), 3)
        else:
            lz_complexity = round(
                min(1.8, max(0.05, (1.0 - rqa_determinism) * 1.2 + 0.1)), 3
            )

        # 102. Fisher Information Measure (120)
        fisher_direct = mot.get("fisher_information_measure")
        if fisher_direct is not None:
            fisher_info = round(max(0.0, min(10.0, safe_float(fisher_direct, 2.5))), 3)
        else:
            fisher_info = round(
                min(9.5, max(0.1, rqa_determinism * 5.0 + (1.0 - mot_energy) * 2.5)), 3
            )

        # 103. Phase Space Mean Trapping Residence Time in seconds (121)
        trap_direct = mot.get("phase_space_trapping_time_seconds")
        if trap_direct is not None:
            trapping_time_s = round(
                max(0.0, min(3600.0, safe_float(trap_direct, 60.0))), 1
            )
        else:
            trapping_time_s = round(
                min(
                    3600.0,
                    residence_persistence_hours * 3600.0 + rqa_laminarity * 120.0,
                ),
                1,
            )

        # 104. Multiscale Approximate Entropy (ApEn) (122)
        apen_direct = mot.get("multiscale_approximate_entropy")
        if apen_direct is not None:
            approximate_entropy = round(
                max(0.0, min(3.0, safe_float(apen_direct, 0.6))), 3
            )
        else:
            approximate_entropy = round(
                min(2.8, max(0.05, permutation_entropy * 2.2)), 3
            )

        # 105. Bifurcation Stability Margin (123)
        bifurc_direct = mot.get("bifurcation_stability_margin")
        if bifurc_direct is not None:
            bifurcation_margin = round(
                max(0.0, min(2.0, safe_float(bifurc_direct, 0.8))), 3
            )
        else:
            bifurcation_margin = round(
                max(0.01, min(1.8, 1.0 - tremor_lyapunov * 0.3)), 3
            )

        # 106. Teager-Kaiser Non-Linear Instantaneous Energy (124)
        teager_direct = mot.get("non_linear_energy_operator_teager")
        if teager_direct is not None:
            teager_energy = round(
                max(0.0, min(10.0, safe_float(teager_direct, 0.3))), 3
            )
        else:
            teager_energy = round(
                min(9.0, max(0.01, mot_energy * 4.0 + rot_energy * 0.5)), 3
            )

        # 107. Phase Space Orbital Angular Momentum Norm (125)
        ang_mom_direct = mot.get("phase_space_angular_momentum")
        if ang_mom_direct is not None:
            orbital_angular_momentum = round(
                max(0.0, min(20.0, safe_float(ang_mom_direct, 1.0))), 3
            )
        else:
            orbital_angular_momentum = round(
                min(18.0, max(0.05, phase_velocity * 3.5 + rot_jerk * 0.2)), 3
            )

        # 108. Topological Betti Number Proxy (126)
        betti_direct = mot.get("topological_betti_number_proxy")
        if betti_direct is not None:
            betti_proxy = round(max(0.0, min(5.0, safe_float(betti_direct, 1.0))), 2)
        else:
            betti_proxy = round(min(4.5, max(0.1, corr_dimension * 0.8)), 2)

        # 109. Kaplan-Yorke Fractal Lyapunov Attractor Dimension (127)
        ky_direct = mot.get("dissipative_lyapunov_dimension_ky")
        if ky_direct is not None:
            kaplan_yorke_dim = round(max(0.0, min(6.0, safe_float(ky_direct, 1.5))), 3)
        else:
            kaplan_yorke_dim = round(
                min(5.5, max(0.2, corr_dimension + max(0.0, tremor_lyapunov * 0.5))), 3
            )

        # 110. Composite Multimodal Holographic Phase Coherence (128)
        cohere_direct = mot.get("holo_cosmic_coherence_index")
        if cohere_direct is not None:
            holo_coherence = round(
                max(0.0, min(1.0, safe_float(cohere_direct, 0.85))), 3
            )
        else:
            raw_cohere = (
                rqa_determinism * 0.3
                + (1.0 - circadian_disruption) * 0.3
                + spatial_coherence * 0.2
                + (1.0 - memory_psi) * 0.2
            )
            holo_coherence = round(min(1.0, max(0.05, raw_cohere)), 3)

        # Update persistent state across frames
        if current_ts_seconds is not None and (
            self._last_ts_seconds is None or current_ts_seconds != self._last_ts_seconds
        ):
            self._last_ts_seconds = current_ts_seconds
            self._last_pressure = pressure
            self._last_temp = dev_temp
            self._last_voltage_mv = voltage_mv
            self._last_current_ua = battery_current_ua
            if (
                accel_raw
                and isinstance(accel_raw, (list, tuple))
                and len(accel_raw) >= 3
            ):
                self._last_accel_raw = [
                    safe_float(accel_raw[0], 0.0),
                    safe_float(accel_raw[1], 0.0),
                    safe_float(accel_raw[2], 9.8),
                ]
            else:
                self._last_accel_raw = None
            self._last_jerk_raw = current_jerk
            self._last_tremor_energy = physio_tremor
            self._last_snap_raw = current_snap
            self._last_crackle_raw = current_crackle

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
            "kinematic_snap_metric": snap_metric,
            "acoustic_harmonic_to_noise_ratio": hnr_db,
            "photometric_spectral_warmth_index": warmth_index,
            "barometric_buoyancy_energy_flux": buoyancy_flux,
            "geomagnetic_quadrupole_anisotropy_ratio": quadrupole_aniso,
            "wifi_channel_dispersion_entropy": channel_entropy,
            "thermodynamic_battery_stress_index": battery_stress,
            "neuromotor_tremor_lyapunov_exponent": tremor_lyapunov,
            "phase_space_information_flow_rate": information_flow,
            "kinematic_crackle_metric": crackle_metric,
            "kinematic_pop_metric": pop_metric,
            "imu_temporal_autocorrelation_decay": autocorr_decay,
            "device_structural_resonance_energy": resonance_energy,
            "acoustic_dual_mic_cross_correlation": mic_cross_corr,
            "acoustic_spatial_coherence_phase": spatial_coherence,
            "acoustic_spectral_flux": spectral_flux,
            "ultrasonic_proximity_micro_doppler": micro_doppler,
            "gnss_visible_satellite_density": visible_sats,
            "gnss_mean_carrier_to_noise_ratio": mean_cn0,
            "gnss_position_dilution_of_precision": pdop_val,
            "gnss_doppler_velocity_drift": doppler_speed,
            "thermal_soc_to_skin_gradient": soc_skin_grad,
            "thermal_modem_to_battery_gradient": modem_bat_grad,
            "thermal_relaxation_time_constant": thermal_relax_time,
            "thermal_entropy_production_rate": entropy_prod_rate,
            "cellular_reference_signal_quality": cellular_rsrq,
            "cellular_signal_to_interference_noise": cellular_sinr,
            "ble_advertising_channel_entropy": ble_adv_entropy,
            "wifi_link_speed_variation_ratio": wifi_speed_jitter,
            "rf_ambient_backscatter_intensity": rf_backscatter,
            "cellular_timing_advance_distance": ta_dist_km,
            "touch_contact_ellipse_eccentricity": touch_eccentricity,
            "touch_downforce_pressure_derivative": touch_downforce_deriv,
            "neuromotor_fitts_law_throughput": fitts_throughput,
            "touch_biomotor_cadence_decay_slope": cadence_decay_slope,
            "touch_contact_area_normalized": touch_contact_area,
            "touch_path_tortuosity_ratio": touch_tortuosity,
            "cpu_frequency_scaling_entropy": cpu_freq_entropy,
            "kernel_context_switch_velocity": context_switch_vel,
            "memory_pressure_stall_ratio": memory_psi,
            "display_frame_pacing_jank_variance": jank_variance,
            "io_wait_latency_proxy": iowait_pressure,
            "cpu_user_system_mode_ratio": cpu_usr_sys_ratio,
            "fractal_hurst_exponent": hurst_exp,
            "permutation_entropy_complexity": permutation_entropy,
            "rqa_recurrence_determinism_det": rqa_determinism,
            "rqa_laminarity_vertical_trapping": rqa_laminarity,
            "lempel_ziv_algorithmic_complexity": lz_complexity,
            "fisher_information_measure": fisher_info,
            "phase_space_trapping_time_seconds": trapping_time_s,
            "multiscale_approximate_entropy": approximate_entropy,
            "bifurcation_stability_margin": bifurcation_margin,
            "non_linear_energy_operator_teager": teager_energy,
            "phase_space_angular_momentum": orbital_angular_momentum,
            "topological_betti_number_proxy": betti_proxy,
            "dissipative_lyapunov_dimension_ky": kaplan_yorke_dim,
            "holo_cosmic_coherence_index": holo_coherence,
        }

        # Topological & Categorical identifiers
        spatial_cell = plc.get("spatial_cell", "NO_GEO")
        wifi_fp = (
            conn.get("wifi_fingerprint")
            or conn.get("wifi_environment_fingerprint")
            or "NO_WIFI"
        )
        cell_fp = conn.get("cellular_cell_fingerprint") or "NO_CELL"

        topological_map: Dict[str, str] = {
            "spatial_cell": spatial_cell,
            "wifi_fingerprint": wifi_fp,
            "cellular_fingerprint": cell_fp,
            "plugged_state": plugged_str,
            "charging_status": status_str,
        }

        return continuous_map, topological_map

    def fit_discovery_window(
        self,
        observations: List[Dict[str, Any]],
        description: str = "Day 1–7 Discovery Window",
    ) -> NormalizationParams:
        """
        Computes median and IQR scaling parameters across observations.
        """
        if not observations:
            raise ValueError("Cannot fit normalizer on empty observation list.")

        self.reset_state()
        feature_values: Dict[str, List[float]] = {
            name: [] for name in CONTINUOUS_FEATURE_NAMES
        }

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
            feature_extraction_version=self.feature_extraction_version,
        )
        return self.params

    def fit_robust_window(
        self,
        observations: List[Dict[str, Any]],
        description: str = "robust_range_v2 window",
    ) -> NormalizationParams:
        """
        Median/scale fit that is not fooled by a deceptively narrow interquartile range.

        The scale is max(IQR, (q95 - q05) / 2.44): for normal data the two agree (IQR is
        1.349 sigma, q95 - q05 is 3.29 sigma), but a channel that is steady for a while and
        then drifts has a tiny IQR, which pins every later value at the +/-10 bound. The
        result never falls below the legacy IQR-based scale. Daily and weekly phases are
        analytic functions of the clock, so a short window cannot estimate them: they get
        the fixed scale of a uniformly distributed phase (median 0, IQR sqrt(2)).
        """
        if not observations:
            raise ValueError("Cannot fit normalizer on empty observation list.")

        self.reset_state()
        raw_maps = [self.extract_raw_features(obs)[0] for obs in observations]
        return self.fit_robust_raw(raw_maps, description)

    def fit_robust_raw(
        self,
        raw_maps: List[Dict[str, float]],
        description: str = "robust_range_v2 window",
    ) -> NormalizationParams:
        """fit_robust_window on already extracted raw feature maps (same policy)."""
        if not raw_maps:
            raise ValueError("Cannot fit normalizer on empty observation list.")

        feature_values: Dict[str, List[float]] = {name: [] for name in CONTINUOUS_FEATURE_NAMES}
        for c_map in raw_maps:
            for k, v in c_map.items():
                feature_values[k].append(v)

        medians: Dict[str, float] = {}
        iqrs: Dict[str, float] = {}
        for k in CONTINUOUS_FEATURE_NAMES:
            vals = sorted(feature_values[k])
            n = len(vals)
            if k in PHASE_FEATURES:
                medians[k] = 0.0
                iqrs[k] = round(math.sqrt(2.0), 6)
                continue
            med = statistics.median(vals)
            iqr = vals[int(n * 0.75)] - vals[int(n * 0.25)]
            if iqr < 1e-6:
                diff = vals[-1] - vals[0]
                iqr = diff if diff > 1e-6 else 1.0
            spread = (vals[min(n - 1, int(n * 0.95))] - vals[int(n * 0.05)]) / 2.44
            medians[k] = round(float(med), 6)
            iqrs[k] = round(float(max(iqr, spread)), 6)

        self.params = NormalizationParams(
            feature_names=CONTINUOUS_FEATURE_NAMES,
            medians=medians,
            iqrs=iqrs,
            fitted_observation_count=len(raw_maps),
            fit_window_description=f"robust_range_v2: {description}",
            feature_extraction_version=self.feature_extraction_version,
        )
        return self.params

    def transform(self, obs: Dict[str, Any]) -> Tuple[List[float], Dict[str, str]]:
        """
        Transforms a single observation into a standardized continuous feature vector
        and canonical topological descriptors.
        """
        if self.params is None:
            raise RuntimeError(
                "MultimodalNormalizer must be fitted or initialized with NormalizationParams before transform."
            )

        c_map, topo_map = self.extract_raw_features(obs)
        return self.scale_raw(c_map), topo_map

    def scale_raw(
        self,
        c_map: Dict[str, float],
        params: Optional[NormalizationParams] = None,
    ) -> List[float]:
        """Scale one frame of raw continuous features with the given (or own) parameters."""
        params = params or self.params
        vector: List[float] = []

        for k in params.feature_names:
            raw_val = c_map.get(k, 0.0)
            med = params.medians.get(k, 0.0)
            iqr = params.iqrs.get(k, 1.0)
            if iqr < 1e-6:
                iqr = 1.0
            # Robust scaling: (x - median) / IQR
            scaled = (raw_val - med) / iqr
            # Bound extremes to [-10.0, 10.0] to prevent outlier distortion
            bounded = max(-10.0, min(10.0, scaled))
            vector.append(round(bounded, 5))

        return vector
