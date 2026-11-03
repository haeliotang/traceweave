#!/usr/bin/env python3
"""Write the recovery card that belongs next to a signing backup.

The card never contains key material or the archive passphrase: it records which
archive it describes, its checksum, where the passphrase is kept (as a location, not
a value), and the exact commands to run when a recovery is needed.
"""
import argparse
import hashlib
import pathlib
import subprocess
import sys

DEFAULT_SERVICES = (
    ("archive key (automated)", "com.inceptless.traceform.edge.archive"),
    ("recovery key (human readable)", "com.inceptless.traceform.edge.archive.recovery"),
)

CARD = """# Signing recovery card

Keep this card **with the backup archive, off this machine**. It contains no secrets:
the passphrase stays wherever the holder decided to keep it.

## What this archive is

- Archive file: `{archive_name}`
- SHA-256: `{archive_sha}` ({archive_bytes} bytes)
- Written: {today}
- Contents: the signing keystores for Traceweave Agent OS and the Edge app
- Encrypted with: AES-256-GCM (`TFEDGE01` container), authenticated on every read

## Where the passphrase is

The 32-byte archive key is held by the macOS login keychain:

| Purpose | Keychain service | Account |
|---|---|---|
{service_rows}

**Offline copy of the passphrase:** {passphrase_location}

> Fill in the line above by hand and keep it somewhere that is not this laptop
> (paper in a safe, a password manager account, an offline USB). Anyone with the
> archive **and** this passphrase can sign updates for every published release.

## How to check the backup still works

```bash
cd <repository checkout>
python3 scripts/recover_signing_archive.py \\
  --archive "{archive_path}" \\
  --keychain-account {keychain_account} \\
  --unsigned-apk agent_os/build/agent-os-unsigned.apk \\
  --expect "agent-os-rotation-20261025=9effee32f57b35c256529e97cd8db435813e84025d48601c00770b6adf95a6db" \\
  --expect "traceform=e4bd7ecd43aba24b7759519e054b89d937417c1dbc08def02b7e9fd11584cf0c" \\
  --output artifacts/agent_os/next_stage/signing_recovery_drill_<date>.json
```

Expected output starts with `{{"verified": true`. The drill never prints key material and
deletes its temporary files.

## Verification log

| Date | What was checked | Result | By |
|---|---|---|---|
| {today} | archive checksum recorded, card generated | ok | |

## If the keychain entry is gone

1. Try the second service listed above; both entries held the same archive key.
2. If neither exists, the archive cannot be decrypted from this machine. Restore the
   archive key from the place recorded above, then re-run the drill.
3. Never regenerate or rotate the signing keys without reading
   `docs/production_signing_decision.md` first: published releases can only be updated
   by the same certificate.
"""


def card_text(archive, keychain_account, passphrase_location, today, services=DEFAULT_SERVICES):
    data = archive.read_bytes()
    service_rows = "\n".join(
        "| %s | `%s` | `%s` |" % (label, service, keychain_account) for label, service in services)
    return CARD.format(
        archive_name=archive.name,
        archive_sha=hashlib.sha256(data).hexdigest(),
        archive_bytes=len(data),
        archive_path=archive.resolve(),
        keychain_account=keychain_account,
        passphrase_location=passphrase_location or "____________________ (fill in by hand)",
        today=today,
        service_rows=service_rows,
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", required=True)
    parser.add_argument("--keychain-account", required=True)
    parser.add_argument("--passphrase-location", default=None,
                        help="where the passphrase is kept, as words (never the passphrase)")
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    archive = pathlib.Path(args.archive)
    if not archive.is_file():
        raise SystemExit("archive not found: %s" % archive)
    today = subprocess.run(["date", "+%Y-%m-%d"], capture_output=True, text=True).stdout.strip()
    text = card_text(archive, args.keychain_account, args.passphrase_location, today)
    output = pathlib.Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(text)
    print("card written: %s (%d bytes, archive sha256 %s)"
          % (output, len(text), hashlib.sha256(archive.read_bytes()).hexdigest()[:16]))


if __name__ == "__main__":
    sys.exit(main())
