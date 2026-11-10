// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave.edge;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.zip.CRC32;

/** Owned temporary training coordinates: bounded heap, exact doubles, checked records. */
final class TrainingVectors implements AutoCloseable {
    static final class Sample {
        final double[] position, velocity;
        Sample(double[] position, double[] velocity) { this.position = position; this.velocity = velocity; }
    }
    final File path;
    private final RandomAccessFile file;
    private final FileLock lease;
    private final byte[] header;
    private final int dimension, count, recordBytes;
    private int written;
    private boolean sealed;
    TrainingVectors(File directory, int dimension, int count, String sourceDigest) throws IOException {
        if (dimension < 1 || dimension > 128 || count < 1 || count > 120960 || !sourceDigest.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("bounded source-bound training vectors required");
        this.dimension = dimension; this.count = count; recordBytes = 12 + 16 * dimension;
        header = ByteBuffer.allocate(76).putInt(0x54564632).putInt(dimension).putInt(count)
            .put(sourceDigest.getBytes(StandardCharsets.US_ASCII)).array();
        cleanupAbandoned(directory);
        path = File.createTempFile("weekly-vectors-", ".bin", directory);
        RandomAccessFile opened = null;
        try { opened = new RandomAccessFile(path, "rw"); lease = opened.getChannel().lock(); opened.write(header); file = opened; }
        catch (IOException error) { if (opened != null) opened.close(); path.delete(); throw error; }
    }
    void append(long instant, double[] position, double[] velocity) throws IOException, InterruptedException {
        interrupted();
        if (sealed || written >= count) throw new IllegalStateException("training spool is sealed or complete");
        if (position.length != dimension || velocity.length != dimension) throw new IllegalArgumentException("matching training dimensions required");
        byte[] encoded = new byte[recordBytes]; ByteBuffer buffer = ByteBuffer.wrap(encoded).putLong(instant);
        for (double[] values : new double[][]{position, velocity}) for (double value : values) {
            if (!Double.isFinite(value)) throw new IllegalArgumentException("finite training coordinates required");
            buffer.putDouble(value);
        }
        CRC32 checksum = new CRC32(); checksum.update(encoded, 0, recordBytes - 4); buffer.putInt((int) checksum.getValue());
        file.seek(header.length + (long) written * recordBytes); file.write(encoded); written++;
    }
    void seal() throws IOException {
        if (written != count) throw new IllegalStateException("complete training spool required");
        if (file.length() != header.length + (long) count * recordBytes) throw new IOException("training spool size mismatch");
        sealed = true;
    }
    Sample read(int index, long expectedInstant) throws IOException, InterruptedException {
        interrupted();
        if (!sealed || index < 0 || index >= count) throw new IllegalStateException("sealed bounded training index required");
        if (file.length() != header.length + (long) count * recordBytes) throw new IOException("training spool size changed");
        byte[] binding = new byte[header.length]; file.seek(0); file.readFully(binding);
        if (!Arrays.equals(header, binding)) throw new IOException("training spool source binding changed");
        byte[] encoded = new byte[recordBytes]; file.seek(header.length + (long) index * recordBytes); file.readFully(encoded);
        CRC32 checksum = new CRC32(); checksum.update(encoded, 0, recordBytes - 4);
        ByteBuffer buffer = ByteBuffer.wrap(encoded);
        if (buffer.getLong() != expectedInstant || buffer.getInt(recordBytes - 4) != (int) checksum.getValue())
            throw new IOException("training spool record binding or checksum mismatch");
        double[] position = new double[dimension], velocity = new double[dimension];
        for (double[] values : new double[][]{position, velocity}) for (int element = 0; element < dimension; element++) {
            values[element] = buffer.getDouble();
            if (!Double.isFinite(values[element])) throw new IOException("nonfinite training spool coordinates");
        }
        return new Sample(position, velocity);
    }
    long bytes() { return header.length + (long) count * recordBytes; }
    private static void cleanupAbandoned(File directory) throws IOException {
        File[] candidates = directory.listFiles((parent, name) -> name.startsWith("weekly-vectors-") && name.endsWith(".bin"));
        if (candidates == null) throw new IOException("training cache directory unavailable");
        for (File candidate : candidates) {
            try (FileChannel channel = FileChannel.open(candidate.toPath(), StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                FileLock lease;
                try { lease = channel.tryLock(); }
                catch (OverlappingFileLockException active) { continue; }
                if (lease == null) continue;
                try {
                    if (channel.size() < 76) continue;
                    ByteBuffer binding = ByteBuffer.allocate(76);
                    while (binding.hasRemaining() && channel.read(binding) > 0) { }
                    if (binding.hasRemaining()) continue;
                    binding.flip();
                    int magic = binding.getInt(), dimension = binding.getInt(), count = binding.getInt();
                    byte[] source = new byte[64]; binding.get(source);
                    if (magic != 0x54564632 || dimension < 1 || dimension > 128 || count < 1 || count > 120960
                            || channel.size() > 76 + (long) count * (12 + 16 * dimension)
                            || !new String(source, StandardCharsets.US_ASCII).matches("[0-9a-f]{64}")) continue;
                    if (!candidate.delete()) throw new IOException("abandoned training spool cleanup failed");
                } finally { lease.release(); }
            } catch (java.nio.file.NoSuchFileException removed) {
                // A concurrent owner completed cleanup before this open.
            } catch (java.nio.file.FileSystemException inaccessible) {
                if (java.nio.file.Files.isSymbolicLink(candidate.toPath())) continue;
                throw inaccessible;
            }
        }
    }
    private static void interrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("training spool cancelled");
    }
    @Override public void close() throws IOException {
        try { if (lease.isValid()) lease.release(); }
        finally {
            try { file.close(); } finally { if (path.exists() && !path.delete()) throw new IOException("training spool cleanup failed"); }
        }
    }
}
