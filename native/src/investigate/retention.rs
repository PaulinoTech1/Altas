//! Explicit context-scoped age deletion. No target, context or contract deletion.
use super::*;
pub(super) fn purge(c: &mut Connection, r: &Value) -> Result<Value> {
    let scope = r
        .get("network_id")
        .and_then(Value::as_str)
        .ok_or_else(|| Failure::input("network_id"))?;
    let age = r
        .get("age_seconds")
        .and_then(Value::as_i64)
        .ok_or_else(|| Failure::input("age_seconds"))?;
    if !(1..=315360000).contains(&age) {
        return Err(Failure::input("age_seconds").into());
    }
    let empty = Vec::new();
    let marked = match r.get("delete_snapshot_ids") {
        None => &empty,
        Some(v) => v
            .as_array()
            .ok_or_else(|| Failure::input("delete_snapshot_ids"))?,
    };
    if marked.len() > 100 || marked.iter().any(|v| v.as_i64().is_none_or(|id| id <= 0)) {
        return Err(Failure::input("delete_snapshot_ids").into());
    }
    let tx = c.transaction_with_behavior(rusqlite::TransactionBehavior::Immediate)?;
    if !tx.query_row(
        "SELECT EXISTS(SELECT 1 FROM network_contexts WHERE id=?)",
        [scope],
        |r| r.get::<_, bool>(0),
    )? {
        return Err(Failure::new("ATLAS_NOT_FOUND").into());
    }
    let cutoff: String = tx.query_row(
        "SELECT strftime('%Y-%m-%dT%H:%M:%fZ','now',?)",
        [format!("-{age} seconds")],
        |r| r.get(0),
    )?;
    let mut snapshots = 0;
    for value in marked {
        let id = value.as_i64().unwrap();
        let owned:bool=tx.query_row("SELECT EXISTS(SELECT 1 FROM identity_history h JOIN targets t ON h.entity='ip:'||t.id WHERE h.id=? AND t.network_id=?)",params![id,scope],|r|r.get(0))?;
        if !owned {
            return Err(Failure::input("delete_snapshot_ids").into());
        }
        snapshots += tx.execute(
            "DELETE FROM identity_history WHERE id=? AND julianday(at)<julianday(?)",
            params![id, cutoff],
        )?;
    }
    let observations=tx.execute("DELETE FROM observations WHERE target_id IN(SELECT id FROM targets WHERE network_id=?) AND julianday(at)<julianday(?)",params![scope,cutoff])?;
    let events=tx.execute("DELETE FROM events WHERE target_id IN(SELECT id FROM targets WHERE network_id=?) AND julianday(at)<julianday(?)",params![scope,cutoff])?;
    tx.commit()?;
    Ok(
        json!({"network_id":scope,"cutoff":cutoff,"removed":{"observations":observations,"events":events,"identity_snapshots":snapshots},"preserved":"Context, targets, identities, unmarked snapshots, alerts, gaps, runs and contracts; database pages are not vacuumed"}),
    )
}
