# Local device data archives

Traceform collects raw sensor observations and derived device telemetry. Keep
these files on the computer; do not commit or push them. The archive workflow
uses a temporary HTTP endpoint bound to loopback on the phone and ADB port
forwarding over USB. It does not use Git, Wi-Fi, or a cloud service, and it
does not remove or modify the source files on the phone.

## Archive from a phone

1. Connect the phone to the computer by USB, enable USB debugging, and accept
   the computer authorization prompt.
2. In Termux on the phone, from the Traceform checkout, run:

   ```sh
   cd ~/traceform
   python3 scripts/export_device_archive.py
   ```

   Leave this command running. It creates a temporary archive and waits for one
   transfer. Copy the displayed one-time token for the next step. The archive
   includes the physical ledger, daily snapshots, runtime data, and local field
   telemetry. Live SQLite databases are copied through
   SQLite's backup API. Device fingerprint salts, lock files, process IDs, logs,
   temporary files, and symlinks are excluded.

3. On the computer, run the command and paste the token when prompted:

   ```sh
   cd Documents/traceform
   python3 scripts/archive_device_data.py
   ```

   If multiple USB devices are connected, select the intended phone with
   `--serial <adb-serial>`; `adb devices -l` shows the available serials.

The computer verifies every archived file against its SHA-256 digest before
keeping the compressed archive at:

```text
local_device_archives/<device-id>/<timestamp>-<archive-id>.tar.gz
```

The device ID is a random UUID stored in the Termux home directory; the archive
does not include the phone's hardware serial. Each fresh device installation
gets a separate ID and archive directory. If copying a Termux home backup to a
new phone, remove `~/.traceform_archive_device_id` on that new phone before its
first export so the new device starts a separate archive history. Archives are
excluded from Git along with raw sensor ledgers and per-device telemetry.

The phone keeps its source files. Its temporary compressed copy is removed
after transfer or when the 15-minute wait expires. The archive on the computer
is retained for local analysis and backup.
