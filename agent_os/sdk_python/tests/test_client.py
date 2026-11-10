# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: Apache-2.0

"""Comprehensive tests for traceform-substrate Python SDK."""

import os
import tempfile
import time
import unittest

from traceform_substrate import (
    AgentManifest,
    CapabilityDeniedError,
    SubstrateAuthError,
    SubstrateClient,
    SubstrateConnectionError,
    SubstrateError,
)


class TestManifestBuilder(unittest.TestCase):
    def test_manifest_builder(self):
        manifest = (
            AgentManifest.builder("org.example.testagent", "Test Agent", "1.0.0")
            .description("A test agent for unit testing")
            .author("Developer")
            .request_capability("demo.note.read", "synthetic.welcome", "Testing note read")
            .request_capability("demo.status.read", "device.status", "Testing status read", required=False)
            .max_concurrent_tasks(4)
            .data_retention("session_only")
            .build()
        )
        data = manifest.to_dict()
        self.assertEqual(data["manifest_version"], 1)
        self.assertEqual(data["agent"]["id"], "org.example.testagent")
        self.assertEqual(data["agent"]["name"], "Test Agent")
        self.assertEqual(len(data["capabilities_requested"]), 2)
        self.assertEqual(data["capabilities_requested"][0]["capability"], "demo.note.read")
        self.assertTrue(data["capabilities_requested"][0]["required"])
        self.assertFalse(data["capabilities_requested"][1]["required"])
        self.assertEqual(data["max_concurrent_tasks"], 4)
        self.assertEqual(data["data_retention_policy"], "session_only")


class TestSubstrateLiveIntegration(unittest.TestCase):
    """Integration test suite executing against running agentosd daemon."""

    @classmethod
    def setUpClass(cls):
        # Test primarily against desktop daemon on port 19899
        cls.client = SubstrateClient(host="127.0.0.1", port=19899, timeout=5.0)
        try:
            cls.client.connect()
        except Exception:
            raise unittest.SkipTest("Desktop agentosd not reachable on port 19899")

    @classmethod
    def tearDownClass(cls):
        cls.client.close()

    def test_01_ping(self):
        status = self.client.ping()
        self.assertEqual(status.service, "agentosd")
        self.assertTrue(status.pong)
        self.assertIn(status.os, ["macos", "linux", "android"])
        self.assertIn(status.arch, ["aarch64", "x86_64"])

    def test_02_register_and_verify(self):
        agent_id = "org.agent.python.tester"
        manifest = (
            AgentManifest.builder(agent_id, "Python Test Agent", "1.0.0")
            .description("Automated SDK integration tester")
            .request_capability("demo.note.read", "synthetic.welcome", "Verify JIT flow")
            .build()
        )
        session = self.client.register(agent_id, manifest)
        self.assertEqual(session.agent_id, agent_id)
        self.assertTrue(session.session_token.startswith("agent_sess_"))
        self.assertGreater(session.expires_at, session.registered_at)

        # Verify session
        verification = self.client.verify_session()
        self.assertTrue(verification.valid)
        self.assertEqual(verification.agent_id, agent_id)

    def test_03_capability_lifecycle(self):
        task_id = "test-task-python-sdk"
        # 1. Request capability
        grant = self.client.request_capability(task_id, "demo.note.read", "synthetic.welcome")
        self.assertTrue(grant.granted)
        self.assertEqual(grant.capability, "demo.note.read")
        self.assertEqual(grant.scope, "synthetic.welcome")

        # 2. List capabilities to verify active grant
        cap_list = self.client.list_capabilities(task_id)
        self.assertIn("demo.note.read", cap_list.capabilities)
        self.assertTrue(len(cap_list.descriptors) > 0)

        # 3. Invoke capability
        inv = self.client.invoke_capability(task_id, "demo.note.read", "synthetic.welcome")
        self.assertEqual(inv.capability, "demo.note.read")
        self.assertIn("便笺", inv.result)

        # 4. Revoke capability
        revoked = self.client.revoke_capability(task_id, "demo.note.read", "synthetic.welcome")
        self.assertTrue(revoked)

        # 5. Subsequent invoke should raise CapabilityDeniedError
        with self.assertRaises(CapabilityDeniedError):
            self.client.invoke_capability(task_id, "demo.note.read", "synthetic.welcome")

    def test_04_task_management(self):
        title = "Python SDK Verification Task"
        task = self.client.create_task(title)
        self.assertEqual(task.title, title)
        self.assertEqual(task.status, "queued")
        self.assertTrue(task.id.startswith("task-"))

        # List tasks
        tasks = self.client.list_tasks()
        self.assertTrue(any(t.id == task.id for t in tasks))

    def test_05_vault_operations(self):
        vault_file = os.path.join(tempfile.gettempdir(), f"agentos_sdk_vault_{int(time.time()*1000)}.enc")
        passphrase = "sdk-vault-passphrase-test"
        # Init vault
        unlocked = self.client.init_vault(passphrase, vault_path=vault_file)
        self.assertTrue(unlocked)
        self.assertTrue(self.client.vault_status())

        # Seal and unseal
        original = "TopSecretPersonalDataUnderSubstrateGovernance"
        sealed = self.client.seal(original, aad="test-task-aad")
        self.assertNotEqual(original, sealed)
        unsealed = self.client.unseal(sealed, aad="test-task-aad")
        self.assertEqual(original, unsealed)

        if os.path.exists(vault_file):
            os.remove(vault_file)

    def test_06_audit_trail(self):
        events = self.client.query_audit(limit=20)
        self.assertTrue(len(events) > 0)
        has_agent_tag = any("org.agent.python.tester" in e for e in events)
        self.assertTrue(has_agent_tag)


if __name__ == "__main__":
    unittest.main()
