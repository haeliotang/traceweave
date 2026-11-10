// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

//! Plaintext synthetic fixture compiled for tests only; never used for user data.
use crate::causal_memory::{merge, resolve, Version};
use rusqlite::{params, Connection, OptionalExtension, TransactionBehavior};

fn open(path: &std::path::Path) -> Result<Connection, String> {
    let db = Connection::open(path).map_err(|e| e.to_string())?;
    db.busy_timeout(std::time::Duration::from_secs(2)).map_err(|e| e.to_string())?;
    db.execute_batch("CREATE TABLE IF NOT EXISTS fixture (id TEXT PRIMARY KEY, versions TEXT NOT NULL)").map_err(|e| e.to_string())?;
    Ok(db)
}
fn read(db: &Connection, id: &str) -> Result<Vec<Version>, String> {
    let json: Option<String> = db.query_row("SELECT versions FROM fixture WHERE id = ?1", [id], |r| r.get(0)).optional().map_err(|e| e.to_string())?;
    let versions: Vec<Version> = match json { Some(raw) => serde_json::from_str(&raw).map_err(|e| e.to_string())?, None => Vec::new() };
    if merge(&versions, &[]).map_err(str::to_string)? != versions { return Err("Noncanonical fixture state".into()); }
    Ok(versions)
}
fn apply(db: &mut Connection, id: &str, incoming: &[Version]) -> Result<Vec<Version>, String> {
    let tx = db.transaction_with_behavior(TransactionBehavior::Immediate).map_err(|e| e.to_string())?;
    let result = merge(&read(&tx, id)?, incoming).map_err(str::to_string)?;
    let json = serde_json::to_string(&result).map_err(|e| e.to_string())?;
    tx.execute("INSERT INTO fixture (id, versions) VALUES (?1, ?2) ON CONFLICT(id) DO UPDATE SET versions = excluded.versions", params![id, json]).map_err(|e| e.to_string())?;
    tx.commit().map_err(|e| e.to_string())?; Ok(result)
}
fn choose(db: &mut Connection, id: &str, reviewed: &[Version], index: usize, actor: &str) -> Result<Version, String> {
    let tx = db.transaction_with_behavior(TransactionBehavior::Immediate).map_err(|e| e.to_string())?;
    if read(&tx, id)? != reviewed { return Err("Conflict choices changed since review".into()); }
    let result = resolve(reviewed, index, actor).map_err(str::to_string)?;
    let json = serde_json::to_string(&vec![result.clone()]).map_err(|e| e.to_string())?;
    tx.execute("UPDATE fixture SET versions = ?1 WHERE id = ?2", params![json, id]).map_err(|e| e.to_string())?;
    tx.commit().map_err(|e| e.to_string())?; Ok(result)
}
#[cfg(test)]
mod tests {
    use super::*;
    use crate::causal_memory::Clock;
    use sha2::{Digest, Sha256};
    #[derive(Debug, PartialEq)]
    enum Receipt { Applied, Duplicate }
    fn receipt_schema(db: &Connection) -> Result<(), String> {
        db.execute_batch("CREATE TABLE IF NOT EXISTS receipts (peer TEXT NOT NULL, operation TEXT NOT NULL, digest BLOB NOT NULL, PRIMARY KEY(peer, operation));
            CREATE TABLE IF NOT EXISTS effects (value INTEGER NOT NULL);
            INSERT INTO effects(value) SELECT 0 WHERE NOT EXISTS (SELECT 1 FROM effects);").map_err(|e| e.to_string())
    }
    fn effect_count(db: &Connection) -> i64 {
        db.query_row("SELECT value FROM effects", [], |row| row.get(0)).unwrap()
    }
    fn apply_once(db: &mut Connection, peer: &str, operation: &str, body: &[u8], fail: bool) -> Result<Receipt, String> {
        apply_once_with_hook(db, peer, operation, body, fail, |_| {})
    }
    fn apply_once_with_hook(db: &mut Connection, peer: &str, operation: &str, body: &[u8], fail: bool, hook: impl Fn(&str)) -> Result<Receipt, String> {
        let digest = Sha256::digest(body).to_vec();
        let tx = db.transaction_with_behavior(TransactionBehavior::Immediate).map_err(|e| e.to_string())?;
        let old: Option<Vec<u8>> = tx.query_row("SELECT digest FROM receipts WHERE peer = ?1 AND operation = ?2", params![peer, operation], |row| row.get(0)).optional().map_err(|e| e.to_string())?;
        if let Some(old) = old {
            return if old == digest { Ok(Receipt::Duplicate) } else { Err("Operation ID reused with different content".into()) };
        }
        tx.execute("UPDATE effects SET value = value + 1", []).map_err(|e| e.to_string())?;
        tx.execute("INSERT INTO receipts(peer, operation, digest) VALUES (?1, ?2, ?3)", params![peer, operation, digest]).map_err(|e| e.to_string())?;
        if fail { return Err("Synthetic failure before commit".into()); }
        hook("before_commit");
        tx.commit().map_err(|e| e.to_string())?;
        hook("after_commit");
        Ok(Receipt::Applied)
    }
    struct Fixture(std::path::PathBuf);
    impl Fixture {
        fn new() -> Self {
            let mut random = [0u8; 16]; getrandom::getrandom(&mut random).unwrap();
            let name = random.iter().map(|b| format!("{b:02x}")).collect::<String>();
            let dir = std::env::temp_dir().join(format!("agent-causal-fixture-{name}"));
            std::fs::create_dir(&dir).unwrap(); Self(dir)
        }
        fn db(&self, name: &str) -> Connection { open(&self.0.join(name)).unwrap() }
    }
    impl Drop for Fixture { fn drop(&mut self) { std::fs::remove_dir_all(&self.0).unwrap(); } }
    fn v(clock: &[(&str, u64)], value: Option<&str>) -> Version {
        Version { clock: Clock(clock.iter().map(|(k, v)| (k.to_string(), *v)).collect()), value: value.map(str::to_string) }
    }
    #[test]
    fn persisted_conflicts_resolve_and_converge_between_isolated_replicas() {
        let dir = Fixture::new();
        let mut left = dir.db("left.sqlite"); let mut right = dir.db("right.sqlite");
        apply(&mut left, "fixture", &[v(&[("a", 2)], Some("synthetic left"))]).unwrap();
        apply(&mut right, "fixture", &[v(&[("a", 1), ("b", 1)], Some("synthetic right"))]).unwrap();
        let choices = apply(&mut left, "fixture", &read(&right, "fixture").unwrap()).unwrap();
        assert_eq!(choices.len(), 2); drop(left);
        let mut left = dir.db("left.sqlite"); assert_eq!(read(&left, "fixture").unwrap(), choices);
        let selection = choose(&mut left, "fixture", &choices, 0, "a").unwrap();
        assert_eq!(apply(&mut right, "fixture", &[selection.clone()]).unwrap(), vec![selection]);
        assert_eq!(read(&left, "fixture").unwrap(), read(&right, "fixture").unwrap());
    }
    #[test]
    fn stale_choice_is_rejected_atomically_after_another_connection_deletes() {
        let dir = Fixture::new(); let mut first = dir.db("replica.sqlite"); let mut second = dir.db("replica.sqlite");
        let original = v(&[("a", 1)], Some("synthetic"));
        let reviewed = apply(&mut first, "fixture", &[original.clone()]).unwrap();
        let deleted = v(&[("b", 1)], None); apply(&mut second, "fixture", &[deleted.clone()]).unwrap();
        assert!(choose(&mut first, "fixture", &reviewed, 0, "a").is_err());
        assert_eq!(read(&first, "fixture").unwrap(), vec![deleted.clone()]);
        assert_eq!(apply(&mut first, "fixture", &[original]).unwrap(), vec![deleted]);
    }
    #[test]
    fn invalid_incoming_data_rolls_back_without_changing_fixture() {
        let dir = Fixture::new(); let mut db = dir.db("replica.sqlite");
        let original = v(&[("a", 1)], Some("synthetic"));
        apply(&mut db, "fixture", &[original.clone()]).unwrap();
        assert!(apply(&mut db, "fixture", &[v(&[("a", 1)], Some("different"))]).is_err());
        assert_eq!(read(&db, "fixture").unwrap(), vec![original]);
    }

    #[test]
    fn competing_choice_observes_a_deletion_committed_by_another_connection() {
        let dir = Fixture::new();
        let mut writer = dir.db("replica.sqlite");
        let reviewed = apply(&mut writer, "fixture", &[v(&[("a", 1)], Some("synthetic"))]).unwrap();
        let deleted = v(&[("b", 1)], None);
        let mut reader = dir.db("replica.sqlite");
        let tx = writer.transaction_with_behavior(TransactionBehavior::Immediate).unwrap();
        let json = serde_json::to_string(&vec![deleted.clone()]).unwrap();
        tx.execute("UPDATE fixture SET versions = ?1 WHERE id = 'fixture'", [json]).unwrap();
        let (ready_tx, ready_rx) = std::sync::mpsc::channel();
        let worker = std::thread::spawn(move || {
            ready_tx.send(()).unwrap();
            choose(&mut reader, "fixture", &reviewed, 0, "a")
        });
        ready_rx.recv().unwrap();
        tx.commit().unwrap();
        assert!(worker.join().unwrap().is_err());
        assert_eq!(read(&writer, "fixture").unwrap(), vec![deleted]);
    }

    #[test]
    fn retry_after_lost_response_is_applied_once_even_after_reopen() {
        let dir = Fixture::new(); let mut db = dir.db("retry.sqlite");
        receipt_schema(&db).unwrap();
        assert_eq!(apply_once(&mut db, "device-a", "operation-1", b"synthetic", false).unwrap(), Receipt::Applied);
        drop(db);
        let mut reopened = dir.db("retry.sqlite");
        assert_eq!(apply_once(&mut reopened, "device-a", "operation-1", b"synthetic", false).unwrap(), Receipt::Duplicate);
        assert_eq!(effect_count(&reopened), 1);
        assert!(apply_once(&mut reopened, "device-a", "operation-1", b"different", false).is_err());
        assert_eq!(effect_count(&reopened), 1);
        assert_eq!(apply_once(&mut reopened, "device-b", "operation-1", b"synthetic", false).unwrap(), Receipt::Applied);
        assert_eq!(effect_count(&reopened), 2);
    }

    #[test]
    fn failed_transaction_does_not_create_effect_or_receipt() {
        let dir = Fixture::new(); let mut db = dir.db("rollback.sqlite"); receipt_schema(&db).unwrap();
        assert!(apply_once(&mut db, "device-a", "operation-1", b"synthetic", true).is_err());
        drop(db);
        let mut reopened = dir.db("rollback.sqlite");
        assert_eq!(effect_count(&reopened), 0);
        assert_eq!(apply_once(&mut reopened, "device-a", "operation-1", b"synthetic", false).unwrap(), Receipt::Applied);
        assert_eq!(effect_count(&reopened), 1);
    }

    #[test]
    fn concurrent_retries_commit_only_one_effect() {
        let dir = Fixture::new(); let mut first = dir.db("race.sqlite"); receipt_schema(&first).unwrap();
        let mut second = dir.db("race.sqlite");
        let barrier = std::sync::Arc::new(std::sync::Barrier::new(2)); let other = barrier.clone();
        let worker = std::thread::spawn(move || { other.wait(); apply_once(&mut second, "device-a", "operation-1", b"synthetic", false).unwrap() });
        barrier.wait();
        let one = apply_once(&mut first, "device-a", "operation-1", b"synthetic", false).unwrap();
        let two = worker.join().unwrap();
        assert_ne!(one, two); assert_eq!(effect_count(&first), 1);
    }

    #[test]
    fn process_exit_at_transaction_boundaries() {
        const CHILD_MODE: &str = "TRACEFORM_RECEIPT_FIXTURE_CHILD";
        const CHILD_PATH: &str = "TRACEFORM_RECEIPT_FIXTURE_DB";
        if let Ok(stage) = std::env::var(CHILD_MODE) {
            let path = std::path::PathBuf::from(std::env::var_os(CHILD_PATH).unwrap());
            let mut db = open(&path).unwrap(); receipt_schema(&db).unwrap();
            let _ = apply_once_with_hook(&mut db, "device-a", "operation-1", b"synthetic", false, |point| {
                if point == stage { std::process::exit(71); }
            });
            panic!("requested exit point was not reached");
        }
        for stage in ["before_commit", "after_commit"] {
            let dir = Fixture::new(); let path = dir.0.join("crash.sqlite");
            let status = std::process::Command::new(std::env::current_exe().unwrap())
                .args(["--exact", "causal_memory_fixture::tests::process_exit_at_transaction_boundaries"])
                .env(CHILD_MODE, stage).env(CHILD_PATH, &path).status().unwrap();
            assert_eq!(status.code(), Some(71));
            let mut db = open(&path).unwrap();
            let expected = if stage == "before_commit" { 0 } else { 1 };
            assert_eq!(effect_count(&db), expected);
            let result = apply_once(&mut db, "device-a", "operation-1", b"synthetic", false).unwrap();
            assert_eq!(result, if stage == "before_commit" { Receipt::Applied } else { Receipt::Duplicate });
            assert_eq!(effect_count(&db), 1);
        }
    }
}
