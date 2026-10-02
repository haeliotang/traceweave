from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class TestNativeCandidateRetention(unittest.TestCase):
    def test_fifth_candidate_reclaims_verified_oldest_and_keeps_latest(self):
        if not shutil.which('javac') or not shutil.which('java'):
            self.skipTest('Java compiler/runtime unavailable')
        root = Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            # Narrow host stand-ins: exercise production store file/hash/retention code,
            # not Android AtomicFile recovery or JSON/model validation.
            (base / 'AtomicFile.java').write_text('''package android.util;
import java.io.*;
public final class AtomicFile {
    private final File file;
    public AtomicFile(File file) { this.file = file; }
    public File getBaseFile() { return file; }
    public FileInputStream openRead() throws Exception {
        File backup = new File(file.getPath() + ".bak");
        if (backup.exists()) java.nio.file.Files.move(backup.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return new FileInputStream(file);
    }
    public FileOutputStream startWrite() throws Exception { return new FileOutputStream(file); }
    public void finishWrite(FileOutputStream output) throws Exception { output.close(); }
    public void failWrite(FileOutputStream output) throws Exception { output.close(); }
}''')
            (base / 'JSONObject.java').write_text('''package org.json;
public final class JSONObject {
    private final String value;
    public JSONObject(String value) { this.value = value; }
    public String toString() { return value; }
    public String getString(String key) {
        if (key.equals("protocol") && value.startsWith("traceform.native-model-candidate.v1:"))
            return "traceform.native-model-candidate.v1";
        throw new IllegalArgumentException("fixture protocol");
    }
}''')
            (base / 'ModelEngine.java').write_text('''package org.traceform.edge;
public final class ModelEngine {
    org.json.JSONObject candidateBundle(org.json.JSONObject report) { return report; }
}''')
            (base / 'RetentionTest.java').write_text('''package org.traceform.edge;
import java.io.*;
import java.nio.file.*;
import org.json.JSONObject;
public final class RetentionTest {
    public static void main(String[] args) throws Exception {
        File directory = new File(args[0], "candidates");
        ModelEngine model = new ModelEngine();
        String[] ids = new String[5];
        for (int i = 0; i < 5; i++) {
            ids[i] = NativeCandidateStore.save(directory, model, new JSONObject("traceform.native-model-candidate.v1:" + i));
            new File(directory, ids[i] + ".json").setLastModified(1000 + i);
            if (i == 3) {
                File original = new File(directory, ids[1] + ".json");
                if (!original.renameTo(new File(original.getPath() + ".bak"))) throw new AssertionError("backup fixture");
            }
        }
        if (new File(directory, ids[0] + ".json").exists()) throw new AssertionError("oldest retained");
        if (directory.listFiles((parent, name) -> name.endsWith(".json")).length != 4) throw new AssertionError("retention bound");
        NativeCandidateStore.read(directory, ids[4]);
        NativeCandidateStore.read(directory, ids[1]);
        if (!NativeCandidateStore.save(directory, model, new JSONObject("traceform.native-model-candidate.v1:4")).equals(ids[4])) throw new AssertionError("idempotence");
        File damaged = new File(directory, ids[1] + ".json");
        Files.write(damaged.toPath(), new byte[]{123});
        damaged.setLastModified(1);
        boolean rejected = false;
        try { NativeCandidateStore.save(directory, model, new JSONObject("traceform.native-model-candidate.v1:5")); }
        catch (IllegalArgumentException expected) { rejected = true; }
        if (!rejected || !damaged.exists()) throw new AssertionError("corrupt candidate silently reclaimed");
    }
}''')
            sources = [root / 'android/src/org/traceform/edge' / name for name in ['NativeCandidateStore.java', 'AlignmentQueryLease.java', 'HexDigest.java']]
            subprocess.run(['javac', '-d', directory, *map(str, sources), *map(str, base.glob('*.java'))], check=True, capture_output=True)
            result = subprocess.run(['java', '-cp', directory, 'org.traceform.edge.RetentionTest', directory], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
