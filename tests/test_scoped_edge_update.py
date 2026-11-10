# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

import importlib.util
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("scoped_edge_update", ROOT / "scripts/build_scoped_edge_update.py")
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)


def node(tag, **attrs):
    return {"name": tag, "attributes": attrs, "text": [], "children": []}


class ScopedEdgeManifestTests(unittest.TestCase):
    def setUp(self):
        self.old = [node("manifest", package="com.inceptless.traceweave.edge", versionCode="1", versionName="0.1"),
                    node("application", debuggable="true", allowBackup="false"),
                    node("service", name=".EdgeService", permission="com.inceptless.traceweave.edge.CANONICAL_INPUT")]
        self.permission = node("permission", name=builder.NEW_PERMISSION, protectionLevel="0x08000002", knownCerts=builder.TRUSTED_SIGNER)
        self.service = node("service", name=builder.NEW_SERVICE, permission=builder.NEW_PERMISSION, exported="true", process=":edge")
        self.new = [node("manifest", package="com.inceptless.traceweave.edge", versionCode="2", versionName="0.2"),
                    self.permission, self.old[1], self.old[2], self.service]

    def test_only_expected_additions_are_allowed(self):
        builder.verify_manifest(self.old, self.new)
        with self.assertRaises(ValueError):
            builder.verify_manifest(self.old, self.new + [node("service", name=".UnreviewedService", exported="true")])

    def test_permission_pin_and_existing_app_flags_cannot_change(self):
        changed_permission = node("permission", name=builder.NEW_PERMISSION, protectionLevel="0x00000002", knownCerts=builder.TRUSTED_SIGNER)
        with self.assertRaises(ValueError):
            builder.verify_manifest(self.old, [self.new[0], changed_permission, *self.new[2:]])
        changed_application = node("application", debuggable="false", allowBackup="false")
        with self.assertRaises(ValueError):
            builder.verify_manifest(self.old, [self.new[0], self.permission, changed_application, self.old[2], self.service])


if __name__ == "__main__":
    unittest.main()
