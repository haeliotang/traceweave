package org.traceform.agentos.sdk;

public class SubstrateException extends Exception {
    public SubstrateException(String message) {
        super(message);
    }

    public SubstrateException(String message, Throwable cause) {
        super(message, cause);
    }

    public static class NotConnectedException extends SubstrateException {
        public NotConnectedException() {
            super("Substrate service is not bound or currently disconnected");
        }
    }

    public static class AuthenticationException extends SubstrateException {
        public AuthenticationException(String message) {
            super(message);
        }
    }

    public static class CapabilityDeniedException extends SubstrateException {
        public CapabilityDeniedException(String capability, String scope) {
            super("Capability denied or expired: " + capability + " (" + scope + ")");
        }
    }
}
