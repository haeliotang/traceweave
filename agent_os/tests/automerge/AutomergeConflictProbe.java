package com.inceptless.traceweave;

import java.util.HashSet;
import java.util.Set;
import org.automerge.AmValue;
import org.automerge.Conflicts;
import org.automerge.Document;
import org.automerge.ObjectId;
import org.automerge.Transaction;

/** Synthetic-only evaluation; Automerge is not a production dependency. */
public final class AutomergeConflictProbe {
    private static void put(Document doc, String key, String value) {
        try (Transaction transaction = doc.startTransaction()) {
            transaction.set(ObjectId.ROOT, key, value);
            transaction.commit();
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    public static boolean evaluate() {
        Document base = new Document();
        Document left = null, right = null, reopened = null, deletion = null, edit = null, tombstone = null, offline = null;
        try {
            put(base, "memory", "synthetic original");
            org.automerge.ChangeHash[] originalHeads = base.getHeads();
            left = base.fork(); right = base.fork();
            put(left, "memory", "synthetic left"); put(right, "memory", "synthetic right");
            left.merge(right);
            Conflicts conflicts = left.getAll(ObjectId.ROOT, "memory").orElseThrow(IllegalStateException::new);
            Set<String> values = new HashSet<>();
            for (AmValue value : conflicts.values()) values.add(((AmValue.Str) value).getValue());
            check(values.size() == 2 && values.contains("synthetic left") && values.contains("synthetic right"),
                "concurrent edits not retained");
            reopened = Document.load(left.save());
            check(reopened.getAll(ObjectId.ROOT, "memory").orElseThrow(IllegalStateException::new).values().size() == 2,
                "conflicts did not survive save and load");
            put(reopened, "memory", "synthetic left");
            right.merge(reopened);
            check(((AmValue.Str) right.get(ObjectId.ROOT, "memory").orElseThrow(IllegalStateException::new)).getValue().equals("synthetic left"),
                "resolved value did not converge");
            check(right.getAll(ObjectId.ROOT, "memory").orElseThrow(IllegalStateException::new).values().size() == 1,
                "resolution did not clear reviewed conflicts");
            right.merge(base); right.merge(left);
            check(((AmValue.Str) right.get(ObjectId.ROOT, "memory").orElseThrow(IllegalStateException::new)).getValue().equals("synthetic left"),
                "old base resurrected after merge");
            deletion = base.fork(); edit = base.fork();
            try (Transaction transaction = deletion.startTransaction()) { transaction.delete(ObjectId.ROOT, "memory"); transaction.commit(); }
            put(edit, "memory", "synthetic concurrent edit");
            deletion.merge(edit);
            check(((AmValue.Str) deletion.get(ObjectId.ROOT, "memory", originalHeads)
                .orElseThrow(IllegalStateException::new)).getValue().equals("synthetic original"),
                "historical content could not be inspected at the old head");
            boolean directDeleteRetainsEdit = deletion.get(ObjectId.ROOT, "memory").isPresent();
            tombstone = base.fork(); offline = base.fork();
            try (Transaction transaction = tombstone.startTransaction()) {
                transaction.set(ObjectId.ROOT, "deleted", true); transaction.commit();
            }
            put(offline, "memory", "synthetic offline edit");
            tombstone.merge(offline); offline.merge(tombstone); offline.merge(base);
            check(((AmValue.Bool) tombstone.get(ObjectId.ROOT, "deleted").orElseThrow(IllegalStateException::new)).getValue()
                    && ((AmValue.Bool) offline.get(ObjectId.ROOT, "deleted").orElseThrow(IllegalStateException::new)).getValue(),
                "synthetic tombstone did not survive honest offline edit and old replay");
            return directDeleteRetainsEdit;
        } finally {
            if (offline != null) offline.free(); if (tombstone != null) tombstone.free();
            if (edit != null) edit.free(); if (deletion != null) deletion.free();
            if (reopened != null) reopened.free(); if (right != null) right.free(); if (left != null) left.free(); base.free();
        }
    }

    public static void main(String[] args) {
        boolean concurrentEditVisibleAfterDelete = evaluate();
        System.out.println("PASS synthetic conflicts, explicit resolution, replay; concurrent_edit_visible_after_delete=" + concurrentEditVisibleAfterDelete);
    }
}
