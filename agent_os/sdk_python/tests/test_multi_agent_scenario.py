"""Multi-Agent collaborative isolation and audit scenario test using traceform-substrate SDK."""

import unittest

from traceform_substrate import (
    AgentManifest,
    CapabilityDeniedError,
    SubstrateClient,
)


class TestMultiAgentScenario(unittest.TestCase):
    """Verifies that multiple independent agents can operate concurrently against Substrate

    with complete isolation, strict capability scoping, and immutable identity-tagged audit logs.
    """

    def test_multi_agent_lifecycle_and_isolation(self):
        # Connect client instances (can represent two distinct processes or threads)
        client_a = SubstrateClient(host="127.0.0.1", port=19899)
        client_b = SubstrateClient(host="127.0.0.1", port=19899)
        client_a.connect()
        client_b.connect()

        try:
            # 1. Register Agent A (Writer)
            id_a = "org.agent.collab.writer"
            manifest_a = (
                AgentManifest.builder(id_a, "Collab Writer", "1.2.0")
                .description("Generates report drafts")
                .request_capability("demo.note.read", "synthetic.welcome", "Draft context reading")
                .build()
            )
            sess_a = client_a.register(id_a, manifest_a)
            self.assertEqual(sess_a.agent_id, id_a)

            # 2. Register Agent B (Reviewer)
            id_b = "org.agent.collab.reviewer"
            manifest_b = (
                AgentManifest.builder(id_b, "Collab Reviewer", "1.0.0")
                .description("Audits drafts")
                .request_capability("demo.note.read", "synthetic.welcome", "Proofreading context")
                .build()
            )
            sess_b = client_b.register(id_b, manifest_b)
            self.assertEqual(sess_b.agent_id, id_b)
            self.assertNotEqual(sess_a.session_token, sess_b.session_token)

            # 3. Agent A requests capability for shared task
            shared_task = "task-collab-pipeline-01"
            grant_a = client_a.request_capability(shared_task, "demo.note.read", "synthetic.welcome")
            self.assertTrue(grant_a.granted)
            self.assertEqual(grant_a.agent_id, id_a)

            # 4. Agent A invokes capability successfully
            inv_a = client_a.invoke_capability(shared_task, "demo.note.read", "synthetic.welcome")
            self.assertIn("便笺", inv_a.result)
            self.assertEqual(inv_a.agent_id, id_a)

            # 5. Agent B attempts to invoke WITHOUT its own grant -> MUST FAIL with CapabilityDeniedError
            with self.assertRaises(CapabilityDeniedError):
                client_b.invoke_capability(shared_task, "demo.note.read", "synthetic.welcome")

            # 6. Agent B requests its own JIT grant
            grant_b = client_b.request_capability(shared_task, "demo.note.read", "synthetic.welcome")
            self.assertTrue(grant_b.granted)
            self.assertEqual(grant_b.agent_id, id_b)

            # 7. Agent B invokes under its own grant -> SUCCEEDS
            inv_b = client_b.invoke_capability(shared_task, "demo.note.read", "synthetic.welcome")
            self.assertIn("便笺", inv_b.result)
            self.assertEqual(inv_b.agent_id, id_b)

            # 8. Agent A revokes its grant; Agent B's grant remains ACTIVE
            rev_a = client_a.revoke_capability(shared_task, "demo.note.read", "synthetic.welcome")
            self.assertTrue(rev_a)

            # Agent A can no longer invoke
            with self.assertRaises(CapabilityDeniedError):
                client_a.invoke_capability(shared_task, "demo.note.read", "synthetic.welcome")

            # Agent B can STILL invoke because grants are strictly isolated per agent
            inv_b_again = client_b.invoke_capability(shared_task, "demo.note.read", "synthetic.welcome")
            self.assertIn("便笺", inv_b_again.result)

            # 9. Verify Audit Trail records discrete operations for both agents
            audit_events = client_a.query_audit(limit=30)
            writer_events = [e for e in audit_events if id_a in e]
            reviewer_events = [e for e in audit_events if id_b in e]

            self.assertTrue(len(writer_events) >= 2, f"Expected events for {id_a}, got {writer_events}")
            self.assertTrue(len(reviewer_events) >= 2, f"Expected events for {id_b}, got {reviewer_events}")

        finally:
            client_a.close()
            client_b.close()


if __name__ == "__main__":
    unittest.main()
