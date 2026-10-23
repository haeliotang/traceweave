"""Substrate SDK exception hierarchy."""

from typing import Optional


class SubstrateError(Exception):
    """Base exception for all Substrate errors."""

    def __init__(self, message: str, raw_response: Optional[dict] = None):
        super().__init__(message)
        self.message = message
        self.raw_response = raw_response or {}


class SubstrateConnectionError(SubstrateError):
    """Raised when communication with the Substrate daemon fails."""
    pass


class SubstrateAuthError(SubstrateError):
    """Raised when authentication, registration, or session verification fails."""
    pass


class CapabilityDeniedError(SubstrateError):
    """Raised when a capability request or invocation is denied or ungranted."""
    pass


class VaultError(SubstrateError):
    """Raised when vault initialization, locking, or cryptographic operations fail."""
    pass


class SubstrateProtocolError(SubstrateError):
    """Raised when response parsing or wire protocol contracts are violated."""
    pass
