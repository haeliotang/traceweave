from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

from scripts.phone_edge_manifold_daemon import intervention_gate_receipts
from traceform.intervention.closed_loop import ClosedLoopIntervention, MicroActuator


class TestNaturalGateDiagnostics(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(); self.addCleanup(self.temporary.cleanup)
        root = Path(self.temporary.name)
        self.silent = root / '.silent'
        self.loop = ClosedLoopIntervention(root / 'trials.sqlite', MicroActuator(silent_file=self.silent), 'shadow', seed=2)
        self.addCleanup(self.loop.close)
        self.store = Mock()
        self.frame = {'timestamp_utc': '2026-10-08T12:00:00Z', 'model_version': 'v',
                      'state_token': 'A', 'dwell_steps': 60, 'source_mode': 'live_follow',
                      'boot_id': 'b', 'continuity_epoch': 1, 'continuous_from_previous': True,
                      'frame_interval_seconds': 30}
        self.event = SimpleNamespace(event_type='PROLONGED_DWELL')

    def receipts(self, frame=None, inserted=True):
        return intervention_gate_receipts(self.store, self.loop, frame or self.frame, self.event, inserted, 60, 'UTC')

    def test_dwell_duplicate_off_and_silent_flags_do_not_fit_or_dispatch(self):
        self.silent.touch()
        before = self.loop.rng.getstate()
        result = self.receipts(dict(self.frame, dwell_steps=59))[0]
        self.assertEqual(result['reason'], 'dwell_below_threshold')
        self.assertTrue(result['actuator_snapshot']['silent_marker_present'])
        self.assertEqual(self.receipts(inserted=False)[0]['reason'], 'duplicate_frame')
        self.loop.mode = 'off'
        self.assertEqual(self.receipts()[0]['reason'], 'mode_off')
        self.store.hot_records.assert_not_called()
        self.assertEqual(self.loop.rng.getstate(), before)
        self.assertEqual(self.loop.db.execute('SELECT count(*) FROM trials').fetchone()[0], 0)

    def test_history_is_only_current_continuity_segment(self):
        self.store.hot_records.return_value = [dict(self.frame, boot_id='old') for _ in range(30)] + [self.frame]
        with patch('scripts.phone_edge_manifold_daemon.SandboxSimulator') as simulator:
            report = self.receipts()[0]
            simulator.assert_not_called()
        self.assertEqual(report['reason'], 'insufficient_continuous_history')
        self.assertEqual(report['continuous_history_frames'], 1)
        self.assertNotIn('continuous_vector', report)

    def test_counterfactual_rejection_and_global_cooldown_are_distinct(self):
        self.store.hot_records.return_value = [self.frame] * 20
        simulator = Mock(); simulator.fit_from_records.return_value = simulator
        simulator.periodic_kernel.phases.return_value = (0, 0)
        comparison = SimpleNamespace(recommended_path='KEEP_BASELINE', to_dict=lambda: {})
        simulator.simulate_counterfactual.return_value = comparison
        with patch('scripts.phone_edge_manifold_daemon.SandboxSimulator', return_value=simulator):
            self.assertEqual(self.receipts()[0]['reason'], 'counterfactual_not_recommended')
            comparison.recommended_path = 'RECOMMEND_INTERVENTION'
            reports = self.receipts()
            self.assertEqual(reports[0]['status'], 'decision_logged')
            self.assertEqual(reports[1]['status'], 'shadow')
            self.assertEqual(self.receipts()[0]['reason'], 'global_cooldown')
        self.assertEqual(self.loop.futures, {})

    def test_actual_silent_receipt_identifies_flag_without_delivery(self):
        self.silent.touch()
        with patch('subprocess.run') as run:
            receipt = self.loop.actuator.execute('pulse')
        run.assert_not_called()
        self.assertFalse(receipt['delivered'])
        self.assertTrue(receipt['availability']['silent_marker_present'])
