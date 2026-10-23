# traceform-substrate Python SDK

Official lightweight Python SDK for communicating with the Personal Agent Substrate Hub (`agentosd`).

## Installation

```bash
pip install -e agent_os/sdk_python
```

## Quickstart

```python
from traceform_substrate import SubstrateClient, AgentManifest

# 1. Initialize client
with SubstrateClient(host="127.0.0.1", port=19898) as client:
    # 2. Check daemon health
    status = client.ping()
    print(f"Connected to Substrate {status.version} on {status.os}/{status.arch}")

    # 3. Build agent manifest and register
    manifest = (
        AgentManifest.builder("org.agent.researcher", "Research Specialist")
        .description("Specialized research and document summarization agent")
        .request_capability("demo.note.read", "synthetic.welcome", "Need to read task outline")
        .build()
    )
    session = client.register("org.agent.researcher", manifest)
    print(f"Registered session: {session.session_token} (expires at {session.expires_at})")

    # 4. Request JIT capability grant for task
    task_id = "task-research-001"
    grant = client.request_capability(task_id, "demo.note.read", "synthetic.welcome")
    print(f"Grant granted: {grant.granted} for {grant.capability}")

    # 5. Invoke capability
    res = client.invoke_capability(task_id, "demo.note.read", "synthetic.welcome")
    print(f"Invocation output: {res.result}")

    # 6. Revoke capability when done
    revoked = client.revoke_capability(task_id, "demo.note.read", "synthetic.welcome")
    print(f"Revoked grant: {revoked}")

    # 7. Query audit trail
    events = client.query_audit(limit=10)
    for event in events:
        print(f"Audit: {event}")
```
