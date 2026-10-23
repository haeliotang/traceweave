"""V2 audio sampling with PCM-derived evidence; no raw-audio persistence."""

from pathlib import Path
import subprocess
import time
from typing import Any, Dict, Tuple
import wave
import numpy as np
from pixel_physical import config
from pixel_physical.audio_features import extract_features_from_pcm


def extract_with_provenance(samples, sample_rate=16000):
    features = extract_features_from_pcm(samples, sample_rate)
    if len(samples) == 0:
        fields = {name: 'empty_pcm_default' for name in features}
    else:
        fields = {name: 'heuristic_from_pcm' if name.endswith('_likelihood') else 'derived_from_pcm'
                  for name in features}
    features['_field_provenance'] = fields
    return features


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

        features = extract_with_provenance(samples, sample_rate=sr)
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
