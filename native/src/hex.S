// AAPCS64: x0=input, x1=length (even), x2=output (length/2 capacity).
// Returns decoded length or -1 for invalid hex. No reads beyond length.
.text
.global atlas_hex_decode
.type atlas_hex_decode, %function
atlas_hex_decode:
    tbnz x1, #0, 9f
    mov x3, #0
    mov x4, #0
    cmp x1, #16
    b.lo 1f
    movi v16.16b, #9
    movi v17.16b, #5
    movi v18.16b, #32
    movi v19.16b, #48
    movi v20.16b, #97
    movi v21.16b, #10
4:
    sub x8, x1, x3
    cmp x8, #16
    b.lo 1f
    ldr q0, [x0, x3]
    orr v1.16b, v0.16b, v18.16b
    sub v2.16b, v0.16b, v19.16b
    sub v4.16b, v1.16b, v20.16b
    cmhi v3.16b, v2.16b, v16.16b
    cmhi v5.16b, v4.16b, v17.16b
    and v5.16b, v5.16b, v3.16b
    umaxv b6, v5.16b
    umov w6, v6.b[0]
    cbnz w6, 9f
    add v4.16b, v4.16b, v21.16b
    bsl v3.16b, v4.16b, v2.16b
    uzp1 v4.16b, v3.16b, v3.16b
    uzp2 v5.16b, v3.16b, v3.16b
    shl v4.8b, v4.8b, #4
    orr v4.8b, v4.8b, v5.8b
    str d4, [x2, x4]
    add x3, x3, #16
    add x4, x4, #8
    b 4b
1:
    cmp x3, x1
    b.hs 8f
    ldrb w5, [x0, x3]
    add x3, x3, #1
    sub w6, w5, #48
    cmp w6, #9
    b.ls 2f
    orr w5, w5, #32
    sub w6, w5, #97
    cmp w6, #5
    b.hi 9f
    add w6, w6, #10
2:
    lsl w7, w6, #4
    ldrb w5, [x0, x3]
    add x3, x3, #1
    sub w6, w5, #48
    cmp w6, #9
    b.ls 3f
    orr w5, w5, #32
    sub w6, w5, #97
    cmp w6, #5
    b.hi 9f
    add w6, w6, #10
3:
    orr w7, w7, w6
    strb w7, [x2, x4]
    add x4, x4, #1
    b 1b
8:
    mov x0, x4
    ret
9:
    mov x0, #-1
    ret
.size atlas_hex_decode, .-atlas_hex_decode
.section .note.GNU-stack,"",%progbits
