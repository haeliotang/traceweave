# Local structural alignment and privacy boundary

The local CLI uses squared-loss entropic Gromov–Wasserstein fixed-point iterations
with log-domain Sinkhorn projections and deterministic multi-start initialization.
It reports marginal feasibility and fixed-point residual separately from the
objective. Nonconvex convergence does not prove a global optimum or biological
equivalence. Node-distance matrices are diameter-normalized with uniform masses;
this deliberately removes absolute scale and does not transfer occupancy weights.

Inputs are locally supplied model files. The tool performs no network calls and
exports no raw observations. It does not implement zero-knowledge proofs,
secure multiparty computation, differential privacy or encrypted cross-device
comparison. Model geometry, coupling and even a scalar similarity score can leak
information under repeated queries. Encryption in transit would not remove this
leakage. Cross-person collection and transfer require a separate, explicit data
sharing protocol and consent; no sharing is enabled by this implementation.

Threat model: a local process authorized to open both model files can inspect
their entire contents. Other processes with filesystem access, compromised
devices and malicious counterparties are not protected. Deployments should keep
models in application-private storage and avoid uploading correspondence reports.

Algorithm reference: [POT Gromov–Wasserstein documentation](https://pythonot.github.io/gen_modules/ot.gromov.html).

An exact bounded integer witness validator now defines the arithmetic relation
used by the experimental Rust proof backend. The Python validator still requires both plaintext structures
locally and explicitly reports cryptographic zero knowledge false. See the
[private arithmetic contract](zero_knowledge_alignment_protocol.md) for required
current proof-verification evidence and remaining secure-computation, production
trust, owner-enrollment and output-leakage work. The separate Rust backend hides
its witness from the verifier; its prover still knows both models. It is not
an encrypted two-owner comparison protocol.
