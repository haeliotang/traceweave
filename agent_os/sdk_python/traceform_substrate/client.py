"""Substrate Client: Native Python client implementation for Personal Agent Substrate."""

import base64
import json
import os
import socket
import tempfile
import time
from typing import Any, Dict, List, Optional, Union
import uuid

from .errors import (
    CapabilityDeniedError,
    SubstrateAuthError,
    SubstrateConnectionError,
    SubstrateError,
    SubstrateProtocolError,
    VaultError,
)
from .manifest import AgentManifest
from .models import (
    CapabilityList,
    GrantInfo,
    InvokeResult,
    PingStatus,
    SessionInfo,
    SessionVerification,
    SyncStats,
    Task,
)


class SubstrateClient:
    """Legacy client for the disabled TCP daemon; unsupported by the Android MVP."""

    def __init__(
        self,
        host: str = "127.0.0.1",
        port: int = 19898,
        timeout: float = 10.0,
    ):
        self.host = host
        self.port = port
        self.timeout = timeout
        self.active_agent_id: Optional[str] = None
        self.active_session_token: Optional[str] = None
        self._sock: Optional[socket.socket] = None
        self._file_reader = None

    def __enter__(self) -> "SubstrateClient":
        self.connect()
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        self.close()

    def connect(self) -> None:
        """Fail closed because the Android Binder-only MVP has no TCP daemon."""
        raise SubstrateConnectionError(
            "The Android Binder-only MVP does not expose the Rust TCP/UDS daemon; use the Android Binder SDK."
        )

    def close(self) -> None:
        """Close TCP connection."""
        if self._file_reader:
            try:
                self._file_reader.close()
            except Exception:
                pass
            finally:
                self._file_reader = None
        if self._sock:
            try:
                self._sock.close()
            except OSError:
                pass
            finally:
                self._sock = None

    def _ensure_connected(self) -> socket.socket:
        if self._sock is None:
            self.connect()
        return self._sock  # type: ignore

    def send_request(
        self,
        action: str,
        payload: Optional[Dict[str, Any]] = None,
        agent_id: Optional[str] = None,
        session_token: Optional[str] = None,
    ) -> Dict[str, Any]:
        """Send formatted request and await JSON response over line-delimited stream."""
        req_id = f"py-{uuid.uuid4().hex[:12]}"
        effective_agent_id = agent_id or self.active_agent_id
        effective_token = session_token or self.active_session_token

        req: Dict[str, Any] = {
            "id": req_id,
            "action": action,
            "payload": payload or {},
            "timestamp_ms": int(time.time() * 1000),
        }
        if effective_agent_id:
            req["agent_id"] = effective_agent_id
        if effective_token:
            req["session_token"] = effective_token

        wire_bytes = json.dumps(req).encode("utf-8") + b"\n"

        sock = self._ensure_connected()
        try:
            sock.sendall(wire_bytes)
            if self._file_reader is None:
                self._file_reader = sock.makefile("r", encoding="utf-8")
            line = self._file_reader.readline()
            if not line:
                raise SubstrateConnectionError("Substrate daemon closed connection unexpectedly")
            line = line.strip()
            if not line:
                raise SubstrateProtocolError("Received empty response from Substrate hub")

            resp = json.loads(line)
        except (socket.timeout, OSError) as e:
            self.close()
            raise SubstrateConnectionError(f"Network error during action '{action}': {e}") from e
        except json.JSONDecodeError as e:
            raise SubstrateProtocolError(f"Failed to parse JSON response: {e}") from e

        status = resp.get("status")
        if status != "ok":
            data = resp.get("data")
            err_msg = ""
            if isinstance(data, dict):
                err_msg = str(data.get("message") or data.get("error") or "")
            if not err_msg:
                err_msg = str(resp.get("error") or "Unknown substrate error")

            raw_err_lower = err_msg.lower()
            if "capability" in raw_err_lower or "denied" in raw_err_lower or "ungranted" in raw_err_lower or "未授权" in err_msg:
                raise CapabilityDeniedError(err_msg, raw_response=resp)
            elif "token" in raw_err_lower or "session" in raw_err_lower or "unauthorized" in raw_err_lower or "auth" in raw_err_lower:
                raise SubstrateAuthError(err_msg, raw_response=resp)
            elif "vault" in raw_err_lower:
                raise VaultError(err_msg, raw_response=resp)
            else:
                raise SubstrateError(err_msg, raw_response=resp)

        return resp

    # =========================================================================
    # High-level Protocol Methods
    # =========================================================================

    def ping(self) -> PingStatus:
        """Ping the Substrate daemon to verify status and telemetry."""
        resp = self.send_request("ping")
        return PingStatus.from_dict(resp.get("data", {}))

    def register(
        self,
        agent_id: str,
        manifest: Union[Dict[str, Any], AgentManifest],
        public_key: str = "",
    ) -> SessionInfo:
        """Register an agent identity with the Substrate."""
        manifest_dict = manifest.to_dict() if isinstance(manifest, AgentManifest) else manifest
        payload = {
            "agent_id": agent_id,
            "public_key": public_key,
            "manifest": manifest_dict,
        }
        resp = self.send_request("agent.register", payload)
        data = resp.get("data", {})
        session = SessionInfo.from_dict(data)

        # Cache session state on successful registration
        self.active_agent_id = session.agent_id
        self.active_session_token = session.session_token
        return session

    def verify_session(self, session_token: Optional[str] = None) -> SessionVerification:
        """Verify validity and expiration of a session token."""
        token = session_token or self.active_session_token
        if not token:
            raise SubstrateAuthError("No session token provided or cached")
        resp = self.send_request("agent.verify", {"session_token": token})
        return SessionVerification.from_dict(resp.get("data", {}))

    def list_agents(self) -> List[Dict[str, Any]]:
        """List registered agents in Substrate identity registry."""
        resp = self.send_request("agent.list")
        return resp.get("data", {}).get("agents", [])

    def list_capabilities(self, task_id: str = "") -> CapabilityList:
        """List available capabilities and active grants for task."""
        payload = {"task_id": task_id} if task_id else {}
        resp = self.send_request("capability.list", payload)
        return CapabilityList.from_dict(resp.get("data", {}))

    def request_capability(
        self,
        task_id: str,
        capability: str,
        scope: str = "*",
    ) -> GrantInfo:
        """Request a JIT capability grant for a specific task."""
        payload = {
            "task_id": task_id,
            "capability": capability,
            "scope": scope,
        }
        resp = self.send_request("capability.request", payload)
        return GrantInfo.from_dict(resp.get("data", {}))

    def invoke_capability(
        self,
        task_id: str,
        capability: str,
        scope: str = "*",
    ) -> InvokeResult:
        """Invoke a previously granted capability."""
        payload = {
            "task_id": task_id,
            "capability": capability,
            "scope": scope,
        }
        resp = self.send_request("capability.invoke", payload)
        data = resp.get("data", {})
        if not data.get("agent_id"):
            data["agent_id"] = self.active_agent_id or ""
        if not data.get("scope"):
            data["scope"] = scope
        return InvokeResult.from_dict(data)

    def revoke_capability(
        self,
        task_id: str,
        capability: str,
        scope: str = "*",
    ) -> bool:
        """Revoke an active grant."""
        payload = {
            "task_id": task_id,
            "capability": capability,
            "scope": scope,
        }
        resp = self.send_request("capability.revoke", payload)
        return bool(resp.get("data", {}).get("revoked", False))

    def init_vault(self, passphrase: str, vault_path: Optional[str] = None) -> bool:
        """Initialize or unlock the Substrate cryptographic vault."""
        if not vault_path:
            # Pick valid path depending on platform
            if os.path.exists("/data/local/tmp"):
                vault_path = "/data/local/tmp/agentos_vault.enc"
            else:
                vault_path = os.path.join(tempfile.gettempdir(), "agentos_vault.enc")
        payload = {
            "passphrase": passphrase,
            "vault_path": vault_path,
        }
        resp = self.send_request("vault.init", payload)
        return bool(resp.get("data", {}).get("initialized", False))

    def unlock_vault(self, passphrase: str, vault_path: Optional[str] = None) -> bool:
        """Unlock existing vault using user passphrase."""
        if not vault_path:
            if os.path.exists("/data/local/tmp"):
                vault_path = "/data/local/tmp/agentos_vault.enc"
            else:
                vault_path = os.path.join(tempfile.gettempdir(), "agentos_vault.enc")
        payload = {
            "passphrase": passphrase,
            "vault_path": vault_path,
        }
        resp = self.send_request("vault.unlock", payload)
        return bool(resp.get("data", {}).get("unlocked", False))

    def vault_status(self) -> bool:
        """Check if vault is unlocked."""
        resp = self.send_request("vault.status")
        return bool(resp.get("data", {}).get("unlocked", False))

    def seal(self, plaintext: str, aad: str = "substrate-data") -> str:
        """Encrypt plaintext payload via vault DEK."""
        plaintext_b64 = base64.b64encode(plaintext.encode("utf-8")).decode("utf-8")
        aad_b64 = base64.b64encode(aad.encode("utf-8")).decode("utf-8")
        payload = {
            "plaintext_b64": plaintext_b64,
            "aad_b64": aad_b64,
        }
        resp = self.send_request("vault.seal", payload)
        return str(resp.get("data", {}).get("sealed_b64", ""))

    def unseal(self, sealed_b64: str, aad: str = "substrate-data") -> str:
        """Decrypt sealed payload via vault DEK."""
        aad_b64 = base64.b64encode(aad.encode("utf-8")).decode("utf-8")
        payload = {
            "sealed_b64": sealed_b64,
            "aad_b64": aad_b64,
        }
        resp = self.send_request("vault.unseal", payload)
        raw_b64 = resp.get("data", {}).get("plaintext_b64", "")
        return base64.b64decode(raw_b64).decode("utf-8")

    def create_task(self, title: str, plan: Optional[Dict[str, Any]] = None) -> Task:
        """Create a new task in the Substrate store."""
        payload: Dict[str, Any] = {"title": title}
        if plan:
            payload["plan"] = plan
        resp = self.send_request("task.create", payload)
        return Task.from_dict(resp.get("data", {}).get("task", {}))

    def list_tasks(self) -> List[Task]:
        """Fetch all tasks recorded in Substrate store."""
        resp = self.send_request("task.list")
        raw_tasks = resp.get("data", {}).get("tasks", [])
        return [Task.from_dict(t) for t in raw_tasks]

    def export_sync(self, passphrase: str, device_id: str) -> str:
        """Export encrypted sync package for cross-device migration."""
        resp = self.send_request(
            "sync.export",
            {"passphrase": passphrase, "device_id": device_id},
        )
        return str(resp.get("data", {}).get("sync_package", ""))

    def import_sync(self, passphrase: str, sync_package: str) -> SyncStats:
        """Import and reconcile cross-device sync package via CRDT."""
        resp = self.send_request(
            "sync.import",
            {"passphrase": passphrase, "sync_package": sync_package},
        )
        return SyncStats.from_dict(resp.get("data", {}))

    def query_audit(self, limit: int = 50) -> List[str]:
        """Query append-only audit trail."""
        resp = self.send_request("audit.query", {"limit": limit})
        return [str(e) for e in resp.get("data", {}).get("events", [])]
