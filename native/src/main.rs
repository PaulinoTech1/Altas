use serde_json::{json, Value};
use std::io::{self, Read};
#[cfg(target_arch = "aarch64")]
std::arch::global_asm!(include_str!("hex.S"));
#[cfg(target_arch = "aarch64")]
extern "C" { fn atlas_hex_decode(input: *const u8, len: usize, output: *mut u8) -> isize; }

fn reference_hex(s: &[u8]) -> Option<Vec<u8>> {
    if s.len()%2 != 0 { return None; }
    fn nibble(c:u8)->Option<u8>{ match c { b'0'..=b'9'=>Some(c-b'0'), b'a'..=b'f'=>Some(c-b'a'+10), b'A'..=b'F'=>Some(c-b'A'+10), _=>None } }
    s.chunks_exact(2).map(|p| Some(nibble(p[0])?*16+nibble(p[1])?)).collect()
}
fn hex(s:&str)->Option<Vec<u8>> {
    #[cfg(target_arch="aarch64")]
    {
        let mut out=vec![0;s.len()/2];
        // SAFETY: input is valid for len bytes, output for len/2 bytes.
        // Assembly rejects odd lengths before reading and only writes complete pairs.
        let n=unsafe{atlas_hex_decode(s.as_ptr(),s.len(),out.as_mut_ptr())};
        if n<0 {None} else {Some(out)}
    }
    #[cfg(not(target_arch="aarch64"))]
    {reference_hex(s.as_bytes())}
}
fn encode(bytes:&[u8])->String {
    const DIGITS:&[u8;16]=b"0123456789abcdef";
    let mut out=String::with_capacity(bytes.len()*2);
    for &b in bytes {out.push(DIGITS[(b>>4)as usize]as char);out.push(DIGITS[(b&15)as usize]as char);} out
}
fn advertisement(bytes:&[u8])->Value {
    let mut result=json!({"records":[]});let mut offset=0;
    while offset<bytes.len(){
        let len=bytes[offset] as usize;offset+=1;if len==0 {break;}
        if offset+len>bytes.len(){result["warning"]=json!("Truncated advertisement record");break;}
        let kind=bytes[offset];let value=&bytes[offset+1..offset+len];offset+=len;
        result["records"].as_array_mut().unwrap().push(json!({"type":kind,"hex":encode(value)}));
        match kind {
            8|9=> {if kind==9 || result.get("name").is_none(){result["name"]=json!(String::from_utf8_lossy(value));}},
            2|3|4|5|6|7=>{
                let size=if kind<=3 {2}else if kind<=5 {4}else{16};
                if value.len()%size!=0 {result["warning"]=json!("Incomplete service UUID");}
                if result.get("service_uuids").is_none(){result["service_uuids"]=json!([]);}
                for chunk in value.chunks_exact(size){let rev:Vec<_>=chunk.iter().rev().copied().collect();result["service_uuids"].as_array_mut().unwrap().push(json!(encode(&rev)));}
            },
            255 if value.len()>=2=> {result["manufacturer_id"]=json!(u16::from_le_bytes([value[0],value[1]]));result["manufacturer_data"]=json!(encode(&value[2..]));},
            10 if value.len()==1=>result["tx_power_dbm"]=json!(value[0] as i8),
            1 if value.len()==1=>result["flags"]=json!(value[0]),
            _=>{}
        }
    } result
}
fn time_ms(s:&str)->Option<u64>{
    let mut fields=s.split(':');let h=fields.next()?.parse::<u64>().ok()?;let m=fields.next()?.parse::<u64>().ok()?;
    let tail=fields.next()?;if fields.next().is_some(){return None;}
    let (sec,frac)=tail.split_once('.').unwrap_or((tail,""));let sec=sec.parse::<u64>().ok()?;
    if h>23||m>59||sec>59||frac.len()>9||!frac.bytes().all(|b|b.is_ascii_digit()){return None;}
    let padded=format!("{frac:0<3}");let ms=padded[..3].parse::<u64>().ok()?;
    Some((h*3600+m*60+sec)*1000+ms)
}
fn address(s:&str)->bool {s.len()==17&&s.split(':').count()==6&&s.split(':').all(|p|p.len()==2&&p.bytes().all(|b|b.is_ascii_hexdigit()))}
fn parse(text:&str)->Value {
    let mut records=Vec::new();let mut rejected=0;let mut truncated=false;
    for line in text.lines(){
        let fields:Vec<_>=line.split_whitespace().take(64).collect();let mut parsed=None;
        for w in fields.windows(4){
            let Some(ms)=time_ms(w[0]) else{continue};if !address(w[1]){continue;}
            let Ok(rssi)=w[2].parse::<i16>() else {continue};if !(-127..=20).contains(&rssi)||w[3].len()>3300{continue;}
            let Some(bytes)=hex(w[3]) else{continue};if bytes.is_empty(){continue;}
            parsed=Some(json!({"time":w[0],"time_ms":ms,"address":w[1].to_ascii_uppercase(),"rssi":rssi,"raw":w[3],"advertisement":advertisement(&bytes),"source_line":line}));break;
        }
        if let Some(record)=parsed {records.push(record);}else if !line.trim().is_empty(){rejected+=1;}
        if records.len()>=10000{truncated=true;break;}
    }
    json!({"records":records,"rejected_lines":rejected,"limit_reached":truncated,"decoder":if cfg!(target_arch="aarch64"){"ARM64 assembly"}else{"Rust portable"}})
}
fn analyze(records:&[Value])->Value {
    let mut points:Vec<Value>=records.iter().filter_map(|r|Some(json!({"time_ms":r.get("time_ms")?.as_u64()?,"rssi":r.get("rssi")?.as_i64()?,"address":r.get("address")?.as_str()?,"name":r["advertisement"]["name"],"raw":r["raw"]}))).collect();
    points.sort_by_key(|p|p["time_ms"].as_u64().unwrap_or(0));
    let sum:i64=points.iter().filter_map(|p|p["rssi"].as_i64()).sum();
    json!({"count":points.len(),"mean_rssi":if points.is_empty(){Value::Null}else{json!(sum as f64/points.len()as f64)},"points":points,"time_basis":"Source clock time; dates unavailable, midnight/multiple-day ordering is ambiguous"})
}
fn run()->Result<(),String>{
    let mut input=String::new();io::stdin().take(1_100_001).read_to_string(&mut input).map_err(|e|e.to_string())?;
    if input.len()>1_100_000{return Err("Input exceeds limit".into());}
    let request:Value=serde_json::from_str(&input).map_err(|e|e.to_string())?;
    let result=if request["mode"]=="analyze" {analyze(request["records"].as_array().ok_or("records required")?)} else {let text=request["text"].as_str().ok_or("text required")?;if text.len()>1_000_000{return Err("Text exceeds 1 MB".into());}parse(text)};
    println!("{result}");Ok(())
}
fn main(){
    if std::env::args().any(|a|a=="--benchmark") {
        let input="02010605095465737403030d18".repeat(64);
        let rounds=20000;
        let start=std::time::Instant::now();
        for _ in 0..rounds{std::hint::black_box(hex(std::hint::black_box(&input)));}
        let assembly=start.elapsed().as_secs_f64();
        let start=std::time::Instant::now();
        for _ in 0..rounds{std::hint::black_box(reference_hex(std::hint::black_box(input.as_bytes())));}
        println!("{}",json!({"bytes_per_input":input.len(),"iterations":rounds,"native_seconds":assembly,"rust_reference_seconds":start.elapsed().as_secs_f64()}));return;
    }
    if let Err(e)=run(){eprintln!("{e}");std::process::exit(1);}}
