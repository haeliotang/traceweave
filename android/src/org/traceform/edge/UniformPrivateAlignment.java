package org.traceform.edge;

import android.app.ActivityManager;
import android.content.Context;
import java.io.File;
import java.security.PublicKey;

public final class UniformPrivateAlignment {
    private static final String CIRCUIT_DIGEST = "fbeaf35131dc63a556067e132f0fb1015fcce5abdd2b3fd2ec47b0474f5dfa03";

    private static final String APPROVED_CIRCUIT_DIGEST = "ba00463c74f7899a069305ab7edf8041234dea76ae6fb5a430182191c924dca2";

    // The pinned circuit is the accepted six-bit search with exact final loss.
    // Command paths, TLS peers and the complete public statement remain trusted app inputs.
    public static String run(Context context, String[] command, File circuit,
                             String privateBits, byte[] publicInputs) throws Exception {
        Runnable checkResources = admit(context, privateBits);
        return PrivateParticipant.runPinnedCircuit(command, circuit, context.getCacheDir(), CIRCUIT_DIGEST,
            privateBits, publicInputs, 128L * 1024 * 1024, checkResources);
    }

    // Owners approve circuit/session/model bindings, without knowing the result score.
    // A reservation persists even if launching or computation subsequently fails.
    public static String computeApproved(Context context, String[] command, File circuit, File request,
                                         PublicKey[] trustedOwners, String privateBits) throws Exception {
        try (AlignmentQueryLease lease = AlignmentQueryLease.acquire(context.getFilesDir())) {
            byte[] bindings = OwnerAuthentication.verifyComputation(request, APPROVED_CIRCUIT_DIGEST, trustedOwners);
            byte[] session = java.util.Arrays.copyOfRange(bindings, 0, 32);
            session[31] = bindings[32];
            ComputationSessions.checkAvailable(context.getFilesDir(), session, lease);
            Runnable checkResources = admit(context, privateBits);
            return PrivateParticipant.runPinnedComputation(command, circuit, context.getCacheDir(), APPROVED_CIRCUIT_DIGEST,
                approvedInputs(privateBits, bindings), bindings, 128L * 1024 * 1024, checkResources, () -> {
                    ComputationSessions.reserve(context.getFilesDir(), session, lease);
                    return null;
                });
        }
    }

    // Authenticated canonical scalar encodings contain three 32-byte bindings.
    // Append the same approval vector that the pinned circuit checks before reveal.
    static String approvedInputs(String privateBits, byte[] bindings) {
        StringBuilder result = new StringBuilder(privateBits);
        for (int block = 0; block < 3; ++block) for (int index = 0; index < 32; ++index) {
            int value = bindings[block * 64 + (index == 31 ? 32 : index)] & 255;
            for (int bit = 0; bit < 8; ++bit) result.append((value >>> bit) & 1);
        }
        return result.toString();
    }

    private static Runnable admit(Context context, String privateBits) throws Exception {
        if (privateBits == null || !privateBits.matches("[01]{6224}"))
            throw new IllegalArgumentException("uniform sixteen-node private input required");
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        manager.getMemoryInfo(memory);
        // Accepted fixed-circuit peak: 628152 KiB; +20% rounds up to 737 MiB.
        // Add the 192 MiB system reserve. This is admission, not a peak guarantee.
        if (memory.lowMemory || memory.availMem < (737L + 192L) * 1024 * 1024)
            throw new IllegalStateException("private alignment memory admission rejected");
        File cache = context.getCacheDir();
        if (cache.getUsableSpace() < 128L * 1024 * 1024)
            throw new IllegalStateException("private alignment snapshot space rejected");
        Runnable checkResources = () -> {
            ActivityManager.MemoryInfo current = new ActivityManager.MemoryInfo();
            manager.getMemoryInfo(current);
            if (current.lowMemory || current.availMem < 192L * 1024 * 1024)
                throw new IllegalStateException("private alignment memory reserve exhausted");
        };
        return checkResources;
    }
}
