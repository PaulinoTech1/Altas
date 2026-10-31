//! Safe, bounded wrappers around AAPCS64 kernels, with portable references.
#[cfg(target_arch = "aarch64")]
std::arch::global_asm!(include_str!("research.S"));
#[cfg(target_arch = "aarch64")]
extern "C" {
    fn atlas_moments_i16(data: *const i16, n: usize, out: *mut Moments);
    fn atlas_histogram_rssi(data: *const i16, n: usize, out: *mut u64);
    fn atlas_dot_i16(a: *const i16, b: *const i16, n: usize) -> i64;
    fn atlas_hamming_bytes(a: *const u8, b: *const u8, n: usize) -> u64;
    fn atlas_window_sums_i16(data: *const i16, n: usize, w: usize, out: *mut i64) -> usize;
}
pub const MAX_SAMPLES: usize = 10_000;
pub const BACKEND: &str = if cfg!(target_arch = "aarch64") {
    "ARM64 assembly (NEON moments, dot product and bit distance)"
} else {
    "portable Rust"
};
#[repr(C)]
#[derive(Debug, Default, PartialEq, Eq, Clone, Copy)]
pub struct Moments {
    pub sum: i64,
    pub squares: u64,
    pub min: i64,
    pub max: i64,
    pub absolute_delta: u64,
}
fn reference_moments(x: &[i16]) -> Moments {
    let Some(&first) = x.first() else {
        return Moments::default();
    };
    let mut m = Moments {
        min: first as i64,
        max: first as i64,
        ..Moments::default()
    };
    let mut previous = first;
    for &v in x {
        let wide = v as i64;
        m.sum += wide;
        m.squares += (wide * wide) as u64;
        m.min = m.min.min(wide);
        m.max = m.max.max(wide);
        m.absolute_delta += (wide - previous as i64).unsigned_abs();
        previous = v;
    }
    m
}
fn reference_windows(x: &[i16], w: usize) -> Vec<i64> {
    if w == 0 || w > x.len() {
        return vec![];
    }
    let mut sum = x[..w].iter().map(|&v| v as i64).sum::<i64>();
    let mut out = Vec::with_capacity(x.len() - w + 1);
    out.push(sum);
    for i in w..x.len() {
        sum += x[i] as i64 - x[i - w] as i64;
        out.push(sum);
    }
    out
}
pub fn moments(x: &[i16]) -> Moments {
    assert!(x.len() <= MAX_SAMPLES);
    #[cfg(target_arch = "aarch64")]
    {
        let mut out = Moments::default();
        // SAFETY: repr(C) matches five 64-bit stores; input is valid for n elements.
        unsafe { atlas_moments_i16(x.as_ptr(), x.len(), &mut out) };
        out
    }
    #[cfg(not(target_arch = "aarch64"))]
    {
        reference_moments(x)
    }
}
/// Exact integer numerator avoids cancellation in nearly constant series.
pub fn sample_variance(m: Moments, n: usize) -> Option<f64> {
    if n < 2 {
        return None;
    }
    let numerator = n as i128 * m.squares as i128 - (m.sum as i128).pow(2);
    Some(numerator as f64 / (n as f64 * (n - 1) as f64))
}
pub fn histogram(x: &[i16]) -> [u64; 148] {
    assert!(x.len() <= MAX_SAMPLES);
    let mut bins = [0u64; 148];
    #[cfg(target_arch = "aarch64")]
    {
        // SAFETY: assembly range-checks every index before addressing the 148 bins.
        unsafe { atlas_histogram_rssi(x.as_ptr(), x.len(), bins.as_mut_ptr()) };
    }
    #[cfg(not(target_arch = "aarch64"))]
    {
        for &v in x {
            if (-127..=20).contains(&v) {
                bins[(v + 127) as usize] += 1;
            }
        }
    }
    bins
}
pub fn dot(a: &[i16], b: &[i16]) -> i64 {
    assert_eq!(a.len(), b.len());
    assert!(a.len() <= MAX_SAMPLES);
    #[cfg(target_arch = "aarch64")]
    {
        // SAFETY: equal-length slices; vector loads only occur with >=8 remaining elements.
        unsafe { atlas_dot_i16(a.as_ptr(), b.as_ptr(), a.len()) }
    }
    #[cfg(not(target_arch = "aarch64"))]
    {
        a.iter().zip(b).map(|(&x, &y)| x as i64 * y as i64).sum()
    }
}
pub fn hamming(a: &[u8], b: &[u8]) -> u64 {
    assert_eq!(a.len(), b.len());
    assert!(a.len() <= 1650);
    #[cfg(target_arch = "aarch64")]
    {
        // SAFETY: slices have equal valid lengths; 16-byte blocks plus bounded byte tail.
        unsafe { atlas_hamming_bytes(a.as_ptr(), b.as_ptr(), a.len()) }
    }
    #[cfg(not(target_arch = "aarch64"))]
    {
        a.iter()
            .zip(b)
            .map(|(&x, &y)| (x ^ y).count_ones() as u64)
            .sum()
    }
}
pub fn window_sums(x: &[i16], w: usize) -> Vec<i64> {
    assert!(x.len() <= MAX_SAMPLES);
    if w == 0 || w > x.len() {
        return Vec::new();
    }
    #[cfg(target_arch = "aarch64")]
    {
        let mut out = vec![0i64; x.len() - w + 1];
        // SAFETY: nonzero window <=n, exact n-w+1 output capacity.
        let n = unsafe { atlas_window_sums_i16(x.as_ptr(), x.len(), w, out.as_mut_ptr()) };
        assert_eq!(n, out.len());
        out
    }
    #[cfg(not(target_arch = "aarch64"))]
    {
        reference_windows(x, w)
    }
}

