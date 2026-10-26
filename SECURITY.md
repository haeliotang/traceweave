# Security reporting

Traceform is a development prototype. It has no security-certified release or maintained stable release line. Fixes target the current main branch; device receipts describe only the source revision and conditions they record.

Report vulnerabilities privately to **support@inceptless.com**. Include the affected revision, subsystem, synthetic reproduction and expected boundary. Do not send API keys, signing keys, archive passwords, user databases or personal telemetry. Public issues are appropriate only after sensitive details have been removed. No response-time or bounty commitment is offered.

The supported executor is the Android Binder/JobScheduler path. The standalone Rust daemon remains disabled. A model response cannot authorize a capability, approve a cloud request or grant file access. Cloud redaction is a best-effort transformation, not a complete privacy boundary.
