# Licensing

Traceweave is licensed layer by layer, so the parts you need in order to
implement or interoperate can be reused freely, while the application and
runtime stay copyleft.

## Layers

| Path | SPDX identifier | Why |
| --- | --- | --- |
| `LICENSE`, `agent_os/android/`, `android/`, `native/`, `agent_os/core/`, `agent_os/sync/`, `agent_os/packaging/`, `agent_os/ts_plugins/`, `scripts/`, `research/`, `tests/`, `agent_os/tests/` (except `libp2p*`) | `AGPL-3.0-or-later` | Applications, runtime, services, CLI, native code, build and operations scripts. |
| `agent_os/sdk_python/`, `agent_os/sdk_android/`, `agent_os/demo_agent/`, `agent_os/tests/libp2p*/`, `agent_os/android/src/**/IAgentSubstrate.aidl` | `Apache-2.0` | Client SDKs, an example agent, the Binder contract and the transport conformance probes exist so that third parties can implement or talk to the protocol without adopting our copyleft. |
| `docs/protocol/*.schema.json` | `Apache-2.0` | JSON Schema is a protocol contract; independent implementations must be able to copy it. |
| `docs/`, `README.md`, `README.zh-CN.md`, `CHANGELOG.md`, `CONTRIBUTING.md`, `GOVERNANCE.md`, `SECURITY.md`, `CODE_OF_CONDUCT.md`, `CITATION.cff`, `LICENSING.md`, `CLA.md` | `CC-BY-4.0` | Documentation, specifications and design text. |
| `agent_os/branding/` | `LicenseRef-Inceptless-Brand` | Logo and icon files are brand assets and are not covered by the source licences; see `LICENSES/LicenseRef-Inceptless-Brand.txt`. |
| `artifacts/`, `field_telemetry/`, `pixel_physical/` | `CC-BY-4.0` | Published measurement records and receipts; frozen byte-for-byte so they can be cited. This includes evidence code (e.g. `artifacts/agent_os/phase1/Phase1EvidenceActivity.java`) and `.patch` files: they are evidence records, not reusable source, so they follow the record licence. |
| `data/` | `Apache-2.0` | Published measurement fixtures and the helper scripts that produced them; frozen byte-for-byte. |
| Third-party vendored code and assets | their own licences | Kept as-is; see the file headers and `REUSE.toml`. |

If a file has no header, `REUSE.toml` declares its licensing. The
machine-readable form of this table is `REUSE.toml` plus the SPDX headers in
the source files and the full texts in `LICENSES/`.

## Why AGPL for the application and Apache for the contracts

The question we ask about a library is: **does it exist so that someone else
can implement or interoperate with our contract?** If yes, it is Apache-2.0.
If it only exists so that *we* can run the product, it is AGPL-3.0-or-later.

That is why the protocol schemas, client SDKs and conformance tests are
permissive: we want independent and closed-source implementations of the
protocol. The app, the runtime, the daemons and the service code are AGPL so
that a hosted or repackaged version has to publish its source.

## Dual licensing

Inceptless also offers commercial licences for the AGPL-licensed parts, to
fund the project. If you need terms other than the AGPL, write to
**support@inceptless.com**.

Contributions are accepted under the Inceptless Contributor License Agreement
(`CLA.md`) so that this dual licensing is possible. See `CONTRIBUTING.md`.

## Brand assets

The project name, logo and icons are not licensed under the code or
documentation licences. They are marked `LicenseRef-Inceptless-Brand`; you may
use them to refer truthfully to this project, but not to imply an official
status, sponsorship or endorsement. Questions: support@inceptless.com.

## Licence history

Releases stay under the licence they were published with; this change is not
retroactive.

| Version / tag | Licence | Notes |
| --- | --- | --- |
| `v0.1.0` – `v0.1.4` (and every earlier commit) | `Apache-2.0` | Published releases remain Apache-2.0 forever. |
| Commit that introduces this file (`license: …` series on branch `license/agpl-split`) and later | `AGPL-3.0-or-later` for the layers above | The first released version that carries the split licence will be noted in its release notes. |

Until that first release, `main` carries the split licence but the published
artefacts (`v0.1.4` and earlier) remain Apache-2.0. Do not re-upload or re-tag
old releases under the new licence.

## Android and app stores

The Android application will be AGPL-3.0-or-later. Any future store
distribution has to remain compatible with the AGPL (in particular, the source
offer in section 13 of the licence must be honoured for network use). This
note is informational; it does not add terms.
