use std::process::Command;
fn main() {
    println!("cargo:rerun-if-changed=build.rs");
    let rustc = std::env::var_os("RUSTC").unwrap_or_else(|| "rustc".into());
    let version = Command::new(rustc)
        .arg("--version")
        .output()
        .expect("rustc version");
    assert!(version.status.success());
    println!(
        "cargo:rustc-env=ATLAS_RUSTC={}",
        String::from_utf8_lossy(&version.stdout).trim()
    );
    println!(
        "cargo:rustc-env=ATLAS_PROFILE={}",
        std::env::var("PROFILE").unwrap_or_default()
    );
}
