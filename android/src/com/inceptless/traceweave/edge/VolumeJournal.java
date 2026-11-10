// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave.edge;

import android.util.AtomicFile;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

final class VolumeJournal {
    static final class Pending {
        final int original, target;
        Pending(int original, int target) { this.original = original; this.target = target; }
    }
    private final AtomicFile file;
    VolumeJournal(File path) { file = new AtomicFile(path); }
    boolean exists() { return file.getBaseFile().exists() || new File(file.getBaseFile().getPath() + ".bak").exists(); }
    void save(int original, int target) throws IOException {
        if (exists()) throw new IOException("pending volume recovery required");
        FileOutputStream output = file.startWrite();
        try {
            DataOutputStream data = new DataOutputStream(output);
            data.writeInt(0x54465631); data.writeInt(original); data.writeInt(target); data.flush();
            output.getFD().sync();
            file.finishWrite(output); output = null;
            Pending checked = read();
            if (checked == null || checked.original != original || checked.target != target)
                throw new IOException("volume recovery journal readback mismatch");
        } finally {
            if (output != null) file.failWrite(output);
        }
    }
    Pending read() throws IOException {
        if (!exists()) return null;
        try (DataInputStream input = new DataInputStream(file.openRead())) {
            if (input.readInt() != 0x54465631) throw new IOException("invalid volume recovery journal");
            Pending pending = new Pending(input.readInt(), input.readInt());
            if (input.read() != -1) throw new IOException("oversized volume recovery journal");
            return pending;
        }
    }
    void clear() throws IOException {
        file.delete();
        if (exists()) throw new IOException("volume recovery journal deletion failed");
    }
}
