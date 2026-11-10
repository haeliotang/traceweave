// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

import com.inceptless.traceweave.PairingCard;

/** Host check for the out-of-band pairing card, run by test_pairing_card.py. */
public final class PairingCardHostTest {
    private static int checks = 0;
    private static final String FP = "ab".repeat(32);
    private static final String PEER = "0123456789abcdef0123456789abcdef";

    private static void check(boolean condition, String label) {
        if (!condition) throw new IllegalStateException("FAIL " + label);
        checks++;
    }

    private static void expectReject(String reason, String text, String label) {
        try {
            PairingCard.parse(text);
            throw new IllegalStateException("FAIL accepted: " + label);
        } catch (IllegalArgumentException error) {
            check(reason.equals(error.getMessage()), label + " -> " + reason + " (got " + error.getMessage() + ")");
        }
    }

    public static void main(String[] args) {
        String card = PairingCard.encode(FP, PEER, "/ip4/192.168.1.20/tcp/45871");
        PairingCard.Card parsed = PairingCard.parse(card);
        check(FP.equals(parsed.fingerprint), "fingerprint round-trips");
        check(PEER.equals(parsed.peerId), "peer id round-trips");
        check("/ip4/192.168.1.20/tcp/45871".equals(parsed.address), "address round-trips");

        String withoutAddress = PairingCard.encode(FP, PEER, "");
        check("".equals(PairingCard.parse(withoutAddress).address), "address is optional");

        expectReject("bad_magic", card.replace("TRACEWEAVE", "TRACEWAVES"), "bad magic");
        expectReject("checksum_mismatch", card.replace(PairingCard.checksum(FP, PEER, "/ip4/192.168.1.20/tcp/45871"), "00000000"), "checksum");
        // An edited address must fail the checksum now that every field is covered.
        expectReject("checksum_mismatch", card.replace("192.168.1.20", "192.168.1.21"), "address tamper");
        expectReject("checksum_mismatch", PairingCard.encode(FP, PEER, "").replace("address=\n", "address=/ip4/10.0.0.1/tcp/1\n"), "address injection");
        expectReject("invalid_fingerprint", card.replace(FP, "AB".repeat(32)), "uppercase fingerprint");
        expectReject("invalid_peer_id", card.replace(PEER, "not-hex"), "invalid peer id");
        expectReject("invalid_address", card.replace("/ip4/192.168.1.20", "/ip4/evil host"), "invalid address");
        expectReject("malformed_line", card.replace("fingerprint=", "fingerprint"), "malformed line");
        expectReject("unknown_field", card.replace("checksum=", "extra=1\nchecksum="), "unknown field");
        expectReject("bad_magic", "hello", "arbitrary text");

        // A truncated card must not silently pair.
        expectReject("missing_field", PairingCard.MAGIC + "\nfingerprint=" + FP + "\n", "missing fields");
        System.out.println("PASS pairing_card checks=" + checks);
    }
}
