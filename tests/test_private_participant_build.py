# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


class TestPrivateParticipantBuild(unittest.TestCase):
    def test_unpatched_listener_rejected_before_compilation(self):
        root = Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            header = base / 'include/emp-tool/io/net_io_channel.h'
            header.parent.mkdir(parents=True)
            header.write_text('serv.sin_addr.s_addr = htonl(INADDR_ANY);')
            command = [sys.executable, str(root / 'scripts/build_private_participant_android.py')]
            for option in ('ndk-root', 'emp-prefix', 'emp-tool-source', 'emp-ag2pc-source', 'openssl-source', 'openssl-build'):
                command.extend(['--' + option, directory])
            output = base / 'output'
            command.extend(['--output-dir', str(output)])
            result = subprocess.run(command, capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn('loopback-only EMP listener required', result.stderr)
            self.assertFalse(output.exists())
