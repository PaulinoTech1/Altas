//! Descriptive local TCP timing analysis, with explicit 0.1 ms quantization.
use super::*;
use crate::kernels;
pub(super) fn sources(c: &Connection, r: &Value) -> Result<Value> {
    let active = active_network(c)?;
    let scope = r["network_id"].as_str().unwrap_or(&active);
    Ok(
        json!({"contexts":rows(c,"SELECT n.id,n.label,n.created_at,(SELECT count(*) FROM targets WHERE network_id=n.id) AS devices FROM network_contexts n WHERE n.id=? OR EXISTS(SELECT 1 FROM targets WHERE network_id=n.id) ORDER BY created_at DESC LIMIT 100",&[&active])?,"active_network_id":active,"targets":targets_for_network(c,scope)?,"sessions":sessions(c)?}),
    )
}
fn percentile(x: &[i16], q: f64) -> Option<f64> {
    if x.is_empty() {
        return None;
    }
    let index = ((x.len() as f64 * q).ceil() as usize).max(1) - 1;
    Some(x[index] as f64 / 10.0)
}
pub(super) fn analyze(c: &Connection, r: &Value) -> Result<Value> {
    let id = number(r, "target_id", 0);
    let port = number(r, "port", 80);
    let window = number(r, "window", 16);
    let threshold = number(r, "threshold_ms", 10);
    if id <= 0 {
        return Err(Failure::input("target_id").into());
    }
    if !(1..=65535).contains(&port) {
        return Err(Failure::input("port").into());
    }
    if !(4..=256).contains(&window) {
        return Err(Failure::input("window").into());
    }
    if !(1..=1000).contains(&threshold) {
        return Err(Failure::input("threshold_ms").into());
    }
    let mut target = rows(
        c,
        "SELECT id,host,label,network_id,notes FROM targets WHERE id=?",
        &[&id],
    )?;
    if target.is_empty() {
        return Err(Failure::new("ATLAS_NOT_FOUND").into());
    }
    let through = number(r, "through_id", i64::MAX);
    if through < 0 {
        return Err(Failure::input("through_id").into());
    }
    let total: i64 = c.query_row(
        "SELECT count(*) FROM observations WHERE target_id=? AND id<=?",
        params![id, through],
        |r| r.get(0),
    )?;
    // First bound observations by target/id, then extract just the selected service.
    let mut records=rows(c,"SELECT id,at,json_extract(data,'$.services') AS services FROM observations WHERE target_id=? AND id<=? ORDER BY id DESC LIMIT 10000",&[&id,&through])?;
    records.reverse();
    let mut samples = Vec::new();
    let mut times = Vec::new();
    let mut ids = Vec::new();
    let mut skipped = 0;
    let mut missing = 0;
    let mut out_of_order = 0;
    let mut origin: Option<f64> = None;
    for record in &records {
        let services: Value = serde_json::from_str(text(record, "services")).unwrap_or(json!([]));
        let selected = services.as_array().and_then(|a| {
            a.iter()
                .find(|s| number(s, "port", 0) == port && s["state"] == "open")
        });
        let Some(value) = selected.and_then(|s| s["latency_ms"].as_f64()) else {
            missing += 1;
            continue;
        };
        if !value.is_finite() || value < 0.0 || value > 3276.7 {
            skipped += 1;
            continue;
        }
        let julian: Option<f64> =
            c.query_row("SELECT julianday(?)", [text(record, "at")], |r| r.get(0))?;
        let Some(julian) = julian else {
            skipped += 1;
            continue;
        };
        let base = *origin.get_or_insert(julian);
        let ms = ((julian - base) * 86_400_000.0).round() as i64;
        if times.last().is_some_and(|&prev| ms < prev) {
            out_of_order += 1;
        }
        samples.push((value * 10.0).round() as i16);
        times.push(ms);
        ids.push(number(record, "id", 0));
    }
    let n = samples.len();
    let m = kernels::moments(&samples);
    let mut sorted = samples.clone();
    sorted.sort_unstable();
    let mean = if n > 0 {
        Some(m.sum as f64 / n as f64 / 10.0)
    } else {
        None
    };
    let variance = kernels::sample_variance(m, n);
    let mut shifts = super::research::shifts(&samples, &times, window as usize, threshold * 10);
    for s in &mut shifts {
        let old = s.clone();
        *s = json!({"sample_index":old["sample_index"],"elapsed_ms":old["elapsed_ms"],"delta_ms":old["delta_db"].as_f64().map(|v|v/10.0),"before_mean_ms":old["before_mean_dbm"].as_f64().map(|v|v/10.0),"after_mean_ms":old["after_mean_dbm"].as_f64().map(|v|v/10.0)});
    }
    let mut quality = Vec::new();
    if n < 8 {
        quality.push("Fewer than eight accepted timing samples");
    }
    if n < window as usize * 2 {
        quality.push("Insufficient data for the configured two-window detector");
    }
    if skipped > 0 {
        quality.push(
            "Invalid timestamps or latencies outside 0..3276.7 ms were excluded, never clipped",
        );
    }
    if out_of_order > 0 {
        quality.push("Stored timestamps move backwards");
    }
    if total > 10000 {
        quality.push("Only the latest 10,000 observations are considered");
    }
    let stride = n.div_ceil(500).max(1);
    let plot:Vec<_>=samples.iter().zip(&times).enumerate().step_by(stride).map(|(i,(&v,&t))|json!({"sample_index":i,"observation_id":ids[i],"elapsed_ms":t,"latency_ms":v as f64/10.0})).collect();
    Ok(
        json!({"method_version":"atlas-research/1","dataset":"network","build":kernels::manifest(),"backend":kernels::BACKEND,"target":target.remove(0),"port":port,"settings":{"window_samples":window,"threshold_ms":threshold,"quantile":"nearest rank","latency_quantization_ms":0.1,"range_ms":[0,3276.7],"variance":"sample variance (n-1), exact i128 numerator"},"provenance":{"evidence_sha256":hash(&serde_json::to_string(&records)?),"total_target_observations":total,"loaded_observations":records.len(),"through_observation_id":records.last().map(|r|r["id"].clone()),"first_included_observation_id":ids.first(),"last_included_observation_id":ids.last()},"signal":{"samples":n,"mean_ms":mean,"sample_stddev_ms":variance.map(|v|v.sqrt()/10.0),"min_ms":if n>0{Some(m.min as f64/10.0)}else{None},"max_ms":if n>0{Some(m.max as f64/10.0)}else{None},"median_ms":percentile(&sorted,0.5),"p95_ms":percentile(&sorted,0.95),"mean_absolute_successive_change_ms":if n>1{Some(m.absolute_delta as f64/(n-1)as f64/10.0)}else{None}},"missing_selected_service_or_latency":missing,"excluded_samples":skipped,"candidate_timing_shifts":shifts,"plot":plot,"quality":quality,"interpretation":["TCP connect timing is not radio signal strength, throughput, one-way latency or physical distance","Only successful connections with recorded timing are analyzed; this selection excludes failed attempts","Changes may reflect phone scheduling, device load, routing, power state or measurement noise","Exploratory threshold candidates do not establish statistical significance or causation","Recorded timestamps and 0.1 ms quantization limit temporal precision"]}),
    )
}
pub(super) fn report(c: &Connection, r: &Value) -> Result<Value> {
    let result = analyze(c, r)?;
    let e = super::reports::escape;
    let plot = result["plot"].as_array().unwrap();
    let mut chart = String::new();
    if let (Some(first), Some(last)) = (plot.first(), plot.last()) {
        let start = number(first, "elapsed_ms", 0) as f64;
        let span = (number(last, "elapsed_ms", 0) as f64 - start).max(1.0);
        let max = plot
            .iter()
            .filter_map(|p| p["latency_ms"].as_f64())
            .fold(1.0f64, f64::max);
        let coords = plot
            .iter()
            .map(|p| {
                format!(
                    "{:.1},{:.1}",
                    40.0 + (number(p, "elapsed_ms", 0) as f64 - start) / span * 720.0,
                    180.0 - p["latency_ms"].as_f64().unwrap_or(0.0) / max * 160.0
                )
            })
            .collect::<Vec<_>>()
            .join(" ");
        chart=format!("<svg viewBox='0 0 800 210' role='img' aria-label='TCP connect latency over elapsed time'><polyline points='{coords}' fill='none' stroke='#287a4c' stroke-width='2'/><text x='40' y='205'>Elapsed recorded time · TCP connect latency (ms)</text></svg>");
    }
    let html=format!("<!doctype html><html lang='en'><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'><title>Atlas timing research</title><style>body{{font:16px/1.6 system-ui;max-width:1000px;margin:32px auto;padding:16px;background:#f8faf6;color:#14271b}}svg{{width:100%;background:white}}pre{{white-space:pre-wrap;overflow-wrap:anywhere;padding:16px;border:1px solid #bacaba}}</style><h1>Atlas TCP timing research</h1><p>Descriptive observations; no causal or distance conclusions.</p>{}<pre>{}</pre></html>",chart,e(&serde_json::to_string_pretty(&result)?));
    Ok(json!({"html":html,"filename":format!("atlas-timing-{}.html",number(r,"target_id",0))}))
}
