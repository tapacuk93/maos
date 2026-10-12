#include "asm.inc"
#include "editor.inc"
/*
 * editor.S - the editor itself: screens, a text buffer per screen, a cursor,
 * and the drawing of all of it.
 *
 * This file knows nothing about macOS or about bare metal.  It is handed
 * translated keys and a pixel buffer, which is why the identical object code
 * backs both front ends.
 *
 * Each screen is a flat byte buffer with the cursor as an index into it.
 * Inserting memmoves the tail - for 8K of text that costs less than the frame
 * it is drawn in, and it keeps the representation honest and inspectable.
 */
        CODE
        .p2align 2

/* ---------------------------------------------------------------- helpers */

/* cur_screen_ptr -> x0 */
ed_cur:
        LEA     x9, ed_state
        ldr     w10, [x9, #EDS_CURRENT] /* current screen index */
        mov     w11, #SCR_STRIDE
        mul     w10, w10, w11
        LEA     x0, ed_screens
        add     x0, x0, w10, uxtw
        ret

/*
 * ed_metrics - the current cell geometry, derived from the font scale.
 *   out w0 = cell width, w1 = cell height, w2 = bar height, w3 = scale
 * Everything that lays anything out asks here, so Cmd +/- moves all of it at
 * once rather than leaving the caret and the text disagreeing.
 */
        .globl  SYM(ed_metrics)
SYM(ed_metrics):
        LEA     x9, ed_state
        ldr     w3, [x9, #EDS_SCALE]
        cmp     w3, #ED_SCALE_MIN
        b.hs    1f
        mov     w3, #ED_SCALE
1:      mov     w0, #GLYPH_ADV
        mul     w0, w0, w3
        mov     w1, #(GLYPH_H + 2)
        mul     w1, w1, w3
        lsl     w2, w3, #2              /* the bar is one row plus 4*scale */
        add     w2, w2, w1
        ret

/* ed_fg - the ink colour the user last chose, as 0xAARRGGBB */
        .globl  SYM(ed_fg)
SYM(ed_fg):
        LEA     x9, ed_state
        ldr     w10, [x9, #EDS_COLOUR]
        and     w10, w10, #0xF
        cmp     w10, #9
        b.ls    1f
        mov     w10, #0
1:      LEA     x11, ed_palette
        ldr     w0, [x11, w10, uxtw #2]
        ret

/* editor_init - one empty screen, cursor at the top. */
        .globl  SYM(editor_init)
SYM(editor_init):
        LEA     x9, ed_state
        str     wzr, [x9, #0]           /* current = 0 */
        mov     w10, #1
        str     w10, [x9, #4]           /* count = 1  */
        mov     w10, #ED_SCALE
        str     w10, [x9, #EDS_SCALE]
        str     wzr, [x9, #EDS_COLOUR]
        mov     w10, #1                 /* a caret, until something blinks it */
        str     w10, [x9, #EDS_CARET]
        LEA     x0, ed_screens
        str     wzr, [x0, #SCR_LEN_OFF]
        str     wzr, [x0, #SCR_CUR_OFF]
        str     wzr, [x0, #SCR_SCROLL_OFF]
        str     wzr, [x0, #SCR_BASELEN_OFF]
        strb    wzr, [x0, #SCR_SUBJ_OFF]
        ret

/* editor_screen_info - w0 = current index, w1 = count */
        .globl  SYM(editor_screen_info)
SYM(editor_screen_info):
        LEA     x9, ed_state
        ldr     w0, [x9, #0]
        ldr     w1, [x9, #4]
        ret

/* ------------------------------------------------------------ text editing */

/* ed_insert - w0 = byte to insert at the cursor */
ed_insert:
        stp     x29, x30, [sp, #-32]!
        mov     x29, sp
        str     x19, [sp, #16]
        mov     w19, w0
        bl      ed_cur
        ldr     w9, [x0, #SCR_LEN_OFF]
        ldr     w10, [x0, #SCR_CUR_OFF]
        mov     w11, #(SCR_BUF_SZ - 1)
        cmp     w9, w11
        b.hs    1f                      /* buffer full: drop the keystroke */
        add     x12, x0, #SCR_TEXT_OFF
        mov     w13, w9                 /* shift [cur..len) up one, backwards */
2:      cmp     w13, w10
        b.ls    3f
        sub     w14, w13, #1
        ldrb    w15, [x12, w14, uxtw]
        strb    w15, [x12, w13, uxtw]
        sub     w13, w13, #1
        b       2b
3:      strb    w19, [x12, w10, uxtw]
        add     w9, w9, #1
        add     w10, w10, #1
        str     w9, [x0, #SCR_LEN_OFF]
        str     w10, [x0, #SCR_CUR_OFF]
        sub     w0, w10, #1             /* where it went, for the journal */
        mov     w1, w19
        bl      ed_journal_ins
1:      ldr     x19, [sp, #16]
        ldp     x29, x30, [sp], #32
        ret

/* ed_backspace - delete the byte before the cursor */
ed_backspace:
        stp     x29, x30, [sp, #-16]!
        mov     x29, sp
        bl      ed_cur
        ldr     w9, [x0, #SCR_LEN_OFF]
        ldr     w10, [x0, #SCR_CUR_OFF]
        cbz     w10, 1f
        add     x12, x0, #SCR_TEXT_OFF
        mov     w13, w10
2:      cmp     w13, w9
        b.hs    3f
        sub     w14, w13, #1
        ldrb    w15, [x12, w13, uxtw]
        strb    w15, [x12, w14, uxtw]
        add     w13, w13, #1
        b       2b
3:      sub     w9, w9, #1
        sub     w10, w10, #1
        str     w9, [x0, #SCR_LEN_OFF]
        str     w10, [x0, #SCR_CUR_OFF]
        mov     w0, w10
        bl      ed_journal_del
1:      ldp     x29, x30, [sp], #16
        ret

/*
 * editor_whoami - x0 = the name the backend authenticated, or 0 for nobody.
 *
 * Set once, when the far side answers the session call, and drawn in the bar
 * from then on. The editor does not invent it and has no default for it: a
 * name a device made up says nothing, and the whole value of showing one is
 * that it came from the machine holding the key.
 */
#define WHO_MAX         32

        .globl  SYM(editor_whoami)
SYM(editor_whoami):
        LEA     x9, ed_whoami
        cbz     x0, 2f
        mov     w10, #0
1:      cmp     w10, #(WHO_MAX - 1)
        b.hs    2f
        ldrb    w11, [x0, w10, uxtw]
        cbz     w11, 2f
        strb    w11, [x9, w10, uxtw]
        add     w10, w10, #1
        b       1b
2:      mov     w10, w10
        strb    wzr, [x9, w10, uxtw]
        ret

/* editor_known - w0 = 1 once somebody has been named. */
        .globl  SYM(editor_known)
SYM(editor_known):
        LEA     x9, ed_whoami
        ldrb    w0, [x9]
        cmp     w0, #0
        cset    w0, ne
        ret

/* ------------------------------------------------------------------ journal

 * What the person has been doing, which is not what the screen says.
 *
 * The backend is handed the screen on every request and that is a photograph:
 * it shows "x = 1" and cannot show that the line was typed just now, or typed
 * and deleted twice, or that the number was 2 a moment ago. The difference
 * matters to the answer. "make it faster" about a function somebody has been
 * editing for ten minutes is a different request from the same words about a
 * function they just pasted in.
 *
 * So the core records, and goes on doing no I/O: this is a ring in memory that
 * the front end drains into whatever request it is already making. The editor
 * still does not know what a network is.
 *
 * <h2>Runs, not keystrokes</h2>
 *
 * One entry per key would fill sixteen slots with the word "hello" and tell
 * the model nothing it could not see. Consecutive inserts that advance by one
 * are the same act of typing and become one entry; consecutive backspaces are
 * one deletion. What comes out is "typed this here, took that back" - which is
 * what a person watching over a shoulder would have reported.
 *
 * <h2>Small on purpose</h2>
 *
 * Sixteen entries, thirty-two bytes of text each. When it fills, the oldest
 * goes. This is the recent past and not an undo log: nothing is reconstructed
 * from it, it is read once by a model as context and then forgotten, and a
 * complete history would cost more to send than the screen it describes.
 */
#define JN_MAX          16
#define JN_TEXT         32
#define JN_OP           0
#define JN_AT           4
#define JN_LEN          8
#define JN_TXT          12
#define JN_SZ           (JN_TXT + JN_TEXT)

/* ed_journal_ins - w0 = where, w1 = the byte that went there. */
ed_journal_ins:
        LEA     x9, jn_count
        ldr     w10, [x9]
        cbz     w10, .Ljn_new_i

        LEA     x9, jn_ring            /* the entry before this one */
        sub     w11, w10, #1
        mov     w12, #JN_SZ
        madd    x11, x11, x12, x9
        ldr     w12, [x11, #JN_OP]
        cmp     w12, #'i'
        b.ne    .Ljn_new_i
        ldr     w13, [x11, #JN_LEN]
        cmp     w13, #JN_TEXT
        b.hs    .Ljn_new_i
        ldr     w14, [x11, #JN_AT]      /* does this one carry on from it? */
        add     w14, w14, w13
        cmp     w14, w0
        b.ne    .Ljn_new_i
        add     x15, x11, #JN_TXT
        strb    w1, [x15, w13, uxtw]
        add     w13, w13, #1
        str     w13, [x11, #JN_LEN]
        ret

.Ljn_new_i:
        stp     x29, x30, [sp, #-32]!
        mov     x29, sp
        stp     x19, x20, [sp, #16]
        mov     w19, w0
        mov     w20, w1
        bl      ed_journal_room
        mov     w9, #'i'
        str     w9, [x0, #JN_OP]
        str     w19, [x0, #JN_AT]
        mov     w9, #1
        str     w9, [x0, #JN_LEN]
        add     x9, x0, #JN_TXT
        strb    w20, [x9]
        ldp     x19, x20, [sp, #16]
        ldp     x29, x30, [sp], #32
        ret

/*
 * ed_journal_key - x0 = a short name for what was just pressed.
 *
 * Typing and deleting were being recorded and commands were not, which left
 * the far side reading a history where text appeared and screens changed for
 * no reason: somebody presses Cmd+T, types, presses Cmd+left, and the journal
 * said only that two runs of text were typed, in one place, with nothing
 * between them. The commands are most of what a person does that is not
 * typing, and they are the half that explains the other half.
 *
 * Never coalesced. Two presses of the same key are two things that happened -
 * unlike two letters, which are one word - and a page down pressed twice is a
 * different place from a page down pressed once.
 */
ed_journal_key:
        stp     x29, x30, [sp, #-32]!
        mov     x29, sp
        str     x19, [sp, #16]
        mov     x19, x0
        bl      ed_journal_room
        mov     w9, #'k'
        str     w9, [x0, #JN_OP]
        str     wzr, [x0, #JN_AT]
        add     x10, x0, #JN_TXT
        mov     w11, #0
1:      cmp     w11, #(JN_TEXT - 1)
        b.hs    2f
        ldrb    w12, [x19, w11, uxtw]
        cbz     w12, 2f
        strb    w12, [x10, w11, uxtw]
        add     w11, w11, #1
        b       1b
2:      str     w11, [x0, #JN_LEN]
        ldr     x19, [sp, #16]
        ldp     x29, x30, [sp], #32
        ret

/* ed_journal_del - w0 = where the byte that went was. */
ed_journal_del:
        LEA     x9, jn_count
        ldr     w10, [x9]
        cbz     w10, .Ljn_new_d

        LEA     x9, jn_ring
        sub     w11, w10, #1
        mov     w12, #JN_SZ
        madd    x11, x11, x12, x9
        ldr     w12, [x11, #JN_OP]
        cmp     w12, #'d'
        b.ne    .Ljn_new_d
        ldr     w14, [x11, #JN_AT]      /* backspace walks backwards, so the
                                           run grows downwards from where it
                                           started rather than upwards */
        sub     w14, w14, #1
        cmp     w14, w0
        b.ne    .Ljn_new_d
        str     w0, [x11, #JN_AT]
        ldr     w13, [x11, #JN_LEN]
        add     w13, w13, #1
        str     w13, [x11, #JN_LEN]
        ret

.Ljn_new_d:
        stp     x29, x30, [sp, #-32]!
        mov     x29, sp
        str     x19, [sp, #16]
        mov     w19, w0
        bl      ed_journal_room
        mov     w9, #'d'
        str     w9, [x0, #JN_OP]
        str     w19, [x0, #JN_AT]
        mov     w9, #1
        str     w9, [x0, #JN_LEN]
        ldr     x19, [sp, #16]
        ldp     x29, x30, [sp], #32
        ret

/*
 * ed_journal_room - out x0 = an empty entry at the end, oldest dropped if full.
 *
 * Shifting the whole ring down costs a few hundred bytes of copying and
 * happens once every sixteen distinct actions. A head index would avoid it and
 * would have to be unwound by every reader; this way the entries are always in
 * order from the start, which is the only property the reader wants.
 */
ed_journal_room:
        LEA     x9, jn_count
        ldr     w10, [x9]
        cmp     w10, #JN_MAX
        b.lo    2f
        LEA     x11, jn_ring            /* drop the oldest */
        mov     w12, #0
        mov     w13, #(JN_SZ * (JN_MAX - 1))
1:      cmp     w12, w13
        b.hs    3f
        add     x14, x11, #JN_SZ
        ldrb    w15, [x14, w12, uxtw]
        strb    w15, [x11, w12, uxtw]
        add     w12, w12, #1
        b       1b
3:      mov     w10, #(JN_MAX - 1)
2:      LEA     x11, jn_ring
        mov     w12, #JN_SZ
        madd    x0, x10, x12, x11
        add     w10, w10, #1
        LEA     x9, jn_count
        str     w10, [x9]
        ret

/*
 * editor_journal_text - x0 = where to write it, w1 = room.
 *   out w0 = bytes written, and the ring is empty afterwards.
 *
 * One line per entry: "ins <offset> <what>" or "del <offset> <how many>".
 * Three words and a number, like everything else this project sends to a
 * reader with no parser - here the reader is a language model, which has even
 * less use for JSON than a kernel does.
 *
 * Emptied by reading, because the alternative is the front end remembering
 * what it has already sent, and the thing that goes wrong with that is sending
 * the same five minutes of typing with every request for the rest of the
 * session.
 */
        .globl  SYM(editor_journal_text)
SYM(editor_journal_text):
        stp     x29, x30, [sp, #-64]!
        mov     x29, sp
        stp     x19, x20, [sp, #16]
        stp     x21, x22, [sp, #32]
        stp     x23, x24, [sp, #48]
        mov     x19, x0                 /* the cursor into the output */
        add     x20, x0, w1, uxtw
        sub     x20, x20, #48           /* one line's worth of headroom */
        mov     x24, x0
        LEA     x9, jn_count
        ldr     w21, [x9]
        mov     w22, #0

1:      cmp     w22, w21
        b.hs    9f
        cmp     x19, x20
        b.hs    9f
        LEA     x9, jn_ring
        mov     w10, #JN_SZ
        madd    x23, x22, x10, x9

        ldr     w9, [x23, #JN_OP]
        cmp     w9, #'k'
        b.ne    .Ljt_edit
        LEA     x1, s_jn_key            /* a command, which has no offset */
        mov     x0, x19
        bl      SYM(str_copy)
        mov     x19, x0
        ldr     w10, [x23, #JN_LEN]
        add     x11, x23, #JN_TXT
        mov     w12, #0
.Ljt_name:
        cmp     w12, w10
        b.hs    .Ljt_named
        ldrb    w13, [x11, w12, uxtw]
        strb    w13, [x19], #1
        add     w12, w12, #1
        b       .Ljt_name
.Ljt_named:
        mov     w9, #'\n'
        strb    w9, [x19], #1
        add     w22, w22, #1
        b       1b
.Ljt_edit:
        cmp     w9, #'i'
        b.ne    2f
        LEA     x1, s_jn_ins
        b       3f
2:      LEA     x1, s_jn_del
3:      mov     x0, x19
        bl      SYM(str_copy)
        mov     x19, x0

        ldr     w1, [x23, #JN_AT]
        mov     x0, x19
        bl      SYM(u32_dec)
        mov     x19, x0
        mov     w9, #' '
        strb    w9, [x19], #1

        ldr     w9, [x23, #JN_OP]
        cmp     w9, #'i'
        b.ne    5f
        ldr     w10, [x23, #JN_LEN]     /* the text, as it was typed */
        add     x11, x23, #JN_TXT
        mov     w12, #0
4:      cmp     w12, w10
        b.hs    6f
        ldrb    w13, [x11, w12, uxtw]
        strb    w13, [x19], #1
        add     w12, w12, #1
        b       4b
5:      ldr     w1, [x23, #JN_LEN]      /* or how many went */
        mov     x0, x19
        bl      SYM(u32_dec)
        mov     x19, x0
6:      mov     w9, #'\n'
        strb    w9, [x19], #1
        add     w22, w22, #1
        b       1b

9:      strb    wzr, [x19]
        LEA     x9, jn_count            /* read once, and then gone */
        str     wzr, [x9]
        sub     x0, x19, x24
        ldp     x23, x24, [sp, #48]
        ldp     x21, x22, [sp, #32]
        ldp     x19, x20, [sp, #16]
        ldp     x29, x30, [sp], #64
        ret

/* ----------------------------------------------------------- screen switch */

/* ed_screen_new - append a fresh screen and switch to it */
ed_screen_new:
        LEA     x9, ed_state
        ldr     w10, [x9, #4]           /* count */
        cmp     w10, #MAX_SCREENS
        b.hs    1f
        mov     w11, #SCR_STRIDE
        mul     w12, w10, w11
        LEA     x13, ed_screens
        add     x13, x13, w12, uxtw
        str     wzr, [x13, #SCR_LEN_OFF]
        str     wzr, [x13, #SCR_CUR_OFF]
        str     wzr, [x13, #SCR_SCROLL_OFF]
        str     wzr, [x13, #SCR_BASELEN_OFF]
        strb    wzr, [x13, #SCR_SUBJ_OFF]
        str     w10, [x9, #0]           /* make it current */
        add     w10, w10, #1
        str     w10, [x9, #4]
1:      ret

/* ed_screen_close - drop the current screen; the last one is never closed */
ed_screen_close:
        LEA     x9, ed_state
        ldr     w0, [x9, #0]            /* current */
        ldr     w1, [x9, #4]            /* count   */
        cmp     w1, #2
        b.lo    9f
        mov     w2, #SCR_STRIDE
        mul     w3, w0, w2              /* byte offset of current */
        LEA     x4, ed_screens
        add     x5, x4, w3, uxtw        /* dst */
        add     w6, w0, #1
1:      cmp     w6, w1                  /* slide the later screens down */
        b.hs    2f
        mul     w7, w6, w2
        add     x10, x4, w7, uxtw       /* src */
        mov     w11, #0
3:      ldr     w12, [x10, w11, uxtw]
        str     w12, [x5, w11, uxtw]
        add     w11, w11, #4
        cmp     w11, w2
        b.lo    3b
        add     x5, x5, w2, uxtw
        add     w6, w6, #1
        b       1b
2:      sub     w1, w1, #1
        str     w1, [x9, #4]
        cmp     w0, w1
        b.lo    4f
        sub     w0, w1, #1
4:      str     w0, [x9, #0]
9:      ret

/*
 * editor_screen_step - w0 = +1 or -1; the next or previous screen.
 * A swipe across the surface means this, so it is part of the interface
 * rather than an internal detail of the Cmd+arrow keys.
 */
        .globl  SYM(editor_screen_step)
SYM(editor_screen_step):
        b       ed_screen_step

/* ed_screen_step - w0 = +1 or -1, wrapping */
ed_screen_step:
        LEA     x9, ed_state
        ldr     w1, [x9, #0]
        ldr     w2, [x9, #4]
        add     w1, w1, w0
        cmp     w1, #0
        b.ge    1f
        sub     w1, w2, #1              /* wrap to the last screen */
1:      cmp     w1, w2
        b.lo    2f
        mov     w1, #0                  /* wrap to the first */
2:      str     w1, [x9, #0]
        ret

/* ------------------------------------------------------------ key dispatch */

/*
 * editor_key - w0 = key code, optionally OR'd with MOD_CMD.
 * The command set the user asked for:
 *   Cmd + Left / Right   previous / next screen
 *   Cmd + T              new screen
 *   Cmd + Backspace      close this screen
 *   Cmd + Enter          hand this screen to the backend
 *   Cmd + Up / Down      page up / page down
 *   Cmd + Shift + Up     the very start of this screen
 *   Cmd + Shift + Down   the very end of it
 *   Cmd + P              rewrite what is on screen, in place
 *
 * Returns ED_NONE, ED_SUBMIT or ED_AIFY.  The editor core does no I/O: the
 * front end owns the network, and neither of them knows what is on the far
 * side of the backend.
 */
        .globl  SYM(editor_key)
SYM(editor_key):
        stp     x29, x30, [sp, #-48]!
        mov     x29, sp
        stp     x19, x20, [sp, #16]
        mov     w19, w0
        mov     w20, #ED_NONE           /* what to tell the front end */

        tst     w19, #MOD_CMD
        b.eq    .Lplain

        tst     w19, #MOD_SHIFT         /* Cmd+Shift goes to the ends */
        b.eq    .Lcmd_only
        and     w19, w19, #0xFFF
        mov     w9, #K_UP
        cmp     w19, w9
        b.ne    1f
        bl      ed_home
        b       .Lkey_out
1:      mov     w9, #K_DOWN
        cmp     w19, w9
        b.ne    .Lkey_out
        bl      ed_end
        b       .Lkey_out

.Lcmd_only:
        and     w19, w19, #0xFFF        /* strip the modifier */
        mov     w9, #K_LEFT
        cmp     w19, w9
        b.ne    1f
        mov     w0, #-1
        bl      ed_screen_step
        LEA     x0, k_prev
        bl      ed_journal_key
        b       .Lkey_out
1:      mov     w9, #K_RIGHT
        cmp     w19, w9
        b.ne    1f
        mov     w0, #1
        bl      ed_screen_step
        LEA     x0, k_next
        bl      ed_journal_key
        b       .Lkey_out
1:      mov     w9, #K_UP
        cmp     w19, w9
        b.ne    1f
        mov     w0, #-1
        bl      ed_page
        b       .Lkey_out
1:      mov     w9, #K_DOWN
        cmp     w19, w9
        b.ne    2f
        mov     w0, #1
        bl      ed_page
        b       .Lkey_out
2:      cmp     w19, #'t'
        b.eq    3f
        cmp     w19, #'T'
        b.ne    2f
3:      bl      ed_screen_new
        LEA     x0, k_newscreen
        bl      ed_journal_key
        b       .Lkey_out
2:      cmp     w19, #'p'
        b.eq    .Laify
        cmp     w19, #'P'
        b.ne    .Lappearance
.Laify: mov     w20, #ED_AIFY
        LEA     x0, k_rewrite
        bl      ed_journal_key
        b       .Lkey_out

.Lappearance:                           /* Cmd +/- resizes, Cmd 0-9 recolours */
        cmp     w19, #'='
        b.eq    .Lbigger
        cmp     w19, #'+'
        b.eq    .Lbigger
        cmp     w19, #'-'
        b.eq    .Lsmaller
        cmp     w19, #'_'
        b.eq    .Lsmaller
        sub     w9, w19, #'0'
        cmp     w9, #9
        b.hi    4f                      /* not a digit either: keep looking */
        LEA     x10, ed_state
        str     w9, [x10, #EDS_COLOUR]
        LEA     x0, k_colour
        bl      ed_journal_key
        b       .Lkey_out
.Lbigger:
        mov     w0, #1
        bl      ed_zoom
        LEA     x0, k_bigger
        bl      ed_journal_key
        b       .Lkey_out
.Lsmaller:
        mov     w0, #-1
        bl      ed_zoom
        LEA     x0, k_smaller
        bl      ed_journal_key
        b       .Lkey_out
4:      cmp     w19, #K_ENTER
        b.eq    .Lsubmit
        cmp     w19, #0x0D
        b.eq    .Lsubmit
        cmp     w19, #K_BS
        b.eq    5f
        cmp     w19, #0x7F              /* macOS sends DEL for Backspace */
        b.ne    .Lkey_out
5:      bl      ed_screen_close
        LEA     x0, k_closescreen
        bl      ed_journal_key
        b       .Lkey_out
.Lsubmit:
        mov     w20, #ED_SUBMIT
        LEA     x0, k_handover
        bl      ed_journal_key
        b       .Lkey_out

.Lplain:
        cmp     w19, #K_BS
        b.eq    .Lback
        cmp     w19, #0x7F
        b.eq    .Lback
        mov     w9, #K_LEFT
        cmp     w19, w9
        b.eq    .Lcurs_left
        mov     w9, #K_RIGHT
        cmp     w19, w9
        b.eq    .Lcurs_right
        mov     w9, #K_UP
        cmp     w19, w9
        b.eq    .Lcurs_up
        mov     w9, #K_DOWN
        cmp     w19, w9
        b.eq    .Lcurs_down
        cmp     w19, #0x0D              /* CR from a terminal */
        b.ne    6f
        mov     w19, #K_ENTER
6:      cmp     w19, #K_ENTER
        b.eq    7f
        cmp     w19, #0x20              /* anything else must be printable */
        b.lo    .Lkey_out
        cmp     w19, #0x7E
        b.hi    .Lkey_out
7:      mov     w0, w19
        bl      ed_insert
        b       .Lkey_out

.Lback: bl      ed_backspace
        b       .Lkey_out

.Lcurs_left:
        bl      ed_cur
        ldr     w9, [x0, #SCR_CUR_OFF]
        cbz     w9, .Lkey_out
        sub     w9, w9, #1
        str     w9, [x0, #SCR_CUR_OFF]
        b       .Lkey_out
.Lcurs_right:
        bl      ed_cur
        ldr     w9, [x0, #SCR_CUR_OFF]
        ldr     w10, [x0, #SCR_LEN_OFF]
        cmp     w9, w10
        b.hs    .Lkey_out
        add     w9, w9, #1
        str     w9, [x0, #SCR_CUR_OFF]
        b       .Lkey_out
.Lcurs_up:
        mov     w0, #-1
        bl      ed_cursor_line
        b       .Lkey_out
.Lcurs_down:
        mov     w0, #1
        bl      ed_cursor_line

.Lkey_out:
        mov     w0, w20
        ldp     x19, x20, [sp, #16]
        ldp     x29, x30, [sp], #48
        ret

/* ed_zoom - w0 = +1 or -1; step the font scale, clamped to what is legible */
ed_zoom:
        LEA     x9, ed_state
        ldr     w10, [x9, #EDS_SCALE]
        cmp     w10, #ED_SCALE_MIN
        b.hs    1f
        mov     w10, #ED_SCALE
1:      add     w10, w10, w0
        cmp     w10, #ED_SCALE_MIN
        b.hs    2f
        mov     w10, #ED_SCALE_MIN
2:      cmp     w10, #ED_SCALE_MAX
        b.ls    3f
        mov     w10, #ED_SCALE_MAX
3:      str     w10, [x9, #EDS_SCALE]
        ret

/*
 * ed_band - w0 = where a line starts; out w0 = 0, 1 for $$ or 2 for $$$.
 *
 * Two marks with two meanings, and they are not the same kind of thing.
 *
 * $$ is about privacy: the text after it is a reference to something the far
 * side is never shown, substituted back on this machine. A line carrying one
 * holds a secret that is nobody else's, and the editor says so.
 *
 * $$$ is about money: the request is this person's alone, so nothing is kept
 * from it, nothing is counted towards anybody else wanting it, and the coins it
 * spends are spent for them and not towards the project.
 *
 * Both are read here rather than anywhere cleverer because both are a property
 * of the first characters of a line, and the editor is the one thing that sees
 * the line before anybody decides anything about it.
 */
ed_band:
        stp     x29, x30, [sp, #-32]!
        mov     x29, sp
        str     x19, [sp, #16]
        mov     w19, w0
        bl      ed_cur
        ldr     w9, [x0, #SCR_LEN_OFF]
        add     x10, x0, #SCR_TEXT_OFF
        add     w11, w19, #1
        cmp     w11, w9
        b.hs    .Lband_none             /* two characters is the shortest mark */
        ldrb    w12, [x10, w19, uxtw]
        cmp     w12, #'$'
        b.ne    .Lband_none
        ldrb    w12, [x10, w11, uxtw]
        cmp     w12, #'$'
        b.ne    .Lband_none
        add     w11, w11, #1
        cmp     w11, w9
        b.hs    .Lband_priv
        ldrb    w12, [x10, w11, uxtw]
        cmp     w12, #'$'
        b.ne    .Lband_priv
        mov     w0, #2
        b       .Lband_out
.Lband_priv:
        mov     w0, #1
        b       .Lband_out
.Lband_none:
        mov     w0, #0
.Lband_out:
        ldr     x19, [sp, #16]
        ldp     x29, x30, [sp], #32
        ret

/* ed_home - the very start of this screen, scrolled back to the top */
ed_home:
        stp     x29, x30, [sp, #-16]!
        mov     x29, sp
        bl      ed_cur
        str     wzr, [x0, #SCR_CUR_OFF]
        str     wzr, [x0, #SCR_SCROLL_OFF]
        ldp     x29, x30, [sp], #16
        ret

/* ed_end - the very end of it; the render pass scrolls to follow the caret */
ed_end:
        stp     x29, x30, [sp, #-16]!
        mov     x29, sp
        bl      ed_cur
        ldr     w9, [x0, #SCR_LEN_OFF]
        str     w9, [x0, #SCR_CUR_OFF]
        ldp     x29, x30, [sp], #16
        ret

/*
 * ed_page - move the cursor a screenful, w0 = -1 (up) or +1 (down).
 * A page is however many rows the last render fitted, less one line of
 * overlap so the reader keeps their place.
 */
ed_page:
        stp     x29, x30, [sp, #-48]!
        mov     x29, sp
        stp     x19, x20, [sp, #16]
        mov     w19, w0
        LEA     x9, ed_state
        ldr     w20, [x9, #12]          /* visible rows */
        cmp     w20, #2
        b.hs    1f
        mov     w20, #2
1:      sub     w20, w20, #1
2:      cbz     w20, 3f
        mov     w0, w19
        bl      ed_cursor_line
        sub     w20, w20, #1
        b       2b
3:      ldp     x19, x20, [sp, #16]
        ldp     x29, x30, [sp], #48
        ret

/*
 * ed_cursor_line - move the cursor one display line, w0 = -1 or +1.
 * Lines are delimited by '\n'; the column is preserved where the target line
 * is long enough, clamped to its end where it is not.
 */
ed_cursor_line:
        stp     x29, x30, [sp, #-48]!
        mov     x29, sp
        stp     x19, x20, [sp, #16]
        stp     x21, x22, [sp, #32]
        mov     w19, w0
        bl      ed_cur
        mov     x20, x0
        add     x21, x20, #SCR_TEXT_OFF
        ldr     w22, [x20, #SCR_CUR_OFF]
        ldr     w9, [x20, #SCR_LEN_OFF]

        mov     w10, w22                /* find the start of this line */
1:      cbz     w10, 2f
        sub     w11, w10, #1
        ldrb    w12, [x21, w11, uxtw]
        cmp     w12, #'\n'
        b.eq    2f
        sub     w10, w10, #1
        b       1b
2:      sub     w13, w22, w10           /* column */

        cmp     w19, #0
        b.gt    .Ldown

        cbz     w10, .Lline_out         /* already on the first line */
        sub     w14, w10, #1            /* end of the previous line */
        mov     w15, w14
3:      cbz     w15, 4f
        sub     w11, w15, #1
        ldrb    w12, [x21, w11, uxtw]
        cmp     w12, #'\n'
        b.eq    4f
        sub     w15, w15, #1
        b       3b
4:      sub     w16, w14, w15           /* length of that line */
        cmp     w13, w16
        csel    w13, w13, w16, lo
        add     w22, w15, w13
        b       .Lline_store

.Ldown:
        mov     w15, w22                /* find the start of the next line */
5:      cmp     w15, w9
        b.hs    .Lline_out
        ldrb    w12, [x21, w15, uxtw]
        add     w15, w15, #1
        cmp     w12, #'\n'
        b.ne    5b
        mov     w16, #0                 /* its length */
        mov     w17, w15
6:      cmp     w17, w9
        b.hs    7f
        ldrb    w12, [x21, w17, uxtw]
        cmp     w12, #'\n'
        b.eq    7f
        add     w16, w16, #1
        add     w17, w17, #1
        b       6b
7:      cmp     w13, w16
        csel    w13, w13, w16, lo
        add     w22, w15, w13

.Lline_store:
        str     w22, [x20, #SCR_CUR_OFF]
.Lline_out:
        ldp     x21, x22, [sp, #32]
        ldp     x19, x20, [sp, #16]
        ldp     x29, x30, [sp], #48
        ret

/*
 * editor_insert_str - x0 = NUL-terminated text, inserted at the cursor.
 * Used to seed a screen and, later, to drop Claude's replies into the buffer.
 */
        .globl  SYM(editor_insert_str)
SYM(editor_insert_str):
        stp     x29, x30, [sp, #-32]!
        mov     x29, sp
        str     x19, [sp, #16]
        mov     x19, x0
1:      ldrb    w0, [x19], #1
        cbz     w0, 2f
        bl      ed_insert
        b       1b
2:      ldr     x19, [sp, #16]
        ldp     x29, x30, [sp], #32
        ret

/*
 * editor_click - put the cursor where the pointer is.
 *   w0 = x in pixels, w1 = y in pixels, both in the editor's own surface
 * A click in the screen bar selects that screen instead.
 */
        .globl  SYM(editor_click)
SYM(editor_click):
        stp     x29, x30, [sp, #-80]!
        mov     x29, sp
        stp     x19, x20, [sp, #16]
        stp     x21, x22, [sp, #32]
        stp     x23, x24, [sp, #48]
        mov     w19, w0
        mov     w20, w1
        bl      SYM(ed_metrics)         /* whatever size the font is now */
        mov     w22, w0                 /* cell width  */
        mov     w23, w1                 /* cell height */
        mov     w24, w2                 /* bar height  */

        cmp     w20, w24
        b.hs    .Lclick_text
        mov     w9, #10                 /* the digits start after the brand */
        mul     w9, w9, w22
        subs    w9, w19, w9
        b.mi    .Lclick_out
        lsl     w10, w22, #1
        udiv    w9, w9, w10             /* which digit was hit */
        bl      SYM(editor_screen_info) /* w0 = current, w1 = count */
        cmp     w9, w1
        b.hs    .Lclick_out
        LEA     x10, ed_state
        str     w9, [x10, #0]
        b       .Lclick_out

.Lclick_text:
        mov     w0, w19
        mov     w1, w20
        bl      SYM(editor_offset_at)
        tbnz    w0, #31, .Lclick_out
        mov     w9, w0
        bl      ed_cur
        str     w9, [x0, #SCR_CUR_OFF]

.Lclick_out:
        ldp     x23, x24, [sp, #48]
        ldp     x21, x22, [sp, #32]
        ldp     x19, x20, [sp, #16]
        ldp     x29, x30, [sp], #80
        ret

/*
 * editor_screen_open - go to the screen about this subject, making it if there
 * is none.
 *   x0 = subject, a short tag like "word:build" or "applet:key-q"
 *
 * Long-pressing a thing opens a screen bound to that thing.  The subject is
 * what makes it bound: it travels with every request from that screen, so
 * "make it faster" typed there is understood to be about the build, and the
 * user never has to restate the context they just pointed at.
 */
        .globl  SYM(editor_screen_open)
SYM(editor_screen_open):
        stp     x29, x30, [sp, #-64]!
        mov     x29, sp
        stp     x19, x20, [sp, #16]
        stp     x21, x22, [sp, #32]
        mov     x19, x0

        LEA     x9, ed_state            /* is one already open for it? */
        ldr     w20, [x9, #EDS_COUNT]
        mov     w21, #0
1:      cmp     w21, w20
        b.hs    2f
        mov     w9, #SCR_STRIDE
        mul     w9, w21, w9
        LEA     x22, ed_screens
        add     x22, x22, w9, uxtw
        add     x0, x22, #SCR_SUBJ_OFF
        mov     x1, x19
        bl      SYM(str_eq)
        cbnz    w0, 3f
        add     w21, w21, #1
        b       1b

2:      bl      ed_screen_new           /* none: take a fresh one */
        LEA     x9, ed_state
        ldr     w21, [x9, #EDS_CURRENT]
        mov     w9, #SCR_STRIDE
        mul     w9, w21, w9
        LEA     x22, ed_screens
        add     x22, x22, w9, uxtw
        add     x0, x22, #SCR_SUBJ_OFF  /* remember what it is about */
        mov     x1, x19
        mov     w2, #SCR_SUBJ_MAX
        bl      ed_copy_bounded
        b       4f

3:      LEA     x9, ed_state            /* switch to the one that exists */
        str     w21, [x9, #EDS_CURRENT]
4:      bl      SYM(editor_selection_clear)
        ldp     x21, x22, [sp, #32]
        ldp     x19, x20, [sp, #16]
        ldp     x29, x30, [sp], #64
        ret

/* ed_copy_bounded - x0 = dst, x1 = src, w2 = capacity including the NUL */
ed_copy_bounded:
        sub     w2, w2, #1
        mov     w3, #0
1:      cmp     w3, w2
        b.hs    2f
        ldrb    w4, [x1, w3, uxtw]
        strb    w4, [x0, w3, uxtw]
        cbz     w4, 3f
        add     w3, w3, #1
        b       1b
2:      strb    wzr, [x0, w3, uxtw]
3:      ret

/*
 * editor_busy - w0 = 1 while a request is in flight, 0 when it is done.
 *
 * A caret blinking away while the far side thinks says "type something", which
 * is the wrong thing to say. So while this is set the caret simply stops -
 * still visible, still where the answer will land, just not blinking. Nothing
 * else is drawn to announce the wait: a marker in the text would be one more
 * thing to take back out again when the answer arrives.
 */
        .globl  SYM(editor_busy)
SYM(editor_busy):
        LEA     x9, ed_state
        str     w0, [x9, #EDS_BUSY]
        mov     w10, #1                 /* coming back from a wait, show the
                                           caret at once rather than on the
                                           next half-second */
        str     w10, [x9, #EDS_CARET]
        ret

/*
 * editor_caret_blink - flip the caret's phase, and say whether to redraw.
 *   out w0 = 1 when something changed on screen
 *
 * The front end calls this on a timer.  While a request is out there is no
 * caret to blink, so it reports no change and the screen is left alone - a
 * blink during a wait would be exactly the wrong signal.
 */
        .globl  SYM(editor_caret_blink)
SYM(editor_caret_blink):
        LEA     x9, ed_state
        ldr     w10, [x9, #EDS_BUSY]
        cbz     w10, 1f
        mov     w0, #0
        ret
1:      ldr     w10, [x9, #EDS_CARET]
        eor     w10, w10, #1
        str     w10, [x9, #EDS_CARET]
        mov     w0, #1
        ret

/*
 * editor_line_bounds - the line the caret is on.
 *   x0 = out first byte, x1 = out one past the last
 *
 * This is what Cmd+P replaces when nothing is selected: the user typed an
 * instruction on a line, and the answer takes its place.
 */
        .globl  SYM(editor_line_bounds)
SYM(editor_line_bounds):
        stp     x29, x30, [sp, #-48]!
        mov     x29, sp
        stp     x19, x20, [sp, #16]
        mov     x19, x0
        mov     x20, x1
        bl      ed_cur
        ldr     w9, [x0, #SCR_LEN_OFF]
        ldr     w10, [x0, #SCR_CUR_OFF]
        add     x11, x0, #SCR_TEXT_OFF
        cmp     w10, w9
        csel    w10, w10, w9, lo

        mov     w12, w10                /* back to the start of the line */
1:      cbz     w12, 2f
        sub     w13, w12, #1
        ldrb    w14, [x11, w13, uxtw]
        cmp     w14, #'\n'
        b.eq    2f
        sub     w12, w12, #1
        b       1b
2:      mov     w13, w10                /* forward to the end of it */
3:      cmp     w13, w9
        b.hs    4f
        ldrb    w14, [x11, w13, uxtw]
        cmp     w14, #'\n'
        b.eq    4f
        add     w13, w13, #1
        b       3b
4:      str     w12, [x19]
        str     w13, [x20]
        ldp     x19, x20, [sp, #16]
        ldp     x29, x30, [sp], #48
        ret

/*
 * editor_replace - w0 = first byte, w1 = one past the last, x2 = new text.
 *
 * Cut the range out, put the text in its place, and leave the caret at the
 * end of what was inserted.  Everything either side of the range is
 * untouched, which is the difference between replacing an instruction and
 * wiping a screen.
 */
        .globl  SYM(editor_replace)
SYM(editor_replace):
        stp     x29, x30, [sp, #-64]!
        mov     x29, sp
        stp     x19, x20, [sp, #16]
        stp     x21, x22, [sp, #32]
        mov     w19, w0
        mov     w20, w1
        mov     x21, x2

        bl      ed_cur
        mov     x22, x0
        ldr     w9, [x22, #SCR_LEN_OFF]
        cmp     w20, w9                 /* clamp to what is actually there */
        csel    w20, w20, w9, lo
        cmp     w19, w20
        csel    w19, w19, w20, lo

        add     x10, x22, #SCR_TEXT_OFF /* close the gap */
        mov     w11, w20
        mov     w12, w19
1:      cmp     w11, w9
        b.hs    2f
        ldrb    w13, [x10, w11, uxtw]
        strb    w13, [x10, w12, uxtw]
        add     w11, w11, #1
        add     w12, w12, #1
        b       1b
2:      str     w12, [x22, #SCR_LEN_OFF]
        str     w19, [x22, #SCR_CUR_OFF]

        mov     x0, x21                 /* and type the new text in at it */
        bl      SYM(editor_insert_str)
        ldp     x21, x22, [sp, #32]
        ldp     x19, x20, [sp, #16]
        ldp     x29, x30, [sp], #64
        ret

/*
 * editor_range_text - w0 = first byte, w1 = one past the last,
 *                     x2 = destination, w3 = capacity; out w0 = length
 */
        .globl  SYM(editor_range_text)
SYM(editor_range_text):
        stp     x29, x30, [sp, #-64]!
        mov     x29, sp
        stp     x19, x20, [sp, #16]
        stp     x21, x22, [sp, #32]
        mov     w19, w0
        mov     w20, w1
        mov     x21, x2
        mov     w22, w3
        bl      ed_cur
        ldr     w9, [x0, #SCR_LEN_OFF]
        add     x10, x0, #SCR_TEXT_OFF
        cmp     w20, w9
        csel    w20, w20, w9, lo
        sub     w11, w22, #1
        mov     w12, #0
1:      cmp     w19, w20
        b.hs    2f
        cmp     w12, w11
        b.hs    2f
        ldrb    w13, [x10, w19, uxtw]
        strb    w13, [x21, w12, uxtw]
        add     w19, w19, #1
        add     w12, w12, #1
        b       1b
2:      strb    wzr, [x21, w12, uxtw]
        mov     w0, w12
        ldp     x21, x22, [sp, #32]
        ldp     x19, x20, [sp, #16]
        ldp     x29, x30, [sp], #64
        ret

/* editor_length - how much text this screen holds */
        .globl  SYM(editor_length)
SYM(editor_length):
        stp     x29, x30, [sp, #-16]!
        mov     x29, sp
        bl      ed_cur
        ldr     w0, [x0, #SCR_LEN_OFF]
        ldp     x29, x30, [sp], #16
        ret

/*
 * editor_colour - w0 = a slot in the palette, 0..9.
 *
 * The same thing Cmd+0..9 does, reachable from somewhere other than a keypress
 * - because "colours blue" is the same request as pressing Cmd+3, arriving in
 * words instead of in a chord. Out-of-range is ignored rather than wrapped: a
 * far side that names a colour this palette does not have should change
 * nothing, not something arbitrary.
 */
        .globl  SYM(editor_colour)
SYM(editor_colour):
        cmp     w0, #9
        b.hi    9f
        LEA     x9, ed_state
        str     w0, [x9, #EDS_COLOUR]
9:      ret

/*
 * editor_scale - w0 = how big the text is, 1 to 8. Anything else is ignored.
 *
 * The companion to editor_colour, and it exists for the same reason that one
 * does: the two things somebody can say about how the editor looks are its ink
 * and its size, and until now only one of them could be said by anything but a
 * key. "larger font" is a sentence about the editor, and a sentence about the
 * editor had nowhere to land - the backend could change the colour and could
 * not change this, which is an odd shape for a system whose whole claim is
 * that it does what it is asked.
 *
 * Absolute rather than a step. ed_zoom takes +1 and -1 because a key press is
 * a step, and a far side that has been told "larger" has to say how much
 * larger - a stream of steps would depend on how many times a reply arrived,
 * which is not a thing anybody means.
 */
        .globl  SYM(editor_scale)
SYM(editor_scale):
        cmp     w0, #ED_SCALE_MIN
        b.lo    9f
        cmp     w0, #ED_SCALE_MAX
        b.hi    9f
        LEA     x9, ed_state
        str     w0, [x9, #EDS_SCALE]
9:      ret

/* editor_colour_get - w0 = the palette slot in use. */
        .globl  SYM(editor_colour_get)
SYM(editor_colour_get):
        LEA     x9, ed_state
        ldr     w0, [x9, #EDS_COLOUR]
        ret

/* editor_scale_get - w0 = what it is now, so a far side can be told. */
        .globl  SYM(editor_scale_get)
SYM(editor_scale_get):
        LEA     x9, ed_state
        ldr     w0, [x9, #EDS_SCALE]
        cmp     w0, #ED_SCALE_MIN
        b.hs    9f
        mov     w0, #ED_SCALE           /* never set: it is at the default */
9:      ret

/* editor_subject - x0 = destination, w1 = capacity.  Empty when unbound. */
        .globl  SYM(editor_subject)
SYM(editor_subject):
        stp     x29, x30, [sp, #-48]!
        mov     x29, sp
        stp     x19, x20, [sp, #16]
        mov     x19, x0
        mov     w20, w1
        bl      ed_cur
        add     x1, x0, #SCR_SUBJ_OFF
        mov     x0, x19
        mov     w2, w20
        bl      ed_copy_bounded
        ldp     x19, x20, [sp, #16]
        ldp     x29, x30, [sp], #48
        ret

/*
 * editor_word_at - the word under a byte offset.
 *   w0 = offset, x1 = destination, w2 = capacity; out w0 = length
 * What a long press landed on, so the screen it opens can be about it.
 */
        .globl  SYM(editor_word_at)
SYM(editor_word_at):
        stp     x29, x30, [sp, #-64]!
        mov     x29, sp
        stp     x19, x20, [sp, #16]
        stp     x21, x22, [sp, #32]
        mov     w19, w0
        mov     x20, x1
        mov     w21, w2
        bl      ed_cur
        ldr     w9, [x0, #SCR_LEN_OFF]
        add     x10, x0, #SCR_TEXT_OFF
        cmp     w19, w9
        b.hs    .Lword_none
        ldrb    w12, [x10, w19, uxtw]
        bl      ed_is_word
        cbz     w0, .Lword_none         /* pressed on a space or punctuation */

        mov     w22, w19                /* find where the word starts */
1:      cbz     w22, 2f
        sub     w11, w22, #1
        ldrb    w12, [x10, w11, uxtw]
        bl      ed_is_word
        cbz     w0, 2f
        sub     w22, w22, #1
        b       1b
2:      sub     w11, w21, #1            /* copy it out */
        mov     w13, #0
3:      cmp     w22, w9
        b.hs    4f
        cmp     w13, w11
        b.hs    4f
        ldrb    w12, [x10, w22, uxtw]
        bl      ed_is_word
        cbz     w0, 4f
        ldrb    w12, [x10, w22, uxtw]
        strb    w12, [x20, w13, uxtw]
        add     w22, w22, #1
        add     w13, w13, #1
        b       3b
4:      strb    wzr, [x20, w13, uxtw]
        mov     w0, w13
        b       .Lword_out
.Lword_none:
        strb    wzr, [x20]
        mov     w0, #0
.Lword_out:
        ldp     x21, x22, [sp, #32]
        ldp     x19, x20, [sp, #16]
        ldp     x29, x30, [sp], #64
        ret

/*
 * editor_offset_at - which byte of the text is under this point?
 *   w0 = x, w1 = y in pixels; out w0 = byte offset, or -1 above the text
 *
 * The same walk the renderer does, stopped early: characters are laid out in
 * order, wrapping at the column count, so the position of the nth byte is
 * whatever the layout says it is.  A click and a swipe both need this, which
 * is why it is not buried inside the click handler any more.
 */
        .globl  SYM(editor_offset_at)
SYM(editor_offset_at):
        stp     x29, x30, [sp, #-80]!
        mov     x29, sp
        stp     x19, x20, [sp, #16]
        stp     x21, x22, [sp, #32]
        stp     x23, x24, [sp, #48]
        mov     w19, w0
        mov     w20, w1
        bl      SYM(ed_metrics)
        mov     w22, w0                 /* cell width  */
        mov     w23, w1                 /* cell height */
        mov     w24, w2                 /* bar height  */
        cmp     w20, w24
        b.lo    .Loff_none              /* that is the screen bar, not text */

        bl      ed_cur
        mov     x21, x0
        LEA     x10, ed_state
        ldr     w11, [x10, #8]          /* scroll  */
        ldr     w12, [x10, #16]         /* columns */
        cbz     w12, .Loff_none
        sub     w13, w20, w24
        udiv    w13, w13, w23
        add     w13, w13, w11           /* target row    */
        udiv    w14, w19, w22           /* target column */

        ldr     w15, [x21, #SCR_LEN_OFF]
        add     x16, x21, #SCR_TEXT_OFF
        mov     w9, #0                  /* index */
        mov     w10, #0                 /* row   */
        mov     w17, #0                 /* col   */
1:      cmp     w9, w15
        b.hs    2f
        cmp     w10, w13
        b.hi    2f
        b.lo    3f
        cmp     w17, w14                /* on the target row, at the column? */
        b.hs    2f
3:      ldrb    w0, [x16, w9, uxtw]
        add     w9, w9, #1
        cmp     w0, #'\n'
        b.ne    4f
        cmp     w10, w13
        b.eq    5f                      /* the line ended before that column */
        add     w10, w10, #1
        mov     w17, #0
        b       1b
4:      add     w17, w17, #1
        cmp     w17, w12
        b.lo    1b
        mov     w17, #0
        add     w10, w10, #1
        b       1b
5:      sub     w9, w9, #1              /* park before the newline */
2:      mov     w0, w9
        b       .Loff_out
.Loff_none:
        mov     w0, #-1
.Loff_out:
        ldp     x23, x24, [sp, #48]
        ldp     x21, x22, [sp, #32]
        ldp     x19, x20, [sp, #16]
        ldp     x29, x30, [sp], #80
        ret

/*
 * editor_select - w0 = one end, w1 = the other, in byte offsets.
 *
 * Ends are sorted, clamped, and then grown outwards to whole words, because
 * a swipe is a gesture and gestures are not precise: the user meant the words
 * they dragged across, not the two characters their finger happened to start
 * and stop on.
 */
        .globl  SYM(editor_select)
SYM(editor_select):
        stp     x29, x30, [sp, #-48]!
        mov     x29, sp
        stp     x19, x20, [sp, #16]
        mov     w19, w0
        mov     w20, w1
        cmp     w19, w20                /* sort them */
        b.ls    1f
        mov     w9, w19
        mov     w19, w20
        mov     w20, w9
1:      bl      ed_cur
        ldr     w9, [x0, #SCR_LEN_OFF]
        cmp     w19, w9
        csel    w19, w19, w9, lo
        cmp     w20, w9
        csel    w20, w20, w9, lo
        add     x10, x0, #SCR_TEXT_OFF

2:      cbz     w19, 3f                 /* back up to the start of a word */
        sub     w11, w19, #1
        ldrb    w12, [x10, w11, uxtw]
        bl      ed_is_word
        cbz     w0, 3f
        sub     w19, w19, #1
        b       2b
3:      cmp     w20, w9                 /* run on to the end of one */
        b.hs    4f
        ldrb    w12, [x10, w20, uxtw]
        bl      ed_is_word
        cbz     w0, 4f
        add     w20, w20, #1
        b       3b
4:      LEA     x13, ed_state
        str     w19, [x13, #EDS_SEL_A]
        str     w20, [x13, #EDS_SEL_B]
        ldp     x19, x20, [sp, #16]
        ldp     x29, x30, [sp], #48
        ret

/* ed_is_word - w12 = byte; out w0 = 1 when it is part of a word */
ed_is_word:
        sub     w0, w12, #'0'
        cmp     w0, #9
        b.ls    1f
        orr     w0, w12, #0x20          /* fold case, then test a-z */
        sub     w0, w0, #'a'
        cmp     w0, #25
        b.ls    1f
        cmp     w12, #'_'
        b.eq    1f
        mov     w0, #0
        ret
1:      mov     w0, #1
        ret

/* editor_selection - w0 = first byte, w1 = one past the last */
        .globl  SYM(editor_selection)
SYM(editor_selection):
        LEA     x9, ed_state
        ldr     w0, [x9, #EDS_SEL_A]
        ldr     w1, [x9, #EDS_SEL_B]
        ret

/* editor_selection_clear - nothing is selected any more */
        .globl  SYM(editor_selection_clear)
SYM(editor_selection_clear):
        LEA     x9, ed_state
        str     wzr, [x9, #EDS_SEL_A]
        str     wzr, [x9, #EDS_SEL_B]
        ret

/*
 * editor_selection_text - x0 = destination, w1 = capacity.
 *   out w0 = bytes copied.  What the user swiped over, and nothing else.
 */
        .globl  SYM(editor_selection_text)
SYM(editor_selection_text):
        stp     x29, x30, [sp, #-48]!
        mov     x29, sp
        stp     x19, x20, [sp, #16]
        str     x21, [sp, #32]
        mov     x19, x0
        mov     w20, w1
        bl      ed_cur                  /* first: ed_cur uses w10 and w11 as
                                           scratch, so reading the bounds
                                           before this loses them */
        LEA     x9, ed_state
        ldr     w10, [x9, #EDS_SEL_A]
        ldr     w11, [x9, #EDS_SEL_B]
        add     x21, x0, #SCR_TEXT_OFF
        ldr     w12, [x0, #SCR_LEN_OFF]
        cmp     w11, w12
        csel    w11, w11, w12, lo
        sub     w12, w20, #1            /* leave room for the NUL */
        mov     w13, #0
1:      cmp     w10, w11
        b.hs    2f
        cmp     w13, w12
        b.hs    2f
        ldrb    w14, [x21, w10, uxtw]
        strb    w14, [x19, w13, uxtw]
        add     w10, w10, #1
        add     w13, w13, #1
        b       1b
2:      strb    wzr, [x19, w13, uxtw]
        mov     w0, w13
        ldr     x21, [sp, #32]
        ldp     x19, x20, [sp, #16]
        ldp     x29, x30, [sp], #48
        ret

/*
 * editor_baseline - copy the snapshot the backend last saw.
 *   x0 = destination, w1 = capacity; out w0 = bytes copied
 */
        .globl  SYM(editor_baseline)
SYM(editor_baseline):
        stp     x29, x30, [sp, #-48]!
        mov     x29, sp
        stp     x19, x20, [sp, #16]
        str     x21, [sp, #32]
        mov     x19, x0
        mov     w20, w1
        bl      ed_cur
        ldr     w9, [x0, #SCR_BASELEN_OFF]
        sub     w10, w20, #1
        cmp     w9, w10
        csel    w9, w9, w10, lo
        mov     w13, #SCR_BASE_OFF      /* too wide for an ADD immediate */
        add     x21, x0, w13, uxtw
        mov     w11, #0
1:      cmp     w11, w9
        b.hs    2f
        ldrb    w12, [x21, w11, uxtw]
        strb    w12, [x19, w11, uxtw]
        add     w11, w11, #1
        b       1b
2:      strb    wzr, [x19, w11, uxtw]
        mov     w0, w11
        ldr     x21, [sp, #32]
        ldp     x19, x20, [sp, #16]
        ldp     x29, x30, [sp], #48
        ret

/*
 * editor_mark_synced - the backend has now seen the current text; make it the
 * baseline, so the next Cmd+P describes the edit since this moment.
 */
        .globl  SYM(editor_mark_synced)
SYM(editor_mark_synced):
        stp     x29, x30, [sp, #-16]!
        mov     x29, sp
        bl      ed_cur
        ldr     w9, [x0, #SCR_LEN_OFF]
        add     x10, x0, #SCR_TEXT_OFF
        mov     w14, #SCR_BASE_OFF
        add     x11, x0, w14, uxtw
        mov     w12, #0
1:      cmp     w12, w9
        b.hs    2f
        ldrb    w13, [x10, w12, uxtw]
        strb    w13, [x11, w12, uxtw]
        add     w12, w12, #1
        b       1b
2:      str     w9, [x0, #SCR_BASELEN_OFF]
        ldp     x29, x30, [sp], #16
        ret

/* editor_cursor_get - w0 = the cursor's byte offset in the current screen */
        .globl  SYM(editor_cursor_get)
SYM(editor_cursor_get):
        stp     x29, x30, [sp, #-16]!
        mov     x29, sp
        bl      ed_cur
        ldr     w0, [x0, #SCR_CUR_OFF]
        ldp     x29, x30, [sp], #16
        ret

/* editor_cursor_set - w0 = byte offset, clamped to the text that is there */
        .globl  SYM(editor_cursor_set)
SYM(editor_cursor_set):
        stp     x29, x30, [sp, #-32]!
        mov     x29, sp
        str     x19, [sp, #16]
        mov     w19, w0
        bl      ed_cur
        ldr     w9, [x0, #SCR_LEN_OFF]
        cmp     w19, w9
        csel    w19, w19, w9, lo
        str     w19, [x0, #SCR_CUR_OFF]
        ldr     x19, [sp, #16]
        ldp     x29, x30, [sp], #32
        ret

/* editor_view - w0 = first visible row, w1 = visible row count */
        .globl  SYM(editor_view)
SYM(editor_view):
        LEA     x9, ed_state
        ldr     w0, [x9, #8]
        ldr     w1, [x9, #12]
        ret

/*
 * editor_screen_set - replace the current screen's text with x0, cursor home.
 * This is what Cmd+P's answer lands in.
 */
        .globl  SYM(editor_screen_set)
SYM(editor_screen_set):
        stp     x29, x30, [sp, #-32]!
        mov     x29, sp
        str     x19, [sp, #16]
        mov     x19, x0
        bl      ed_cur
        str     wzr, [x0, #SCR_LEN_OFF]
        str     wzr, [x0, #SCR_CUR_OFF]
        str     wzr, [x0, #SCR_SCROLL_OFF]
        mov     x0, x19
        bl      SYM(editor_insert_str)
        bl      ed_cur
        str     wzr, [x0, #SCR_CUR_OFF]
        ldr     x19, [sp, #16]
        ldp     x29, x30, [sp], #32
        ret

/*
 * editor_context - copy the current screen's text out for Claude.
 *   x0 = destination, w1 = capacity (NUL is always written)
 *   out w0 = bytes copied
 */
        .globl  SYM(editor_context)
SYM(editor_context):
        stp     x29, x30, [sp, #-48]!
        mov     x29, sp
        stp     x19, x20, [sp, #16]
        str     x21, [sp, #32]
        mov     x19, x0
        mov     w20, w1
        bl      ed_cur
        ldr     w9, [x0, #SCR_LEN_OFF]
        sub     w10, w20, #1
        cmp     w9, w10
        csel    w9, w9, w10, lo         /* clamp to the caller's buffer */
        add     x21, x0, #SCR_TEXT_OFF
        mov     w11, #0
1:      cmp     w11, w9
        b.hs    2f
        ldrb    w12, [x21, w11, uxtw]
        strb    w12, [x19, w11, uxtw]
        add     w11, w11, #1
        b       1b
2:      strb    wzr, [x19, w11, uxtw]
        mov     w0, w11
        ldr     x21, [sp, #32]
        ldp     x19, x20, [sp, #16]
        ldp     x29, x30, [sp], #48
        ret

/* ----------------------------------------------------------------- drawing */

/*
 * editor_render - draw the current screen into a 32bpp buffer.
 *   x0 = buffer, w1 = stride in pixels, w2 = width, w3 = height
 *
 * Two passes over the text: the first locates the cursor's row and column and
 * settles the scroll offset, the second draws only the rows that are on
 * screen.  Row/column state lives on the stack across the draw calls because
 * every callee-saved register is already spoken for.
 */
        .globl  SYM(editor_render)
SYM(editor_render):
        stp     x29, x30, [sp, #-192]!
        mov     x29, sp
        stp     x19, x20, [sp, #16]
        stp     x21, x22, [sp, #32]
        stp     x23, x24, [sp, #48]
        stp     x25, x26, [sp, #64]
        stp     x27, x28, [sp, #80]
        mov     x19, x0                 /* buffer */
        mov     w20, w1                 /* stride */
        mov     w21, w2                 /* width  */
        mov     w22, w3                 /* height */

        bl      SYM(ed_metrics)         /* stage the geometry: every register
                                           this function has is spoken for, so
                                           it lives in the frame */
        str     w0, [sp, #132]          /* cell width  */
        str     w1, [sp, #136]          /* cell height */
        str     w2, [sp, #140]          /* bar height  */
        str     w3, [sp, #144]          /* scale       */
        bl      SYM(ed_fg)
        str     w0, [sp, #148]          /* ink         */
        bl      SYM(editor_selection)   /* the swiped span, if any */
        str     w0, [sp, #152]
        str     w1, [sp, #156]

        mov     x0, x19                 /* backdrop */
        mov     w1, w20
        mov     w2, #0
        mov     w3, #0
        mov     w4, w21
        mov     w5, w22
        LDCOL   w6, COL_BG_LO, COL_BG_HI
        bl      SYM(fb_fill)

        mov     x0, x19                 /* screen bar */
        mov     w1, w20
        mov     w2, #0
        mov     w3, #0
        mov     w4, w21
        ldr     w5, [sp, #140]
        LDCOL   w6, COL_BAR_LO, COL_BAR_HI
        bl      SYM(fb_fill)

        ldr     w9, [sp, #132]          /* text grid that fits */
        udiv    w23, w21, w9
        ldr     w9, [sp, #140]
        sub     w10, w22, w9
        ldr     w9, [sp, #136]
        udiv    w24, w10, w9

        bl      ed_cur
        mov     x25, x0
        ldr     w26, [x25, #SCR_LEN_OFF]
        ldr     w27, [x25, #SCR_CUR_OFF]
        ldr     w28, [x25, #SCR_SCROLL_OFF]

/* pass one: where is the cursor, in rows and columns? */
        str     wzr, [sp, #96]          /* a cursor past the end of the text is
                                           never matched below, and stale stack
                                           read as a row and column puts the
                                           caret outside the framebuffer */
        str     wzr, [sp, #100]
        mov     w9, #0                  /* index */
        mov     w10, #0                 /* row   */
        mov     w11, #0                 /* col   */
        add     x17, x25, #SCR_TEXT_OFF
1:      cmp     w9, w27
        b.ne    2f
        str     w10, [sp, #96]
        str     w11, [sp, #100]
2:      cmp     w9, w26
        b.hs    3f
        ldrb    w13, [x17, w9, uxtw]
        add     w9, w9, #1
        cmp     w13, #'\n'
        b.ne    4f
        add     w10, w10, #1
        mov     w11, #0
        b       1b
4:      add     w11, w11, #1
        cmp     w11, w23
        b.lo    1b
        mov     w11, #0
        add     w10, w10, #1
        b       1b
3:

        ldr     w13, [sp, #96]          /* keep the cursor on screen */
        cmp     w13, w28
        b.hs    5f
        mov     w28, w13
5:      add     w14, w28, w24
        cmp     w13, w14
        b.lo    6f
        sub     w28, w13, w24
        add     w28, w28, #1
6:      str     w28, [x25, #SCR_SCROLL_OFF]
        LEA     x9, ed_state            /* remember the viewport: the client
                                           reports it, and clicks need it */
        str     w28, [x9, #8]
        str     w24, [x9, #12]
        str     w23, [x9, #16]

/* pass two: draw the visible rows */
        str     wzr, [sp, #112]         /* index */
        str     wzr, [sp, #116]         /* row   */
        str     wzr, [sp, #120]         /* col   */
        mov     w0, #0                  /* the band this line wears */
        bl      ed_band
        str     w0, [sp, #124]
.Ldraw:
        ldr     w9, [sp, #112]
        cmp     w9, w26
        b.hs    .Ldraw_done
        add     x17, x25, #SCR_TEXT_OFF
        ldrb    w12, [x17, w9, uxtw]
        add     w9, w9, #1
        str     w9, [sp, #112]
        cmp     w12, #'\n'
        b.eq    .Ldraw_nl

        ldr     w10, [sp, #116]
        ldr     w11, [sp, #120]
        sub     w15, w10, w28           /* row on screen; unsigned wrap hides */
        cmp     w15, w24                /* everything above the scroll point  */
        b.hs    .Ldraw_adv

        ldr     w16, [sp, #132]
        mul     w16, w11, w16
        ldr     w17, [sp, #136]
        mul     w17, w15, w17
        ldr     w13, [sp, #140]
        add     w17, w17, w13

/*
 * A line that is marked wears its mark across the whole width.
 *
 * Behind the text rather than in it, because what is being said is about the
 * line and not about any character of it: this line is private, or this line is
 * yours alone. Colouring the letters would say it quietly, in a place somebody
 * has to be looking already, and these two are exactly the lines nobody should
 * be able to be surprised by.
 *
 * Painted on every row the line occupies, wraps included - the band follows the
 * line rather than the row, which is why it is worked out once where the line
 * begins and used here.
 */
        ldr     w11, [sp, #120]
        cbnz    w11, .Lno_band          /* only at the left-hand edge */
        ldr     w11, [sp, #124]
        cbz     w11, .Lno_band
        stp     x16, x17, [sp, #160]
        sub     w11, w11, #1            /* 0 = private, 1 = theirs alone */
        cbnz    w11, 8f
        LDCOL   w6, COL_MARK_PRIV_LO, COL_MARK_PRIV_HI
        b       9f
8:      LDCOL   w6, COL_MARK_MINE_LO, COL_MARK_MINE_HI
9:      mov     x0, x19
        mov     w1, w20
        mov     w2, #0                  /* the whole width, not the glyph */
        mov     w3, w17
        mov     w4, w21
        ldr     w5, [sp, #136]
        bl      SYM(fb_fill)
        ldp     x16, x17, [sp, #160]
.Lno_band:

        ldr     w9, [sp, #112]          /* is this byte inside the swipe? */
        sub     w9, w9, #1              /* the index before it advanced */
        ldr     w10, [sp, #152]
        ldr     w11, [sp, #156]
        cmp     w10, w11
        b.hs    6f                      /* nothing selected */
        cmp     w9, w10
        b.lo    6f
        cmp     w9, w11
        b.hs    6f
        stp     x16, x17, [sp, #160]    /* the fill clobbers the scratch.  Not
                                           at 96: that is where the caret's row
                                           and column live, and stamping on
                                           them sends the caret off the end of
                                           the framebuffer. */
        mov     x0, x19
        mov     w1, w20
        mov     w2, w16
        mov     w3, w17
        ldr     w4, [sp, #132]
        ldr     w5, [sp, #136]
        LDCOL   w6, COL_SEL_LO, COL_SEL_HI
        bl      SYM(fb_fill)
        ldp     x16, x17, [sp, #160]
6:
        strb    w12, [sp, #104]         /* a one-character string */
        strb    wzr, [sp, #105]
        mov     x0, x19
        mov     w1, w20
        mov     w2, w16
        mov     w3, w17
        ldr     w4, [sp, #144]
        ldr     w5, [sp, #148]
        add     x6, sp, #104
        bl      SYM(font_draw_text)

.Ldraw_adv:
        ldr     w11, [sp, #120]
        add     w11, w11, #1
        cmp     w11, w23
        b.lo    7f
        mov     w11, #0
        ldr     w10, [sp, #116]
        add     w10, w10, #1
        str     w10, [sp, #116]
7:      str     w11, [sp, #120]
        b       .Ldraw
.Ldraw_nl:
        str     wzr, [sp, #120]
        ldr     w10, [sp, #116]
        add     w10, w10, #1
        str     w10, [sp, #116]
        ldr     w0, [sp, #112]          /* the line that starts after it */
        bl      ed_band
        str     w0, [sp, #124]
        b       .Ldraw
.Ldraw_done:

        LEA     x9, ed_state            /* Waiting is shown by the caret going
                                           still: idle it blinks on the phase
                                           the front end drives, busy it just
                                           sits there.  The phase is a flag
                                           rather than a clock read, because
                                           this file also runs where there is
                                           no clock to call. */
        ldr     w10, [x9, #EDS_BUSY]
        cbnz    w10, 7f
        ldr     w9, [x9, #EDS_CARET]
        cbz     w9, .Lbar
7:

        ldr     w13, [sp, #96]          /* the caret */
        ldr     w14, [sp, #100]
        sub     w15, w13, w28
        cmp     w15, w24
        b.hs    .Lbar
        ldr     w16, [sp, #132]
        mul     w16, w14, w16
        ldr     w17, [sp, #136]
        mul     w17, w15, w17
        ldr     w13, [sp, #140]
        add     w17, w17, w13
        mov     x0, x19
        mov     w1, w20
        mov     w2, w16
        mov     w3, w17
        ldr     w4, [sp, #144]
        lsl     w4, w4, #1              /* a caret two font pixels wide */
        ldr     w5, [sp, #136]
        LDCOL   w6, COL_CURSOR_LO, COL_CURSOR_HI
        bl      SYM(fb_fill)

/* the screen bar: one digit per screen, the current one lit */
.Lbar:
        mov     x0, x19
        mov     w1, w20
        ldr     w2, [sp, #144]
        lsl     w2, w2, #1
        mov     w3, w2
        ldr     w4, [sp, #144]
        LDCOL   w5, COL_DIM_LO, COL_DIM_HI
        LEA     x6, s_brand
        bl      SYM(font_draw_text)

        str     wzr, [sp, #128]         /* screen index */
.Lbar_loop:
        ldr     w9, [sp, #128]
        bl      SYM(editor_screen_info) /* w0 = current, w1 = count */
        ldr     w9, [sp, #128]
        cmp     w9, w1
        b.hs    .Lrender_out
        ldr     w12, [sp, #132]         /* x = brand width + slot */
        mul     w10, w9, w12
        lsl     w10, w10, #1
        mov     w13, #10
        madd    w10, w12, w13, w10
        add     w11, w9, #'1'           /* screens are 1-based on screen */
        strb    w11, [sp, #104]
        strb    wzr, [sp, #105]
        cmp     w9, w0
        b.ne    8f
        LDCOL   w5, COL_CURSOR_LO, COL_CURSOR_HI
        b       9f
8:      LDCOL   w5, COL_DIM_LO, COL_DIM_HI
9:      mov     x0, x19
        mov     w1, w20
        mov     w2, w10
        ldr     w3, [sp, #144]
        lsl     w3, w3, #1
        ldr     w4, [sp, #144]
        add     x6, sp, #104
        bl      SYM(font_draw_text)
        ldr     w9, [sp, #128]
        add     w9, w9, #1
        str     w9, [sp, #128]
        b       .Lbar_loop

/*
 * And whoever the far side says is sitting here, at the other end of the bar.
 *
 * Right-aligned, so it does not move when a screen is opened and the digits
 * grow leftwards into the space. Drawn in the caret's colour rather than the
 * dim one the brand and the screen numbers use: it is the one thing in the bar
 * that is about this person rather than about the program.
 */
        LEA     x9, ed_whoami
        ldrb    w10, [x9]
        cbz     w10, .Lrender_out
        mov     w11, #0
1:      ldrb    w12, [x9, w11, uxtw]
        cbz     w12, 2f
        add     w11, w11, #1
        b       1b
2:      ldr     w12, [sp, #132]         /* cell width */
        mul     w11, w11, w12
        sub     w11, w21, w11           /* from the right-hand edge */
        ldr     w12, [sp, #132]
        sub     w11, w11, w12           /* and one cell of margin */
        tbnz    w11, #31, .Lrender_out  /* a bar too narrow to hold it */
        mov     x0, x19
        mov     w1, w20
        mov     w2, w11
        ldr     w3, [sp, #144]
        lsl     w3, w3, #1
        ldr     w4, [sp, #144]
        LDCOL   w5, COL_CURSOR_LO, COL_CURSOR_HI
        LEA     x6, ed_whoami
        bl      SYM(font_draw_text)

.Lrender_out:
        ldp     x27, x28, [sp, #80]
        ldp     x25, x26, [sp, #64]
        ldp     x23, x24, [sp, #48]
        ldp     x21, x22, [sp, #32]
        ldp     x19, x20, [sp, #16]
        ldp     x29, x30, [sp], #192
        ret

        RODATA
s_brand:        .asciz "MAOS"

        CSTR
s_jn_ins:       .asciz "ins "
s_jn_del:       .asciz "del "
s_jn_key:       .asciz "key "
/*
 * What each command is called when it is written down.
 *
 * Named for what it does rather than for which key does it, because the key
 * differs - Cmd on macOS, Ctrl on a serial console - and what the person meant
 * does not. A far side reading "new-screen" knows what happened; one reading
 * "ctrl-t" would have to know which machine this is.
 */
k_prev:         .asciz "previous-screen"
k_next:         .asciz "next-screen"
k_newscreen:    .asciz "new-screen"
k_closescreen:  .asciz "closed-screen"
k_rewrite:      .asciz "asked-you-to-rewrite"
k_handover:     .asciz "handed-you-the-screen"
k_bigger:       .asciz "made-the-text-bigger"
k_smaller:      .asciz "made-the-text-smaller"
k_colour:       .asciz "changed-the-colour"

        BSS
        .p2align 4
ed_screens:                             /* private: the screens themselves */
        .space  (MAX_SCREENS * SCR_STRIDE)
ed_state:
        .space  EDS_SZ                  /* current, count, viewport, scale, colour */
        .p2align 3
ed_whoami:      .space  WHO_MAX
        .p2align 3
jn_ring:        .space  (JN_MAX * JN_SZ)
jn_count:       .space  4

        RODATA
        .p2align 2
/*
 * Ten inks, reachable with Cmd 0-9.  Green first because that is what the
 * whole thing looked like on the first day; the rest are chosen to stay
 * legible on the same dark ground.
 */
ed_palette:
        .word   0xFF8AE2B8              /* 0 green   */
        .word   0xFFE2E2E2              /* 1 white   */
        .word   0xFFE2B85A              /* 2 amber   */
        .word   0xFF8AB8E2              /* 3 blue    */
        .word   0xFFE28A8A              /* 4 red     */
        .word   0xFFC08AE2              /* 5 violet  */
        .word   0xFF8AE2E2              /* 6 cyan    */
        .word   0xFFE2E28A              /* 7 yellow  */
        .word   0xFF7A8A82              /* 8 grey    */
        .word   0xFFE28AC0              /* 9 pink    */
