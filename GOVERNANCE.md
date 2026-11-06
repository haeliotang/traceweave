# Governance

Traceweave is **maintainer-led**. Inceptless (the repository owner) makes the final call on
direction, releases and security fixes. There is no foundation, PMC or elected committee behind
this repository, and no roadmap commitment beyond what is written in
[docs/agent_os_milestones_2026-10-30.md](docs/agent_os_milestones_2026-10-30.md).

## How decisions are made

- Design and scope discussions happen in issues and pull requests; decisions that change
  behaviour are recorded in the same commit as the code.
- Claims about the system must be backed by a receipt in `artifacts/` or a test in the suite.
  "Artifact verified" and "runs on a device" are separate statements and are stated separately.
- Security reports follow [SECURITY.md](SECURITY.md) and are handled privately until a fix ships.

## Releases

Only the owner publishes releases. Every release follows
[docs/agent_os_release_process.md](docs/agent_os_release_process.md): clean tree, green CI,
preflight pass, tag on the built commit, published checksums, and a development-key side-load
notice. Published assets are immutable.

## Contributions

Contributions are accepted under Apache-2.0 (see [CONTRIBUTING.md](CONTRIBUTING.md)). By
submitting a pull request you confirm you have the right to license the change under those terms.
