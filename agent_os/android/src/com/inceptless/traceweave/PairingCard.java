package com.inceptless.traceweave;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Out-of-band pairing card: a short text block a user carries from one device to another
 * (clipboard, message, file). It only carries public identity material, but moving it
 * yourself is what makes it out-of-band: nothing on the network can alter it.
 *
 * <pre>
 * TRACEWEAVE-PAIRING-CARD-1
 * fingerprint=&lt;64 lowercase hex&gt;
 * peerId=&lt;1..128 lowercase hex&gt;
 * address=&lt;optional multiaddr&gt;
 * checksum=&lt;first 8 hex of SHA-256("fingerprint|peerId")&gt;
 * </pre>
 */
public final class PairingCard {
    public static final String MAGIC = "TRACEWEAVE-PAIRING-CARD-1";
    private static final int MAX_TEXT_BYTES = 2048;
    private static final String ADDRESS_PATTERN = "^(/(ip4|ip6|dns4|dns6)/[A-Za-z0-9.:_-]{1,64}/(tcp|udp)/[0-9]{1,5})?$";

    private PairingCard() {}

    public static final class Card {
        public final String fingerprint;
        public final String peerId;
        public final String address;

        Card(String fingerprint, String peerId, String address) {
            this.fingerprint = fingerprint;
            this.peerId = peerId;
            this.address = address;
        }
    }

    public static String encode(String fingerprint, String peerId, String address) {
        requireFingerprint(fingerprint);
        requirePeerId(peerId);
        String target = address == null ? "" : address.trim();
        if (!target.matches(ADDRESS_PATTERN)) throw new IllegalArgumentException("invalid_address");
        return MAGIC + "\n"
            + "fingerprint=" + fingerprint + "\n"
            + "peerId=" + peerId + "\n"
            + "address=" + target + "\n"
            + "checksum=" + checksum(fingerprint, peerId) + "\n";
    }

    /** Parses a card; throws IllegalArgumentException with a short machine reason. */
    public static Card parse(String text) {
        if (text == null) throw new IllegalArgumentException("empty");
        String trimmed = text.trim();
        if (trimmed.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_BYTES) throw new IllegalArgumentException("too_large");
        String[] lines = trimmed.split("\n", -1);
        if (lines.length == 0 || !MAGIC.equals(lines[0].trim())) throw new IllegalArgumentException("bad_magic");
        String fingerprint = null;
        String peerId = null;
        String address = "";
        String checksum = null;
        for (int index = 1; index < lines.length; index++) {
            String line = lines[index].trim();
            if (line.isEmpty()) continue;
            int equals = line.indexOf('=');
            if (equals <= 0) throw new IllegalArgumentException("malformed_line");
            String key = line.substring(0, equals).trim();
            String value = line.substring(equals + 1).trim();
            switch (key) {
                case "fingerprint": fingerprint = value; break;
                case "peerId": peerId = value; break;
                case "address": address = value; break;
                case "checksum": checksum = value; break;
                default: throw new IllegalArgumentException("unknown_field");
            }
        }
        if (fingerprint == null || peerId == null || checksum == null) throw new IllegalArgumentException("missing_field");
        requireFingerprint(fingerprint);
        requirePeerId(peerId);
        if (!address.matches(ADDRESS_PATTERN)) throw new IllegalArgumentException("invalid_address");
        if (!checksum.equals(checksum(fingerprint, peerId))) throw new IllegalArgumentException("checksum_mismatch");
        return new Card(fingerprint, peerId, address);
    }

    /** First 8 hex characters of SHA-256("fingerprint|peerId"); a typo check, not a secret. */
    public static String checksum(String fingerprint, String peerId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest((fingerprint + "|" + peerId).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (int index = 0; index < 4; index++) result.append(String.format(Locale.ROOT, "%02x", hashed[index] & 255));
            return result.toString();
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private static void requireFingerprint(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("invalid_fingerprint");
    }

    private static void requirePeerId(String value) {
        if (value == null || !value.matches("[0-9a-f]{1,128}")) throw new IllegalArgumentException("invalid_peer_id");
    }
}
