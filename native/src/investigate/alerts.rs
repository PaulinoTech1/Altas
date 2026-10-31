//! Alert rules and evidence identity snapshots.
use super::*;
pub(super) fn alert(
    c: &Connection,
    key: &str,
    kind: &str,
    entity: &str,
    message: &str,
) -> Result<bool> {
    let enabled: bool = c.query_row("SELECT enabled FROM alert_rules WHERE id=1", [], |r| {
        r.get(0)
    })?;
    if enabled {
        let changed=c.execute("INSERT OR IGNORE INTO local_alerts(alert_key,at,kind,entity,message) VALUES(?,?,?,?,?)",params![key,timestamp(c)?,kind,entity,message])?;
        return Ok(changed > 0);
    }
    Ok(false)
}
pub(super) fn snapshot(c: &Connection, entity: &str, data: &Value) -> Result<()> {
    let encoded = data.to_string();
    let digest = hash(&encoded);
    let prev: Option<String> = c
        .query_row(
            "SELECT digest FROM identity_history WHERE entity=? ORDER BY id DESC LIMIT 1",
            [entity],
            |r| r.get(0),
        )
        .optional()?;
    if prev.as_deref() != Some(&digest) {
        c.execute(
            "INSERT INTO identity_history(id,entity,at,digest,data) SELECT (SELECT value+1 FROM evidence_sequences WHERE name='identity_history'),?,?,?,?",
            params![entity, timestamp(c)?, digest, encoded],
        )?;
    }
    Ok(())
}
pub(super) fn sync(c: &Connection) -> Result<()> {
    let mut cursor: i64 = c
        .query_row(
            "SELECT cast(value AS INTEGER) FROM investigation_state WHERE key='event_cursor'",
            [],
            |r| r.get(0),
        )
        .unwrap_or(0);
    for e in rows(
        c,
        "SELECT id,target_id,message,batch_id FROM events WHERE id>? ORDER BY id LIMIT 500",
        &[&cursor],
    )? {
        cursor = number(&e, "id", cursor);
        let message = text(&e, "message");
        if message.contains("certificate")
            || message.contains("unreachable → open")
            || message.starts_with("Baseline recorded")
            || message.contains("monitoring started (open)")
        {
            let created = alert(
                c,
                &format!("event:{cursor}"),
                "network_change",
                &format!("ip:{}", number(&e, "target_id", 0)),
                message,
            )?;
            if created {
                c.execute(
                    "UPDATE local_alerts SET batch_id=? WHERE alert_key=? AND batch_id IS NULL",
                    params![e["batch_id"].as_i64(), format!("event:{cursor}")],
                )?;
            }
        }
    }
    c.execute(
        "INSERT OR REPLACE INTO investigation_state VALUES('event_cursor',?)",
        [cursor.to_string()],
    )?;
    let (missed, stale): (i64, i64) = c.query_row(
        "SELECT missed_checks,stale_minutes FROM alert_rules WHERE id=1",
        [],
        |r| Ok((r.get(0)?, r.get(1)?)),
    )?;
    for row in rows(c,"SELECT t.id,t.host,t.paused,i.data AS identity,(SELECT data FROM observations WHERE target_id=t.id ORDER BY id DESC LIMIT 1) AS latest FROM targets t LEFT JOIN identities i ON i.host=t.host AND i.network_id=t.network_id WHERE t.network_id=(SELECT active_id FROM network_state WHERE id=1) ORDER BY t.id LIMIT 64",&[])? {
        let id=number(&row,"id",0);let entity=format!("ip:{id}");
        let identity:Value=serde_json::from_str(text(&row,"identity")).unwrap_or(Value::Null);
        let latest:Value=serde_json::from_str(text(&row,"latest")).unwrap_or(Value::Null);
        let services:Vec<Value>=latest["services"].as_array().unwrap_or(&Vec::new()).iter().filter(|s|s["state"]=="open").map(|s|json!({"port":s["port"],"title":s["title"],"server":s["server"],"certificate_sha256":s["certificate_sha256"]})).collect();
        snapshot(c,&entity,&json!({"identity":identity,"open_services":services,"provenance":"local observation and device claims"}))?;
        if number(&row,"paused",0)==1{continue;}
        let last=rows(c,"SELECT id,at,batch_id,json_extract(data,'$.status') AS status FROM observations WHERE target_id=? ORDER BY id DESC LIMIT ?",&[&id,&missed])?;
        if last.len()==missed as usize && last.iter().all(|r|r["status"]=="no_response"){
            let last_good:i64=c.query_row("SELECT coalesce(max(id),0) FROM observations WHERE target_id=? AND json_extract(data,'$.status')='responding'",[id],|r|r.get(0))?;
            let key=format!("missed:{id}:{last_good}");
            if alert(c,&key,"missed_checks",&entity,&format!("{missed} consecutive checks received no TCP response; device power state unknown"))? {
                c.execute("UPDATE local_alerts SET batch_id=? WHERE alert_key=?",params![last[0]["batch_id"].as_i64(),key])?;
            }
        }
        let stale_row:Option<i64>=c.query_row("SELECT id FROM observations WHERE target_id=? AND julianday(at)<julianday('now',?) ORDER BY id DESC LIMIT 1",params![id,format!("-{stale} minutes")],|r|r.get(0)).optional()?;
        if let (Some(old),Some(newest))=(stale_row,last.first()) {if old==number(newest,"id",0){let key=format!("stale:{id}:{old}");
            if alert(c,&key,"stale",&entity,"No fresh observation within the configured stale interval")? {
                c.execute("UPDATE local_alerts SET batch_id=? WHERE alert_key=?",params![newest["batch_id"].as_i64(),key])?;
            }}}
    }
    Ok(())
}
pub(super) fn detail(c: &Connection, r: &Value) -> Result<Value> {
    let entity = text(r, "entity");
    if entity.len() > 100 {
        return Err(Failure::input("entity").into());
    }
    let exists = if let Some(id) = entity
        .strip_prefix("ip:")
        .and_then(|s| s.parse::<i64>().ok())
    {
        c.query_row(
            "SELECT EXISTS(SELECT 1 FROM targets WHERE id=?)",
            [id],
            |row| row.get::<_, bool>(0),
        )?
    } else if let Some(address) = entity.strip_prefix("ble:") {
        if !crate::capture::address(address) {
            return Err(Failure::input("entity").into());
        }
        c.query_row(
            "SELECT EXISTS(SELECT 1 FROM capture_packets WHERE address=?)",
            [address.to_uppercase()],
            |row| row.get::<_, bool>(0),
        )?
    } else {
        return Err(Failure::input("entity").into());
    };
    if !exists {
        return Err(Failure::new("ATLAS_NOT_FOUND").into());
    }
    let mut history = rows(
        c,
        "SELECT id,at,data FROM identity_history WHERE entity=? ORDER BY id DESC LIMIT 100",
        &[&entity],
    )?;
    for h in &mut history {
        parse_field(h, "data");
    }
    let events = if let Some(id) = entity
        .strip_prefix("ip:")
        .and_then(|s| s.parse::<i64>().ok())
    {
        rows(
            c,
            "SELECT at,message FROM events WHERE target_id=? ORDER BY id DESC LIMIT 200",
            &[&id],
        )?
    } else {
        Vec::new()
    };
    Ok(
        json!({"entity":entity,"history":history,"events":events,"collection_contracts":if let Some(id)=entity.strip_prefix("ip:").and_then(|v|v.parse::<i64>().ok()){evidence::target_contracts(c,id)?}else{json!(null)},"confidence":"Device claims and observed changes; stable identity across addresses is unproven"}),
    )
}
