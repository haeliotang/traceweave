package org.traceform.agentos;

final class CapabilityDescriptor {
    final String capability;
    final String scope;
    final String title;
    final String description;
    final boolean available;

    CapabilityDescriptor(String capability, String scope, String title, String description, boolean available) {
        this.capability = capability;
        this.scope = scope;
        this.title = title == null ? "" : title;
        this.description = description == null ? "" : description;
        this.available = available;
    }
}
