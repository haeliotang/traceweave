# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

"""
Tests for Ambient Resonance Engine.
"""

import unittest

from traceform.intervention.resonance_hooks import (
    AmbientResonanceEngine,
    ResonanceTriggerType,
)


class TestIntervention(unittest.TestCase):
    def test_prolonged_dwell_trigger(self):
        engine = AmbientResonanceEngine(
            max_regime_dwell_steps=5, entropy_spike_threshold=2.0
        )

        # 4 steps in S01: no event
        for i in range(4):
            evt = engine.process_step(
                i, f"2026-09-03T10:0{i}:00Z", "S01", "R01", sliding_entropy=0.5
            )
            self.assertIsNone(evt)

        # 5th step: fires prolonged dwell
        evt = engine.process_step(
            4, "2026-09-03T10:05:00Z", "S01", "R01", sliding_entropy=0.5
        )
        self.assertIsNotNone(evt)
        self.assertEqual(evt.trigger_type, ResonanceTriggerType.REGIME_PROLONGED_DWELL)
        self.assertEqual(evt.consecutive_dwell_steps, 5)

    def test_high_entropy_flux_trigger(self):
        engine = AmbientResonanceEngine(
            max_regime_dwell_steps=100, entropy_spike_threshold=1.8
        )

        # High entropy step
        evt = engine.process_step(
            0, "2026-09-03T12:00:00Z", "S02", "R02", sliding_entropy=2.5
        )
        self.assertIsNotNone(evt)
        self.assertEqual(evt.trigger_type, ResonanceTriggerType.HIGH_ENTROPY_FLUX)
        self.assertEqual(evt.severity, "ADAPTATION")


if __name__ == "__main__":
    unittest.main()
