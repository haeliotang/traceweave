# Legacy traceform-substrate Python client

**Unsupported by the current Agent OS MVP.** The Rust `agentosd` TCP/UDS runtime is disabled, and this client now fails closed with a clear error instead of attempting a localhost connection. The supported external-agent transport is the Android SDK over Binder.

The Python package remains in the repository as experimental protocol code. Do not use it to connect to a running Traceform service until an authenticated non-Android transport is designed and implemented.

See [`agent_os/sdk_android/README.md`](../sdk_android/README.md) for the supported Binder client and the Agent identity contract.
