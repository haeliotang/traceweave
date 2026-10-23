package org.traceform.agentos.sdk;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class AgentManifest {
    private final String id;
    private final String name;
    private final String version;
    private final String description;
    private final List<CapabilityDecl> capabilities;
    private final int maxConcurrentTasks;
    private final String dataRetentionPolicy;

    private AgentManifest(Builder builder) {
        this.id = builder.id;
        this.name = builder.name;
        this.version = builder.version;
        this.description = builder.description;
        this.capabilities = new ArrayList<>(builder.capabilities);
        this.maxConcurrentTasks = builder.maxConcurrentTasks;
        this.dataRetentionPolicy = builder.dataRetentionPolicy;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getVersion() {
        return version;
    }

    public JSONObject toJsonObject() throws JSONException {
        JSONObject root = new JSONObject();
        root.put("manifest_version", 1);

        JSONObject agent = new JSONObject();
        agent.put("id", id);
        agent.put("name", name);
        agent.put("version", version);
        if (description != null && !description.isEmpty()) {
            agent.put("description", description);
        }
        root.put("agent", agent);

        JSONArray capsArray = new JSONArray();
        for (CapabilityDecl decl : capabilities) {
            JSONObject c = new JSONObject();
            c.put("capability", decl.capability);
            c.put("scope", decl.scope);
            c.put("reason", decl.reason);
            c.put("required", decl.required);
            capsArray.put(c);
        }
        root.put("capabilities_requested", capsArray);
        root.put("max_concurrent_tasks", maxConcurrentTasks);
        root.put("data_retention_policy", dataRetentionPolicy);
        return root;
    }

    public String toJsonString() {
        try {
            return toJsonObject().toString(2);
        } catch (JSONException e) {
            return "{}";
        }
    }

    public static class CapabilityDecl {
        public final String capability;
        public final String scope;
        public final String reason;
        public final boolean required;

        public CapabilityDecl(String capability, String scope, String reason, boolean required) {
            this.capability = capability;
            this.scope = scope;
            this.reason = reason;
            this.required = required;
        }
    }

    public static class Builder {
        private final String id;
        private final String name;
        private String version = "1.0.0";
        private String description = "";
        private final List<CapabilityDecl> capabilities = new ArrayList<>();
        private int maxConcurrentTasks = 1;
        private String dataRetentionPolicy = "session_only";

        public Builder(String id, String name) {
            this.id = id;
            this.name = name;
        }

        public Builder version(String version) {
            this.version = version;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder requestCapability(String capability, String scope, String reason) {
            return requestCapability(capability, scope, reason, true);
        }

        public Builder requestCapability(String capability, String scope, String reason, boolean required) {
            this.capabilities.add(new CapabilityDecl(capability, scope, reason, required));
            return this;
        }

        public Builder maxConcurrentTasks(int maxTasks) {
            this.maxConcurrentTasks = Math.max(1, maxTasks);
            return this;
        }

        public Builder dataRetentionPolicy(String policy) {
            this.dataRetentionPolicy = policy;
            return this;
        }

        public AgentManifest build() {
            return new AgentManifest(this);
        }
    }
}
