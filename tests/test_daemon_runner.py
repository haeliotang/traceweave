# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

import json
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest


class TestDaemonRunner(unittest.TestCase):
    def test_termination_signal_runs_daemon_cleanup(self):
        project = Path(__file__).resolve().parent.parent
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            ledger = root / 'observations.jsonl'
            ledger.write_text('')
            runtime = root / 'runtime'
            process = subprocess.Popen([sys.executable, '-u', str(project / 'scripts/phone_edge_manifold_daemon.py'),
                '--follow', '--raw-ledger', str(ledger), '--storage-mode', 'tiered',
                '--output-stream', str(runtime / 'stream.jsonl'), '--events-file', str(runtime / 'events.jsonl'),
                '--disable-haptics'], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            try:
                deadline = time.monotonic() + 5
                while not (runtime / 'interventions.sqlite').exists() and time.monotonic() < deadline:
                    if process.poll() is not None:
                        break
                    time.sleep(.05)
                self.assertTrue((runtime / 'interventions.sqlite').exists())
                time.sleep(.1)
                process.terminate()
                output, errors = process.communicate(timeout=5)
                self.assertEqual(process.returncode, 0, errors.decode())
                self.assertIn(b'Daemon interrupted', output)
                self.assertFalse((runtime / 'edge_manifold_daemon.pid').exists())
            finally:
                if process.poll() is None:
                    process.kill()
                    process.communicate()

    def test_historical_replay_does_not_write_source_directory(self):
        project = Path(__file__).resolve().parent.parent
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / 'frozen'
            source.mkdir()
            path = source / 'observations.jsonl'
            path.write_text(''.join(json.dumps({'timestamp_utc': f'2026-10-01T00:00:0{index}Z', 'observation_id': str(index)}) + '\n' for index in range(2)))
            source.chmod(0o555)
            try:
                result = subprocess.run([sys.executable, str(project / 'scripts/phone_edge_manifold_daemon.py'),
                    '--simulate', '--max-frames', '2', '--raw-ledger', str(path), '--storage-mode', 'tiered',
                    '--output-stream', str(root / 'runtime/stream.jsonl'), '--events-file', str(root / 'runtime/events.jsonl'),
                    '--disable-haptics'], capture_output=True, text=True, cwd=project, timeout=10)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(sorted(item.name for item in source.iterdir()), ['observations.jsonl'])
                self.assertIn('2 frames processed', result.stdout)
            finally:
                source.chmod(0o755)
