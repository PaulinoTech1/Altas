//! Capture persistence, search and within-session replay.
use super::*;
pub(super) fn import(c: &mut Connection, r: &Value, sensor: &str) -> Result<Value> {
    let content = text(r, "text");
    if content.len() > 1_000_000 {
        return Err(Failure::new("ATLAS_BODY_TOO_LARGE").into());
    }
    let parsed = crate::capture::parse(content);
    let packets = parsed["records"]
        .as_array()
        .ok_or_else(|| Failure::new("ATLAS_CAPTURE_EMPTY"))?;
    if packets.is_empty() {
        return Err(Failure::new("ATLAS_CAPTURE_EMPTY").into());
    }
    let source: String = text(r, "source").chars().take(160).collect();
    let digest = hash(&format!("{sensor}\n{source}\n{content}"));
    let key = if text(r, "session_key").is_empty() {
        digest.clone()
    } else {
        hash(&format!("{sensor}:{}", text(r, "session_key")))
    };
    let existing: Option<(i64, String, i64)> = c
        .query_row(
            "SELECT id,digest,packets FROM capture_sessions WHERE capture_key=?",
            [&key],
            |q| Ok((q.get(0)?, q.get(1)?, q.get(2)?)),
        )
        .optional()?;
    if let Some((id, previous, count)) = existing {
        if previous != digest {
            return Err(Failure::new("ATLAS_SESSION_CONFLICT").into());
        }
        return Ok(
            json!({"session_id":id,"imported":0,"parsed":packets.len(),"duplicates":count,"rejected_lines":parsed["rejected_lines"],"limit_reached":parsed["limit_reached"],"decoder":parsed["decoder"]}),
        );
    }
    let name = if text(r, "name").is_empty() {
        source.clone()
    } else {
        text(r, "name").chars().take(160).collect()
    };
    let tx = c.transaction()?;
    let at = timestamp(&tx)?;
    tx.execute("INSERT INTO capture_sessions(capture_key,name,source,imported_at,digest,source_text,sensor,packets,rejected) VALUES(?,?,?,?,?,?,?,?,?)",params![key,name,source,at,digest,content,sensor,packets.len()as i64,number(&parsed,"rejected_lines",0)])?;
    let sid = tx.last_insert_rowid();
    let mut previous = 0i64;
    let mut day = 0i64;
    let mut origin = None;
    let mut identities: BTreeMap<String, String> = BTreeMap::new();
    let mut insert=tx.prepare("INSERT INTO capture_packets(session_id,seq,address,time_ms,elapsed_ms,rssi,name,raw,data) VALUES(?,?,?,?,?,?,?,?,?)")?;
    let mut legacy = tx.prepare(
        "INSERT OR IGNORE INTO ble_samples(fingerprint,address,imported_at,data) VALUES(?,?,?,?)",
    )?;
    for (seq, original) in packets.iter().enumerate() {
        let mut p = original.clone();
        p.as_object_mut().unwrap().remove("source_line");
        p["source"] = json!(source);
        p["session_id"] = json!(sid);
        let ms = number(&p, "time_ms", 0);
        if seq > 0 && previous - ms > 43_200_000 {
            day += 86_400_000;
        }
        previous = ms;
        let absolute = ms + day;
        let base = *origin.get_or_insert(absolute);
        let elapsed = absolute - base;
        p["elapsed_ms"] = json!(elapsed);
        insert.execute(params![
            sid,
            seq as i64,
            text(&p, "address"),
            ms,
            elapsed,
            number(&p, "rssi", -127),
            text(&p["advertisement"], "name"),
            text(&p, "raw"),
            p.to_string()
        ])?;
        legacy.execute(params![
            hash(&format!("{sid}:{seq}")),
            text(&p, "address"),
            at,
            p.to_string()
        ])?;
        let entity = format!("ble:{}", text(&p, "address"));
        let identity = json!({"name":p["advertisement"]["name"],"manufacturer_id":p["advertisement"]["manufacturer_id"],"service_uuids":p["advertisement"]["service_uuids"],"provenance":"advertised; unverified"});
        let signature = identity.to_string();
        if identities.get(&entity) != Some(&signature) {
            let seen: bool = tx.query_row(
                "SELECT EXISTS(SELECT 1 FROM identity_history WHERE entity=?)",
                [&entity],
                |q| q.get(0),
            )?;
            if !seen {
                alert(
                    &tx,
                    &entity,
                    "new_ble",
                    &entity,
                    "New BLE address in imported or sensor observations; address may be randomized",
                )?;
            }
            snapshot(&tx, &entity, &identity)?;
            identities.insert(entity, signature);
        }
    }
    drop(insert);
    drop(legacy);
    tx.commit()?;
    Ok(
        json!({"session_id":sid,"imported":packets.len(),"parsed":packets.len(),"duplicates":0,"rejected_lines":parsed["rejected_lines"],"limit_reached":parsed["limit_reached"],"decoder":parsed["decoder"]}),
    )
}
pub(super) fn sessions(c: &Connection) -> Result<Value> {
    Ok(json!(rows(c,"SELECT id,name,source,imported_at,digest,sensor,packets,rejected,(SELECT count(DISTINCT address) FROM capture_packets WHERE session_id=s.id) AS devices FROM capture_sessions s ORDER BY id DESC LIMIT 200",&[])?))
}
pub(super) fn packets(c: &Connection, r: &Value) -> Result<Value> {
    let sid = number(r, "session_id", 0);
    if sid > 0 {
        require_session(c, sid)?;
    }
    let cursor = number(r, "cursor", i64::MAX);
    let limit = number(r, "limit", 50).clamp(1, 100);
    let query = text(r, "q").chars().take(160).collect::<String>();
    let address = text(r, "address").to_uppercase();
    let low = number(r, "rssi_min", -127).clamp(-127, 20);
    let high = number(r, "rssi_max", 20).clamp(-127, 20);
    let pattern = format!(
        "%{}%",
        query
            .replace('!', "!!")
            .replace('%', "!%")
            .replace('_', "!_")
    );
    let mut list=rows(c,"SELECT id,session_id,seq,address,time_ms,elapsed_ms,rssi,name,raw,data FROM capture_packets WHERE (?=0 OR session_id=?) AND id<? AND (?='' OR address=?) AND rssi BETWEEN ? AND ? AND data LIKE ? ESCAPE '!' ORDER BY id DESC LIMIT ?",&[&sid,&sid,&cursor,&address,&address,&low,&high,&pattern,&(limit+1)])?;
    let more = list.len() > limit as usize;
    if more {
        list.pop();
    }
    let next = if more {
        list.last().map(|p| p["id"].clone()).unwrap_or(Value::Null)
    } else {
        Value::Null
    };
    for row in &mut list {
        parse_field(row, "data");
    }
    Ok(json!({"packets":list,"next_cursor":next,"limit":limit,"session_id":sid}))
}
pub(super) fn replay(c: &Connection, r: &Value) -> Result<Value> {
    let sid = number(r, "session_id", 0);
    if sid <= 0 {
        return Err(Failure::input("session_id").into());
    }
    require_session(c, sid)?;
    let address = text(r, "address").to_uppercase();
    let total: i64 = c.query_row(
        "SELECT count(*) FROM capture_packets WHERE session_id=? AND (?='' OR address=?)",
        params![sid, address, address],
        |r| r.get(0),
    )?;
    let points=rows(c,"SELECT id,address,time_ms,elapsed_ms,rssi,name FROM capture_packets WHERE session_id=? AND (?='' OR address=?) ORDER BY seq LIMIT 2000",&[&sid,&address,&address])?;
    Ok(
        json!({"points":points,"total":total,"truncated":total>2000,"time_basis":"Within-session sequence with >12-hour backward jumps treated as midnight; absolute date remains unknown"}),
    )
}
pub(super) fn compare(c: &Connection, r: &Value) -> Result<Value> {
    let a = number(r, "a", 0);
    let b = number(r, "b", 0);
    if a <= 0 || b <= 0 {
        return Err(Failure::input("a,b").into());
    }
    require_session(c, a)?;
    require_session(c, b)?;
    let stats = |sid| -> Result<Vec<Value>> {
        rows(c,"SELECT address,count(*) AS samples,round(avg(rssi),1) AS mean_rssi,min(rssi) AS min_rssi,max(rssi) AS max_rssi FROM capture_packets WHERE session_id=? GROUP BY address ORDER BY address LIMIT 1000",&[&sid])
    };
    let left = stats(a)?;
    let right = stats(b)?;
    let la: BTreeSet<_> = left.iter().filter_map(|r| r["address"].as_str()).collect();
    let ra: BTreeSet<_> = right.iter().filter_map(|r| r["address"].as_str()).collect();
    Ok(
        json!({"a":a,"b":b,"added_addresses":ra.difference(&la).collect::<Vec<_>>(),"missing_addresses":la.difference(&ra).collect::<Vec<_>>(),"shared_addresses":la.intersection(&ra).collect::<Vec<_>>(),"left":left,"right":right,"identity_note":"Address comparison only; rotating BLE addresses are not merged"}),
    )
}
