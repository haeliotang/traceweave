# Keep repository and research-package imports available to CLI subprocesses.
import os
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PYTHON_PATHS = [str(ROOT), str(ROOT / "research")]
if os.environ.get("PYTHONPATH"):
    PYTHON_PATHS.append(os.environ["PYTHONPATH"])
os.environ["PYTHONPATH"] = os.pathsep.join(PYTHON_PATHS)

# These v2 physical-device tests depend on modules removed from the tracked
# repository. Keep the test files as historical specifications, but do not
# collect them as runnable tests until their implementation is restored.
collect_ignore = [
    "test_physical_acquisition_v2.py",
    "test_physical_api_preflight.py",
    "test_physical_audio_provenance_v2.py",
    "test_physical_connectivity_provenance_v2.py",
    "test_physical_device_state_provenance_v2.py",
    "test_physical_location_v2.py",
    "test_physical_observation_provenance_v2.py",
    "test_physical_sensors_provenance_v2.py",
    "test_physical_supervision_v2.py",
]
