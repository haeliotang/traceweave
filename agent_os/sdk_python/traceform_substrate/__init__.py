# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: Apache-2.0

"""traceform-substrate: Official Python SDK for Personal Agent Substrate."""

from .client import SubstrateClient
from .errors import (
    CapabilityDeniedError,
    SubstrateAuthError,
    SubstrateConnectionError,
    SubstrateError,
    SubstrateProtocolError,
    VaultError,
)
from .manifest import (
    AgentManifest,
    AgentManifestBuilder,
    AgentMetadata,
    CapabilityDeclaration,
)
from .models import (
    ActiveGrant,
    CapabilityList,
    GrantInfo,
    InvokeResult,
    PingStatus,
    SessionInfo,
    SessionVerification,
    SyncStats,
    Task,
)

__version__ = "1.0.0"

__all__ = [
    "SubstrateClient",
    "AgentManifest",
    "AgentManifestBuilder",
    "AgentMetadata",
    "CapabilityDeclaration",
    "PingStatus",
    "SessionInfo",
    "SessionVerification",
    "ActiveGrant",
    "CapabilityList",
    "GrantInfo",
    "InvokeResult",
    "Task",
    "SyncStats",
    "SubstrateError",
    "SubstrateConnectionError",
    "SubstrateAuthError",
    "CapabilityDeniedError",
    "VaultError",
    "SubstrateProtocolError",
]
