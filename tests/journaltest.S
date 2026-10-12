#include "asm.inc"
#include "editor.inc"
/*
 * What the person did, as the editor remembers it.
 *
 * The screen is a photograph and this is the thing it cannot show: that a line
 * was typed just now, that a word was taken back, that the cursor went
 * somewhere else first. The backend has had somewhere to put this for a long
 * time and no client ever sent any, so the model's "what the user just did"
 * briefing was empty for every device that ever connected.
 *
 * Three properties, and the first is the one that keeps it small enough to
 * send: a run of typing is one entry, not one per key.
 */
#define SYS_WRITE       4

        CODE
        .p2align 2
        .globl  _main
_main:
        stp     x29, x30, [sp, #-32]!
        mov     x29, sp

        bl      SYM(editor_init)

/* ---- two keys that are one act of typing ---- */
        LEA     x0, s_hi
        bl      SYM(editor_insert_str)

/* ---- and one taken back ---- */
        mov     w0, #K_BS
        bl      SYM(editor_key)

/* ---- then somewhere else entirely, which cannot join the run above ---- */
        mov     w0, #0
        bl      SYM(editor_cursor_set)
        LEA     x0, s_x
        bl      SYM(editor_insert_str)

        LEA     x0, out
        mov     w1, #512
        bl      SYM(editor_journal_text)
        LEA     x0, out
        bl      say

/*
 * ---- and now it is empty ----
 *
 * Reading drains it, so an action is reported once. Without that every request
 * would carry the whole session, which is the same bug as a log that is never
 * rotated and arrives in somebody's prompt.
 */
        LEA     x0, out
        mov     w1, #512
        bl      SYM(editor_journal_text)
        LEA     x0, out
        ldrb    w9, [x0]
        cbnz    w9, 1f
        LEA     x0, s_drained
        bl      say
        b       2f
1:      LEA     x0, s_notdrained
        bl      say
2:
        mov     w0, #0
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
2:      cbz     w2, 3f
        mov     x0, #1
        mov     x16, #SYS_WRITE
        svc     #0x80
3:      ldp     x29, x30, [sp], #32
        ret

        CSTR
s_hi:           .asciz "hi"
s_x:            .asciz "x"
s_drained:      .asciz "drained\n"
s_notdrained:   .asciz "STILL THERE\n"

        BSS
        .p2align 4
out:    .space 512
