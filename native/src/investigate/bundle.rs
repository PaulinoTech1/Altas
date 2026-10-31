//! Bounded, transactional evidence archive; never presents omitted data as complete.
use super::*;
pub(super) fn export(c: &mut Connection, r: &Value) -> Result<Value> {
    let scope = r
        .get("network_id")
        .and_then(Value::as_str)
        .ok_or_else(|| Failure::input("network_id"))?;
    let limit = number(r, "limit", 100);
    let cursor = number(r, "cursor", i64::MAX);
    if cursor < 1 {
        return Err(Failure::input("cursor").into());
    }
    let tx = c.transaction()?;
    let context = rows(&tx, "SELECT * FROM network_contexts WHERE id=?", &[&scope])?
        .pop()
        .ok_or_else(|| Failure::new("ATLAS_NOT_FOUND"))?;
    let input_bytes:i64=tx.query_row("SELECT coalesce(sum(length(data)),0) FROM (SELECT o.data FROM observations o JOIN targets t ON t.id=o.target_id WHERE t.network_id=? AND o.id<? ORDER BY o.id DESC LIMIT ?)",params![scope,cursor,limit],|r|r.get(0))?;
    if input_bytes > 8_000_000 {
        return Err(Failure::new("ATLAS_EXPORT_LIMIT").into());
    }
    let mut observations=rows(&tx,"SELECT o.* FROM observations o JOIN targets t ON t.id=o.target_id WHERE t.network_id=? AND o.id<? ORDER BY o.id DESC LIMIT ?",&[&scope,&cursor,&(limit+1)])?;
    let more = observations.len() > limit as usize;
    observations.truncate(limit as usize);
    for row in &mut observations {
        parse_field(row, "data");
    }
    let ids: BTreeSet<i64> = observations
        .iter()
        .filter_map(|r| r["batch_id"].as_i64())
        .collect();
    let mut contracts = Vec::new();
    for id in ids {
        contracts.push(evidence::contract(&tx, id)?);
    }
    let mut gaps = rows(
        &tx,
        "SELECT * FROM collection_gaps WHERE network_id=? ORDER BY id DESC LIMIT 101",
        &[&scope],
    )?;
    let gaps_more = gaps.len() > 100;
    gaps.truncate(100);
    let total:i64=tx.query_row("SELECT count(*) FROM observations o JOIN targets t ON t.id=o.target_id WHERE t.network_id=?",[scope],|r|r.get(0))?;
    let evidence = json!({"format":"atlas-evidence/1","exported_at":timestamp(&tx)?,"network_context":context,"network_fingerprint":context["fingerprint"],"provenance":"local TCP observations only; BLE imports and external sensor posts are not assigned to this network","observations":observations,"collection_batches":contracts,"gaps":gaps,"legacy_contracts_unknown":observations.iter().any(|o|o["batch_id"].is_null()),"current_failure_catalog":serde_json::from_str::<Value>(include_str!("../../../failure_codes.json"))?,"failure_catalog_note":"Each collection batch retains the catalog in force then. Current catalog is not substituted for unknown legacy catalogs.","research_build":crate::kernels::manifest(),"limits":{"observation_page":limit,"gap_limit":100,"byte_limit":8000000},"truncation":{"observations":more,"earlier_observation_pages_omitted":cursor!=i64::MAX,"gaps":gaps_more},"total_context_observations":total,"next_cursor":if more{observations.last().map(|v|v["id"].clone())}else{None},"interpretation":["No uninterrupted observation claim; gaps can include clock changes, process downtime or scheduling delays","A subnet fingerprint does not establish SSID membership or physical topology","Device metadata and TLS certificates are unverified"]});
    let encoded = serde_json::to_string(&evidence)?;
    if encoded.len() > 8_000_000 {
        return Err(Failure::new("ATLAS_EXPORT_LIMIT").into());
    }
    tx.commit()?;
    Ok(
        json!({"archive":evidence,"sha256":hash(&encoded),"hash_encoding":"SHA-256 of compact serde_json archive encoding","filename":"atlas-evidence.json"}),
    )
}
