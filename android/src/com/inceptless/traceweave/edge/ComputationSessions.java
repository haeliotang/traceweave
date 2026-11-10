// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave.edge;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;

/** Internal one-use reservation; caller verifies owner approvals before calling. */
final class ComputationSessions {
    static void reserve(File privateFiles, byte[] session, AlignmentQueryLease lease) throws Exception {
        byte[] tag = availableTag(privateFiles, session, lease);
        File ledger = new File(privateFiles, "computation_sessions.bin");
        try (FileChannel output = FileChannel.open(ledger.toPath(), StandardOpenOption.CREATE,
                                                   StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ByteBuffer bytes = ByteBuffer.wrap(tag);
            while (bytes.hasRemaining()) output.write(bytes);
            output.force(true);
        }
        // Persist the directory entry as well before any private input is sent.
        try (FileChannel directory = FileChannel.open(privateFiles.toPath(), StandardOpenOption.READ)) {
            directory.force(true);
        }
        // Failed or interrupted computation does not release this reservation.
        // A torn append is rejected on the next call rather than silently repaired.
    }

    static void checkAvailable(File privateFiles, byte[] session, AlignmentQueryLease lease) throws Exception {
        availableTag(privateFiles, session, lease);
    }

    private static byte[] availableTag(File privateFiles, byte[] session, AlignmentQueryLease lease) throws Exception {
        if (session == null || session.length != 32 || lease == null)
            throw new SecurityException("session and active query lease required");
        lease.requireQueryLease(privateFiles);
        File ledger = new File(privateFiles, "computation_sessions.bin");
        byte[] prior = ledger.exists() ? OwnerAuthentication.readBounded(ledger, 131072) : new byte[0];
        if (prior.length % 32 != 0) throw new SecurityException("invalid computation session ledger");
        byte[] tag = MessageDigest.getInstance("SHA-256").digest(session.clone());
        for (int offset = 0; offset < prior.length; offset += 32)
            if (MessageDigest.isEqual(tag, java.util.Arrays.copyOfRange(prior, offset, offset + 32)))
                throw new SecurityException("replayed computation session");
        if (prior.length == 131072) throw new SecurityException("computation session capacity reached");
        return tag;
    }
}
