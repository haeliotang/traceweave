# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

import json
from pathlib import Path
import tempfile
import unittest

from traceform.evolution.model_registry import ModelRegistry


class TestModelRegistry(unittest.TestCase):
    def test_atomic_activation_rejection_and_rollback(self):
        with tempfile.TemporaryDirectory() as directory:
            registry = ModelRegistry(directory)
            first = registry.register({"nodes": [1]}, {}, {"accepted": True})
            registry.activate(first)
            rejected = registry.register({"nodes": [2]}, {}, {"accepted": False})
            with self.assertRaises(ValueError):
                registry.activate(rejected, first)
            self.assertEqual(registry.active()["version"], first)
            second = registry.register({"nodes": [3]}, {}, {"accepted": True})
            registry.activate(second, first)
            with self.assertRaises(ValueError):
                registry.activate(first, first)
            self.assertEqual(registry.rollback(), first)
            self.assertEqual(registry.active()["version"], first)

    def test_tampered_bundle_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            registry = ModelRegistry(directory)
            version = registry.register({"nodes": [1]}, {}, {"accepted": True})
            path = Path(directory) / (version + ".json")
            value = json.loads(path.read_text())
            value["manifold"]["nodes"] = [9]
            path.write_text(json.dumps(value))
            with self.assertRaises(ValueError):
                registry.activate(version)

    def test_pruning_keeps_active_and_rollback(self):
        with tempfile.TemporaryDirectory() as directory:
            registry = ModelRegistry(directory)
            first = registry.register({"nodes": [1]}, {}, {"accepted": True})
            registry.activate(first)
            second = registry.register({"nodes": [2]}, {}, {"accepted": True})
            registry.activate(second, first)
            for index in range(3, 10):
                registry.register({"nodes": [index]}, {}, {"accepted": False})
            retained = registry.prune(4)
            self.assertEqual(len(retained), 4)
            self.assertIn(first, retained)
            self.assertIn(second, retained)
            self.assertEqual(registry.rollback(), first)
