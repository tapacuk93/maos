#include "asm.inc"
/*
 * The TLS 1.3 record layer: sealed, counted, opened, and refused.
 *
 * RFC 8448's trace is AES-128-GCM throughout, so it cannot check records this
 * cipher suite produces. The expected bytes here come from an independent
 * implementation of ChaCha20-Poly1305 written against RFC 8439 and checked
 * against that RFC's own published vector first - so what is compared is two
 * implementations agreeing, rather than this one agreeing with itself.
 *
 * Four things, and the last two are the ones worth having:
 *
 *   a record, byte for byte, including its header and tag
 *   a second record under the same keys, which must differ in more than its
 *     contents - the counter changes the nonce, and a record layer that forgot
 *     to count would encrypt the second one under the first one's nonce and
 *     hand anybody watching the exclusive-or of two plaintexts
 *   the same bytes opened back, with the content type recovered from inside
 *     the encryption where it lives
 *   and one altered byte, refused
 */
#define SYS_WRITE       4
#define TK_SZ           56

        CODE
        .p2align 2
        .globl  _main
_main:
        stp     x29, x30, [sp, #-48]!
        mov     x29, sp
        stp     x19, x20, [sp, #16]

        LEA     x0, sendkeys
        LEA     x1, key
        LEA     x2, iv
        bl      SYM(tls_keys_set)

/* ---- one record, whole ---- */
        LEA     x0, sendkeys
        mov     w1, #0x17
        LEA     x2, msg
        mov     w3, #15
        LEA     x4, rec
        bl      SYM(tls_seal)
        mov     w19, w0
        LEA     x0, rec
        mov     w1, w19
        bl      show

/* ---- and the next one, which must not reuse the nonce ---- */
        LEA     x0, sendkeys
        mov     w1, #0x16
        LEA     x2, msg2
        mov     w3, #19
        LEA     x4, rec2
        bl      SYM(tls_seal)
        mov     w20, w0
        LEA     x0, rec2
        mov     w1, w20
        bl      show

/*
 * ---- opened again, by a reader that has only the keys ----
 *
 * A second context, counting from zero, which is what the far side has: it was
 * never told how many records were sent, it counts what it receives. If the
 * two counters can disagree, nothing after the first record decrypts.
 */
        LEA     x0, recvkeys
        LEA     x1, key
        LEA     x2, iv
        bl      SYM(tls_keys_set)

        LEA     x0, recvkeys
        LEA     x1, rec
        mov     w2, w19
        LEA     x3, plain
        LEA     x4, gottype
        bl      SYM(tls_open)
        cmp     w0, #0
        b.le    .Lbad
        mov     w1, w0
        LEA     x0, plain
        bl      show
        LEA     x0, gottype             /* the type, from inside the record */
        mov     w1, #1
        bl      show
        b       .Lshown
.Lbad:  LEA     x0, s_refused
        bl      say
.Lshown:

/*
 * ---- and one byte of it altered ----
 *
 * In the ciphertext rather than the header, because the header has an obvious
 * check behind it and the ciphertext has only the tag. A record layer that
 * authenticated nothing would hand back fifteen plausible bytes here, one of
 * them wrong, and nothing downstream would ever know.
 */
        LEA     x0, recvkeys            /* a reader that has seen nothing yet */
        LEA     x1, key
        LEA     x2, iv
        bl      SYM(tls_keys_set)

        LEA     x9, rec
        ldrb    w10, [x9, #7]
        eor     w10, w10, #1
        strb    w10, [x9, #7]

        LEA     x0, recvkeys
        LEA     x1, rec
        mov     w2, w19
        LEA     x3, plain
        LEA     x4, gottype
        bl      SYM(tls_open)
        cmn     w0, #1
        b.ne    .Ltook
        LEA     x0, s_refused
        bl      say
        b       .Ltamper_done
.Ltook: LEA     x0, s_took
        bl      say
.Ltamper_done:

        mov     w0, #0
        ldp     x19, x20, [sp, #16]
        ldp     x29, x30, [sp], #48
        ret

/* show - x0 = bytes, w1 = how many, as hex and a newline. */
show:   stp     x29, x30, [sp, #-32]!
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
key:
        .byte 0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07
        .byte 0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f
        .byte 0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17
        .byte 0x18, 0x19, 0x1a, 0x1b, 0x1c, 0x1d, 0x1e, 0x1f
iv:
        .byte 0x40, 0x41, 0x42, 0x43, 0x44, 0x45, 0x46, 0x47
        .byte 0x48, 0x49, 0x4a, 0x4b

        CSTR
msg:    .asciz "hello from maos"
msg2:   .asciz "a handshake message"
s_nl:   .asciz "\n"
s_refused: .asciz "refused\n"
s_took:    .asciz "TOOK AN ALTERED RECORD\n"

        BSS
        .p2align 4
sendkeys:   .space TK_SZ
recvkeys:   .space TK_SZ
rec:        .space 256
rec2:       .space 256
plain:      .space 256
gottype:    .space 8
hexbuf:     .space 1024
