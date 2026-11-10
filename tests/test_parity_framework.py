# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

import unittest

import parity_support as parity


class TestParityFramework(unittest.TestCase):
    def test_reset_marker_follows_every_frame_on_which_python_raised(self):
        frames = [{'__reset': True}, {'a': 1}, {'a': 2}, {'__reset': True}, {'a': 3}]
        expected = [None, [1.0], None, None, [3.0]]  # python raised on {'a': 2}
        java_frames, aligned = parity.align_after_python_errors(frames, expected)
        self.assertEqual(java_frames, [{'__reset': True}, {'a': 1}, {'a': 2}, {'__reset': True}, {'__reset': True}, {'a': 3}])
        self.assertEqual(aligned, [None, [1.0], None, None, None, [3.0]])

    def test_explicit_resets_are_not_duplicated(self):
        frames = [{'__reset': True}, {'a': 1}]
        java_frames, aligned = parity.align_after_python_errors(frames, [None, [1.0]])
        self.assertEqual(java_frames, frames)
        self.assertEqual(aligned, [None, [1.0]])

    def test_compare_flags_a_value_mismatch_and_ignores_python_error_rows(self):
        report = parity.compare(['x'], [{}, {}, {}], [[1.0], None, [2.0]], [[1.0], [99.0], [2.5]])
        self.assertEqual(len(report['problems']), 1)
        self.assertIn('frame 2', report['problems'][0])

    def test_compare_allows_one_rounding_unit_only_where_declared(self):
        expected = [[1.0]] * 999 + [[2.0]]
        actual = [[1.0]] * 999 + [[2.001]]
        quiet = parity.compare(['x'], [{}] * 1000, expected, actual, quantum={'x': 0.001})
        self.assertEqual(quiet['problems'], [])
        self.assertEqual(quiet['flips'], {'x': 1})
        loud = parity.compare(['x'], [{}] * 1000, expected, actual)
        self.assertEqual(len(loud['problems']), 1)
        many = parity.compare(['x'], [{}] * 10, [[2.0]] * 10, [[2.001]] * 10, quantum={'x': 0.001})
        self.assertTrue(any('exceeds budget' in p for p in many['problems']))

    def test_java_exception_where_python_succeeded_is_a_failure(self):
        report = parity.compare(['x'], [{}], [[1.0]], ['error'])
        self.assertEqual(report['java_only_errors'], 1)


if __name__ == '__main__':
    unittest.main()
