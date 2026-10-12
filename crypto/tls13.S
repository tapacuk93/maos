#include "asm.inc"
/*
 * tls13.S - the TLS 1.3 key schedule.
 *
 * Everything in RFC 8446 section 7.1 and nothing else: a fixed sequence of
 * extracts and expands that turns one X25519 shared secret and a running hash
 * of the conversation into the keys both sides encrypt with. No sockets, no
 * records, no parsing - this file is arithmetic, which is why it can be proved
 * against the published vectors without a server anywhere.
 *
 * <h2>The shape of it</h2>
 *
 *     early    = Extract(0, 0)                  no PSK, so both are zeros
 *     derived  = Expand-Label(early, "derived", Hash(""))
 *     handshake= Extract(derived, ECDHE)        the only secret input
 *     c/s hs   = Expand-Label(handshake, "c hs traffic", transcript)
 *     derived2 = Expand-Label(handshake, "derived", Hash(""))
 *     master   = Extract(derived2, 0)
 *     c/s ap   = Expand-Label(master, "c ap traffic", transcript)
 *
 * The transcript is the one thing here that is not a fixed computation, and it
 * is also the thing the whole design rests on: the traffic keys depend on every
 * handshake byte either side sent, so a middle that altered one of them derives
 * different keys and cannot read what follows. That is why the transcript is a
 * running hash fed by the caller rather than a buffer assembled here - by the
 * time this file sees it, the question of which bytes count has been answered.
 *
 * <h2>Why the hash is copied rather than finished</h2>
 *
 * The transcript is hashed at three points and goes on growing after each of
 * them, and sha256_final pads and consumes the context it is given. So
 * tls13_transcript_hash takes a copy and finishes that, leaving the real one
 * able to continue. A hundred and twelve bytes of stack, against the
 * alternative of re-hashing the whole conversation three times.
 *
 * <h2>What is not here</h2>
 *
 * Resumption and exporter secrets, 0-RTT, and key update. Each is another
 * Expand-Label on a secret this already derives, and none of them is needed to
 * talk to one server once. They are left out rather than written untested.
 */

/* A sha256 context, which is what a transcript is until it is asked for. */
#define TR_SZ           112

        CODE
        .p2align 2

/*
 * tls13_transcript_init - x0 = a TR_SZ-byte context.
 * tls13_transcript_add  - x0 = ctx, x1 = a handshake message, w2 = its length.
 *
 * Both are the hash underneath, named for what they are for. A caller that
 * reached past these to sha256 directly would work and would be writing in two
 * vocabularies about one thing.
 */
        .globl  SYM(tls13_transcript_init)
SYM(tls13_transcript_init):
        b       SYM(sha256_init)

        .globl  SYM(tls13_transcript_add)
SYM(tls13_transcript_add):
        b       SYM(sha256_update)

/*
 * tls13_transcript_hash - x0 = ctx, x1 = 32 bytes out.
 *
 * The conversation so far, without ending it. See the note above about why the
 * context is copied: finishing the real one would make every later message
 * hash into a padded, closed state and produce traffic keys that agree with
 * nobody.
 */
        .globl  SYM(tls13_transcript_hash)
