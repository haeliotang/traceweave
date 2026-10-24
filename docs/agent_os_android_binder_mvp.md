# Agent OS Android Binder-only MVP

Status: adopted for the current MVP on 2026-10-24

This decision narrows the executable product boundary to the Android app prototype. It does not claim an AOSP or GrapheneOS fork.

## Execution boundary

- First-party tasks run through the Android APK's `AgentExecutor` and `JobScheduler` path.
- Cross-app agents request capabilities through the exported `SubstrateService` AIDL Binder interface. The Broker shows an explicit user approval for each new capability grant.
- Rust C-ABI code may remain as app-local computation. The standalone Rust `agentosd` process, Java/JNI TCP/UDS client paths, Magisk installer/init service/Android watchdog, and file-backed daemon store remain disabled.
- The Python TCP client is unsupported by this MVP and fails closed. The Android Binder SDK is the supported external-agent client.
- Task persistence and user-mediated encrypted archive import/export remain app-owned. No automatic device sync is implied.

## Agent identity rule

The host derives an external Agent identity using this scheme:

```text
android-package:<package-name>:sha256:<current-apk-signer-certificate-fingerprint>
```

The signer fingerprint is lowercase hexadecimal SHA-256 over the installed signer certificate bytes. The MVP accepts packages with exactly one current signer and a UID assigned to exactly one package. Shared-UID packages and multi-signer packages are rejected.

For every Binder call, the service checks the session token against the Binder caller UID, verifies that the package is still the sole package assigned to that UID, and recomputes the current signer fingerprint. A signer change invalidates the session; the app must register again. The user approval screen displays both the package name and signer fingerprint. Capability grants and audit attribution use the derived identity.

The AIDL `legacyAgentPublicKeyHash` argument is kept only to preserve the existing three-string Binder wire shape. The host ignores it and never treats a caller-provided hash as proof of key ownership. The `AgentManifest` ID is descriptive metadata and does not affect authentication or authorization. The Android SDK exposes registration methods that do not ask callers to provide a hash; its old overloads remain deprecated for source compatibility.

One APK package is one Agent identity in this MVP. Multiple Agent components inside the same APK share the identity, task access, grants, and audit attribution. Separate identities require separate APK packages. A future design for multiple agents within one package must include a public-key challenge signed by a key held by that Agent; adding a self-asserted hash is insufficient.

## Explicitly deferred

- AOSP/GrapheneOS system image, privileged daemon, or cross-platform daemon transport.
- Independent cryptographic identity for multiple Agents in one APK.
- Automatic memory sync, causal conflict resolution, and tombstone collection.
- Broader cloud authorization and data-minimization policy.
- Modular hardware contracts and production peripheral drivers.

These are separate decisions and are not prerequisites for describing the current Android Binder MVP accurately.

## Separate follow-up decisions

Keep the remaining risks in separate decision records so transport, privacy, and hardware assumptions do not become coupled:

1. **Memory synchronization protocol:** specify versioned archive/event semantics, device identity, conflict resolution, deletion propagation, recovery, and key handling. Gate automatic sync on interoperability fixtures and conflict/recovery evidence; user-mediated encrypted archive import/export remains the only supported portability path until then.
2. **Cloud authorization policy:** specify per-task user consent, allowed data classes, payload minimization, endpoint trust, revocation, and auditable egress. Gate cloud execution on a request-level policy and measured network-egress evidence; current local execution does not establish a general zero-egress guarantee.
3. **Hardware target:** compare candidate compute boards and phone platforms against boot-chain control, driver coverage, modem/camera/GPU support, battery behavior, repairability, and update ownership. Gate a platform fork or modular hardware contract on a chosen target and a working driver/update path; Pixel 6a on GrapheneOS remains a development device, not the production hardware decision.
