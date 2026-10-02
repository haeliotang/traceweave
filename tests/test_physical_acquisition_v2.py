import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from pixel_physical.observation import PhysicalObservation
from traceform.streaming.physical_acquisition_v2 import RollingPhysicalAcquisitionEngine
from traceform.streaming.physical_ledger_v2 import read_recent_observations
from traceform.streaming.rolling_ledger import RollingLedger


class TestPhysicalAcquisitionV2(unittest.TestCase):
    def test_unavailable_location_attempts_obey_monotonic_cooldown(self):
        with tempfile.TemporaryDirectory() as directory:
            engine = RollingPhysicalAcquisitionEngine(Path(directory))
            observation = PhysicalObservation.from_dict({
                'observation_id': 'unavailable-place', 'timestamp_utc': '2026-10-01T00:00:00Z',
                'motion': {'stationary_likelihood': 1.0},
                'availability': {'place': 'TEMPORARILY_UNAVAILABLE'}})
            with patch('traceform.streaming.physical_acquisition_v2.acquire_observation_frame', return_value=observation) as acquire, \
                    patch('traceform.streaming.physical_acquisition_v2.time.monotonic', side_effect=[1, 31, 121]):
                engine.sample_once(False, False)
                engine.sample_once(False, False)
                engine.sample_once(False, False)
            self.assertEqual([call.kwargs['include_location'] for call in acquire.call_args_list], [True, False, True])

    def test_sample_persists_and_recovers_prior_state(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            observation = PhysicalObservation.from_dict({
                'observation_id': 'first', 'timestamp_utc': '2026-10-01T00:00:00Z',
                'motion': {'stationary_likelihood': 1.0}})
            engine = RollingPhysicalAcquisitionEngine(root)
            with patch('traceform.streaming.physical_acquisition_v2.acquire_observation_frame', return_value=observation):
                result, _, _ = engine.sample_once(False, False)
            self.assertEqual(result.observation_id, 'first')
            restored = RollingPhysicalAcquisitionEngine(root)
            self.assertEqual(restored.prior_observation.observation_id, 'first')
            self.assertEqual(restored.episode_assembler.active_episode.initial_state_ref, 'first')

    def test_failed_location_attempt_cools_down_while_force_overrides(self):
        with tempfile.TemporaryDirectory() as directory:
            engine = RollingPhysicalAcquisitionEngine(Path(directory))
            observation = PhysicalObservation.from_dict({
                'observation_id': 'moving', 'timestamp_utc': '2026-10-01T00:00:00Z',
                'motion': {'stationary_likelihood': 0.0}})
            with patch('traceform.streaming.physical_acquisition_v2.acquire_observation_frame',
                       side_effect=[RuntimeError('API failure'), observation, observation, observation]) as acquire, \
                    patch('traceform.streaming.physical_acquisition_v2.time.monotonic', side_effect=[100, 130, 145, 146]):
                with self.assertRaisesRegex(RuntimeError, 'API failure'):
                    engine.sample_once(False, False)
                engine.sample_once(False, False)
                engine.sample_once(False, False)
                engine.sample_once(False, True)
            self.assertEqual([call.kwargs['include_location'] for call in acquire.call_args_list], [True, False, True, True])

    def test_recent_reader_spans_segments_and_ignores_partial_tail(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'observations.jsonl'
            writer = RollingLedger(path, max_bytes=64)
            for index in range(4):
                writer.append({'observation_id': str(index)})
            with path.open('a') as output:
                output.write('{"observation_id":')
            self.assertEqual([item.observation_id for item in read_recent_observations(3, path)], ['1', '2', '3'])

    def test_runner_uses_selected_directory_and_rejects_second_owner(self):
        from scripts.physical_acquisition_v2 import PhysicalRunner
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            first, second = PhysicalRunner(root), PhysicalRunner(root)
            try:
                self.assertTrue(first.acquire_lock())
                self.assertTrue((root / 'physical_acquisition.pid').exists())
                self.assertFalse(second.acquire_lock())
                self.assertIsNone(second.lock_fd)
                second.release_lock()
                self.assertTrue((root / 'physical_acquisition.pid').exists())
            finally:
                first.release_lock()
                if second.lock_fd is not None:
                    second.lock_fd.close()

    def test_initialization_failure_releases_owner_and_pid(self):
        from scripts.physical_acquisition_v2 import PhysicalRunner
        with tempfile.TemporaryDirectory() as directory:
            runner = PhysicalRunner(Path(directory))
            with patch('scripts.physical_acquisition_v2.signal.signal'), patch('scripts.physical_acquisition_v2.RollingPhysicalAcquisitionEngine', side_effect=ValueError('invalid source')):
                try:
                    with self.assertRaises(ValueError):
                        runner.run()
                    self.assertIsNone(runner.lock_fd)
                    self.assertFalse((Path(directory) / 'physical_acquisition.pid').exists())
                finally:
                    runner.release_lock()
