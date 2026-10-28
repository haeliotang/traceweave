import sys
import unittest
from pathlib import Path

sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'scripts'))
from build_edge_application_probe import probe_debuggable, verify_probe_classes


class ProbeBuildTests(unittest.TestCase):
    def test_allows_only_probe_and_compiler_generated_lambda(self):
        allowed = { 'Lorg/traceform/edge/EdgeApplicationRecoveryProbe;',
                    'Lorg/traceform/edge/EdgeApplicationRecoveryProbe$$ExternalSyntheticLambda0;' }
        verify_probe_classes(allowed)
        with self.assertRaises(ValueError): verify_probe_classes(allowed | {'Lorg/example/Unexpected;'})
        with self.assertRaises(ValueError): verify_probe_classes(allowed - {'Lorg/traceform/edge/EdgeApplicationRecoveryProbe;'})

    def test_four_version_cycle_has_temporary_debug_recovery_only(self):
        self.assertEqual([probe_debuggable(v) for v in (1, 2, 3, 4)], [True, False, True, False])
        with self.assertRaises(ValueError): probe_debuggable(5)


if __name__=='__main__':unittest.main()
