package com.inceptless.traceweave;

public final class CapabilityDescriptor {
    public final String capability;
    public final String scope;
    public final String title;
    public final String description;
    public final boolean available;

    public CapabilityDescriptor(String capability, String scope, String title, String description, boolean available) {
        this.capability = capability;
        this.scope = scope;
        this.title = title == null ? "" : title;
        this.description = description == null ? "" : description;
        this.available = available;
    }
}
