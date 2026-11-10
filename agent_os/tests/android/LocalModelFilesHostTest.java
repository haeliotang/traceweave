// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

public final class LocalModelFilesHostTest {
    private static final byte[] VALID = "synthetic model".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ORIGINAL = "existing model".getBytes(StandardCharsets.UTF_8);

    private static String hash(byte[] value) throws Exception {
        StringBuilder hex = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(value)) hex.append(String.format("%02x", b & 255));
        return hex.toString();
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }

    private static void rejected(File destination, InputStream source, long size, String digest, AtomicBoolean cancelled) throws Exception {
        try {
            LocalModelFiles.installChecked(source, destination, size, digest, cancelled);
            throw new AssertionError("invalid import succeeded");
        } catch (IOException expected) {
            check(Arrays.equals(ORIGINAL, Files.readAllBytes(destination.toPath())), "existing model changed");
            check(destination.getParentFile().list().length == 1, "temporary model was left behind");
        }
    }

    public static void main(String[] args) throws Exception {
        File directory = args.length == 0 ? Files.createTempDirectory("synthetic-model-import-").toFile()
            : Files.createTempDirectory(new File(args[0]).toPath(), "synthetic-model-import-").toFile();
        File target = new File(directory, "model.gguf");
        try {
            Files.write(target.toPath(), ORIGINAL);
            String digest = hash(VALID);
            rejected(target, new ByteArrayInputStream(VALID), VALID.length, hash(ORIGINAL), new AtomicBoolean());
            rejected(target, new ByteArrayInputStream(VALID), VALID.length + 1, digest, new AtomicBoolean());
            rejected(target, new ByteArrayInputStream(VALID), VALID.length - 1, digest, new AtomicBoolean());
            rejected(target, new ByteArrayInputStream(VALID), VALID.length, digest, new AtomicBoolean(true));
            AtomicBoolean midImport = new AtomicBoolean();
            rejected(target, new ByteArrayInputStream(VALID) {
                @Override public synchronized int read(byte[] buffer, int offset, int length) {
                    int count = super.read(buffer, offset, length);
                    LocalModelFiles.cancelImport(midImport);
                    return count;
                }
            }, VALID.length, digest, midImport);
            rejected(target, new InputStream() {
                @Override public int read() throws IOException { throw new IOException("synthetic source failure"); }
            }, VALID.length, digest, new AtomicBoolean());
            Files.write(new File(directory, "model-import-interrupted.tmp").toPath(), VALID);
            LocalModelFiles.installChecked(new ByteArrayInputStream(VALID), target, VALID.length, digest, new AtomicBoolean());
            check(Arrays.equals(VALID, Files.readAllBytes(target.toPath())), "verified model not installed");
            check(LocalModelFiles.verifyChecked(target, VALID.length, digest, new AtomicBoolean()), "installed model did not verify");
            byte[] modified = VALID.clone(); modified[0] ^= 1;
            Files.write(target.toPath(), modified);
            check(!LocalModelFiles.verifyChecked(target, VALID.length, digest, new AtomicBoolean()), "modified model verified");
            check(directory.list().length == 1, "temporary model was left behind after success");
        } finally {
            for (File file : directory.listFiles()) Files.delete(file.toPath());
            Files.delete(directory.toPath());
        }
    }
}
