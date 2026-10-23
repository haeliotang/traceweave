"""Agent Manifest builder and schema representation."""

from dataclasses import asdict, dataclass, field
import json
from typing import Any, Dict, List, Optional


@dataclass
class CapabilityDeclaration:
    capability: str
    scope: str
    reason: str
    required: bool = True

    def to_dict(self) -> Dict[str, Any]:
        return {
            "capability": self.capability,
            "scope": self.scope,
            "reason": self.reason,
            "required": self.required,
        }


@dataclass
class AgentMetadata:
    id: str
    name: str
    version: str
    description: str = ""
    author: str = ""
    homepage: str = ""

    def to_dict(self) -> Dict[str, Any]:
        res = {
            "id": self.id,
            "name": self.name,
            "version": self.version,
        }
        if self.description:
            res["description"] = self.description
        if self.author:
            res["author"] = self.author
        if self.homepage:
            res["homepage"] = self.homepage
        return res


@dataclass
class AgentManifest:
    manifest_version: int = 1
    agent: Optional[AgentMetadata] = None
    capabilities_requested: List[CapabilityDeclaration] = field(default_factory=list)
    max_concurrent_tasks: int = 1
    data_retention_policy: str = "session_only"

    def to_dict(self) -> Dict[str, Any]:
        if not self.agent:
            raise ValueError("Manifest must contain agent metadata")
        return {
            "manifest_version": self.manifest_version,
            "agent": self.agent.to_dict(),
            "capabilities_requested": [c.to_dict() for c in self.capabilities_requested],
            "max_concurrent_tasks": self.max_concurrent_tasks,
            "data_retention_policy": self.data_retention_policy,
        }

    def to_json(self) -> str:
        return json.dumps(self.to_dict(), indent=2)

    @classmethod
    def builder(cls, agent_id: str, name: str, version: str = "1.0.0") -> "AgentManifestBuilder":
        return AgentManifestBuilder(agent_id, name, version)


class AgentManifestBuilder:
    def __init__(self, agent_id: str, name: str, version: str = "1.0.0"):
        self._agent = AgentMetadata(id=agent_id, name=name, version=version)
        self._capabilities: List[CapabilityDeclaration] = []
        self._max_tasks = 1
        self._retention = "session_only"

    def description(self, desc: str) -> "AgentManifestBuilder":
        self._agent.description = desc
        return self

    def author(self, author: str) -> "AgentManifestBuilder":
        self._agent.author = author
        return self

    def request_capability(
        self, capability: str, scope: str, reason: str, required: bool = True
    ) -> "AgentManifestBuilder":
        self._capabilities.append(
            CapabilityDeclaration(
                capability=capability,
                scope=scope,
                reason=reason,
                required=required,
            )
        )
        return self

    def max_concurrent_tasks(self, max_tasks: int) -> "AgentManifestBuilder":
        self._max_tasks = max(1, max_tasks)
        return self

    def data_retention(self, policy: str) -> "AgentManifestBuilder":
        self._retention = policy
        return self

    def build(self) -> AgentManifest:
        return AgentManifest(
            manifest_version=1,
            agent=self._agent,
            capabilities_requested=self._capabilities,
            max_concurrent_tasks=self._max_tasks,
            data_retention_policy=self._retention,
        )
