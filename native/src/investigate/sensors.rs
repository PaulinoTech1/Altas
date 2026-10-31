//! Credential lifecycle and atomic sensor ingestion.
use super::*;
pub(super) fn random() -> Result<String> {
    let mut bytes = [0u8; 32];
    std::fs::File::open("/dev/urandom")?.read_exact(&mut bytes)?;
    Ok(bytes.iter().map(|b| format!("{b:02x}")).collect())
}
pub(super) fn sensor_create(c: &Connection, r: &Value) -> Result<Value> {
    let count: i64 = c.query_row("SELECT count(*) FROM sensors", [], |r| r.get(0))?;
    if count >= 32 {
        return Err(Failure::new("ATLAS_LIMIT_REACHED").into());
    }
    let name: String = text(r, "name").trim().chars().take(100).collect();
    if name.is_empty() {
        return Err(Failure::input("name").into());
    }
    let token = random()?;
    let id = hash(&token)[..12].to_string();
    let inserted=c.execute(
        "INSERT INTO sensors(id,name,token_hash,created_at) SELECT ?,?,?,? WHERE (SELECT count(*) FROM sensors)<32",
        params![id,name,hash(&token),timestamp(c)?],
    )?;
    if inserted == 0 {
        return Err(Failure::new("ATLAS_LIMIT_REACHED").into());
    }
    Ok(
        json!({"id":id,"name":name,"token":token,"note":"Token shown once. Keep localhost binding; use an authenticated tunnel for remote sensors."}),
    )
}
pub(super) fn ingest(c: &mut Connection, r: &Value) -> Result<Value> {
    let token = text(r, "token");
    if token.len() != 64 {
        return Err(Failure::new("ATLAS_AUTH_INVALID").into());
    }
    let sensor: Option<String> = c
        .query_row(
            "SELECT id FROM sensors WHERE token_hash=? AND enabled=1",
            [hash(token)],
            |r| r.get(0),
        )
        .optional()?;
    let sensor = sensor.ok_or_else(|| Failure::new("ATLAS_AUTH_INVALID"))?;
    let result = if !text(r, "text").is_empty() {
        import(c, r, &sensor)?
    } else {
        let observations = r["observations"]
            .as_array()
            .ok_or_else(|| Failure::input("observations"))?;
        if observations.is_empty() || observations.len() > 64 {
            return Err(Failure::input("observations").into());
        }
        let tx = c.transaction()?;
        let batch = evidence::imported_batch(
            &tx,
            "sensor_ip",
            &sensor,
            json!({"max_observations":64,"max_services":32,"max_observation_bytes":16384,"observations":observations.len()}),
        )?;
        for o in observations {
            let host = text(o, "host");
            host.parse::<std::net::IpAddr>()?;
            let status = text(o, "status");
            if !["responding", "no_response", "unknown"].contains(&status) {
                return Err(Failure::input("status").into());
            }
            let services = o["services"]
                .as_array()
                .ok_or_else(|| Failure::input("services"))?;
            if services.len() > 32 {
                return Err(Failure::input("services").into());
            }
            for s in services {
                if !(1..=65535).contains(&number(s, "port", 0))
                    || !["open", "unreachable"].contains(&text(s, "state"))
                {
                    return Err(Failure::input("service.port,state").into());
                }
            }
            let encoded = o.to_string();
            if encoded.len() > 16384 {
                return Err(Failure::input("observation_size").into());
            }
            tx.execute(
                "INSERT INTO sensor_observations(sensor,host,at,data,batch_id) VALUES(?,?,?,?,?)",
                params![sensor, host, timestamp(&tx)?, encoded, batch],
            )?;
        }
        tx.commit()?;
        json!({"accepted":observations.len(),"sensor":sensor})
    };
    c.execute(
        "UPDATE sensors SET last_seen=? WHERE id=?",
        params![timestamp(c)?, sensor],
    )?;
    Ok(result)
}
