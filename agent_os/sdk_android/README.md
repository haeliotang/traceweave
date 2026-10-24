# traceform-substrate Android Client SDK

Official Android client library for connecting third-party Agent applications to the **Personal Agent Substrate** via AIDL Binder IPC.

## Architecture

```
Third-Party Agent APK               Personal Agent Substrate (AgentOS)
┌───────────────────────┐           ┌──────────────────────────────────┐
│  SubstrateClient      │           │  SubstrateService                │
│  (sdk_android)        │ ──AIDL──> │  - CapabilityBroker (JIT Grants) │
│                       │   IPC     │  - UID/package/signer binding    │
│  AgentManifest.java   │           │  - Encrypted Personal Vault      │
└───────────────────────┘           └──────────────────────────────────┘
```

## Setup

In your third-party `AndroidManifest.xml`, declare query intent and Substrate permission:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.example.myagent">

    <queries>
        <package android:name="org.traceform.agentos" />
        <intent>
            <action android:name="org.traceform.agentos.action.BIND_SUBSTRATE" />
        </intent>
    </queries>

```

The host derives each Agent identity as `android-package:<package>:sha256:<current-signer-fingerprint>`; each Binder session is separately bound to the caller UID. It verifies that the package is the sole package associated with that UID, reads the installed APK signing certificate through `PackageManager`, and rechecks caller UID, package ownership, and signer fingerprint on every session call. The manifest's Agent ID is descriptive metadata, not identity. The legacy `agentPublicKeyHash` AIDL argument remains in the wire shape but is ignored; it is not proof of key ownership. Each APK package is one Agent identity, so multiple in-app agents share grants and audit identity. Shared-UID packages and APKs with multiple current signers are unsupported. A signer change creates a new identity and requires registration again. Requesting a capability posts a system notification that shows the package and signer SHA-256; the user must approve the five-minute grant. Denials and notification-permission failures fail closed. Session tokens are secrets and must not be logged.

## Usage Example

```java
import org.traceform.agentos.sdk.AgentManifest;
import org.traceform.agentos.sdk.SubstrateCallback;
import org.traceform.agentos.sdk.SubstrateClient;

// 1. Initialize client
SubstrateClient client = new SubstrateClient(context);

// 2. Connect to Substrate Service
client.connect(new SubstrateCallback.ConnectionListener() {
    @Override
    public void onConnected() {
        // 3. Declare manifest and register
        AgentManifest manifest = new AgentManifest.Builder(context.getPackageName(), "My Agent")
                .description("Specialized autonomous helper agent")
                .requestCapability("demo.note.read", "synthetic.welcome", "Inspect daily notes")
                .build();

        client.registerAgent(manifest,
            new SubstrateCallback.ActionCallback<String>() {
                @Override
                public void onSuccess(String sessionToken) {
                    // 4. Request JIT Capability
                    client.requestCapability(client.getActiveTaskId(), "demo.note.read", "synthetic.welcome",
                        new SubstrateCallback.ActionCallback<Boolean>() {
                            @Override
                            public void onSuccess(Boolean granted) {
                                if (granted) {
                                    // 5. Invoke capability under grant
                                    client.invokeCapability(client.getActiveTaskId(), "demo.note.read", "synthetic.welcome",
                                        new SubstrateCallback.ActionCallback<String>() {
                                            @Override
                                            public void onSuccess(String noteContent) {
                                                android.util.Log.i("MyAgent", "Note content: " + noteContent);
                                            }

                                            @Override
                                            public void onError(Throwable error) {
                                                // Handle invocation failure
                                            }
                                        });
                                }
                            }

                            @Override
                            public void onError(Throwable error) {
                                // Handle permission denial
                            }
                        });
                }

                @Override
                public void onError(Throwable error) {
                    // Handle registration error
                }
            });
    }

    @Override
    public void onDisconnected() {
        android.util.Log.w("MyAgent", "Substrate disconnected");
    }

    @Override
    public void onError(Throwable error) {
        android.util.Log.e("MyAgent", "Connection failed", error);
    }
});
```
