// ARM64 AAPCS64 research kernels. Only caller-saved registers are used.
// All lengths are element counts; wrappers bound input size and output capacity.
.text
.global atlas_moments_i16
.type atlas_moments_i16,%function
// x0=i16 input, x1=count, x2=output: sum,squares,min,max,absolute successive delta (5 x 64-bit)
atlas_moments_i16:
    mov x3,#0
    mov x4,#0
    mov x5,#0
    mov x6,#0
    mov x7,#0
    cbz x1,3f
    ldrsh x5,[x0]
    mov x6,x5
    mov x8,x5
    dup v7.8h,w5
4:
    cbz x1,3f
    cmp x1,#8
    b.lo 1f
    ldr q0,[x0],#16
    saddlv s1,v0.8h
    smov x9,v1.s[0]
    add x3,x3,x9
    smull v2.4s,v0.4h,v0.4h
    smull2 v3.4s,v0.8h,v0.8h
    saddlp v2.2d,v2.4s
    saddlp v3.2d,v3.4s
    add v2.2d,v2.2d,v3.2d
    addp d2,v2.2d
    fmov x9,d2
    add x4,x4,x9
    sminv h2,v0.8h
    smov x9,v2.h[0]
    cmp x9,x5
    csel x5,x9,x5,lt
    smaxv h2,v0.8h
    smov x9,v2.h[0]
    cmp x9,x6
    csel x6,x9,x6,gt
    ext v1.16b,v7.16b,v0.16b,#14
    sabd v1.8h,v0.8h,v1.8h
    uaddlv s1,v1.8h
    umov w9,v1.s[0]
    add x7,x7,x9
    mov v7.16b,v0.16b
    smov x8,v0.h[7]
    sub x1,x1,#8
    b 4b
1:
    ldrsh x9,[x0],#2
    add x3,x3,x9
    madd x4,x9,x9,x4
    cmp x9,x5
    csel x5,x9,x5,lt
    cmp x9,x6
    csel x6,x9,x6,gt
    sub x10,x9,x8
    cmp x10,#0
    cneg x10,x10,lt
    add x7,x7,x10
    mov x8,x9
    subs x1,x1,#1
    b.ne 1b
3:
    stp x3,x4,[x2]
    stp x5,x6,[x2,#16]
    str x7,[x2,#32]
    ret
.size atlas_moments_i16,.-atlas_moments_i16

.global atlas_histogram_rssi
.type atlas_histogram_rssi,%function
// x0=i16 input, x1=count, x2=148 zeroed u64 bins for -127..20 dBm.
atlas_histogram_rssi:
    cbz x1,3f
1:
    ldrsh x3,[x0],#2
    add x3,x3,#127
    cmp x3,#147
    b.hi 2f
    ldr x4,[x2,x3,lsl #3]
    add x4,x4,#1
    str x4,[x2,x3,lsl #3]
2:
    subs x1,x1,#1
    b.ne 1b
3:  ret
.size atlas_histogram_rssi,.-atlas_histogram_rssi

.global atlas_dot_i16
.type atlas_dot_i16,%function
// x0=i16 a, x1=i16 b, x2=count; return signed 64-bit dot product.
atlas_dot_i16:
    mov x8,#0
    eor v4.16b,v4.16b,v4.16b
1:
    cmp x2,#8
    b.lo 2f
    ldr q0,[x0],#16
    ldr q1,[x1],#16
    smull v2.4s,v0.4h,v1.4h
    smull2 v3.4s,v0.8h,v1.8h
    saddlp v2.2d,v2.4s
    saddlp v3.2d,v3.4s
    add v4.2d,v4.2d,v2.2d
    add v4.2d,v4.2d,v3.2d
    sub x2,x2,#8
    b 1b
2:
    addp d4,v4.2d
    fmov x8,d4
    cbz x2,4f
3:
    ldrsh x4,[x0],#2
    ldrsh x5,[x1],#2
    madd x8,x4,x5,x8
    subs x2,x2,#1
    b.ne 3b
4:
    mov x0,x8
    ret
.size atlas_dot_i16,.-atlas_dot_i16

.global atlas_hamming_bytes
.type atlas_hamming_bytes,%function
// x0=bytes a, x1=bytes b, x2=count; return differing bit count.
atlas_hamming_bytes:
    mov x8,#0
1:
    cmp x2,#16
    b.lo 2f
    ldr q0,[x0],#16
    ldr q1,[x1],#16
    eor v0.16b,v0.16b,v1.16b
    cnt v0.16b,v0.16b
    uaddlv h0,v0.16b
    umov w3,v0.h[0]
    add x8,x8,x3
    sub x2,x2,#16
    b 1b
2:
    cbz x2,4f
3:
    ldrb w3,[x0],#1
    ldrb w4,[x1],#1
    eor w3,w3,w4
    dup v0.8b,w3
    cnt v0.8b,v0.8b
    umov w3,v0.b[0]
    add x8,x8,x3
    subs x2,x2,#1
    b.ne 3b
4:
    mov x0,x8
    ret
.size atlas_hamming_bytes,.-atlas_hamming_bytes

.global atlas_window_sums_i16
.type atlas_window_sums_i16,%function
// x0=input, x1=count, x2=window, x3=output i64[count-window+1]; return output count.
atlas_window_sums_i16:
    cbz x2,5f
    cmp x1,x2
    b.lo 5f
    mov x4,#0
    mov x5,#0
1:
    ldrsh x6,[x0,x5,lsl #1]
    add x4,x4,x6
    add x5,x5,#1
    cmp x5,x2
    b.lo 1b
    str x4,[x3],#8
    sub x7,x1,x2
    add x8,x7,#1
    mov x9,#0
    cbz x7,4f
2:
    ldrsh x6,[x0,x9,lsl #1]
    sub x4,x4,x6
    ldrsh x6,[x0,x5,lsl #1]
    add x4,x4,x6
    str x4,[x3],#8
    add x9,x9,#1
    add x5,x5,#1
    subs x7,x7,#1
    b.ne 2b
4:
    mov x0,x8
    ret
5:
    mov x0,#0
    ret
.size atlas_window_sums_i16,.-atlas_window_sums_i16
.section .note.GNU-stack,"",%progbits
