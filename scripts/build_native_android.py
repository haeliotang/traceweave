#!/usr/bin/env python3
"""Build an ARM64 standalone APK using explicit Android toolchain paths."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import random
import re
import subprocess
import sys
import zipfile


def build_sdk_jar(classes, destination, android_jar):
    exports = ('NativeCore', 'ModelEngine', 'CanonicalClient', 'ProofVerifier',
               'OwnerAuthentication', 'PrivateAlignmentOutput', 'PrivateParticipant',
               'ApprovedPrivateAlignment', 'UniformPrivateAlignment', 'AlignmentQueryLease',
               'PeriodicTransitionKernel', 'FittedPeriodicSpectrum')
    analysis = subprocess.run(['jdeps', '-verbose:class', '-filter:none', '--class-path',
                               str(android_jar), str(classes)], check=True, capture_output=True, text=True)
    dependencies = {}
    for source, target in re.findall(r'^\s*(org\.traceform\.edge\.[\w$]+)\s+->\s+(org\.traceform\.edge\.[\w$]+)',
                                     analysis.stdout, re.MULTILINE):
        dependencies.setdefault(source, set()).add(target)
    retained, pending = set(), ['org.traceform.edge.' + name for name in exports]
    while pending:
        name = pending.pop()
        if name in retained:
            continue
        retained.add(name)
        pending.extend(dependencies.get(name, ()))
    entries = [name.replace('.', '/') + '.class' for name in sorted(retained)]
    if any(not (classes / entry).is_file() for entry in entries):
        raise ValueError('SDK dependency class is missing from compiled output')
    arguments = ['jar', 'cf', str(destination)]
    for entry in entries:
        arguments.extend(['-C', str(classes), entry])
    subprocess.run(arguments, check=True)
    return sorted(retained)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ndk-root", type=Path, required=True)
    parser.add_argument("--build-tools", type=Path, required=True)
    parser.add_argument("--android-jar", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path)
    parser.add_argument("--zk-verifier", type=Path, help="Optional ARM64 public-only proof verifier executable")
    parser.add_argument("--private-participant", type=Path, help="Optional ARM64 TLS private-computation participant executable")
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    sys.path.insert(0, str(root))
    from traceform.representation.manifold_lattice import DynamicalEnergyManifold
    from traceform.representation.normalizer import MultimodalNormalizer
    from traceform.representation.provenance import content_digest
    build = args.output_dir or root / "android/build"
    build.mkdir(parents=True, exist_ok=True)
    classes, dex, libraries, assets = [build / name for name in ("classes", "dex", "lib/arm64-v8a", "assets")]
    for directory in (classes, dex, libraries, assets): directory.mkdir(parents=True, exist_ok=True)
    compiler = args.ndk_root / "toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android26-clang++"
    library = libraries / "libtraceform.so"
    subprocess.run([str(compiler), "-std=c++17", "-O3", "-ffp-contract=off", "-shared", "-fPIC", "-static-libstdc++", "-Wl,-z,max-page-size=16384", "-Wl,--build-id=sha1", "-Wl,--gc-sections",
                    str(root / "native/traceform_core.cpp"), str(root / "native/traceform_jni.cpp"), str(root / "native/periodic_spectrum.cpp"), str(root / "native/periodic_spectrum_jni.cpp"), "-o", str(library)], check=True)
    benchmark = build / "traceform-core-benchmark"
    subprocess.run([str(compiler), "-std=c++17", "-O3", "-ffp-contract=off", "-static-libstdc++",
                    str(root / "native/traceform_core.cpp"), str(root / "native/traceform_benchmark.cpp"), "-o", str(benchmark)], check=True)
    java = sorted((root / "android/src").rglob("*.java"))
    subprocess.run(["javac", "--release", "8", "-classpath", str(args.android_jar), "-d", str(classes), *(str(path) for path in java)], check=True)
    subprocess.run([str(args.build_tools / "d8"), "--min-api", "26", "--lib", str(args.android_jar), "--output", str(dex), *(str(path) for path in sorted(classes.rglob("*.class")))], check=True)
    shutil.copy2(root / "artifacts/manifold_dynamics_v1/manifold_lattice_model.json", assets / "manifold.json")
    shutil.copy2(root / "artifacts/representation_v1/normalizer_v1.json", assets / "normalizer.json")
    parameters = json.loads((assets / "normalizer.json").read_text())
    parameters = parameters.get("normalization_parameters", parameters)
    model = DynamicalEnergyManifold.from_dict(json.loads((assets / "manifold.json").read_text()))
    binding = {"model_sha256": content_digest(model.to_dict()),
               "normalizer_sha256": content_digest(MultimodalNormalizer.from_dict(parameters).to_dict())}
    binding["model_version"] = content_digest({"manifold": binding["model_sha256"], "normalizer": binding["normalizer_sha256"]})
    (assets / "model_binding.json").write_text(json.dumps(binding))
    rng = random.Random(42)
    cases = []
    for sample in range(8):
        names = parameters["feature_names"]
        raw = [parameters["medians"].get(name, 0) + rng.uniform(-12, 12) * parameters["iqrs"].get(name, 1) for name in names]
        vector = [round(max(-10, min(10, (value - parameters["medians"].get(name, 0)) / (parameters["iqrs"].get(name, 1) if parameters["iqrs"].get(name, 1) >= 1e-6 else 1))), 5) for name, value in zip(names, raw)]
        velocity = [0.0 if sample == 0 else rng.uniform(-1, 1) for _ in names]
        energies = [model.compute_energy_distance(vector, velocity, node) for node in model.nodes]
        index = min(range(len(energies)), key=energies.__getitem__)
        cases.append({"raw": raw, "velocity": velocity, "expected_vector": vector,
                      "expected_state": model.nodes[index].state_token, "expected_distance": energies[index],
                      "expected_grid_coord": list(model.nodes[index].grid_coord)})
    (assets / "parity.json").write_text(json.dumps({"cases": cases}))
    unsigned = build / "traceform-unsigned.apk"
    subprocess.run([str(args.build_tools / "aapt2"), "link", "-I", str(args.android_jar), "--manifest", str(root / "android/AndroidManifest.xml"), "-A", str(assets), "-o", str(unsigned)], check=True)
    with zipfile.ZipFile(unsigned, "a", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.write(dex / "classes.dex", "classes.dex")
        archive.write(library, "lib/arm64-v8a/libtraceform.so")
        if args.zk_verifier:
            archive.write(args.zk_verifier, "lib/arm64-v8a/libtraceform_zk_verify.so")
        if args.private_participant:
            archive.write(args.private_participant, "lib/arm64-v8a/libtraceform_private_participant.so")
    aligned = build / "traceform-aligned.apk"
    subprocess.run([str(args.build_tools / "zipalign"), "-f", "-p", "4", str(unsigned), str(aligned)], check=True)
    key = build / "development.keystore"
    if not key.exists():
        subprocess.run(["keytool", "-genkeypair", "-keystore", str(key), "-storepass", "traceform-local", "-keypass", "traceform-local", "-alias", "traceform", "-dname", "CN=Traceform Local Development", "-keyalg", "RSA", "-validity", "3650"], check=True, capture_output=True)
    apk = build / "traceform.apk"
    subprocess.run([str(args.build_tools / "apksigner"), "sign", "--ks", str(key), "--ks-pass", "pass:traceform-local", "--out", str(apk), str(aligned)], check=True)
    subprocess.run([str(args.build_tools / "apksigner"), "verify", "--verbose", str(apk)], check=True)
    sdk_classes = build_sdk_jar(classes, build / "traceform-sdk.jar", args.android_jar)
    sources = [Path(__file__).resolve(), root / "android/AndroidManifest.xml", root / "native/traceform_core.h", root / "native/traceform_core.cpp", root / "native/traceform_jni.cpp", root / "native/traceform_benchmark.cpp", root / "native/periodic_spectrum.h", root / "native/periodic_spectrum.cpp", root / "native/periodic_spectrum_jni.cpp", *java]
    report = {"apk_sha256": hashlib.sha256(apk.read_bytes()).hexdigest(), "apk_bytes": apk.stat().st_size,
              "library_bytes": library.stat().st_size, "abi": "arm64-v8a", "signing": "local_development_only",
              "benchmark_sha256": hashlib.sha256(benchmark.read_bytes()).hexdigest(), "benchmark_bytes": benchmark.stat().st_size,
              "source_sha256": {str(path.relative_to(root)): hashlib.sha256(path.read_bytes()).hexdigest() for path in sources},
              "model_binding": binding, "sdk_classes": sdk_classes,
              "scope": "native_canonical_vector_sdk_and_partial_sensor_adapter_not_full_python_sensorium_parity"}
    if args.zk_verifier:
        report["proof_verifier_sha256"] = hashlib.sha256(args.zk_verifier.read_bytes()).hexdigest()
    if args.private_participant:
        report["private_participant_sha256"] = hashlib.sha256(args.private_participant.read_bytes()).hexdigest()
        report["private_participant_bytes"] = args.private_participant.stat().st_size
    (build / "build_report.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({"apk": str(apk.resolve()), "apk_bytes": report["apk_bytes"]}))


if __name__ == "__main__":
    main()
