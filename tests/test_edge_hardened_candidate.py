import sys
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
from build_edge_hardened_candidate import verify_hardened_manifest


def node(tag, **attrs):
    return {'name': tag, 'attributes': attrs, 'text': [], 'children': []}


class HardenedManifestTests(unittest.TestCase):
    def setUp(self):
        self.old = [node('manifest', package='com.inceptless.traceweave.edge', versionCode='2', versionName='0.2'),
                    node('application', debuggable='true', allowBackup='false'),
                    node('service', name='.EdgeService', exported='true', permission='signature')]
        self.new = [node('manifest', package='com.inceptless.traceweave.edge', versionCode='3', versionName='0.3'),
                    node('application', allowBackup='false'), self.old[2]]

    def test_only_version_and_debug_removal_are_permitted(self):
        verify_hardened_manifest(self.old, self.new)
        with self.assertRaises(ValueError):
            verify_hardened_manifest(self.old, self.new + [node('service', name='.Extra')])
        with self.assertRaises(ValueError):
            verify_hardened_manifest(self.old, [self.new[0], node('application', allowBackup='true'), self.old[2]])

    def test_debug_must_change_from_true_to_disabled(self):
        with self.assertRaises(ValueError):
            verify_hardened_manifest(self.old, [self.new[0], node('application', debuggable='true', allowBackup='false'), self.old[2]])
        with self.assertRaises(ValueError):
            verify_hardened_manifest(self.new, self.new)

    def test_component_hierarchy_is_preserved(self):
        self.old[0]['children'] = [self.old[1]]
        self.old[1]['children'] = [self.old[2]]
        self.new[0]['children'] = [self.new[1], self.new[2]]
        with self.assertRaises(ValueError):
            verify_hardened_manifest(self.old, self.new)


if __name__ == '__main__':
    unittest.main()
