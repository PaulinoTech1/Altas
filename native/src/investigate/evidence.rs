//! Stored method contracts. Missing legacy contracts remain explicitly unknown.
use super::*;
pub(super) fn initialize(c: &Connection) -> Result<()> {
    c.execute_batch(include_str!("../../../evidence_schema.sql"))?;
    c.execute_batch("INSERT OR IGNORE INTO evidence_sequences SELECT 'identity_history',coalesce(max(id),0) FROM identity_history; CREATE TRIGGER IF NOT EXISTS identity_sequence AFTER INSERT ON identity_history BEGIN UPDATE evidence_sequences SET value=max(value,new.id) WHERE name='identity_history'; END;")?;
    for table in [
        "observations",
        "events",
        "identities",
        "collection_runs",
        "capture_sessions",
        "sensor_observations",
        "local_alerts",
    ] {
        if !rows(c, &format!("PRAGMA table_info({table})"), &[])?
            .iter()
            .any(|r| r["name"] == "batch_id")
        {
            c.execute_batch(&format!("ALTER TABLE {table} ADD COLUMN batch_id INTEGER"))?;
        }
    }
    Ok(())
}
pub(super) fn imported_batch(
    c: &Connection,
    kind: &str,
    sensor: &str,
    parameters: Value,
) -> Result<i64> {
    let contract = json!({"version":"atlas-collection/1","kind":kind,"provenance":if sensor=="local"{"import"}else{"sensor post"},"sensor":sensor,"network_fingerprint":Value::Null,"collector_build":crate::kernels::manifest(),"parameters":parameters,"remote_collection_method":"unknown; submitted metadata is untrusted"});
    c.execute("INSERT INTO collection_batches(network_id,started_at,kind,contract,failure_catalog) VALUES('',?,?,?,?)",params![timestamp(c)?,kind,contract.to_string(),include_str!("../../../failure_codes.json")])?;
    Ok(c.last_insert_rowid())
}
pub(super) fn contract(c: &Connection, id: i64) -> Result<Value> {
    let mut v = rows(c, "SELECT * FROM collection_batches WHERE id=?", &[&id])?;
    let mut row = v.pop().ok_or_else(|| Failure::new("ATLAS_NOT_FOUND"))?;
    parse_field(&mut row, "contract");
    parse_field(&mut row, "failure_catalog");
    Ok(row)
}
pub(super) fn target_contracts(c: &Connection, id: i64) -> Result<Value> {
    let mut result = Vec::new();
    for row in rows(c,"SELECT DISTINCT batch_id FROM observations WHERE target_id=? AND batch_id IS NOT NULL ORDER BY batch_id DESC LIMIT 100",&[&id])? {
        result.push(contract(c,number(&row,"batch_id",0))?);
    }
    Ok(json!({"contracts":result,"limit":100,"legacy_contracts":"unknown"}))
}
