package com.inceptless.traceweave;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** Installs one explicitly selected, verified model into private storage. */
final class LocalModelFiles {
    static final long QWEN_15_SIZE = 1066227232L;
    static final String QWEN_15_SHA = "dcd819ff094852c38faba6873d8ff0c9d51eadb2844539e52042ae5d647bbfdb";

    private LocalModelFiles() {}

    static void installQwen15(InputStream source, File destination, AtomicBoolean cancelled) throws Exception {
        installChecked(source, destination, QWEN_15_SIZE, QWEN_15_SHA, cancelled);
    }

    /** Installs whichever catalog model the stream contains, rejecting anything else. */
    static LocalModelCatalog.Entry installSupported(InputStream source, File destination, AtomicBoolean cancelled) throws Exception {
        if (source == null) throw new IllegalArgumentException("Invalid model import");
        File parent = destination.getParentFile();
        if (parent == null || !parent.isDirectory()) throw new IOException("Model directory is unavailable");
        cleanInterruptedImports(parent);
        File temporary = File.createTempFile("model-import-", ".tmp", parent);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long size = 0;
            long limit = 0;
            for (LocalModelCatalog.Entry entry : LocalModelCatalog.all()) limit = Math.max(limit, entry.size);
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = source.read(buffer)) != -1) {
                    if (cancelled.get()) throw new IOException("Model import cancelled");
                    if (count == 0) throw new IOException("Model source made no progress");
                    size += count;
                    if (size > limit) throw new IOException("Model file is larger than any supported model");
                    output.write(buffer, 0, count);
                    digest.update(buffer, 0, count);
                }
                output.getFD().sync();
            }
            LocalModelCatalog.Entry entry = LocalModelCatalog.bySize(size);
            if (entry == null || !entry.sha256.equals(hex(digest.digest())))
                throw new IOException("Model file failed integrity verification");
            synchronized (cancelled) {
                if (cancelled.get()) throw new IOException("Model import cancelled");
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }
            return entry;
        } finally {
            Files.deleteIfExists(temporary.toPath());
        }
    }

    /** Returns the catalog entry for a verified file, or null. */
    static LocalModelCatalog.Entry verifySupported(File model, AtomicBoolean cancelled) throws Exception {
        if (!model.isFile()) return null;
        LocalModelCatalog.Entry entry = LocalModelCatalog.bySize(model.length());
        if (entry == null) return null;
        return verifyChecked(model, entry.size, entry.sha256, cancelled) ? entry : null;
    }

    static boolean verifyQwen15(File model, AtomicBoolean cancelled) throws Exception {
        return verifyChecked(model, QWEN_15_SIZE, QWEN_15_SHA, cancelled);
    }

    static void cancelImport(AtomicBoolean cancelled) {
        synchronized (cancelled) { cancelled.set(true); }
    }

    static boolean verifyChecked(File model, long expectedSize, String expectedSha, AtomicBoolean cancelled) throws Exception {
        if (!model.isFile() || model.length() != expectedSize) return false;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(model)) {
            byte[] buffer = new byte[65536]; int count;
            while ((count = input.read(buffer)) != -1) {
                if (cancelled.get()) throw new IOException("Model verification cancelled");
                digest.update(buffer, 0, count);
            }
        }
        return expectedSha.equals(hex(digest.digest()));
    }

    private static String hex(byte[] digest) {
        StringBuilder result = new StringBuilder();
        for (byte value : digest) result.append(String.format(Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }

    static synchronized void cleanInterruptedImports(File directory) throws IOException {
        File[] entries = directory.listFiles();
        if (entries == null) throw new IOException("Model directory is unavailable");
        for (File entry : entries) {
            if (entry.getName().startsWith("model-import-") && entry.getName().endsWith(".tmp")
                    && Files.isRegularFile(entry.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
                Files.delete(entry.toPath());
        }
    }

    static synchronized void deleteModel(File model) throws IOException {
        Files.deleteIfExists(model.toPath());
        cleanInterruptedImports(model.getParentFile());
    }

    static synchronized void installChecked(InputStream source, File destination, long expectedSize,
                                            String expectedSha, AtomicBoolean cancelled) throws Exception {
        if (source == null || expectedSize <= 0 || !expectedSha.matches("[0-9a-f]{64}") || cancelled == null)
            throw new IllegalArgumentException("Invalid model import");
        File parent = destination.getParentFile();
        if (parent == null || !parent.isDirectory()) throw new IOException("Model directory is unavailable");
        cleanInterruptedImports(parent);
        File temporary = File.createTempFile("model-import-", ".tmp", parent);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long size = 0;
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = source.read(buffer)) != -1) {
                    if (cancelled.get()) throw new IOException("Model import cancelled");
                    if (count == 0) throw new IOException("Model source made no progress");
                    if (count > expectedSize - size) throw new IOException("Model file is too large");
                    output.write(buffer, 0, count);
                    digest.update(buffer, 0, count);
                    size += count;
                }
                output.getFD().sync();
            }
            if (size != expectedSize || !expectedSha.equals(hex(digest.digest())))
                throw new IOException("Model file failed integrity verification");
            synchronized (cancelled) {
                if (cancelled.get()) throw new IOException("Model import cancelled");
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary.toPath());
        }
    }
}
