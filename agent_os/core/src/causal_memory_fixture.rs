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
}
