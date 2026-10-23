# AOSP System-Level Sinking & Substrate Service Architecture

**Revision**: 1.0.0  
**Target Platform**: Android Open Source Project (AOSP) Android 15 / 16 / 17  
**Module Identifier**: `frameworks/base/services/core/java/com/android/server/agent/`

---

## 1. Executive Vision

The ultimate evolution of the **Personal Agent Substrate** is migrating from an application-level bound service into an **AOSP Native Core System Service**. In this design:
1. `agentosd` becomes a core system daemon launched during the `late_start` init trigger by Android `init`.
2. `AgentSubstrateManagerService` operates within `system_server` alongside `ActivityManagerService` and `PackageManagerService`.
3. Standard applications interact through `Context.getSystemService(Context.AGENT_SUBSTRATE_SERVICE)`.
4. Cryptographic keys are bound directly to hardware StrongBox / TEE Keymaster without dependency on APK signatures.

---

## 2. AOSP Layered Architecture

```
┌────────────────────────────────────────────────────────┐
│               Third-Party Agent Applications           │
│         (Meta Agent, xAI Agent, Local Assistant)       │
└───────────────────────────┬────────────────────────────┘
                            │
               Context.getSystemService(...)
                            │
┌───────────────────────────▼────────────────────────────┐
│      android.app.agent.AgentSubstrateManager           │
│      (Public SDK Client in frameworks/base/core)       │
└───────────────────────────┬────────────────────────────┘
                            │ Binder IPC
┌───────────────────────────▼────────────────────────────┐
│   com.android.server.agent.AgentSubstrateManagerService│
│   (system_server privileged system service)           │
│   - Package Signature & UID Validation                │
│   - JIT Capability Enforcement                         │
│   - User Consent Dialog & Capsule Dispatch             │
└──────────────┬──────────────────────────┬──────────────┘
               │                          │
         Native Binder              Native Socket
               │                          │
┌──────────────▼─────────────┐ ┌──────────▼──────────────┐
│  /system/bin/agentosd      │ │  Hardware StrongBox/TEE │
│  - Ed25519 Identity Store  │ │  - Master Key Derivation│
│  - CRDT LWW Sync Engine    │ │  - Wrapped DEK Storage  │
│  - Append-Only Audit Log   │ └─────────────────────────┘
└────────────────────────────┘
```

---

## 3. SELinux Domain Policy

Native daemon permissions are defined in `system/sepolicy/private/agentosd.te`:

- Domain: `agentosd`
- Binary: `/system/bin/agentosd`
- Context: `u:r:agentosd:s0`
- Storage: `/data/system/agentosd/` labeled `agentosd_data_file`
- Sockets: Unix Domain Socket `/dev/socket/agentosd`

---

## 4. Hardware Keymaster & StrongBox Integration

In the standalone prototype (Phase 2), the User Master Key (UMK) is derived via PBKDF2-HMAC-SHA256 (600,000 rounds) and wraps the DEK.

In full AOSP integration:
1. Master secret派生: `IKeymasterDevice` or `IKeystoreService` generates an asymmetric ECC P-256 or AES-256 hardware-backed root key in StrongBox with `TAG_USER_AUTH_TYPE = USER_AUTH_TYPE_PASSWORD`.
2. Biometric and passcode unlock: Successful device unlock automatically provisions the hardware key to unwrap the Substrate DEK.
3. Cold storage guarantee: Upon device lock (`DevicePolicyManager.lockNow()`), the DEK is evicted from RAM via `zeroize`.

---

## 5. Implementation Roadmap to AOSP Core

1. **Step 1: System Service Definition**
   - Add `AGENT_SUBSTRATE_SERVICE` constant to `android.content.Context`.
   - Register `AgentSubstrateManagerService` in `SystemServer.java` (`startCoreServices()`).

2. **Step 2: Core AIDL Expansion**
   - Move `IAgentSubstrate.aidl` into `frameworks/base/core/java/android/app/agent/`.
   - Expose synchronous and asynchronous permission broker APIs.

3. **Step 3: SELinux Integration**
   - Merge `agentosd.te` into `system/sepolicy/private/` and `system/sepolicy/public/`.
   - Compile and verify with `make sepolicy`.

4. **Step 4: Boot Init Script**
   - Place `agentosd.rc` in `/system/etc/init/agentosd.rc`.
   - Verify daemon startup via `logcat -s agentosd`.
