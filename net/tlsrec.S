#include "asm.inc"
/*
 * tlsrec.S - the TLS 1.3 record layer.
 *
 * Between the key schedule and the handshake sits this: the thing that takes a
 * run of bytes and makes it a record, and takes a record and gives the bytes
 * back. It is small, and almost all of it is detail that cannot be guessed at.
 *
 * <h2>A record</h2>
 *
 *     17 03 03 LL LL   <ciphertext>   <16-byte tag>
 *
 * The first byte says application_data and is a lie in every case where it
 * matters: handshake messages travel under it too, once the keys exist, so
 * that somebody watching cannot tell a handshake from a conversation. The real
 * type is the last byte of the plaintext, inside the encryption, which is why
 * decrypting ends by looking backwards for it.
 *
 * The 03 03 is "TLS 1.2" and is also a lie, kept because middleboxes learned
 * what a version number looked like in 2008 and drop anything else. Both lies
 * are in the specification.
 *
 * <h2>The nonce, which is where this goes wrong quietly</h2>
 *
 * Not random, and not sent: each side counts its own records, and the nonce is
 * the twelve-byte iv from the key schedule with that count exclusive-ored into
 * its last eight bytes, big-endian. So the two ends construct the same nonce
 * without exchanging anything, and a record that arrives out of order cannot be
 * decrypted at all - which is the point, because a record that could be
 * replayed or reordered is a conversation somebody else can rearrange.
 *
 * The counter belongs to the key and not to the connection. It starts at zero
 * when the keys change, which happens twice in a handshake, and it is never
 * reset otherwise. Getting that wrong produces a connection that works for one
 * record and then does not, which reads like a network problem.
 *
 * <h2>The header is authenticated and not encrypted</h2>
 *
 * Those five bytes go in as additional data. They have to: the length is how
 * the far side knows where the record ends, so it must be readable, and it must
 * also be impossible to alter - a length nobody signed is a length an attacker
 * chooses.
 */

/* What one direction's keys are. The caller holds two of these. */
#define TK_KEY          0               /* 32 bytes */
#define TK_IV           32              /* 12 */
#define TK_SEQ          48              /* the records sent or seen so far */
#define TK_SZ           56

#define REC_HDR         5
#define REC_TAG         16
/* A record carries at most this much plaintext - RFC 8446 section 5.1. */
#define REC_MAX         16384

        CODE
        .p2align 2

/*
 * tls_keys_set - x0 = a TK_SZ context, x1 = 32-byte key, x2 = 12-byte iv.
 *
 * And the sequence number to zero, which is the whole reason this is a function
 * rather than two copies at the call site: new keys mean a new count, every
 * time, and a caller that remembered to copy the key and forgot the counter
 * would produce a connection that fails on its second record.
 */
        .globl  SYM(tls_keys_set)
SYM(tls_keys_set):
        mov     w9, #0
1:      cmp     w9, #32
        b.hs    2f
        ldrb    w10, [x1, w9, uxtw]
        strb    w10, [x0, w9, uxtw]
        add     w9, w9, #1
        b       1b
2:      mov     w9, #0
        add     x11, x0, #TK_IV
3:      cmp     w9, #12
        b.hs    4f
        ldrb    w10, [x2, w9, uxtw]
        strb    w10, [x11, w9, uxtw]
        add     w9, w9, #1
        b       3b
