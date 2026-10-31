//! Offline descriptive analysis of one bounded capture session. No active probes.
use super::*;
use crate::kernels;
use std::collections::{BTreeMap, BTreeSet};
const VERSION: &str = "atlas-research/1";
fn quantile(bins: &[u64; 148], n: usize, q: f64) -> Option<f64> {
    if n == 0 {
        return None;
    }
    let rank = ((n as f64 * q).ceil() as u64).max(1);
    let mut count = 0;
    for (i, &v) in bins.iter().enumerate() {
        count += v;
        if count >= rank {
            return Some(i as f64 - 127.0);
        }
    }
    None
}
fn signal(samples: &[i16]) -> Value {
    let n = samples.len();
    let m = kernels::moments(samples);
    let bins = kernels::histogram(samples);
    let mean = if n > 0 {
        Some(m.sum as f64 / n as f64)
    } else {
        None
    };
    let variance = kernels::sample_variance(m, n);
    let entropy = if n > 0 {
        Some(
            bins.iter()
                .filter(|&&v| v > 0)
                .map(|&v| {
                    let p = v as f64 / n as f64;
                    -p * p.log2()
                })
                .sum::<f64>(),
        )
    } else {
        None
    };
    let lag = if n > 2 {
        let a = &samples[..n - 1];
        let b = &samples[1..];
        let ma = kernels::moments(a);
        let mb = kernels::moments(b);
        let count = a.len() as i128;
        let va = count * ma.squares as i128 - (ma.sum as i128).pow(2);
        let vb = count * mb.squares as i128 - (mb.sum as i128).pow(2);
        let covariance = count * kernels::dot(a, b) as i128 - ma.sum as i128 * mb.sum as i128;
        if va > 0 && vb > 0 {
            Some((covariance as f64 / (va as f64 * vb as f64).sqrt()).clamp(-1.0, 1.0))
        } else {
            None
        }
    } else {
        None
    };
    json!({"samples":n,"mean_dbm":mean,"sample_stddev_db":variance.map(f64::sqrt),"min_dbm":if n>0{json!(m.min)}else{Value::Null},"max_dbm":if n>0{json!(m.max)}else{Value::Null},"p05_dbm":quantile(&bins,n,0.05),"median_dbm":quantile(&bins,n,0.5),"p95_dbm":quantile(&bins,n,0.95),"mean_absolute_successive_change_db":if n>1{Some(m.absolute_delta as f64/(n-1)as f64)}else{None},"lag1_packet_correlation":lag,"rssi_entropy_bits":entropy,"histogram":bins.iter().enumerate().filter(|(_,v)|**v>0).map(|(i,&v)|json!({"dbm":i as i64-127,"count":v})).collect::<Vec<_>>()})
}
pub(super) fn shifts(samples: &[i16], times: &[i64], window: usize, threshold: i64) -> Vec<Value> {
    if samples.len() < window * 2 {
        return Vec::new();
    }
    let sums = kernels::window_sums(samples, window);
    let mut proposals = Vec::new();
    for i in window..=samples.len() - window {
        let before = sums[i - window] as f64 / window as f64;
        let after = sums[i] as f64 / window as f64;
        let delta = after - before;
        if delta.abs() >= threshold as f64 {
            proposals.push((i, delta, before, after));
        }
    }
    proposals.sort_by(|a, b| b.1.abs().total_cmp(&a.1.abs()));
    let mut selected: Vec<(usize, f64, f64, f64)> = Vec::new();
    for p in proposals {
        if selected.iter().all(|s| s.0.abs_diff(p.0) >= window) {
            selected.push(p);
            if selected.len() == 20 {
                break;
            }
        }
    }
    selected.sort_by_key(|p| p.0);
    selected.into_iter().map(|(i,delta,before,after)|json!({"sample_index":i,"elapsed_ms":times[i],"delta_db":delta,"before_mean_dbm":before,"after_mean_dbm":after})).collect()
}
fn device(address: &str, rows: &[Value], window: usize, threshold: i64) -> Result<Value> {
    let samples: Vec<i16> = rows
        .iter()
        .map(|p| number(p, "rssi", -127) as i16)
        .collect();
    let times: Vec<i64> = rows.iter().map(|p| number(p, "elapsed_ms", 0)).collect();
    let mut quality = Vec::new();
    if rows.len() < 8 {
        quality.push("Fewer than eight samples; descriptive estimates are unstable");
    }
    let mut gaps: Vec<i64> = times
        .windows(2)
        .filter_map(|p| if p[1] > p[0] { Some(p[1] - p[0]) } else { None })
        .collect();
    gaps.sort_unstable();
    let median_gap = gaps.get(gaps.len().div_ceil(2).saturating_sub(1)).copied();
    let duplicate_times = times.windows(2).filter(|p| p[1] == p[0]).count();
    let reversed_times = times.windows(2).filter(|p| p[1] < p[0]).count();
    if duplicate_times > 0 {
        quality.push("Repeated source timestamps; timing resolution may be insufficient");
    }
    if reversed_times > 0 {
        quality.push("Source time moves backwards inside this address stream");
    }
    if samples.iter().all(|v| *v == samples[0]) {
        quality.push("Constant RSSI; variance and correlation may be undefined");
    }
    if rows.len() < window * 2 {
        quality.push("Not enough samples for the configured two-window change detector");
    }
    let mut previous: Option<Vec<u8>> = None;
    let mut unique = BTreeSet::new();
    let mut bits = 0u64;
    let mut compared_bits = 0u64;
    let mut pairs = 0;
    let mut length_changes = 0;
    let mut unchanged = 0;
    for row in rows {
        let raw = text(row, "raw");
        unique.insert(raw.to_ascii_lowercase());
        let bytes = crate::capture::hex(raw).ok_or_else(|| Failure::input("stored_raw"))?;
        if bytes.len() > 1650 {
            return Err(Failure::input("stored_raw").into());
        }
        if let Some(prev) = previous {
            if prev.len() == bytes.len() {
                let d = kernels::hamming(&prev, &bytes);
                bits += d;
                compared_bits += (bytes.len() * 8) as u64;
                pairs += 1;
                if d == 0 {
                    unchanged += 1;
                }
            } else {
                length_changes += 1;
            }
        }
        previous = Some(bytes);
    }
    let candidates = shifts(&samples, &times, window, threshold);
    let large_gaps = median_gap
        .map(|median| {
            gaps.iter()
                .filter(|&&g| g > median.saturating_mul(3).max(1000))
                .count()
        })
        .unwrap_or(0);
    Ok(
        json!({"address":address,"signal":signal(&samples),"timing":{"positive_intervals":gaps.len(),"median_interval_ms":median_gap,"p95_interval_ms":gaps.get((gaps.len()*95).div_ceil(100).saturating_sub(1)).copied(),"duplicate_timestamp_pairs":duplicate_times,"backward_timestamp_pairs":reversed_times,"large_observed_gaps":large_gaps},"payload":{"unique_payloads":unique.len(),"comparable_adjacent_pairs":pairs,"unchanged_adjacent_pairs":unchanged,"length_changes":length_changes,"differing_bits":bits,"compared_bits":compared_bits,"bit_change_fraction":if compared_bits>0{Some(bits as f64/compared_bits as f64)}else{None}},"candidate_signal_shifts":candidates,"quality":quality,"plot":rows.iter().step_by(rows.len().div_ceil(500).max(1)).take(500).map(|r|json!({"elapsed_ms":r["elapsed_ms"],"rssi":r["rssi"]})).collect::<Vec<_>>()}),
    )
}
pub(super) fn analyze(c: &Connection, r: &Value) -> Result<Value> {
    let sid = number(r, "session_id", 0);
    if sid <= 0 {
        return Err(Failure::input("session_id").into());
    }
    require_session(c, sid)?;
    let window = number(r, "window", 16);
    let threshold = number(r, "threshold_db", 6);
    if !(4..=256).contains(&window) {
        return Err(Failure::input("window").into());
    }
    if !(1..=60).contains(&threshold) {
        return Err(Failure::input("threshold_db").into());
    }
    let address = text(r, "address").to_uppercase();
    let total: i64 = c.query_row(
        "SELECT count(*) FROM capture_packets WHERE session_id=? AND (?='' OR address=?)",
        params![sid, address, address],
        |r| r.get(0),
    )?;
    let packet_rows=rows(c,"SELECT address,elapsed_ms,rssi,raw FROM capture_packets WHERE session_id=? AND (?='' OR address=?) ORDER BY seq LIMIT 10000",&[&sid,&address,&address])?;
    let mut groups: BTreeMap<String, Vec<Value>> = BTreeMap::new();
    let mut invalid_rssi = 0;
    for row in packet_rows {
        if !(-127..=20).contains(&number(&row, "rssi", -1000)) {
            invalid_rssi += 1;
            continue;
        }
        groups
            .entry(text(&row, "address").to_owned())
            .or_default()
            .push(row);
    }
    let address_count = groups.len();
    let mut devices = Vec::new();
    for (a, samples) in groups.iter().take(64) {
        devices.push(device(a, samples, window as usize, threshold)?);
    }
    let session = rows(
        c,
        "SELECT id,batch_id,name,source,imported_at,digest,sensor FROM capture_sessions WHERE id=?",
        &[&sid],
    )?
    .remove(0);
    Ok(
        json!({"method_version":VERSION,"dataset":"ble","collection_contract":if let Some(batch)=session["batch_id"].as_i64(){evidence::contract(c,batch)?}else{json!(null)},"build":kernels::manifest(),"backend":kernels::BACKEND,"session":session,"settings":{"window_samples":window,"threshold_db":threshold,"quantile":"nearest rank","variance":"sample variance (n-1), exact i128 numerator","change_detector":"Adjacent fixed windows; absolute mean difference threshold; greedy peaks separated by at least one window; at most 20 candidates"},"limits":{"packet_limit":10000,"device_limit":64,"plot_points_per_device":500},"total_matching_packets":total,"eligible_packets":groups.values().map(Vec::len).sum::<usize>(),"analyzed_packets":groups.values().take(64).map(Vec::len).sum::<usize>(),"invalid_rssi_skipped":invalid_rssi,"devices_in_sample":address_count,"device_limit_reached":address_count>64,"packet_limit_reached":total>10000,"devices":devices,"interpretation":["Descriptive exploratory results, not hypothesis-test p-values or confidence intervals","RSSI is not a calibrated distance or location measurement","Lag one is measured in packet order, not a fixed time interval","Payload bit changes do not establish device identity or cryptographic randomness","Observed timing gaps are not proof of radio packet loss","Source clock dates remain unknown; midnight handling follows capture import heuristics"]}),
    )
}
pub(super) fn report(c: &Connection, r: &Value) -> Result<Value> {
    if text(r, "dataset") == "network" {
        return super::research_network::report(c, r);
    }
    let result = analyze(c, r)?;
    let e = super::reports::escape;
    let mut sections = String::new();
    for d in result["devices"].as_array().unwrap() {
        let signal = &d["signal"];
        let bins = signal["histogram"].as_array().unwrap();
        let max = bins
            .iter()
            .map(|b| number(b, "count", 0))
            .max()
            .unwrap_or(1)
            .max(1) as f64;
        let bars = bins
            .iter()
            .map(|b| {
                let x = 40.0 + (number(b, "dbm", -127) + 127) as f64 * 4.8;
                let h = number(b, "count", 0) as f64 / max * 140.0;
                format!(
                    "<rect x='{x:.1}' y='{:.1}' width='4' height='{h:.1}' fill='#287a4c'/>",
                    170.0 - h
                )
            })
            .collect::<String>();
        sections.push_str(&format!("<section><h2>{}</h2><p>{} samples · mean {} dBm · sample standard deviation {} dB</p><svg viewBox='0 0 800 210' role='img' aria-label='Observed RSSI histogram'>{}<path d='M40 20V170H755' fill='none' stroke='#777'/><text x='40' y='198'>−127 dBm</text><text x='710' y='198'>20 dBm</text></svg><pre>{}</pre></section>",e(text(d,"address")),signal["samples"],signal["mean_dbm"],signal["sample_stddev_db"],bars,e(&serde_json::to_string_pretty(d)?)));
    }
    let html=format!("<!doctype html><html lang='en'><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'><title>Atlas research notebook</title><style>body{{font:16px/1.6 system-ui;max-width:1000px;margin:32px auto;padding:16px;color:#14271b;background:#f8faf6}}svg{{width:100%;background:white}}pre{{white-space:pre-wrap;overflow-wrap:anywhere;padding:16px;border:1px solid #bacaba}}section{{margin:32px 0}}</style><h1>Atlas research notebook</h1><p>{} · {}</p><p>Exploratory descriptive analysis. No location, identity, packet-loss or causal conclusions are established.</p><h2>Provenance and method</h2><pre>{}</pre>{}</html>",VERSION,e(text(&result,"backend")),e(&serde_json::to_string_pretty(&json!({"session":result["session"],"settings":result["settings"],"build":result["build"],"interpretation":result["interpretation"]}))?),sections);
    Ok(
        json!({"html":html,"filename":format!("atlas-research-{sid}.html",sid=number(r,"session_id",0))}),
    )
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn exact_descriptive_statistics() {
        let s = signal(&[-60, -50, -40]);
        assert_eq!(s["mean_dbm"], -50.0);
        assert_eq!(s["sample_stddev_db"], 10.0);
        assert_eq!(s["median_dbm"], -50.0);
        assert_eq!(s["mean_absolute_successive_change_db"], 10.0);
        assert!(signal(&[-50])["sample_stddev_db"].is_null());
    }
    #[test]
    fn step_change_localization() {
        let mut x = vec![-80i16; 32];
        x.extend(vec![-40; 32]);
        let times: Vec<_> = (0..64).collect();
        let c = shifts(&x, &times, 8, 20);
        assert!(c
            .iter()
            .any(|v| v["sample_index"] == 32 && v["delta_db"] == 40.0));
        assert!(shifts(&[-50; 64], &times, 8, 6).is_empty());
    }
    #[test]
    fn payload_and_timing_quality() {
        let rows = vec![
            json!({"rssi":-50,"elapsed_ms":0,"raw":"00"}),
            json!({"rssi":-50,"elapsed_ms":0,"raw":"ff"}),
            json!({"rssi":-50,"elapsed_ms":-1,"raw":"ffff"}),
        ];
        let d = device("fixture", &rows, 4, 6).unwrap();
        assert_eq!(d["payload"]["differing_bits"], 8);
        assert_eq!(d["payload"]["length_changes"], 1);
        assert_eq!(d["timing"]["backward_timestamp_pairs"], 1);
        assert!(d["signal"]["lag1_packet_correlation"].is_null());
    }
}
