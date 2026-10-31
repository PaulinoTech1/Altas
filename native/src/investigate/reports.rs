//! Self-contained escaped evidence reports.
use super::*;
pub(super) fn escape(s: &str) -> String {
    s.replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
        .replace('"', "&quot;")
        .replace('\'', "&#39;")
}
pub(super) fn report(c: &Connection, r: &Value) -> Result<Value> {
    let sid = number(r, "session_id", 0);
    let entity = text(r, "entity");
    let mut graph = String::new();
    let mut evidence = if sid > 0 {
        let s=rows(c,"SELECT id,name,source,imported_at,digest,sensor,packets,rejected FROM capture_sessions WHERE id=?",&[&sid])?;
        if s.is_empty() {
            return Err(Failure::new("ATLAS_NOT_FOUND").into());
        }
        let replay = replay(c, &json!({"session_id":sid}))?;
        let points = replay["points"].as_array().unwrap();
        if let (Some(first), Some(last)) = (points.first(), points.last()) {
            let start = number(first, "elapsed_ms", 0) as f64;
            let span = (number(last, "elapsed_ms", 0) as f64 - start).max(1.0);
            let coords: Vec<_> = points
                .iter()
                .map(|p| {
                    format!(
                        "{:.1},{:.1}",
                        40.0 + 700.0 * (number(p, "elapsed_ms", 0) as f64 - start) / span,
                        180.0 - (number(p, "rssi", -127) + 127) as f64
                    )
                })
                .collect();
            graph=format!("<svg viewBox='0 0 800 220' role='img' aria-label='Capture RSSI plot'><path d='M40 20V180H760' fill='none' stroke='#777'/><polyline points='{}' fill='none' stroke='#276b45' stroke-width='2'/><text x='40' y='210'>Elapsed capture time · first 2,000 packets · RSSI dBm</text></svg>",coords.join(" "));
        }
        json!({"session":s,"statistics":rows(c,"SELECT address,count(*) AS samples,min(rssi) AS min_rssi,max(rssi) AS max_rssi,round(avg(rssi),1) AS mean_rssi FROM capture_packets WHERE session_id=? GROUP BY address ORDER BY address LIMIT 1000",&[&sid])?,"replay_limit":2000,"replay_truncated":replay["truncated"],"research":research::analyze(c,&json!({"session_id":sid}))?})
    } else if !entity.is_empty() {
        let details = detail(c, r)?;
        let target_scope = c
            .query_row(
                "SELECT network_id FROM targets WHERE id=?",
                [entity
                    .strip_prefix("ip:")
                    .and_then(|s| s.parse::<i64>().ok())
                    .unwrap_or(0)],
                |r| r.get::<_, String>(0),
            )
            .optional()?
            .unwrap_or_default();
        let target = targets_for_network(c, &target_scope)?
            .into_iter()
            .find(|t| format!("ip:{}", number(t, "id", 0)) == entity);
        json!({"target":target,"timeline":details})
    } else {
        json!({"inventory":targets(c)?,"health":health(c)?})
    };
    evidence["research_build"] = crate::kernels::manifest();
    let html=format!("<!doctype html><html lang='en'><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'><title>Atlas investigation report</title><style>body{{font:16px/1.6 system-ui;max-width:1000px;margin:32px auto;padding:16px;background:#f8faf6;color:#15271c}}pre{{white-space:pre-wrap;overflow-wrap:anywhere;border:1px solid #bacaba;padding:16px}}svg{{width:100%;background:white}}h1{{letter-spacing:-.04em}}</style><h1>Atlas investigation report</h1><p>Generated {} · selected evidence, bounded to documented query limits.</p><p>Device names and models are self-reported. Observation edges do not establish physical topology or SSID membership. BLE addresses may rotate. Capture clock dates may be unknown.</p>{}<h2>Recorded evidence</h2><pre>{}</pre></html>",escape(&timestamp(c)?),graph,escape(&serde_json::to_string_pretty(&evidence)?));
    Ok(
        json!({"html":html,"filename":if sid>0{format!("atlas-session-{sid}.html")}else{"atlas-investigation.html".to_string()}}),
    )
}
