#include "asm.inc"
/*
 * The TLS 1.3 key schedule, against the values RFC 8448 publishes.
 *
 * RFC 8448 is a complete handshake printed out - every message, every
 * intermediate secret, every key. That makes it the one thing worth testing
 * this against: an implementation that agrees with it agrees with every other
 * implementation that does, and one that merely agrees with its own author's
 * reading of RFC 8446 agrees with nothing until it is pointed at a server.
 *
 * Four of its values need no transcript at all, which is why they are the ones
 * checked here:
 *
 *   the early secret         Extract(0, 0) - no inputs whatsoever
 *   the handshake secret     from the published ECDHE output
 *   both handshake traffic secrets, from the published transcript hash
 *   the master secret        from the handshake secret and zeros
 *   the server write key/iv  from the published server handshake secret
 *
 * Between them they cover every operation in the schedule: both extracts, the
 * "derived" expansions that join them, the two traffic derivations that take a
 * transcript, and the key and iv expansions at the end. What is deliberately
 * not tested here is which bytes belong in a transcript - that is the
 * handshake's question, and here the transcript hash is an input with a
 * published value.
 *
 * Output is hex, one value per line, compared by the Makefile against the
 * strings copied out of the RFC. Comparing in the shell rather than in here is
 * deliberate: the expected values then live in the build where a person can
 * read them beside the output, instead of in a buffer being memcmp'd.
 */
#define SYS_WRITE       4

        CODE
        .p2align 2
        .globl  _main
_main:
        stp     x29, x30, [sp, #-32]!
        mov     x29, sp

/* ---- the early secret: no PSK, so nothing goes in ---- */
        LEA     x0, early
        bl      SYM(tls13_early_secret)
        LEA     x0, early
        bl      show

/* ---- the handshake secret, from the published shared secret ---- */
        LEA     x0, early
        LEA     x1, ecdhe
        LEA     x2, handshake
        bl      SYM(tls13_handshake_secret)
        LEA     x0, handshake
        bl      show

/* ---- both handshake traffic secrets, from the published transcript ---- */
        LEA     x0, handshake
        LEA     x1, transcript
        LEA     x2, chs
        LEA     x3, shs
        bl      SYM(tls13_hs_traffic)
        LEA     x0, chs
        bl      show
        LEA     x0, shs
        bl      show

/* ---- and the master secret, which adds nothing but a step ---- */
        LEA     x0, handshake
        LEA     x1, master
        bl      SYM(tls13_master_secret)
        LEA     x0, master
        bl      show

/*
 * The server's handshake write key and iv.
 *
 * From the traffic secret the RFC publishes rather than from one derived here,
 * so this checks the key and iv expansions on their own: a mistake in the
 * transcript cannot make this pass and a mistake here cannot be hidden by one.
 * Sixteen bytes because RFC 8448's trace is AES-128-GCM. The schedule does not
 * know or care - the length is the cipher suite's, and asking for it is how
 * this file stays honest about what it proved.
 */
        LEA     x0, s_hs_secret
        LEA     x1, wkey
        mov     w2, #16
        LEA     x3, wiv
        bl      SYM(tls13_traffic_key)
        LEA     x0, wkey
        mov     w1, #16
        bl      show_n
        LEA     x0, wiv
        mov     w1, #12
        bl      show_n

        mov     w0, #0
        ldp     x29, x30, [sp], #32
        ret

/* show - x0 = 32 bytes, as hex and a newline. */
show:   mov     w1, #32
        b       show_n

/* show_n - x0 = bytes, w1 = how many. */
show_n: stp     x29, x30, [sp, #-32]!
        mov     x29, sp
        LEA     x2, hexbuf
        bl      SYM(hex_encode)
        LEA     x0, hexbuf
        bl      say
        LEA     x0, s_nl
        bl      say
        ldp     x29, x30, [sp], #32
        ret

say:    stp     x29, x30, [sp, #-32]!
        mov     x29, sp
        mov     x1, x0
        mov     w2, #0
1:      ldrb    w9, [x1, w2, uxtw]
        cbz     w9, 2f
        add     w2, w2, #1
        b       1b
2:      mov     x0, #1
        mov     x16, #SYS_WRITE
        svc     #0x80
        ldp     x29, x30, [sp], #32
        ret

        RODATA
        .p2align 3
/*
 * RFC 8448 section 3, the IKM of {server} extract secret "handshake": the
 * X25519 output both sides arrive at. Everything below it in that trace
 * follows from this and the conversation, and this test takes the first and is
 * handed the second.
 */
ecdhe:
        .byte 0x8b, 0xd4, 0x05, 0x4f, 0xb5, 0x5b, 0x9d, 0x63
        .byte 0xfd, 0xfb, 0xac, 0xf9, 0xf0, 0x4b, 0x9f, 0x0d
        .byte 0x35, 0xe6, 0xd6, 0x3f, 0x53, 0x75, 0x63, 0xef
        .byte 0xd4, 0x62, 0x72, 0x90, 0x0f, 0x89, 0x49, 0x2d

/*
 * And the trace's hash of ClientHello..ServerHello, so the traffic secrets can
 * be derived here and checked rather than assumed.
 *
 * This is the transcript, taken as a hash rather than rebuilt from the
 * messages the RFC also prints. Rebuilding it would test this file's idea of
 * which bytes belong in a transcript, which is the handshake's job and not the
 * key schedule's - here it is an input, and the published value is the input.
 */
transcript:
        .byte 0x86, 0x0c, 0x06, 0xed, 0xc0, 0x78, 0x58, 0xee
        .byte 0x8e, 0x78, 0xf0, 0xe7, 0x42, 0x8c, 0x58, 0xed
        .byte 0xd6, 0xb4, 0x3f, 0x2c, 0xa3, 0xe6, 0xe9, 0x5f
        .byte 0x02, 0xed, 0x06, 0x3c, 0xf0, 0xe1, 0xca, 0xd8

/* The same trace's server handshake traffic secret, so the key and iv
   expansions can be checked without deriving it first. */
s_hs_secret:
        .byte 0xb6, 0x7b, 0x7d, 0x69, 0x0c, 0xc1, 0x6c, 0x4e
        .byte 0x75, 0xe5, 0x42, 0x13, 0xcb, 0x2d, 0x37, 0xb4
        .byte 0xe9, 0xc9, 0x12, 0xbc, 0xde, 0xd9, 0x10, 0x5d
        .byte 0x42, 0xbe, 0xfd, 0x59, 0xd3, 0x91, 0xad, 0x38

        CSTR
s_nl:   .asciz "\n"

        BSS
        .p2align 4
early:      .space 32
chs:        .space 32
shs:        .space 32
handshake:  .space 32
master:     .space 32
wkey:       .space 32
wiv:        .space 16
hexbuf:     .space 80
