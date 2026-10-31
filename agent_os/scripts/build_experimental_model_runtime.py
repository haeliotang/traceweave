#!/usr/bin/env python3
"""Build the isolated local-model probe runtime from a pinned llama.cpp checkout."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
COMMIT = "d78f40d9d34d7a72ecdcd2e854f75ff86afc8ca3"


def run(args, **kwargs):
    return subprocess.run([str(arg) for arg in args], check=True, **kwargs)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--llama-source", type=Path, required=True)
    parser.add_argument("--ndk", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    source = args.llama_source.resolve()
    ndk = args.ndk.resolve()
    out = args.output_dir.resolve()
    revision = run(["git", "-C", source, "rev-parse", "HEAD"], capture_output=True, text=True).stdout.strip()
    dirty = run(["git", "-C", source, "status", "--porcelain", "--untracked-files=all"], capture_output=True, text=True).stdout
    if revision != COMMIT or dirty:
        raise SystemExit("llama.cpp must be a clean checkout at the pinned commit")
    toolchains = list((ndk / "toolchains/llvm/prebuilt").glob("*/bin/aarch64-linux-android29-clang++"))
    if len(toolchains) != 1 or not (ndk / "build/cmake/android.toolchain.cmake").is_file():
        raise SystemExit("Android NDK toolchain missing or ambiguous")
    compiler = toolchains[0]
    strip = compiler.parent / "llvm-strip"
    output = out / "libtraceweave_local_model.so"
    report = out / "experimental_model_build.json"
    out.mkdir(parents=True, exist_ok=True)
    report.unlink(missing_ok=True)
    native = ROOT / "agent_os/android/native/experimental_local_model.cpp"
    source_hash = hashlib.sha256(native.read_bytes()).hexdigest()
    with tempfile.TemporaryDirectory(prefix="traceform-model-build-", dir=out) as temporary:
        build = Path(temporary)
        run(["cmake", "-S", source, "-B", build,
             "-DCMAKE_TOOLCHAIN_FILE=" + str(ndk / "build/cmake/android.toolchain.cmake"),
             "-DANDROID_ABI=arm64-v8a", "-DANDROID_PLATFORM=android-29", "-DCMAKE_BUILD_TYPE=Release",
             "-DCMAKE_POSITION_INDEPENDENT_CODE=ON", "-DBUILD_SHARED_LIBS=OFF", "-DGGML_OPENMP=OFF",
             "-DLLAMA_BUILD_TESTS=OFF", "-DLLAMA_BUILD_EXAMPLES=OFF", "-DLLAMA_BUILD_SERVER=OFF"])
        run(["cmake", "--build", build, "--target", "llama", "--parallel", "4"])
        runtime = build / output.name
        run([compiler, "-std=c++17", "-O2", "-fPIC", "-shared", "-static-libstdc++",
             "-Wl,-z,max-page-size=16384", "-Wl,--gc-sections", "-Wl,--exclude-libs,ALL",
             "-I", source / "include", "-I", source / "ggml/include", native,
             build / "src/libllama.a", build / "ggml/src/libggml.a",
             build / "ggml/src/libggml-cpu.a", build / "ggml/src/libggml-base.a",
             "-lm", "-ldl", "-latomic", "-pthread", "-o", runtime])
        run([strip, runtime])
        if (hashlib.sha256(native.read_bytes()).hexdigest() != source_hash
                or run(["git", "-C", source, "rev-parse", "HEAD"], capture_output=True, text=True).stdout.strip() != COMMIT
                or run(["git", "-C", source, "status", "--porcelain", "--untracked-files=all"], capture_output=True, text=True).stdout):
            raise SystemExit("Source changed during build")
        output.write_bytes(runtime.read_bytes())
    data = {"scope": "experimental_probe_only", "llama_cpp_commit": COMMIT,
            "native_source_sha256": source_hash,
            "runtime_sha256": hashlib.sha256(output.read_bytes()).hexdigest(),
            "android_abi": "arm64-v8a", "android_api": 29, "gpu_layers": 0,
            "production_integration": False}
    report.write_text(json.dumps(data, indent=2) + "\n")
    print("Experimental isolated runtime built; no model or production APK was modified")


if __name__ == "__main__":
    main()