4:      str     xzr, [x0, #TK_SEQ]
        ret

/*
 * tls_nonce - x0 = ctx, x1 = 12 bytes out.
 *
 * The iv, with the record count exclusive-ored into the last eight bytes
 * big-endian. Written a byte at a time on purpose: the obvious version loads
 * the iv as two words and byte-swaps, and the obvious version is where the
 * endianness mistakes live. This one is slower than the records it protects by
 * a margin nobody can measure.
 */
tls_nonce:
        ldr     x9, [x0, #TK_SEQ]
        add     x12, x0, #TK_IV
        mov     w10, #0
1:      cmp     w10, #12
        b.hs    9f
        ldrb    w11, [x12, w10, uxtw]
        mov     w13, #0
        cmp     w10, #4
        b.lo    2f
        sub     w14, w10, #4            /* which byte of the counter */
        mov     w15, #7
        sub     w15, w15, w14
        lsl     w15, w15, #3
        lsr     x13, x9, x15
        and     w13, w13, #0xFF
2:      eor     w11, w11, w13
        strb    w11, [x1, w10, uxtw]
        add     w10, w10, #1
        b       1b
9:      ret

/*
 * tls_seal - x0 = ctx, w1 = content type, x2 = plaintext, w3 = its length,
 *            x4 = where the record goes.
 *   out w0 = the whole record's length, or -1 if it would be too long.
 *
 * The output needs room for the plaintext plus twenty-two bytes: five of
 * header, one of type, and sixteen of tag.
 */
        .globl  SYM(tls_seal)
SYM(tls_seal):
        stp     x29, x30, [sp, #-96]!
        mov     x29, sp
        stp     x19, x20, [sp, #16]
        stp     x21, x22, [sp, #32]
        stp     x23, x24, [sp, #48]
        mov     x19, x0
        mov     w20, w1
        mov     x21, x2
        mov     w22, w3
        mov     x23, x4

        cmp     w22, #REC_MAX
        b.hi    .Lseal_no

/*
 * The inner plaintext: what was asked for, and then what it was.
 *
 * Copied rather than encrypted in place, because the type byte has to go on
 * the end and the caller's buffer is the caller's. No padding is added - it is
 * allowed and it hides lengths, and hiding lengths is a decision this file has
 * no information to make. The handshake can pad if it wants to.
 */
        LEA     x24, inner
        mov     w9, #0
1:      cmp     w9, w22
        b.hs    2f
        ldrb    w10, [x21, w9, uxtw]
        strb    w10, [x24, w9, uxtw]
        add     w9, w9, #1
        b       1b
2:      strb    w20, [x24, w9, uxtw]
        add     w9, w9, #1              /* the inner length */

        add     w10, w9, #REC_TAG       /* what the header will claim */
        mov     w11, #0x17              /* application_data, whatever it is */
        strb    w11, [x23, #0]
        mov     w11, #0x03
        strb    w11, [x23, #1]
        strb    w11, [x23, #2]
        lsr     w11, w10, #8
        strb    w11, [x23, #3]
        strb    w10, [x23, #4]

        mov     w21, w9                 /* inner length, past the calls below */
        LEA     x0, noncebuf
        mov     x1, x0
        mov     x0, x19
        bl      tls_nonce

        add     x6, x23, #REC_HDR
        add     x7, x6, w21, uxtw       /* the tag sits after the ciphertext */
        mov     x0, x19                 /* the key is the start of the ctx */
        LEA     x1, noncebuf
        mov     x2, x23                 /* the header, authenticated whole */
        mov     w3, #REC_HDR
        LEA     x4, inner
        mov     w5, w21
        bl      SYM(aead_seal)

        ldr     x9, [x19, #TK_SEQ]      /* this record is spent */
        add     x9, x9, #1
        str     x9, [x19, #TK_SEQ]

        add     w0, w21, #(REC_HDR + REC_TAG)
        b       .Lseal_out
.Lseal_no:
        mov     w0, #-1
.Lseal_out:
        ldp     x23, x24, [sp, #48]
        ldp     x21, x22, [sp, #32]
        ldp     x19, x20, [sp, #16]
        ldp     x29, x30, [sp], #96
        ret

/*
 * tls_open - x0 = ctx, x1 = the whole record, w2 = its length,
 *            x3 = where the plaintext goes, x4 = one byte for the type.
 *   out w0 = the plaintext length, or -1.
 *
 * -1 covers every way this can fail and says nothing about which, deliberately.
 * A record that will not authenticate, one with a length that disagrees with
 * what arrived, one too short to contain a tag: the correct response to all of
 * them is to stop talking to whoever sent it, and telling them which it was is
 * how padding oracles are built.
 */
        .globl  SYM(tls_open)
SYM(tls_open):
        stp     x29, x30, [sp, #-96]!
        mov     x29, sp
        stp     x19, x20, [sp, #16]
        stp     x21, x22, [sp, #32]
        stp     x23, x24, [sp, #48]
        mov     x19, x0
        mov     x20, x1
        mov     w21, w2
        mov     x22, x3
        mov     x23, x4

        cmp     w21, #(REC_HDR + REC_TAG + 1)
        b.lo    .Lopen_no               /* no room for a tag and a type byte */

        ldrb    w9, [x20, #3]           /* the length it claims */
        ldrb    w10, [x20, #4]
        orr     w9, w10, w9, lsl #8
        add     w10, w9, #REC_HDR
        cmp     w10, w21
        b.ne    .Lopen_no               /* and the length that arrived */
        sub     w24, w9, #REC_TAG       /* the ciphertext, without its tag */
        cbz     w24, .Lopen_no

        LEA     x1, noncebuf
        mov     x0, x19
        bl      tls_nonce

        add     x4, x20, #REC_HDR
        add     x7, x4, w24, uxtw
        mov     x0, x19
        LEA     x1, noncebuf
        mov     x2, x20                 /* the header as it arrived */
        mov     w3, #REC_HDR
        mov     w5, w24
        mov     x6, x22
        bl      SYM(aead_open)
        cbnz    w0, .Lopen_no

        ldr     x9, [x19, #TK_SEQ]      /* a record that opened counts */
        add     x9, x9, #1
        str     x9, [x19, #TK_SEQ]

/*
 * And now backwards for the type.
 *
 * The real content type is the last byte that is not zero: everything after it
 * is padding, which is there to make records a uniform size and carries no
 * information. A plaintext that is all zeros has no type and is not a record
 * anybody should act on.
 */
        mov     w9, w24
1:      cbz     w9, .Lopen_no
        sub     w9, w9, #1
        ldrb    w10, [x22, w9, uxtw]
        cbz     w10, 1b
        strb    w10, [x23]
        mov     w0, w9
        b       .Lopen_out
.Lopen_no:
        mov     w0, #-1
.Lopen_out:
        ldp     x23, x24, [sp, #48]
        ldp     x21, x22, [sp, #32]
        ldp     x19, x20, [sp, #16]
        ldp     x29, x30, [sp], #96
        ret

        BSS
        .p2align 4
/*
 * One record's worth of working space, and one nonce.
 *
 * Static because there is one connection: this editor talks to its own backend
 * and to nothing else at the same time, and a kernel with no allocator should
 * not grow one to hold a buffer whose size is known at build time. The day
 * there are two connections this becomes part of the context and every caller
 * already passes one.
 */
inner:      .space (REC_MAX + 1)
noncebuf:   .space 16