#[cfg(test)]
mod tests {
    use super::*;
    #[test] fn exhaustive_hex_pairs(){for a in 0..=255u8 {for b in 0..=255u8{let input=[a,b];if let Ok(s)=std::str::from_utf8(&input){assert_eq!(hex(s),reference_hex(&input));}}}assert_eq!(hex("a"),None);assert_eq!(hex(""),Some(vec![]));}
    #[test] fn vector_boundaries(){
        for n in 0..256 {let s="aB09ef12".repeat(n);assert_eq!(hex(&s),reference_hex(s.as_bytes()));}
        for n in 0..64 {for c in 0..=127u8 {let mut s=vec![b'0';64];s[n]=c;let text=std::str::from_utf8(&s).unwrap();assert_eq!(hex(text),reference_hex(&s));}}
    }
    #[test] fn packets(){let d=advertisement(&hex("05095465737403030D18").unwrap());assert_eq!(d["name"],"Test");assert_eq!(d["service_uuids"][0],"180d");assert!(advertisement(&[5,9,1]).get("warning").is_some());}
    #[test] fn malformed(){assert_eq!(parse("12:34:56 AA:BB:CC:DD:EE:FF -45 0Z")["records"].as_array().unwrap().len(),0);assert_eq!(time_ms("25:00:00"),None);}
    #[test] fn analysis(){let r=parse("12:00:00 AA:BB:CC:DD:EE:FF -40 020106\n12:00:01 AA:BB:CC:DD:EE:FF -60 020106");assert_eq!(analyze(r["records"].as_array().unwrap())["mean_rssi"],-50.0);}
}
