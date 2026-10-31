package com.inceptless.traceweave;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** Parsing and validation for the pairing/sync UI, kept out of the activity. */
final class SyncActions {
    static final class Session {
        final String sas;
        final String fingerprint;
        final String peerId;

        Session(String sas, String fingerprint, String peerId) {
            this.sas = sas;
            this.fingerprint = fingerprint;
            this.peerId = peerId;
        }
    }

    private SyncActions() {}

    /** Turns "host:port" into a multiaddr, or null when invalid. */
    static String address(String hostPort) {
        return AddressParser.address(hostPort);
    }

    static List<String> labels(String json) {
        List<String> result = new ArrayList<>();
        for (JSONObject item : items(json)) {
            String peer = item.optString("peer", "");
            String address = item.optString("addr", "");
            result.add((peer.length() > 12 ? peer.substring(0, 12) : peer) + " · " + address);
        }
        return result;
    }

    /** Address advertised by a specific peer during discovery, or an empty string. */
    static String addressFor(String json, String peerId) {
        if (peerId == null || peerId.isEmpty()) return "";
        for (JSONObject item : items(json)) {
            if (peerId.equals(item.optString("peer", ""))) return item.optString("addr", "");
        }
        return "";
    }

    static List<String> addresses(String json) {
        List<String> result = new ArrayList<>();
        for (JSONObject item : items(json)) result.add(item.optString("addr", ""));
        return result;
    }

    /** Returns null when the payload carries an error or is malformed. */
    static Session session(String json) {
        try {
            if (json == null || json.isEmpty()) return null;
            JSONObject object = new JSONObject(json);
            if (object.has("error")) return null;
            String sas = object.optString("sas", "");
            String fingerprint = object.optString("peerFingerprint", "");
            String peerId = object.optString("peer", "");
            if (sas.length() != 6 || fingerprint.length() != 64 || peerId.isEmpty()) return null;
            return new Session(sas, fingerprint, peerId);
        } catch (Exception error) {
            return null;
        }
    }

    static String error(String json) {
        try {
            if (json == null || json.isEmpty()) return "";
            return new JSONObject(json).optString("error", "");
        } catch (Exception error) {
            return "";
        }
    }

    private static List<JSONObject> items(String json) {
        List<JSONObject> result = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(json == null || json.isEmpty() ? "[]" : json);
            for (int index = 0; index < array.length(); index++) result.add(array.getJSONObject(index));
        } catch (Exception ignored) {
        }
        return result;
    }
}
