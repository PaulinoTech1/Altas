//! Bounded investigation queries and capture storage. No network probing here.
use crate::errors::Failure;
use rusqlite::{params, Connection, OptionalExtension};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::{
    collections::{BTreeMap, BTreeSet},
    io::Read,
    time::Duration,
};

type Result<T> = std::result::Result<T, Box<dyn std::error::Error>>;
mod captures;
use captures::{compare, import, packets, replay, sessions};
mod alerts;
use alerts::{alert, detail, snapshot, sync};
mod sensors;
use sensors::{ingest, sensor_create};
mod reports;
use reports::report;
mod overview;
mod research;
mod research_network;
use overview::{health, overview_for_network};
fn hash(s: &str) -> String {
    format!("{:x}", Sha256::digest(s.as_bytes()))
}
fn text<'a>(r: &'a Value, key: &str) -> &'a str {
    r[key].as_str().unwrap_or("")
}
fn number(r: &Value, key: &str, default: i64) -> i64 {
    r[key].as_i64().unwrap_or(default)
}
fn timestamp(c: &Connection) -> Result<String> {
    Ok(
        c.query_row("SELECT strftime('%Y-%m-%dT%H:%M:%fZ','now')", [], |r| {
            r.get(0)
        })?,
    )
}
fn rows(c: &Connection, sql: &str, p: &[&dyn rusqlite::ToSql]) -> Result<Vec<Value>> {
    let mut stmt = c.prepare(sql)?;
    let names: Vec<_> = stmt.column_names().iter().map(|s| s.to_string()).collect();
    let mut query = stmt.query(p)?;
    let mut out = Vec::new();
    while let Some(row) = query.next()? {
        let mut obj = serde_json::Map::new();
        for (i, name) in names.iter().enumerate() {
            use rusqlite::types::ValueRef;
            let v = match row.get_ref(i)? {
                ValueRef::Null => Value::Null,
                ValueRef::Integer(n) => json!(n),
                ValueRef::Real(f) => json!(f),
                ValueRef::Text(s) => json!(String::from_utf8_lossy(s)),
                _ => Value::Null,
            };
            obj.insert(name.clone(), v);
        }
        out.push(Value::Object(obj));
    }
    Ok(out)
}
fn parse_field(v: &mut Value, key: &str) {
    if let Some(s) = v[key].as_str() {
        if let Ok(j) = serde_json::from_str(s) {
            v[key] = j;
        }
    }
}
fn init(c: &mut Connection) -> Result<()> {
    c.busy_timeout(Duration::from_secs(8))?;
    c.execute_batch("PRAGMA journal_mode=WAL;
    CREATE TABLE IF NOT EXISTS capture_sessions(id INTEGER PRIMARY KEY, capture_key TEXT UNIQUE NOT NULL, name TEXT NOT NULL, source TEXT NOT NULL, imported_at TEXT NOT NULL, digest TEXT NOT NULL, source_text TEXT, sensor TEXT NOT NULL DEFAULT 'local', packets INTEGER NOT NULL DEFAULT 0, rejected INTEGER NOT NULL DEFAULT 0);
    CREATE TABLE IF NOT EXISTS capture_packets(id INTEGER PRIMARY KEY, session_id INTEGER NOT NULL, seq INTEGER NOT NULL, address TEXT NOT NULL, time_ms INTEGER NOT NULL, elapsed_ms INTEGER NOT NULL, rssi INTEGER NOT NULL, name TEXT NOT NULL, raw TEXT NOT NULL, data TEXT NOT NULL, legacy_id INTEGER UNIQUE, UNIQUE(session_id,seq));
    CREATE INDEX IF NOT EXISTS packet_session ON capture_packets(session_id,id);
    CREATE INDEX IF NOT EXISTS packet_address ON capture_packets(address,id);
    CREATE INDEX IF NOT EXISTS packet_rssi ON capture_packets(session_id,rssi);
    CREATE TABLE IF NOT EXISTS investigation_state(key TEXT PRIMARY KEY,value TEXT NOT NULL);
    CREATE TABLE IF NOT EXISTS identity_history(id INTEGER PRIMARY KEY,entity TEXT NOT NULL,at TEXT NOT NULL,digest TEXT NOT NULL,data TEXT NOT NULL);
    CREATE INDEX IF NOT EXISTS identity_entity ON identity_history(entity,id);
    CREATE TABLE IF NOT EXISTS local_alerts(id INTEGER PRIMARY KEY,alert_key TEXT UNIQUE NOT NULL,at TEXT NOT NULL,kind TEXT NOT NULL,entity TEXT NOT NULL,message TEXT NOT NULL,acknowledged INTEGER NOT NULL DEFAULT 0);
    CREATE TABLE IF NOT EXISTS alert_rules(id INTEGER PRIMARY KEY CHECK(id=1),enabled INTEGER NOT NULL DEFAULT 1,missed_checks INTEGER NOT NULL DEFAULT 3,stale_minutes INTEGER NOT NULL DEFAULT 30);
    INSERT OR IGNORE INTO alert_rules(id) VALUES(1);
    CREATE TABLE IF NOT EXISTS sensors(id TEXT PRIMARY KEY,name TEXT NOT NULL,token_hash TEXT UNIQUE NOT NULL,created_at TEXT NOT NULL,last_seen TEXT,enabled INTEGER NOT NULL DEFAULT 1);
    CREATE TABLE IF NOT EXISTS sensor_observations(id INTEGER PRIMARY KEY,sensor TEXT NOT NULL,host TEXT NOT NULL,at TEXT NOT NULL,data TEXT NOT NULL);
    CREATE INDEX IF NOT EXISTS sensor_host ON sensor_observations(sensor,host,id);
    CREATE TABLE IF NOT EXISTS collection_runs(id INTEGER PRIMARY KEY,kind TEXT NOT NULL,started_at TEXT NOT NULL,finished_at TEXT NOT NULL,duration_ms INTEGER NOT NULL,items INTEGER NOT NULL,error TEXT);
    CREATE INDEX IF NOT EXISTS collection_finished ON collection_runs(id);
    CREATE INDEX IF NOT EXISTS event_target ON events(target_id,id);
    CREATE INDEX IF NOT EXISTS event_time ON events(at);")?;
    c.execute_batch("CREATE TABLE IF NOT EXISTS network_contexts(id TEXT PRIMARY KEY,label TEXT NOT NULL,address TEXT,cidr TEXT,fingerprint TEXT,created_at TEXT NOT NULL,connected INTEGER NOT NULL DEFAULT 0);
    CREATE TABLE IF NOT EXISTS network_state(id INTEGER PRIMARY KEY CHECK(id=1),active_id TEXT NOT NULL DEFAULT '');
    INSERT OR IGNORE INTO network_state(id,active_id) VALUES(1,'');
    INSERT OR IGNORE INTO network_contexts(id,label,created_at,connected) VALUES('','Unscoped observations',strftime('%Y-%m-%dT%H:%M:%fZ','now'),0);")?;
    // Full host-uniqueness migration belongs to Python Store.initialize().
    // These additive columns support standalone engine fixtures and older schemas.
    for table in ["targets", "identities"] {
        let columns = rows(c, &format!("PRAGMA table_info({table})"), &[])?;
        if !columns.iter().any(|r| r["name"] == "network_id") {
            c.execute_batch(&format!(
                "ALTER TABLE {table} ADD COLUMN network_id TEXT NOT NULL DEFAULT '';"
            ))?;
        }
    }
    let migrated: bool = c.query_row(
        "SELECT EXISTS(SELECT 1 FROM investigation_state WHERE key='legacy_migrated')",
        [],
        |r| r.get(0),
    )?;
    if !migrated {
        let tx = c.transaction()?;
        let has_old: bool =
            tx.query_row("SELECT EXISTS(SELECT 1 FROM ble_samples)", [], |r| r.get(0))?;
        if has_old {
            let at = timestamp(&tx)?;
            tx.execute("INSERT OR IGNORE INTO capture_sessions(capture_key,name,source,imported_at,digest,sensor) VALUES('legacy','Legacy imports (dates unknown)','legacy',?,'legacy','local')", [&at])?;
            let sid: i64 = tx.query_row(
                "SELECT id FROM capture_sessions WHERE capture_key='legacy'",
                [],
                |r| r.get(0),
            )?;
            let mut insert = tx.prepare("INSERT OR IGNORE INTO capture_packets(session_id,seq,address,time_ms,elapsed_ms,rssi,name,raw,data,legacy_id) VALUES(?,?,?,?,?,?,?,?,?,?)")?;
            let mut read = tx.prepare("SELECT id,address,data FROM ble_samples ORDER BY id")?;
            let mut old = read.query([])?;
            let mut seq = 0i64;
            while let Some(row) = old.next()? {
                let row = json!({"id":row.get::<_,i64>(0)?,"address":row.get::<_,String>(1)?,"data":row.get::<_,String>(2)?});
                let data: Value = serde_json::from_str(text(&row, "data"))?;
                let ms = data["time_ms"]
                    .as_i64()
                    .or_else(|| crate::capture::time_ms(text(&data, "time")).map(|n| n as i64))
                    .unwrap_or(0);
                insert.execute(params![
                    sid,
                    seq as i64,
                    text(&row, "address"),
                    ms,
                    ms,
                    number(&data, "rssi", -127),
                    text(&data["advertisement"], "name"),
                    text(&data, "raw"),
                    data.to_string(),
                    number(&row, "id", 0)
                ])?;
                seq += 1;
            }
            drop(old);
            drop(read);
            drop(insert);
            tx.execute("UPDATE capture_sessions SET packets=(SELECT count(*) FROM capture_packets WHERE session_id=?) WHERE id=?",params![sid,sid])?;
        }
        tx.execute(
            "INSERT OR IGNORE INTO investigation_state VALUES('legacy_migrated','1')",
            [],
        )?;
        let id: i64 = tx.query_row("SELECT coalesce(max(id),0) FROM events", [], |r| r.get(0))?;
        tx.execute(
            "INSERT OR IGNORE INTO investigation_state VALUES('event_cursor',?)",
            [id.to_string()],
        )?;
        tx.commit()?;
    }
    Ok(())
}
fn active_network(c: &Connection) -> Result<String> {
    Ok(
        c.query_row("SELECT active_id FROM network_state WHERE id=1", [], |r| {
            r.get(0)
        })?,
    )
}
fn targets_for_network(c: &Connection, network_id: &str) -> Result<Vec<Value>> {
    let mut out=rows(c,"SELECT t.id,t.host,t.label,t.paused,t.network_id,i.data AS identity,(SELECT data FROM observations WHERE target_id=t.id ORDER BY id DESC LIMIT 1) AS latest,(SELECT at FROM observations WHERE target_id=t.id ORDER BY id DESC LIMIT 1) AS observed_at FROM targets t LEFT JOIN identities i ON i.host=t.host AND i.network_id=t.network_id WHERE t.network_id=? ORDER BY t.id LIMIT 64",&[&network_id])?;
    for row in &mut out {
        parse_field(row, "identity");
        parse_field(row, "latest");
    }
    Ok(out)
}
fn targets(c: &Connection) -> Result<Vec<Value>> {
    targets_for_network(c, &active_network(c)?)
}
fn require_session(c: &Connection, id: i64) -> Result<()> {
    let exists: bool = c.query_row(
        "SELECT EXISTS(SELECT 1 FROM capture_sessions WHERE id=?)",
        [id],
        |r| r.get(0),
    )?;
    if !exists {
        return Err(Failure::new("ATLAS_NOT_FOUND").into());
    }
    Ok(())
}
fn validate_request(r: &Value) -> Result<()> {
    for key in [
        "session_id",
        "cursor",
        "limit",
        "rssi_min",
        "rssi_max",
        "a",
        "b",
        "missed_checks",
        "stale_minutes",
        "window",
        "threshold_db",
        "target_id",
        "port",
        "threshold_ms",
        "through_id",
    ] {
        if let Some(value) = r.get(key) {
            if !value.is_i64() {
                return Err(Failure::input(key).into());
            }
        }
    }
    for key in [
        "q",
        "address",
        "entity",
        "text",
        "source",
        "name",
        "session_key",
        "network_id",
        "dataset",
    ] {
        if let Some(value) = r.get(key) {
            if !value.is_string() {
                return Err(Failure::input(key).into());
            }
        }
    }
    if let Some(value) = r.get("enabled") {
        if !value.is_boolean() {
            return Err(Failure::input("enabled").into());
        }
    }
    if let Some(value) = r.get("session_id") {
        if value.as_i64().unwrap_or(-1) < 0 {
            return Err(Failure::input("session_id").into());
        }
    }
    if let Some(value) = r.get("limit") {
        if !(1..=100).contains(&value.as_i64().unwrap_or(0)) {
            return Err(Failure::input("limit").into());
        }
    }
    let low = number(r, "rssi_min", -127);
    let high = number(r, "rssi_max", 20);
    if low < -127 || high > 20 || low > high {
        return Err(Failure::input("rssi_range").into());
    }
    if !text(r, "address").is_empty() && !crate::capture::address(text(r, "address")) {
        return Err(Failure::input("address").into());
    }
    if text(r, "op") == "ack" && number(r, "id", 0) <= 0 {
        return Err(Failure::input("id").into());
    }
    Ok(())
}
pub fn dispatch(r: &Value) -> Result<Value> {
    validate_request(r)?;
    if !["", "ble", "network"].contains(&text(r, "dataset")) {
        return Err(Failure::input("dataset").into());
    }
    let path = text(r, "database");
    if path.is_empty() {
        return Err(Failure::input("database").into());
    }
    let mut c = Connection::open(path)?;
    init(&mut c)?;
    match text(r, "op") {
        "init" => Ok(json!({"ok":true})),
        "import" => import(&mut c, r, "local"),
        "sync" => {
            let tx = c.transaction()?;
            sync(&tx)?;
            tx.commit()?;
            Ok(json!({"ok":true}))
        }
        "sessions" => sessions(&c),
        "packets" => packets(&c, r),
        "replay" => replay(&c, r),
        "research_benchmark" => Ok(crate::kernels::benchmark()),
        "research_sources" => research_network::sources(&c, r),
        "research" => {
            let tx = c.transaction()?;
            let result = if text(r, "dataset") == "network" {
                research_network::analyze(&tx, r)?
            } else {
                research::analyze(&tx, r)?
            };
            tx.commit()?;
            Ok(result)
        }
        "research_report" => {
            let tx = c.transaction()?;
            let result = research::report(&tx, r)?;
            tx.commit()?;
            Ok(result)
        }
        "compare" => compare(&c, r),
        "overview" => {
            let tx = c.transaction()?;
            let result = overview_for_network(&tx, r)?;
            tx.commit()?;
            Ok(result)
        }
        "detail" => detail(&c, r),
        "health" => health(&c),
        "report" => report(&c, r),
        "source" => {
            let id = number(r, "session_id", 0);
            require_session(&c, id)?;
            let out: Option<String> = c
                .query_row(
                    "SELECT source_text FROM capture_sessions WHERE id=?",
                    [id],
                    |r| r.get(0),
                )
                .optional()?
                .flatten();
            Ok(json!({"text":out,"note":"Original source is unavailable for legacy imports"}))
        }
        "alerts" => {
            sync(&c)?;
            Ok(json!(rows(
                &c,
                "SELECT * FROM local_alerts ORDER BY id DESC LIMIT 100",
                &[]
            )?))
        }
        "ack" => {
            let changed = c.execute(
                "UPDATE local_alerts SET acknowledged=1 WHERE id=?",
                [number(r, "id", 0)],
            )?;
            if changed == 0 {
                return Err(Failure::new("ATLAS_NOT_FOUND").into());
            }
            Ok(json!({"ok":true}))
        }
        "rules" => {
            let missed = number(r, "missed_checks", 3);
            let stale = number(r, "stale_minutes", 30);
            if !(1..=20).contains(&missed) || !(5..=1440).contains(&stale) {
                return Err(Failure::input("alert_rules").into());
            }
            c.execute(
                "UPDATE alert_rules SET enabled=?,missed_checks=?,stale_minutes=? WHERE id=1",
                params![r["enabled"].as_bool().unwrap_or(true), missed, stale],
            )?;
            Ok(json!({"ok":true}))
        }
        "sensors" => Ok(json!(rows(
            &c,
            "SELECT id,name,created_at,last_seen,enabled FROM sensors ORDER BY created_at LIMIT 32",
            &[]
        )?)),
        "sensor_create" => sensor_create(&c, r),
        "sensor_revoke" => {
            if c.execute("UPDATE sensors SET enabled=0 WHERE id=?", [text(r, "id")])? == 0 {
                return Err(Failure::new("ATLAS_NOT_FOUND").into());
            }
            Ok(json!({"ok":true}))
        }
        "ingest" => ingest(&mut c, r),
        "metric" => {
            c.execute("INSERT INTO collection_runs(kind,started_at,finished_at,duration_ms,items,error) VALUES(?,?,?,?,?,?)",params![text(r,"kind"),text(r,"started_at"),timestamp(&c)?,number(r,"duration_ms",0),number(r,"items",0),r["error"].as_str()])?;
            Ok(json!({"ok":true}))
        }
        _ => Err(Failure::new("ATLAS_NOT_FOUND").into()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn database() -> Connection {
        let mut c = Connection::open_in_memory().unwrap();
        c.execute_batch("CREATE TABLE ble_samples(id INTEGER PRIMARY KEY,fingerprint TEXT UNIQUE,address TEXT,imported_at TEXT,data TEXT);CREATE TABLE targets(id INTEGER PRIMARY KEY,host TEXT,label TEXT,paused INTEGER DEFAULT 0);CREATE TABLE identities(host TEXT,data TEXT);CREATE TABLE observations(id INTEGER PRIMARY KEY,target_id INTEGER,at TEXT,data TEXT);CREATE TABLE events(id INTEGER PRIMARY KEY,target_id INTEGER,at TEXT,message TEXT);").unwrap();
        init(&mut c).unwrap();
        c
    }
    fn capture() -> Value {
        json!({"text":"23:59:59.000 AA:BB:CC:DD:EE:FF -40 05095465737403030D18\n00:00:01.000 AA:BB:CC:DD:EE:FF -60 05095465737403030D18","source":"test.log","name":"Fixture"})
    }
    #[test]
    fn sessions_dedup_and_distinct_identity() {
        let mut c = database();
        let first = import(&mut c, &capture(), "local").unwrap();
        assert_eq!(first["imported"], 2);
        assert_eq!(
            import(&mut c, &capture(), "local").unwrap()["duplicates"],
            2
        );
        let mut other = capture();
        other["session_key"] = json!("day-two");
        assert_eq!(import(&mut c, &other, "local").unwrap()["imported"], 2);
        assert_eq!(sessions(&c).unwrap().as_array().unwrap().len(), 2);
        other["text"] = json!("00:00:01 AA:BB:CC:DD:EE:FF -45 020106");
        assert!(import(&mut c, &other, "local").is_err());
    }
    #[test]
    fn replay_handles_midnight() {
        let mut c = database();
        let s = import(&mut c, &capture(), "local").unwrap();
        let r = replay(&c, &json!({"session_id":s["session_id"]})).unwrap();
        assert_eq!(r["points"][1]["elapsed_ms"], 2000);
    }
    #[test]
    fn packet_search_pagination() {
        let mut c = database();
        import(&mut c, &capture(), "local").unwrap();
        let page = packets(&c, &json!({"q":"Test","limit":1})).unwrap();
        assert_eq!(page["packets"].as_array().unwrap().len(), 1);
        assert!(page["next_cursor"].is_i64());
        let second = packets(&c, &json!({"cursor":page["next_cursor"],"limit":1})).unwrap();
        assert_ne!(page["packets"][0]["id"], second["packets"][0]["id"]);
        assert_eq!(
            packets(&c, &json!({"q":"%"})).unwrap()["packets"]
                .as_array()
                .unwrap()
                .len(),
            0
        );
    }
    #[test]
    fn sensor_auth_and_revoke() {
        let mut c = database();
        let sensor = sensor_create(&c, &json!({"name":"fixture"})).unwrap();
        let mut payload = json!({"token":sensor["token"],"observations":[{"host":"192.168.1.20","status":"responding","services":[{"port":80,"state":"open"}]}]});
        assert_eq!(ingest(&mut c, &payload).unwrap()["accepted"], 1);
        payload["token"] = json!("0".repeat(64));
        assert!(ingest(&mut c, &payload).is_err());
        payload["token"] = sensor["token"].clone();
        c.execute("UPDATE sensors SET enabled=0", []).unwrap();
        assert!(ingest(&mut c, &payload).is_err());
    }
    #[test]
    fn map_has_provenance_not_topology() {
        let mut c = database();
        import(&mut c, &capture(), "local").unwrap();
        let o = overview_for_network(&c, &json!({})).unwrap();
        assert!(o["nodes"]
            .as_array()
            .unwrap()
            .iter()
            .any(|n| n["id"] == "imports"));
        assert!(o["edges"]
            .as_array()
            .unwrap()
            .iter()
            .any(|e| e["kind"] == "Imported BLE observation"));
        assert_eq!(o["counts"]["unread_alerts"], 1);
    }
    #[test]
    fn report_escapes_device_text() {
        let mut c = database();
        let mut r = capture();
        r["name"] = json!("<script>alert(1)</script>");
        let s = import(&mut c, &r, "local").unwrap();
        let html = report(&c, &json!({"session_id":s["session_id"]})).unwrap()["html"]
            .as_str()
            .unwrap()
            .to_string();
        assert!(!html.contains("<script>"));
        assert!(html.contains("&lt;script&gt;"));
        assert!(html.contains("<svg"));
    }
    #[test]
    fn sensor_observations_are_atomic() {
        let mut c = database();
        let s = sensor_create(&c, &json!({"name":"fixture"})).unwrap();
        let r = json!({"token":s["token"],"observations":[{"host":"192.168.1.20","status":"responding","services":[]},{"host":"not-an-ip","status":"unknown","services":[]}]});
        assert!(ingest(&mut c, &r).is_err());
        let count: i64 = c
            .query_row("SELECT count(*) FROM sensor_observations", [], |r| r.get(0))
            .unwrap();
        assert_eq!(count, 0);
    }
}
