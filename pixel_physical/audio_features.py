"""Audio context feature extraction for Pixel 6a without persisting raw audio."""

import math
import os
from pathlib import Path
import subprocess
import time
from typing import Any, Dict, Optional, Tuple
import wave

import numpy as np

from . import config


def extract_features_from_pcm(samples: np.ndarray, sample_rate: int = 16000) -> Dict[str, Any]:
    """
    Extracts acoustic features from normalized float32 PCM samples [-1.0, 1.0].
    Guarantees no NaN/Inf values.
    """
    if len(samples) == 0:
        return {
            "rms_loudness": 0.0,
            "peak_level": 0.0,
            "zero_crossing_rate": 0.0,
            "spectral_centroid_hz": 0.0,
            "spectral_bandwidth_hz": 0.0,
            "spectral_entropy": 0.0,
            "silence_likelihood": 1.0,
            "speech_likelihood": 0.0,
            "music_likelihood": 0.0,
        }

    # Time-domain features
    rms = float(np.sqrt(np.mean(samples ** 2)))
    peak = float(np.max(np.abs(samples)))
    zcr = float(np.mean(np.abs(np.diff(np.signbit(samples))))) if len(samples) > 1 else 0.0

    if math.isnan(rms) or math.isinf(rms):
        rms = 0.0
    if math.isnan(peak) or math.isinf(peak):
        peak = 0.0
    if math.isnan(zcr) or math.isinf(zcr):
        zcr = 0.0

    # If practically total silence
    if peak < 1e-5 or rms < 1e-5:
        return {
            "rms_loudness": round(rms, 5),
            "peak_level": round(peak, 5),
            "zero_crossing_rate": round(zcr, 4),
            "spectral_centroid_hz": 0.0,
            "spectral_bandwidth_hz": 0.0,
            "spectral_entropy": 0.0,
            "silence_likelihood": 1.0,
            "speech_likelihood": 0.0,
            "music_likelihood": 0.0,
        }

    # Frequency-domain spectral features
    spectrum = np.abs(np.fft.rfft(samples))
    freqs = np.fft.rfftfreq(len(samples), d=1.0 / sample_rate)
    sum_spec = float(np.sum(spectrum))

    if sum_spec > 1e-9:
        centroid = float(np.sum(freqs * spectrum) / sum_spec)
        variance = float(np.sum(((freqs - centroid) ** 2) * spectrum) / sum_spec)
        bandwidth = float(np.sqrt(max(0.0, variance)))

        # Normalized spectral entropy
        p = spectrum / sum_spec
        p = p[p > 1e-12]
        if len(p) > 1:
            entropy = float(-np.sum(p * np.log(p)) / np.log(len(spectrum)))
        else:
            entropy = 0.0
    else:
        centroid = 0.0
        bandwidth = 0.0
        entropy = 0.0

    # Ensure finite floats
    if math.isnan(centroid) or math.isinf(centroid):
        centroid = 0.0
    if math.isnan(bandwidth) or math.isinf(bandwidth):
        bandwidth = 0.0
    if math.isnan(entropy) or math.isinf(entropy):
        entropy = 0.0

    # Likelihood heuristics
    silence_prob = 1.0 if rms < 0.002 else max(0.0, min(1.0, 1.0 - (rms / 0.03)))

    # Speech likelihood: vocal range (300-3400 Hz centroid), moderate entropy, non-silent
    speech_prob = 0.0
    if silence_prob < 0.8 and 300.0 <= centroid <= 3400.0 and 0.3 <= entropy <= 0.85:
        speech_prob = min(1.0, (1.0 - silence_prob) * 0.8)

    # Music likelihood: wider bandwidth, higher harmonic richness
    music_prob = 0.0
    if silence_prob < 0.8 and bandwidth > 1500.0 and entropy > 0.6:
        music_prob = min(1.0, (1.0 - silence_prob) * 0.6)

    return {
        "rms_loudness": round(rms, 5),
        "peak_level": round(peak, 5),
        "zero_crossing_rate": round(zcr, 4),
        "spectral_centroid_hz": round(centroid, 1),
        "spectral_bandwidth_hz": round(bandwidth, 1),
        "spectral_entropy": round(entropy, 4),
        "silence_likelihood": round(silence_prob, 3),
        "speech_likelihood": round(speech_prob, 3),
        "music_likelihood": round(music_prob, 3),
    }


def sample_audio_context(
    record_seconds: float = config.AUDIO_RECORD_DURATION_SECONDS,
    timeout: float = 6.0,
) -> Tuple[Dict[str, Any], str]:
    """
    Records a short audio window to a temporary file, computes acoustic features,
    and immediately purges the raw audio files.
    Returns (audio_dict, availability_status).
    """
    if not Path(config.MIC_BIN).exists():
        return {"error": "MIC_BIN_NOT_FOUND"}, "UNAVAILABLE"

    # Temporary paths
    temp_m4a = Path("/data/data/com.termux/files/home") / f"temp_phys_audio_{int(time.time()*1000)}.m4a"
    temp_wav = Path(f"/tmp/temp_phys_audio_{int(time.time()*1000)}.wav")

    try:
        # 1. Start recording
        cmd = [config.MIC_BIN, "-f", str(temp_m4a), "-l", str(int(record_seconds))]
        res = subprocess.run(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=timeout)

        # Wait for duration
        time.sleep(record_seconds + 0.3)

        # Ensure stopped
        subprocess.run([config.MIC_BIN, "-q"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=2.0)

        if not temp_m4a.exists() or temp_m4a.stat().st_size < 100:
            return {"error": "EMPTY_OR_MISSING_RECORDING"}, "TEMPORARILY_UNAVAILABLE"

        # 2. Convert to PCM wav 16kHz mono via ffmpeg
        ffmpeg_cmd = [
            config.FFMPEG_BIN,
            "-y",
            "-i", str(temp_m4a),
            "-vn",
            "-acodec", "pcm_s16le",
            "-ac", "1",
            "-ar", "16000",
            str(temp_wav),
        ]
        conv_res = subprocess.run(ffmpeg_cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=4.0)
        if conv_res.returncode != 0 or not temp_wav.exists():
            return {"error": "AUDIO_CONVERSION_FAILED"}, "ERROR"

        # 3. Read PCM samples
        with wave.open(str(temp_wav), "rb") as wf:
            n_frames = wf.getnframes()
            sr = wf.getframerate()
            raw_bytes = wf.readframes(n_frames)
            samples = np.frombuffer(raw_bytes, dtype=np.int16).astype(np.float32) / 32768.0

        features = extract_features_from_pcm(samples, sample_rate=sr)
        return features, "AVAILABLE"

    except subprocess.TimeoutExpired:
        return {"error": "AUDIO_RECORD_TIMEOUT"}, "TEMPORARILY_UNAVAILABLE"
    except Exception as exc:
        return {"error": str(exc)}, "ERROR"
    finally:
        # Strictly purge raw audio files immediately
        if temp_m4a.exists():
            try:
                temp_m4a.unlink()
            except Exception:
                pass
        if temp_wav.exists():
            try:
                temp_wav.unlink()
            except Exception:
                pass