pub fn manifest() -> serde_json::Value {
    use sha2::{Digest, Sha256};
    let mut digest = Sha256::new();
    for (name, bytes) in [
        ("research.S", include_bytes!("research.S").as_slice()),
        ("kernels.rs", include_bytes!("kernels.rs").as_slice()),
        (
            "research.rs",
            include_bytes!("investigate/research.rs").as_slice(),
        ),
        (
            "research_network.rs",
            include_bytes!("investigate/research_network.rs").as_slice(),
        ),
    ] {
        digest.update((name.len() as u64).to_be_bytes());
        digest.update(name.as_bytes());
        digest.update((bytes.len() as u64).to_be_bytes());
        digest.update(bytes);
    }
    serde_json::json!({"source_sha256":format!("{:x}",digest.finalize()),"cargo_lock_sha256":format!("{:x}",Sha256::digest(include_bytes!("../Cargo.lock"))),"compiler":env!("ATLAS_RUSTC"),"profile":env!("ATLAS_PROFILE"),"architecture":std::env::consts::ARCH,"backend":BACKEND})
}
pub fn benchmark() -> serde_json::Value {
    use serde_json::json;
    use std::{hint::black_box, time::Instant};
    fn measured(mut operation: impl FnMut()) -> f64 {
        let mut times = Vec::new();
        for _ in 0..5 {
            operation();
            let started = Instant::now();
            for _ in 0..100 {
                operation();
            }
            times.push(started.elapsed().as_secs_f64() * 1000.0);
        }
        times.sort_by(f64::total_cmp);
        times[2]
    }
    let x: Vec<i16> = (0..10000).map(|i| -100 + (i % 90) as i16).collect();
    let y: Vec<i16> = x.iter().rev().copied().collect();
    let a: Vec<u8> = (0..1650).map(|i| (i * 31) as u8).collect();
    let b: Vec<u8> = (0..1650).map(|i| (i * 17) as u8).collect();
    let mut results = Vec::new();
    let mut add = |name: &str, native: f64, reference: f64, elements: usize| {
        results.push(json!({"kernel":name,"elements_per_call":elements,"calls_per_batch":100,"native_median_ms":native,"rust_reference_median_ms":reference,"reference_over_native":if native>0.0{Some(reference/native)}else{None}}));
    };
    add(
        "moments",
        measured(|| {
            black_box(moments(black_box(&x)));
        }),
        measured(|| {
            black_box(reference_moments(black_box(&x)));
        }),
        x.len(),
    );
    add(
        "dot",
        measured(|| {
            black_box(dot(black_box(&x), black_box(&y)));
        }),
        measured(|| {
            black_box(
                black_box(&x)
                    .iter()
                    .zip(black_box(&y))
                    .map(|(&a, &b)| a as i64 * b as i64)
                    .sum::<i64>(),
            );
        }),
        x.len(),
    );
    add(
        "histogram",
        measured(|| {
            black_box(histogram(black_box(&x)));
        }),
        measured(|| {
            let mut bins = [0u64; 148];
            for &v in black_box(&x) {
                if (-127..=20).contains(&v) {
                    bins[(v + 127) as usize] += 1;
                }
            }
            black_box(bins);
        }),
        x.len(),
    );
    add(
        "window_sums",
        measured(|| {
            black_box(window_sums(black_box(&x), 16));
        }),
        measured(|| {
            black_box(reference_windows(black_box(&x), 16));
        }),
        x.len(),
    );
    add(
        "hamming",
        measured(|| {
            black_box(hamming(black_box(&a), black_box(&b)));
        }),
        measured(|| {
            black_box(
                black_box(&a)
                    .iter()
                    .zip(black_box(&b))
                    .map(|(&a, &b)| (a ^ b).count_ones() as u64)
                    .sum::<u64>(),
            );
        }),
        a.len(),
    );
    json!({"backend":BACKEND,"build":manifest(),"runs":5,"results":results,"method":"Median of five warmed 100-call batches; optimized same-complexity Rust references; allocation included for window outputs","interpretation":"Local microbenchmark, not whole-app performance; scheduling and thermal state affect results. A ratio below one means assembly was slower."})
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn integer_extremes_and_tails() {
        let mut seed = 7u64;
        for n in 0..260 {
            let x: Vec<i16> = (0..n)
                .map(|_| {
                    seed = seed.wrapping_mul(6364136223846793005).wrapping_add(1);
                    (seed >> 32) as i16
                })
                .collect();
            let y: Vec<_> = x.iter().rev().copied().collect();
            assert_eq!(moments(&x), reference_moments(&x));
            assert_eq!(
                dot(&x, &y),
                x.iter()
                    .zip(&y)
                    .map(|(&a, &b)| a as i64 * b as i64)
                    .sum::<i64>()
            );
        }
        let x = vec![i16::MIN; MAX_SAMPLES];
        assert_eq!(moments(&x), reference_moments(&x));
    }
    #[test]
    fn nearly_constant_variance_is_not_cancelled() {
        let mut x = vec![32766i16; 10000];
        x[9999] = 32767;
        assert_eq!(sample_variance(moments(&x), x.len()), Some(0.0001));
    }
    #[test]
    fn histogram_range_checks() {
        let x: Vec<i16> = (-200..200).collect();
        let b = histogram(&x);
        assert_eq!(b.iter().sum::<u64>(), 148);
        assert!(b.iter().all(|&v| v == 1));
        assert_eq!(histogram(&[]), [0; 148]);
    }
    #[test]
    fn bit_distance_unaligned_and_tails() {
        for n in 0..260 {
            let a: Vec<u8> = (0..n + 2).map(|v| (v * 31) as u8).collect();
            let b: Vec<u8> = (0..n + 2).map(|v| (v * 17) as u8).collect();
            let a = &a[1..n + 1];
            let b = &b[1..n + 1];
            assert_eq!(
                hamming(a, b),
                a.iter()
                    .zip(b)
                    .map(|(&x, &y)| (x ^ y).count_ones() as u64)
                    .sum::<u64>()
            );
        }
        assert_eq!(hamming(&[0; 16], &[255; 16]), 128);
    }
    #[test]
    fn sliding_windows_match_reference() {
        for n in 0..70 {
            let x: Vec<i16> = (0..n).map(|i| i as i16 - 40).collect();
            for w in 0..=n + 1 {
                let expected: Vec<i64> = if w == 0 || w > n {
                    vec![]
                } else {
                    x.windows(w)
                        .map(|win| win.iter().map(|&v| v as i64).sum())
                        .collect()
                };
                assert_eq!(window_sums(&x, w), expected);
            }
        }
    }
    #[test]
    fn histogram_canary() {
        #[cfg(target_arch = "aarch64")]
        {
            let mut out = [0u64; 150];
            out[0] = 0x1234;
            out[149] = 0x5678;
            let x = [i16::MIN, -127, 20, i16::MAX];
            unsafe { atlas_histogram_rssi(x.as_ptr(), x.len(), out.as_mut_ptr().add(1)) };
            assert_eq!(out[0], 0x1234);
            assert_eq!(out[149], 0x5678);
            assert_eq!(out[1], 1);
            assert_eq!(out[148], 1);
        }
    }
}
