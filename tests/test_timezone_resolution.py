import shutil
import tempfile
import unittest
import zoneinfo
from pathlib import Path

from traceform.timezones import resolve_zone


def system_tzif(name='UTC'):
    for root in zoneinfo.TZPATH:
        candidate = Path(root) / name
        if candidate.is_file():
            return candidate
    return None


class TestResolveZone(unittest.TestCase):
    def test_known_system_zone_resolves_normally(self):
        self.assertEqual(str(resolve_zone('UTC')), 'UTC')

    def test_zone_missing_from_the_default_search_path_is_found_in_extra_directories(self):
        source = system_tzif('UTC')
        if source is None:
            self.skipTest('no system TZif file to copy')
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / 'Custom' / 'Fixture'
            target.parent.mkdir()
            shutil.copy(source, target)
            zone = resolve_zone('Custom/Fixture', extra_dirs=[directory])
            self.assertEqual(zone.key, 'Custom/Fixture')
            import datetime
            moment = datetime.datetime(2026, 10, 3, 12, 0, tzinfo=datetime.timezone.utc).astimezone(zone)
            self.assertEqual(moment.utcoffset(), datetime.timedelta(0))  # fixture copies UTC

    def test_missing_everywhere_raises_the_standard_error(self):
        with self.assertRaises(zoneinfo.ZoneInfoNotFoundError):
            resolve_zone('Nowhere/Land', extra_dirs=[])

    def test_directory_traversal_in_the_name_is_rejected(self):
        for bad in ('../etc/passwd', '/etc/passwd', 'a/../../b', ''):
            with self.assertRaises(ValueError):
                resolve_zone(bad, extra_dirs=['/tmp'])

    def test_periodic_kernel_and_maintenance_use_the_resolver(self):
        root = Path(__file__).resolve().parents[1]
        for relative in ('traceform/forecasting/periodic_kernel.py', 'scripts/maintain_edge_runtime.py'):
            source = (root / relative).read_text()
            self.assertIn('resolve_zone', source, relative)
            self.assertNotIn('ZoneInfo(', source, relative)


if __name__ == '__main__':
    unittest.main()
