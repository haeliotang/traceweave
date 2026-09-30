"""Configuration parameters for Physical Observation Stack v1."""

import os
from pathlib import Path

PROTOCOL = "pixel-sensorium.physical-observation.v1"
OBSERVATION_PROTOCOL = PROTOCOL
TRANSITION_PROTOCOL = "pixel-sensorium.physical-transition.v1"
EPISODE_PROTOCOL = "pixel-sensorium.physical-episode.v1"

# Storage paths
DEFAULT_LEDGER_DIR = Path.home() / ".pixel_sensorium"
OBSERVATIONS_LEDGER = DEFAULT_LEDGER_DIR / "physical_observations.jsonl"
TRANSITIONS_LEDGER = DEFAULT_LEDGER_DIR / "physical_transitions.jsonl"
EPISODES_LEDGER = DEFAULT_LEDGER_DIR / "physical_episodes.jsonl"
LIVE_STATE_PATH = DEFAULT_LEDGER_DIR / "physical_live_state.json"

# Tool binaries
TERMUX_BIN_DIR = Path("/data/data/com.termux/files/usr/bin")
SENSOR_BIN = str(TERMUX_BIN_DIR / "termux-sensor")
LOCATION_BIN = str(TERMUX_BIN_DIR / "termux-location")
BATTERY_BIN = str(TERMUX_BIN_DIR / "termux-battery-status")
WIFI_CONN_BIN = str(TERMUX_BIN_DIR / "termux-wifi-connectioninfo")
WIFI_SCAN_BIN = str(TERMUX_BIN_DIR / "termux-wifi-scaninfo")
TELEPHONY_DEV_BIN = str(TERMUX_BIN_DIR / "termux-telephony-deviceinfo")
TELEPHONY_CELL_BIN = str(TERMUX_BIN_DIR / "termux-telephony-cellinfo")
AUDIO_INFO_BIN = str(TERMUX_BIN_DIR / "termux-audio-info")
MIC_BIN = str(TERMUX_BIN_DIR / "termux-microphone-record")
FFMPEG_BIN = "/usr/bin/ffmpeg" if Path("/usr/bin/ffmpeg").exists() else "ffmpeg"

# Timeouts and intervals
SENSOR_TIMEOUT_SECONDS = 5.0
LOCATION_TIMEOUT_SECONDS = 15.0
AUDIO_RECORD_DURATION_SECONDS = 2.0
SUBPROCESS_TIMEOUT_SECONDS = 6.0

# Detection thresholds
TRANSITION_SCORE_THRESHOLD = 1.0
EPISODE_STABILITY_TIMEOUT_SECONDS = 300.0  # 5 minutes without significant transition
STATIONARY_ACCEL_VARIANCE_MAX = 0.05
STATIONARY_GYRO_ENERGY_MAX = 0.005

# Privacy and bounds
MAX_FINGERPRINT_LEN = 16
MAX_CACHED_OBSERVATIONS = 100
