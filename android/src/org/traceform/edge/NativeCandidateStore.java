package org.traceform.edge;

import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Bounded immutable accepted candidates; activation belongs to the registry. */
final class NativeCandidateStore {
    static String save(File directory, ModelEngine model, JSONObject calibration) throws Exception {
        byte[] bytes = model.candidateBundle(calibration).toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 256000) throw new IllegalArgumentException("candidate exceeds bounded bundle size");
        String digest = HexDigest.encode(MessageDigest.getInstance("SHA-256").digest(bytes));
        if (!directory.isDirectory() && !directory.mkdirs()) throw new java.io.IOException("candidate directory unavailable");
        try (AlignmentQueryLease lease = AlignmentQueryLease.acquireCandidateStore(directory)) {
            AtomicFile file = new AtomicFile(new File(directory, digest + ".json"));
            if (file.getBaseFile().exists() || new File(directory, digest + ".json.bak").exists()) {
                read(directory, digest);
                retain(directory, 4, digest);
                return digest;
            }
            retain(directory, 3, digest);
            FileOutputStream output = file.startWrite();
            try { output.write(bytes); file.finishWrite(output); }
            catch (Exception error) { file.failWrite(output); throw error; }
            read(directory, digest);
            return digest;
        }
    }
    private static void retain(File directory, int maximum, String protectedDigest) throws Exception {
        File[] entries = directory.listFiles((parent, name) -> name.matches("[0-9a-f]{64}\\.json(?:\\.bak)?"));
        if (entries == null) throw new java.io.IOException("candidate listing unavailable");
        java.util.Set<String> identities = new java.util.TreeSet<>();
        for (File entry : entries) identities.add(entry.getName().substring(0, 64));
        File[] candidates = new File[identities.size()]; int index = 0;
        for (String identity : identities) {
            read(directory, identity);
            candidates[index++] = new File(directory, identity + ".json");
        }
        java.util.Arrays.sort(candidates, java.util.Comparator.comparingLong(File::lastModified).thenComparing(File::getName));
        int remaining = candidates.length;
        for (File candidate : candidates) {
            if (remaining <= maximum) return;
            String digest = candidate.getName().substring(0, 64);
            if (digest.equals(protectedDigest)) continue;
            read(directory, digest);
            if (!candidate.delete()) throw new java.io.IOException("candidate retention deletion failed");
            remaining--;
        }
        if (remaining > maximum) throw new IllegalStateException("candidate retention cannot reclaim protected bundle");
    }
    static JSONObject read(File directory, String digest) throws Exception {
        if (!digest.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("candidate digest required");
        AtomicFile file = new AtomicFile(new File(directory, digest + ".json"));
        byte[] bytes;
        try (FileInputStream input = file.openRead()) {
            long size = input.getChannel().size();
            if (size < 1 || size > 256000) throw new IllegalArgumentException("bounded candidate bundle required");
            bytes = new byte[(int) size];
            int offset = 0;
            while (offset < bytes.length) {
                int count = input.read(bytes, offset, bytes.length - offset);
                if (count < 0) throw new java.io.IOException("candidate file truncated");
                offset += count;
            }
            if (input.read() != -1) throw new java.io.IOException("candidate file changed during read");
        }
        if (!digest.equals(HexDigest.encode(MessageDigest.getInstance("SHA-256").digest(bytes))))
            throw new IllegalArgumentException("candidate digest mismatch");
        JSONObject bundle = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        if (!bundle.getString("protocol").equals("traceform.native-model-candidate.v1")) throw new IllegalArgumentException("candidate protocol mismatch");
        return bundle;
    }
}
