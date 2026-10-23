"""Data models and type definitions for Substrate protocol entities."""

from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional


@dataclass
class PingStatus:
    service: str
    version: str
    os: str
    arch: str
    pong: bool
    vault_unlocked: bool
    active_agent_sessions: int
    request_index: int
    server_time_ms: int

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "PingStatus":
        return cls(
            service=str(data.get("service", "")),
            version=str(data.get("version", "")),
            os=str(data.get("os", "")),
            arch=str(data.get("arch", "")),
            pong=bool(data.get("pong", False)),
            vault_unlocked=bool(data.get("vault_unlocked", False)),
            active_agent_sessions=int(data.get("active_agent_sessions", 0)),
            request_index=int(data.get("request_index", 0)),
            server_time_ms=int(data.get("server_time_ms", 0)),
        )


@dataclass
class SessionInfo:
    session_token: str
    agent_id: str
    registered_at: int
    expires_at: int

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "SessionInfo":
        return cls(
            session_token=str(data.get("session_token", "")),
            agent_id=str(data.get("agent_id", "")),
            registered_at=int(data.get("registered_at", 0)),
            expires_at=int(data.get("expires_at", 0)),
        )


@dataclass
class SessionVerification:
    valid: bool
    agent_id: str
    expires_at: int

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "SessionVerification":
        return cls(
            valid=bool(data.get("valid", False)),
            agent_id=str(data.get("agent_id", "")),
            expires_at=int(data.get("expires_at", 0)),
        )


@dataclass
class ActiveGrant:
    agent_id: str
    task_id: str
    capability: str
    scope: str
    expires_in_secs: int

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "ActiveGrant":
        return cls(
            agent_id=str(data.get("agent_id", "")),
            task_id=str(data.get("task_id", "")),
            capability=str(data.get("capability", "")),
            scope=str(data.get("scope", "")),
            expires_in_secs=int(data.get("expires_in_secs", 0)),
        )


@dataclass
class CapabilityDescriptor:
    capability: str
    title: str
    description: str
    scope: str
    available: bool

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "CapabilityDescriptor":
        return cls(
            capability=str(data.get("capability", "")),
            title=str(data.get("title", "")),
            description=str(data.get("description", "")),
            scope=str(data.get("scope", "")),
            available=bool(data.get("available", True)),
        )


@dataclass
class CapabilityList:
    capabilities: List[str]
    descriptors: List[CapabilityDescriptor]
    active_grants: List[ActiveGrant]

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "CapabilityList":
        raw_grants = data.get("active_grants", [])
        grants = [ActiveGrant.from_dict(g) for g in raw_grants]
        raw_caps = data.get("capabilities", [])

        cap_names: List[str] = []
        descriptors: List[CapabilityDescriptor] = []

        for c in raw_caps:
            if isinstance(c, dict):
                desc = CapabilityDescriptor.from_dict(c)
                descriptors.append(desc)
                cap_names.append(desc.capability)
            else:
                cap_names.append(str(c))

        return cls(
            capabilities=cap_names,
            descriptors=descriptors,
            active_grants=grants,
        )


@dataclass
class GrantInfo:
    granted: bool
    capability: str
    scope: str
    agent_id: str
    expires_at: int

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "GrantInfo":
        return cls(
            granted=bool(data.get("granted", False)),
            capability=str(data.get("capability", "")),
            scope=str(data.get("scope", "")),
            agent_id=str(data.get("agent_id", "")),
            expires_at=int(data.get("expires_at", 0)),
        )


@dataclass
class InvokeResult:
    result: str
    capability: str
    scope: str
    agent_id: str

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "InvokeResult":
        return cls(
            result=str(data.get("result", "")),
            capability=str(data.get("capability", "")),
            scope=str(data.get("scope", "")),
            agent_id=str(data.get("agent_id", "")),
        )


@dataclass
class Task:
    id: str
    title: str
    status: str
    created_at: int
    updated_at: int
    execution_step: int = 0
    plan: Optional[Dict[str, Any]] = None
    result: str = ""
    events: List[str] = field(default_factory=list)

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "Task":
        return cls(
            id=str(data.get("id", "")),
            title=str(data.get("title", "")),
            status=str(data.get("status", "")),
            created_at=int(data.get("created_at", 0)),
            updated_at=int(data.get("updated_at", 0)),
            execution_step=int(data.get("execution_step", 0)),
            plan=data.get("plan"),
            result=str(data.get("result", "")),
            events=[str(e) for e in data.get("events", [])],
        )


@dataclass
class SyncStats:
    imported_tasks: int
    updated_tasks: int
    imported_memories: int
    updated_memories: int
    skipped_records: int

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "SyncStats":
        return cls(
            imported_tasks=int(data.get("imported_tasks", 0)),
            updated_tasks=int(data.get("updated_tasks", 0)),
            imported_memories=int(data.get("imported_memories", 0)),
            updated_memories=int(data.get("updated_memories", 0)),
            skipped_records=int(data.get("skipped_records", 0)),
        )