SYM(tls13_transcript_hash):
        stp     x29, x30, [sp, #-(48 + TR_SZ)]!
        mov     x29, sp
        stp     x19, x20, [sp, #16]
        mov     x19, x0
        mov     x20, x1
        add     x2, sp, #48
        mov     w9, #0
1:      cmp     w9, #TR_SZ
        b.hs    2f
        ldrb    w10, [x19, w9, uxtw]
        strb    w10, [x2, w9, uxtw]
        add     w9, w9, #1
        b       1b
2:      add     x0, sp, #48
        mov     x1, x20
        bl      SYM(sha256_final)
        ldp     x19, x20, [sp, #16]
        ldp     x29, x30, [sp], #(48 + TR_SZ)
        ret

/*
 * tls13_derive_secret - x0 = 32-byte secret, x1 = label, w2 = label length,
 *                       x3 = 32-byte transcript hash, x4 = 32 bytes out.
 *
 * RFC 8446's Derive-Secret, which is Expand-Label with a hash as the context
 * and a hash-sized output. Exported because every step below is one of these
 * and a caller deriving an exporter or a resumption secret should not have to
 * reimplement it to get a different label.
 */
        .globl  SYM(tls13_derive_secret)
SYM(tls13_derive_secret):
        mov     x5, x4
        mov     w4, #32
        mov     w6, #32
        b       SYM(hkdf_expand_label)

/*
 * tls13_early_secret - x0 = 32 bytes out.
 *
 * With no pre-shared key this is Extract(zeros, zeros), which makes it a
 * constant: the same thirty-two bytes in every connection this will ever make.
 * Computed anyway. A constant written into the source is a number nobody can
 * check against the specification without doing this computation, and the one
 * place that is worth refusing to save three microseconds is a key schedule.
 */
        .globl  SYM(tls13_early_secret)
SYM(tls13_early_secret):
        stp     x29, x30, [sp, #-16]!
        mov     x29, sp
        mov     x4, x0
        LEA     x0, tls_zeros
        mov     w1, #32
        LEA     x2, tls_zeros
        mov     w3, #32
        bl      SYM(hkdf_extract)
        ldp     x29, x30, [sp], #16
        ret

/*
 * tls13_handshake_secret - x0 = early secret, x1 = 32-byte X25519 output,
 *                          x2 = 32 bytes out.
 *
 * The one place the shared secret enters the schedule. Everything before this
 * is fixed and everything after is derived, so this is the single line between
 * "any connection" and "this connection".
 */
        .globl  SYM(tls13_handshake_secret)
SYM(tls13_handshake_secret):
        stp     x29, x30, [sp, #-64]!
        mov     x29, sp
        stp     x19, x20, [sp, #16]
        mov     x19, x1                 /* the shared secret */
        mov     x20, x2                 /* where it goes */

        LEA     x1, s_derived
        mov     w2, #7
        LEA     x3, tls13_empty_hash
        mov     w4, #32
        add     x5, sp, #32
        mov     w6, #32
        bl      SYM(hkdf_expand_label)

        add     x0, sp, #32             /* derived is the salt */
        mov     w1, #32
        mov     x2, x19
        mov     w3, #32
        mov     x4, x20
        bl      SYM(hkdf_extract)

        ldp     x19, x20, [sp, #16]
        ldp     x29, x30, [sp], #64
        ret

/*
 * tls13_master_secret - x0 = handshake secret, x1 = 32 bytes out.
 *
 * The same move again with nothing new put in: derive from what we have, then
 * extract with zeros as the key material. It looks like a step that does
 * nothing and is the step that makes the application keys independent of the
 * handshake keys - somebody who learns the handshake secret afterwards cannot
 * walk forwards from it to what was actually said.
 */
        .globl  SYM(tls13_master_secret)
SYM(tls13_master_secret):
        stp     x29, x30, [sp, #-64]!
        mov     x29, sp
        stp     x19, x20, [sp, #16]
        mov     x20, x1

        LEA     x1, s_derived
        mov     w2, #7
        LEA     x3, tls13_empty_hash
        mov     w4, #32
        add     x5, sp, #32
        mov     w6, #32
        bl      SYM(hkdf_expand_label)

        add     x0, sp, #32
        mov     w1, #32
        LEA     x2, tls_zeros
        mov     w3, #32
        mov     x4, x20
        bl      SYM(hkdf_extract)

        ldp     x19, x20, [sp, #16]
        ldp     x29, x30, [sp], #64
        ret

/*
 * tls13_hs_traffic - x0 = handshake secret, x1 = transcript hash through
 *                    ServerHello, x2 = client secret out, x3 = server out.
 * tls13_ap_traffic - x0 = master secret, x1 = transcript hash through the
 *                    server's Finished, x2 = client out, x3 = server out.
 *
 * Two labels apiece and otherwise identical, which is why they are two four-
 * line functions rather than one with a flag: the difference between them is
 * which conversation they are about, and that is not a parameter.
 */
        .globl  SYM(tls13_hs_traffic)
SYM(tls13_hs_traffic):
        LEA     x4, s_c_hs
        LEA     x5, s_s_hs
        b       tls_two

        .globl  SYM(tls13_ap_traffic)
SYM(tls13_ap_traffic):
        LEA     x4, s_c_ap
        LEA     x5, s_s_ap
        b       tls_two

/* x0 = secret, x1 = transcript, x2/x3 = outs, x4/x5 = the two labels. */
tls_two:
        stp     x29, x30, [sp, #-64]!
        mov     x29, sp
        stp     x19, x20, [sp, #16]
        stp     x21, x22, [sp, #32]
        str     x23, [sp, #48]
        mov     x19, x0
        mov     x20, x1
        mov     x21, x3
        mov     x22, x5
        mov     x23, x2

        mov     x1, x4
        mov     w2, #12                 /* every one of these labels is 12 */
        mov     x3, x20
        mov     x4, x23
        bl      SYM(tls13_derive_secret)

        mov     x0, x19
        mov     x1, x22
        mov     w2, #12
        mov     x3, x20
        mov     x4, x21
        bl      SYM(tls13_derive_secret)

        ldr     x23, [sp, #48]
        ldp     x21, x22, [sp, #32]
        ldp     x19, x20, [sp, #16]
        ldp     x29, x30, [sp], #64
        ret

/*
 * tls13_traffic_key - x0 = a traffic secret, x1 = key out, w2 = key length,
 *                     x3 = 12-byte iv out.
 *
 * The key length is the caller's because it is the cipher suite's: sixteen
 * bytes for AES-128, thirty-two for ChaCha20. The iv is twelve either way,
 * which is a property of the record nonce rather than of the cipher, so it is
 * not asked for.
 */
        .globl  SYM(tls13_traffic_key)
SYM(tls13_traffic_key):
        stp     x29, x30, [sp, #-64]!
        mov     x29, sp
        stp     x19, x20, [sp, #16]
        stp     x21, x22, [sp, #32]
        mov     x19, x0                 /* the secret, needed twice */
        mov     x21, x1                 /* key out  */
        mov     w22, w2                 /* key length - callee-saved, because
                                           LEA below writes x9 and the obvious
                                           scratch register is x9 */
        mov     x20, x3                 /* iv out   */

        LEA     x1, s_key
        mov     w2, #3
        LEA     x3, tls_zeros
        mov     w4, #0
        mov     x5, x21
        mov     w6, w22
        bl      SYM(hkdf_expand_label)

        mov     x0, x19
        LEA     x1, s_iv
        mov     w2, #2
        LEA     x3, tls_zeros
        mov     w4, #0
        mov     x5, x20
        mov     w6, #12
        bl      SYM(hkdf_expand_label)

        ldp     x21, x22, [sp, #32]
        ldp     x19, x20, [sp, #16]
        ldp     x29, x30, [sp], #64
        ret

/*
 * tls13_finished_key - x0 = a traffic secret, x1 = 32 bytes out.
 *
 * What the Finished message is HMAC'd with. Derived from the traffic secret
 * rather than being it, so a Finished anybody recorded says nothing about the
 * key the records use.
 */
        .globl  SYM(tls13_finished_key)
SYM(tls13_finished_key):
        mov     x5, x1
        LEA     x1, s_finished
        mov     w2, #8
        LEA     x3, tls_zeros
        mov     w4, #0
        mov     w6, #32
        b       SYM(hkdf_expand_label)

        RODATA
        .p2align 3
/*
 * SHA-256 of nothing at all.
 *
 * Derive-Secret with an empty transcript is defined to hash the empty string,
 * and this is that hash. Unlike the early secret above it is written down,
 * because it is a published property of SHA-256 rather than a step of this
 * schedule - the same thirty-two bytes appear in RFC 6234's own test vectors.
 */
tls13_empty_hash:
        .byte 0xe3, 0xb0, 0xc4, 0x42, 0x98, 0xfc, 0x1c, 0x14
        .byte 0x9a, 0xfb, 0xf4, 0xc8, 0x99, 0x6f, 0xb9, 0x24
        .byte 0x27, 0xae, 0x41, 0xe4, 0x64, 0x9b, 0x93, 0x4c
        .byte 0xa4, 0x95, 0x99, 0x1b, 0x78, 0x52, 0xb8, 0x55

tls_zeros:
        .space 32

        CSTR
s_derived:      .asciz "derived"
s_c_hs:         .asciz "c hs traffic"
s_s_hs:         .asciz "s hs traffic"
s_c_ap:         .asciz "c ap traffic"
s_s_ap:         .asciz "s ap traffic"
s_key:          .asciz "key"
s_iv:           .asciz "iv"
s_finished:     .asciz "finished"
