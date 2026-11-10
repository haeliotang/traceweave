// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave.edge;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class PrivateParticipant {
    // privateCache must be app-private; the digest must come from trusted configuration.
    public static String runPinnedCircuit(String[] command, File circuit, File privateCache,
                                         String trustedDigest, String privateBits, byte[] publicInputs) throws Exception {
        return runPinnedCircuit(command, circuit, privateCache, trustedDigest, privateBits, publicInputs,
            64L * 1024 * 1024, null);
    }

    static String runPinnedCircuit(String[] command, File circuit, File privateCache,
                                   String trustedDigest, String privateBits, byte[] publicInputs,
                                   long snapshotLimit, Runnable checkResources) throws Exception {
        return runPinned(command, circuit, privateCache, trustedDigest, privateBits, publicInputs,
            snapshotLimit, checkResources, null, null);
    }

    static String runPinnedComputation(String[] command, File circuit, File privateCache,
                                       String trustedDigest, String privateBits, byte[] approvedBindings,
                                       long snapshotLimit, Runnable checkResources, Callable<Void> beforeLaunch) throws Exception {
        if (approvedBindings == null || approvedBindings.length != 192 || beforeLaunch == null)
            throw new IllegalArgumentException("approved bindings and reservation required");
        byte[] bindings = approvedBindings.clone();
        byte[] statement = new byte[224];
        System.arraycopy(bindings, 0, statement, 32, 192);
        return runPinned(command, circuit, privateCache, trustedDigest, privateBits, statement,
            snapshotLimit, checkResources, bindings, beforeLaunch);
    }

    private static String runPinned(String[] command, File circuit, File privateCache,
                                    String trustedDigest, String privateBits, byte[] publicInputs,
                                    long snapshotLimit, Runnable checkResources, byte[] approvedBindings,
                                    Callable<Void> beforeLaunch) throws Exception {
        if (command == null || command.length != 9 || trustedDigest == null
            || !trustedDigest.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("trusted circuit configuration required");
        String[] pinned = command.clone();
        if (publicInputs == null || publicInputs.length != 224)
            throw new IllegalArgumentException("public statement required");
        byte[] statement = publicInputs.clone();
        StringBuilder session = new StringBuilder();
        for (int value = 0; value < 32; value++)
            session.append(String.format("%02x", statement[value < 31 ? 32 + value : 64] & 255));
        if (!session.toString().equals(pinned[8]))
            throw new SecurityException("public session configuration rejected");
        for (int offset = 63; offset <= 95; offset++)
            if (offset != 64 && statement[offset] != 0)
                throw new SecurityException("public session configuration rejected");
        if (checkResources != null) checkResources.run();
        File snapshot = Files.createTempFile(privateCache.toPath(), approvedBindings == null ? "private-circuit-" : "private-circuit-approved-", ".txt").toFile();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream source = Files.newInputStream(circuit.toPath());
                 java.io.OutputStream target = Files.newOutputStream(snapshot.toPath())) {
                byte[] buffer = new byte[65536]; int count; long total = 0;
                while ((count = source.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    total += count;
                    if (total > snapshotLimit) throw new SecurityException("circuit snapshot exceeds bound");
                    digest.update(buffer, 0, count); target.write(buffer, 0, count);
                }
            }
            StringBuilder hash = new StringBuilder();
            for (byte value : digest.digest()) hash.append(String.format("%02x", value & 255));
            if (!hash.toString().equals(trustedDigest)) throw new SecurityException("untrusted circuit");
            pinned[3] = snapshot.getAbsolutePath();
            return run(pinned, privateBits, statement, 120000, checkResources, approvedBindings, beforeLaunch);
        } finally {
            Files.deleteIfExists(snapshot.toPath());
        }
    }

    // Command paths and TLS configuration must come from trusted app configuration.
    // Private model bits are sent only through stdin, never command arguments.
    public static String run(String[] command, String privateBits, byte[] publicInputs) throws Exception {
        return run(command, privateBits, publicInputs, 120000);
    }

    static String run(String[] command, String privateBits, byte[] publicInputs, long timeoutMillis) throws Exception {
        return run(command, privateBits, publicInputs, timeoutMillis, null);
    }

    static String run(String[] command, String privateBits, byte[] publicInputs, long timeoutMillis,
                      Runnable checkResources) throws Exception {
        return run(command, privateBits, publicInputs, timeoutMillis, checkResources, null, null);
    }

    private static String run(String[] command, String privateBits, byte[] publicInputs, long timeoutMillis,
                              Runnable checkResources, byte[] approvedBindings, Callable<Void> beforeLaunch) throws Exception {
        if (privateBits == null || !privateBits.matches("[01]{1,11600}"))
            throw new IllegalArgumentException("bounded binary private input required");
        if (publicInputs == null || publicInputs.length != 224)
            throw new IllegalArgumentException("public statement required");
        byte[] statement = publicInputs.clone();
        if (checkResources != null) checkResources.run();
        if (beforeLaunch != null) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            beforeLaunch.call();
        }
        Process process = new ProcessBuilder(command).start();
        ExecutorService workers = Executors.newFixedThreadPool(3);
        boolean interrupted = false;
        try {
            Future<String> output = workers.submit(() -> readBounded(process.getInputStream(), process));
            Future<String> errors = workers.submit(() -> readBounded(process.getErrorStream(), process));
            Future<?> input = workers.submit(() -> {
                try (java.io.OutputStream stream = process.getOutputStream()) {
                    stream.write((privateBits + "\n").getBytes("US-ASCII"));
                }
                return null;
            });
            if (checkResources == null) {
                if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS))
                    throw new IllegalStateException("private computation timed out");
            } else {
                long started = System.nanoTime();
                long remaining = timeoutMillis;
                while (!process.waitFor(Math.min(250, remaining), TimeUnit.MILLISECONDS)) {
                    checkResources.run();
                    remaining = timeoutMillis - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                    if (remaining <= 0) throw new IllegalStateException("private computation timed out");
                }
            }
            input.get(1, TimeUnit.SECONDS);
            String stdout = output.get(1, TimeUnit.SECONDS);
            errors.get(1, TimeUnit.SECONDS);
            if (process.exitValue() != 0) throw new SecurityException("private computation rejected");
            String bits = null;
            for (String line : stdout.split("\n")) {
                if (!line.startsWith("PUBLIC_OUTPUT=")) continue;
                if (bits != null) throw new SecurityException("duplicate private output");
                bits = line.substring(14);
            }
            if (approvedBindings == null) PrivateAlignmentOutput.verify(bits, statement);
            else PrivateAlignmentOutput.verifyBindings(bits, approvedBindings);
            return bits;
        } catch (InterruptedException error) {
            interrupted = true;
            throw error;
        } finally {
            process.destroyForcibly();
            try {
                process.waitFor(1, TimeUnit.SECONDS);
            } catch (InterruptedException error) {
                interrupted = true;
                throw error;
            } finally {
                workers.shutdownNow();
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }

    private static String readBounded(InputStream stream, Process process) throws Exception {
        try (InputStream source = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int count;
            while ((count = source.read(buffer)) != -1) {
                if (output.size() + count > 16384) {
                    process.destroyForcibly();
                    throw new SecurityException("private process output exceeds bound");
                }
                output.write(buffer, 0, count);
            }
            return output.toString("US-ASCII");
        }
    }
}
