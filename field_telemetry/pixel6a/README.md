# Local Field Telemetry

This directory contains device-generated health summaries, events, and model snapshots. These files can reveal device and behavioral information, so they stay on the local machine and are excluded from Git.

`scripts/phone_field_sync.py` writes summaries locally. It does not commit, push, or pull telemetry. Use the USB-only workflow in `docs/local_device_archives.md` to copy device data to the computer.
