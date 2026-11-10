# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

HARNESS = r'''package com.inceptless.traceweave.edge;
public class H {
    static void check(boolean ok, String what) { if (!ok) { System.out.println("FAIL " + what); System.exit(1); } }
    public static void main(String[] a) throws Exception {
        ModelBindingCache c = new ModelBindingCache();
        String b1 = "sha=aaa", b2 = "sha=bbb";
        check(c.modelSha256(b1).equals("aaa") && c.parses() == 1, "first call parses");
        for (int i = 0; i < 50; i++) check(c.modelSha256(new String(b1)).equals("aaa"), "equal text, different instance");
        check(c.parses() == 1, "unchanged bundle is not re-parsed");
        check(c.modelSha256(b2).equals("bbb") && c.parses() == 2, "a different bundle is parsed again");
        check(c.modelSha256(b1).equals("aaa") && c.parses() == 3, "switching back is parsed again, never served stale");
        check(c.modelSha256("sha=aaa ").equals("aaa") && c.parses() == 4, "any text difference invalidates");
        try { c.modelSha256("broken"); check(false, "unparseable bundle must throw"); } catch (RuntimeException expected) { }
        check(c.modelSha256("sha=aaa ").equals("aaa") && c.parses() == 4, "a failed parse keeps the previous valid entry intact");
        try { c.modelSha256("sha=zzz broken"); check(false, "must throw"); } catch (RuntimeException expected) { }
        check(c.modelSha256(b2).equals("bbb") && c.parses() == 5, "a failed parse does not poison the cache");
        try { c.modelSha256(null); check(false, "null must throw"); } catch (IllegalArgumentException expected) { }
        System.out.println("OK");
    }
}
'''
JSON = '''package org.json;
public class JSONObject {
    private final String sha;
    public JSONObject(String text) {
        if (!text.startsWith("sha=") || text.contains("broken")) throw new JSONException();
        sha = text.substring(4).trim();
    }
    public String getString(String key) { if (!key.equals("model_sha256")) throw new JSONException(); return sha; }
}
'''
EXC = 'package org.json; public class JSONException extends RuntimeException {}\n'


class TestModelBindingCache(unittest.TestCase):
    def run_java(self, mutate=None):
        if not shutil.which('javac') or not shutil.which('java'):
            self.skipTest('Java compiler/runtime unavailable')
        root = Path(__file__).resolve().parents[1]
        source = (root / 'android/src/com/inceptless/traceweave/edge/ModelBindingCache.java').read_text()
        if mutate:
            source = mutate(source)
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            files = {'org/json/JSONObject.java': JSON, 'org/json/JSONException.java': EXC,
                     'com/inceptless/traceweave/edge/ModelBindingCache.java': source, 'com/inceptless/traceweave/edge/H.java': HARNESS}
            for name, text in files.items():
                path = base / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(text)
            out = base / 'out'
            out.mkdir()
            subprocess.run(['javac', '-d', str(out)] + [str(base / n) for n in files], check=True, capture_output=True)
            return subprocess.run(['java', '-cp', str(out), 'com.inceptless.traceweave.edge.H'], capture_output=True, text=True)

    def test_cache_skips_only_the_parse_and_never_serves_a_changed_bundle(self):
        result = self.run_java()
        self.assertEqual(result.stdout.strip(), 'OK', result.stdout + result.stderr)

    def test_mutation_keying_on_something_weaker_than_the_full_text_is_caught(self):
        # Compare only the length: a same-length replacement would be served stale.
        weaker = lambda s: s.replace('!text.equals(bundleText)', 'bundleText == null || text.length() != bundleText.length()')
        result = self.run_java(weaker)
        self.assertNotEqual(result.stdout.strip(), 'OK')

    def test_mutation_poisoning_the_cache_before_a_successful_parse_is_caught(self):
        poison = lambda s: s.replace('            String parsed = new JSONObject(text).getString("model_sha256");',
                                     '            bundleText = text;\n            String parsed = new JSONObject(text).getString("model_sha256");')
        result = self.run_java(poison)
        self.assertNotEqual(result.stdout.strip(), 'OK')

    def test_production_check_still_compares_the_model_hash_on_every_frame(self):
        root = Path(__file__).resolve().parents[1]
        source = (root / 'android/src/com/inceptless/traceweave/edge/ColdTrajectory.java').read_text()
        self.assertIn('frame.getString("model_sha256").equals(BINDINGS.modelSha256(binding.getString(1)))', source)
        self.assertNotIn('new JSONObject(binding.getString(1)).getString("model_sha256")', source)


if __name__ == '__main__':
    unittest.main()
