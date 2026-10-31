"""Structural checks for the grammar-capable native runtime.

The bare-grammar sampler aborted the process once; these checks keep the
sampler-chain construction and the JNI signature from regressing silently.
"""
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
NATIVE = ROOT / "agent_os/android/native/experimental_local_model.cpp"
RUNTIME = ROOT / "agent_os/android/src/com/inceptless/traceweave/LocalModelRuntime.java"
PROBE = ROOT / "agent_os/tests/android/LocalModelProbe.java"


class GrammarRuntimeTests(unittest.TestCase):
    def test_grammar_is_added_through_a_sampler_chain(self):
        source = NATIVE.read_text()
        self.assertIn("llama_sampler_chain_init", source)
        self.assertIn("llama_sampler_chain_add(sampler.get(), grammar_sampler)", source)
        self.assertIn("llama_sampler_chain_add(sampler.get(), llama_sampler_init_greedy())", source)
        self.assertNotIn("grammar.empty() ? llama_sampler_init_greedy()", source)

    def test_generate_accepts_a_grammar_argument(self):
        source = NATIVE.read_text()
        self.assertIn("jbyteArray prompt_bytes, jint max_tokens,\n                                                    jstring grammar_text", source)
        self.assertIn("jstring grammar)", source)
        self.assertIn("int maxTokens, String grammar)", RUNTIME.read_text())
        self.assertIn("int maxTokens, String grammar)", PROBE.read_text())

    def test_token_budget_matches_the_catalog(self):
        source = NATIVE.read_text()
        self.assertIn("max_tokens <= 512", source)
        self.assertIn("count + max_tokens <= 2048", source)
        catalog = (ROOT / "agent_os/android/src/com/inceptless/traceweave/LocalModelCatalog.java").read_text()
        self.assertIn("256)", catalog)
        self.assertIn("384)", catalog)


if __name__ == "__main__":
    unittest.main()
