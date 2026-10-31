mod capture;
mod errors;
mod investigate;
mod kernels;
use capture::*;
use errors::Failure;
use serde_json::{json, Value};
use std::io::{self, Read};
fn run() -> Result<(), Box<dyn std::error::Error>> {
    let mut input = String::new();
    io::stdin().take(1_100_001).read_to_string(&mut input)?;
    if input.len() > 1_100_000 {
        return Err(Failure::new("ATLAS_BODY_TOO_LARGE").into());
    }
    let request: Value = serde_json::from_str(&input)?;
    let result = if request["mode"] == "investigate" {
        investigate::dispatch(&request)?
    } else if request["mode"] == "analyze" {
        analyze(
            request["records"]
                .as_array()
                .ok_or_else(|| Failure::input("records"))?,
        )
    } else if request["mode"].is_null() || request["mode"] == "parse" {
        let text = request["text"]
            .as_str()
            .ok_or_else(|| Failure::input("text"))?;
        if text.len() > 1_000_000 {
            return Err(Failure::new("ATLAS_BODY_TOO_LARGE").into());
        }
        parse(text)
    } else {
        return Err(Failure::input("mode").into());
    };
    println!("{result}");
    Ok(())
}
fn main() {
    if std::env::args().any(|a| a == "--research-benchmark") {
        println!("{}", kernels::benchmark());
        return;
    }
    if std::env::args().any(|a| a == "--benchmark") {
        let input = "02010605095465737403030d18".repeat(64);
        let rounds = 20000;
        let start = std::time::Instant::now();
        for _ in 0..rounds {
            std::hint::black_box(hex(std::hint::black_box(&input)));
        }
        let assembly = start.elapsed().as_secs_f64();
        let start = std::time::Instant::now();
        for _ in 0..rounds {
            std::hint::black_box(reference_hex(std::hint::black_box(input.as_bytes())));
        }
        println!(
            "{}",
            json!({"bytes_per_input":input.len(),"iterations":rounds,"native_seconds":assembly,"rust_reference_seconds":start.elapsed().as_secs_f64()})
        );
        return;
    }
    if let Err(e) = run() {
        eprintln!(
            "{}",
            json!({"failure":Failure::classify(e.as_ref()).payload()})
        );
        std::process::exit(1);
    }
}
