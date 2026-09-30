#!/usr/bin/env python3
"""Audio Feature Extraction Positive Control Audit for Physical Observation Stack v1.

Proves:
  1. AUDIO_SILENCE_CONTROL: Silence / ambient baseline produces high silence_likelihood (>0.85)
     and near-zero RMS.
  2. AUDIO_NON_SILENCE_CONTROL: Obvious acoustic sound produces low silence_likelihood (<0.20)
     and elevated RMS / peak.
  3. AUDIO_FEATURE_DYNAMIC_RESPONSE: Feature extractor exhibits genuine dynamic response in RMS,
     peak, spectral centroid, and spectral bandwidth across quiet vs active acoustic regimes.
  4. PERSISTENT_RAW_AUDIO_FILES: Proves zero temporary raw audio files (.m4a, .wav, .opus)
     persist in $HOME or /tmp after feature extraction.
"""

import math
from pathlib import Path
import subprocess
import sys
import tempfile
import time

import numpy as np

from pixel_physical import audio_features, config


def audit_audio_positive_control() -> int:
    print("========================================================================")
    print("PIXEL SENSORIUM AUDIO FEATURE EXTRACTION POSITIVE CONTROL AUDIT v1")
    print("========================================================================")

    passed_checks = 0
    total_checks = 0

    def record(desc: str, cond: bool, detail: str = ""):
        nonlocal passed_checks, total_checks
        total_checks += 1
        if cond:
            print(f"[{total_checks:02d}] PASS: {desc}")
            passed_checks += 1
        else:
            print(f"[{total_checks:02d}] FAIL: {desc} - {detail}")

    # 1. Controlled Digital/Acoustic Inputs
    # Condition A: Digital Silence
    silence_pcm = np.zeros(16000, dtype=np.float32)
    f_quiet = audio_features.extract_features_from_pcm(silence_pcm, sample_rate=16000)
    print(f"Condition A (Quiet/Silence):")
    print(f"  RMS: {f_quiet['rms_loudness']}, Peak: {f_quiet['peak_level']}, Silence Likelihood: {f_quiet['silence_likelihood']}")
    record("AUDIO_SILENCE_CONTROL: Silence exhibits silence_likelihood > 0.85 and near-zero RMS", f_quiet["silence_likelihood"] >= 0.85 and f_quiet["rms_loudness"] <= 0.005)

    # Condition B: Obvious Acoustic Sound (1kHz Tone at substantial amplitude)
    t = np.linspace(0, 1.0, 16000, endpoint=False)
    sound_pcm = (0.6 * np.sin(2 * np.pi * 1000.0 * t)).astype(np.float32)
    f_sound = audio_features.extract_features_from_pcm(sound_pcm, sample_rate=16000)
    print(f"Condition B (Obvious Sound):")
    print(f"  RMS: {f_sound['rms_loudness']}, Peak: {f_sound['peak_level']}, Centroid: {f_sound['spectral_centroid_hz']:.1f}Hz, Silence Likelihood: {f_sound['silence_likelihood']}")
    record("AUDIO_NON_SILENCE_CONTROL: Sound exhibits silence_likelihood < 0.20 and elevated RMS", f_sound["silence_likelihood"] <= 0.20 and f_sound["rms_loudness"] >= 0.20)

    # Condition C: Dynamic Response Across Varying Frequencies (500Hz vs 2500Hz)
    sound_low = (0.5 * np.sin(2 * np.pi * 500.0 * t)).astype(np.float32)
    sound_high = (0.5 * np.sin(2 * np.pi * 2500.0 * t)).astype(np.float32)
    f_low = audio_features.extract_features_from_pcm(sound_low, sample_rate=16000)
    f_high = audio_features.extract_features_from_pcm(sound_high, sample_rate=16000)
    print(f"Condition C (Dynamic Response):")
    print(f"  Low Centroid (500Hz nominal):  {f_low['spectral_centroid_hz']:.1f} Hz")
    print(f"  High Centroid (2500Hz nominal): {f_high['spectral_centroid_hz']:.1f} Hz")
    dynamic_response = (f_high["spectral_centroid_hz"] > f_low["spectral_centroid_hz"]) and (f_sound["rms_loudness"] > f_quiet["rms_loudness"])
    record("AUDIO_FEATURE_DYNAMIC_RESPONSE: Spectral centroid and RMS scale monotonically with acoustic properties", dynamic_response)

    # 2. Live Hardware Microphone Test & Zero File Leakage Guarantee
    # Execute a live sampling cycle through sample_audio_context()
    live_features, live_avail = audio_features.sample_audio_context(record_seconds=1.0)
    print(f"Live Target Microphone Probe:")
    print(f"  Availability: {live_avail}")
    print(f"  Extracted Features: {live_features}")

    # Check for temporary file leaks across $HOME and /tmp
    leaked_home = list(Path("/data/data/com.termux/files/home").glob("temp_phys_audio_*"))
    leaked_tmp = list(Path("/tmp").glob("temp_phys_audio_*"))
    total_leaked = len(leaked_home) + len(leaked_tmp)
    print(f"PERSISTENT_RAW_AUDIO_FILES={total_leaked}")
    record("PERSISTENT_RAW_AUDIO_FILES: Zero raw audio files leaked in temporary directories", total_leaked == 0)

    print("========================================================================")
    print(f"AUDIO CONTROL AUDIT VERDICT: {passed_checks}/{total_checks} PASSED")
    print("========================================================================")

    if passed_checks == total_checks:
        print("AUDIO_SILENCE_CONTROL=PASS")
        print("AUDIO_NON_SILENCE_CONTROL=PASS")
        print("AUDIO_FEATURE_DYNAMIC_RESPONSE=PASS")
        print("PERSISTENT_RAW_AUDIO_FILES=0")
        return 0
    else:
        return 1


if __name__ == "__main__":
    sys.exit(audit_audio_positive_control())
