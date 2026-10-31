//! Observation graph and collection health queries.
use super::*;
pub(super) fn overview_for_network(c: &Connection, r: &Value) -> Result<Value> {
    sync(c)?;
    let active = active_network(c)?;
    let scope = r["network_id"].as_str().unwrap_or(&active);
    let contexts=rows(c,"SELECT n.*,(SELECT count(*) FROM targets WHERE network_id=n.id) AS devices FROM network_contexts n WHERE id=? OR EXISTS(SELECT 1 FROM targets WHERE network_id=n.id) ORDER BY created_at DESC LIMIT 100",&[&active])?;
    let network = rows(c, "SELECT * FROM network_contexts WHERE id=?", &[&scope])?
        .into_iter()
        .next()
        .ok_or_else(|| Failure::new("ATLAS_NOT_FOUND"))?;
    let targets = targets_for_network(c, scope)?;
    let mut nodes = vec![
        json!({"id":"local","label":"Atlas · this Pixel","kind":"sensor","status":"local","evidence":"Local network observer"}),
    ];
    let mut edges = Vec::new();
    for t in &targets {
        let id = format!("ip:{}", number(t, "id", 0));
        let desc = &t["identity"]["description"];
        let kind = if desc["modelName"] == "IPC" {
            "camera (device claim)"
        } else if t["latest"]["services"].as_array().is_some_and(|s| {
            s.iter()
                .any(|p| p["state"] == "open" && (p["port"] == 631 || p["port"] == 9100))
        }) {
            "printing service"
        } else {
            "IP device"
        };
        nodes.push(json!({"id":id,"label":t["label"],"host":t["host"],"kind":kind,"status":if number(t,"paused",0)==1{json!("paused")}else{t["latest"]["status"].clone()},"observed_at":t["observed_at"],"evidence":t}));
        edges.push(
            json!({"from":"local","to":id,"kind":if t["observed_at"].is_null(){"Configured target; no observations"}else{"TCP observation"},"observed_at":t["observed_at"]}),
        );
    }
    let ble=rows(c,"SELECT p.address,p.name,p.rssi,p.session_id,s.imported_at,s.sensor FROM capture_packets p JOIN (SELECT address,max(id) AS id FROM capture_packets GROUP BY address) x ON p.id=x.id JOIN capture_sessions s ON s.id=p.session_id ORDER BY p.id DESC LIMIT 256",&[])?;
    for p in &ble {
        let id = format!("ble:{}", text(p, "address"));
        let sensor = text(p, "sensor");
        let from = if sensor == "local" {
            "imports".to_string()
        } else {
            format!("sensor:{sensor}")
        };
        nodes.push(json!({"id":id,"label":if text(p,"name").is_empty(){text(p,"address")}else{text(p,"name")},"host":p["address"],"kind":"BLE observation","status":"imported","evidence":p}));
        edges.push(json!({"from":from,"to":id,"kind":"Imported BLE observation"}));
    }
    if ble.iter().any(|p| p["sensor"] == "local") {
        nodes.push(json!({"id":"imports","label":"Imported captures","kind":"sensor","status":"imported","evidence":"File imports, not a live radio"}));
    }
    for sensor in rows(
        c,
        "SELECT id,name,last_seen,enabled FROM sensors ORDER BY created_at LIMIT 32",
        &[],
    )? {
        let id = format!("sensor:{}", text(&sensor, "id"));
        nodes.push(json!({"id":id,"label":sensor["name"],"kind":"sensor","status":if sensor["enabled"]==1{"external"}else{"revoked"},"evidence":sensor}));
    }
    for remote in rows(c,"SELECT o.sensor,o.host,o.at,o.data FROM sensor_observations o JOIN (SELECT sensor,host,max(id) AS id FROM sensor_observations GROUP BY sensor,host) x ON o.id=x.id ORDER BY o.id DESC LIMIT 128",&[])?{
        let id=format!("remote:{}:{}",text(&remote,"sensor"),text(&remote,"host"));let data:Value=serde_json::from_str(text(&remote,"data"))?;
        nodes.push(json!({"id":id,"label":remote["host"],"host":remote["host"],"kind":"External IP observation","status":data["status"],"evidence":data,"observed_at":remote["at"]}));
        edges.push(json!({"from":format!("sensor:{}",text(&remote,"sensor")),"to":id,"kind":"Sensor-reported observation"}));
    }
    let alerts = rows(
        c,
        "SELECT * FROM local_alerts ORDER BY id DESC LIMIT 100",
        &[],
    )?;
    let rules = rows(c, "SELECT * FROM alert_rules WHERE id=1", &[])?;
    let counts=rows(c,"SELECT (SELECT count(*) FROM capture_sessions) AS sessions,(SELECT count(*) FROM capture_packets) AS packets,(SELECT count(*) FROM observations) AS observations,(SELECT count(*) FROM local_alerts WHERE acknowledged=0) AS unread_alerts",&[])?;
    Ok(
        json!({"network":network,"active_network_id":active,"network_contexts":contexts,"historical":scope!=active,"nodes":nodes,"edges":edges,"alerts":alerts,"rules":rules.first(),"counts":counts.first(),"note":"Edges represent observations; physical topology, SSID membership and BLE-to-IP identity are not inferred"}),
    )
}
pub(super) fn health(c: &Connection) -> Result<Value> {
    let db_size: i64 = c.query_row(
        "SELECT page_count*page_size FROM pragma_page_count(),pragma_page_size()",
        [],
        |r| r.get(0),
    )?;
    let journal: String = c.query_row("PRAGMA journal_mode", [], |r| r.get(0))?;
    let runs = rows(
        c,
        "SELECT * FROM collection_runs ORDER BY id DESC LIMIT 30",
        &[],
    )?;
    let stale:i64=c.query_row("SELECT count(*) FROM targets t WHERE paused=0 AND network_id=(SELECT active_id FROM network_state WHERE id=1) AND NOT EXISTS(SELECT 1 FROM observations o WHERE o.target_id=t.id AND julianday(o.at)>julianday('now','-30 minutes'))",[],|r|r.get(0))?;
    Ok(
        json!({"database_bytes":db_size,"journal_mode":journal,"stale_or_unobserved_targets":stale,"runs":runs,"gaps":rows(c,"SELECT * FROM collection_gaps ORDER BY id DESC LIMIT 30",&[])?,"gap_limit":30,"limits":{"capture_bytes":1000000,"packets_per_import":10000,"packet_page":100,"replay_points":2000,"map_local_targets":64,"map_ble_nodes":256,"map_external_nodes":128},"engine":"Rust + SQLite; ARM64 NEON capture decoding"}),
    )
}
