package com.inceptless.traceweave.edge;

import java.io.File;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.StandardOpenOption;

public final class AlignmentQueryLease implements AutoCloseable {
    private static final java.util.Set<String> activeFiles = new java.util.HashSet<>();
    private final FileChannel channel;
    private final FileLock lock;
    private final String identity;
    private boolean closed;

    private AlignmentQueryLease(FileChannel channel, FileLock lock, String identity) {
        this.channel = channel; this.lock = lock; this.identity = identity;
    }

    public static synchronized AlignmentQueryLease acquire(File privateFiles) throws Exception {
        return acquireFile(new File(privateFiles, "alignment_query.lock"), "alignment query already active");
    }

    static synchronized AlignmentQueryLease acquireColorTemperature(File privateFiles) throws Exception {
        return acquireFile(new File(privateFiles, "color_temperature.lock"), "color adjustment already active");
    }
    static synchronized AlignmentQueryLease acquireVolume(File privateFiles) throws Exception {
        return acquireFile(new File(privateFiles, "volume_adjustment.lock"), "volume adjustment already active");
    }
    static synchronized AlignmentQueryLease acquireCandidateStore(File privateFiles) throws Exception {
        return acquireFile(new File(privateFiles, "candidate_store.lock"), "candidate store already active");
    }
    static synchronized AlignmentQueryLease acquireModelRegistry(File privateFiles) throws Exception {
        return acquireFile(new File(privateFiles, "model_registry.lock"), "model registry already active");
    }
    static synchronized AlignmentQueryLease acquireWeeklyMaintenance(File privateFiles) throws Exception {
        return acquireFile(new File(privateFiles, "weekly_maintenance.lock"), "weekly maintenance already active");
    }

    private static AlignmentQueryLease acquireFile(File requested, String busyMessage) throws Exception {
        File file = requested.getCanonicalFile();
        String identity = file.getPath();
        if (activeFiles.contains(identity)) throw new SecurityException(busyMessage);
        FileChannel channel = FileChannel.open(file.toPath(), StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        try {
            FileLock lock;
            try { lock = channel.tryLock(); }
            catch (OverlappingFileLockException error) { throw new SecurityException(busyMessage); }
            if (lock == null) throw new SecurityException(busyMessage);
            activeFiles.add(identity);
            return new AlignmentQueryLease(channel, lock, identity);
        } catch (Exception error) {
            channel.close();
            throw error;
        }
    }

    void requireQueryLease(File privateFiles) throws Exception {
        synchronized (AlignmentQueryLease.class) {
            if (closed || !lock.isValid()
                || !identity.equals(new File(privateFiles, "alignment_query.lock").getCanonicalPath()))
                throw new SecurityException("active matching query lease required");
        }
    }

    @Override public void close() throws Exception {
        synchronized (AlignmentQueryLease.class) {
            if (closed) return;
            closed = true;
            try { lock.release(); }
            finally {
                try { channel.close(); } finally { activeFiles.remove(identity); }
            }
        }
    }
}
