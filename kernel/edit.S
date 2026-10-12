#include "asm.inc"
#include "editor.inc"
/*
 * edit.S - maos as the machine, rather than as a program on one.
 *
 * This is the OS mode: the same editor core the window and the phone run,
 * driven by a keyboard that is a serial line and drawing into a framebuffer
 * the firmware handed us. Nothing in this file names a peripheral - keys come
 * from platform_getc, time from platform_ticks, pixels from platform_fb - so
 * a port supplies those three and this file is unchanged.
 *
 * Two things differ from the hosted front ends, and both are consequences of
 * there being no operating system here rather than decisions:
 *
 * A serial console has no Cmd key, so Ctrl stands in for it. Ctrl+P is Cmd+P,
 * Ctrl+T opens a screen, and so on. That mapping is not a compromise for the
 * kernel's benefit - it is what the editor core already expected, because
 * editor.inc has said "Ctrl on the serial console" since the key codes were
 * written.
 *
 * And there is no network yet, so Cmd+P and Cmd+Enter have nowhere to send
 * the screen. Rather than silently doing nothing, they say so. This is the
 * same path the hosted build takes when the backend is unreachable: the
 * editor keeps editing, and the two keys that need a far side are the only
 * things that stop working.
 */

#define BLINK_MS        500

/*
 * How many keys may be typed through one wait before the rest are dropped.
 *
 * Two hundred and fifty six is about a minute of fast typing, which is longer
 * than any answer this waits for. It is not a buffer anybody should be able to
 * fill on purpose; it is the size at which "I typed a sentence while it was
 * thinking" stops being a thing that loses letters.
 */
#define KE_HELD_MAX     256

        .section .text
        .p2align 2

/*
 * kedit_run - the editor, forever. Never returns.
 *   w0 = 0 on entry means the machine has a framebuffer; anything else and
 *   we should not have been called.
 */
        .globl  kedit_run
kedit_run:
        stp     x29, x30, [sp, #-128]!
        mov     x29, sp
        stp     x19, x20, [sp, #16]
        stp     x21, x22, [sp, #32]
        stp     x23, x24, [sp, #48]
        stp     x25, x26, [sp, #64]

        add     x0, sp, #96             /* base */
        add     x1, sp, #104            /* width */
        add     x2, sp, #108            /* height */
        bl      platform_fb
        ldr     x19, [sp, #96]
        ldr     w20, [sp, #104]
        ldr     w21, [sp, #108]
        cbz     x19, .Lke_out
/*
 * The same three, where something other than this function can reach them.
 *
 * They stay in x19-x21 for the loop, which is where they belong - but the
 * screen also has to be painted from inside a network wait, four calls down,
 * on a stack frame that knows nothing about any of this. A copy costs three
 * words and is the whole of what kedit_paint needs.
 */
        LEA     x9, ke_fb
        str     x19, [x9]
        LEA     x9, ke_w
        str     w20, [x9]
        LEA     x9, ke_h
        str     w21, [x9]
        LEA     x0, kedit_waiting
        bl      platform_on_idle

        bl      SYM(editor_init)
        bl      SYM(applet_clear)

/*
 * The document, after the editor exists and not before.
 *
 * editor_init starts an empty screen, so anything loaded ahead of this call is
 * thrown away by it - and the autosave that follows then writes the emptiness
 * back over the disk, which loses the work rather than merely failing to
 * restore it.
 */
        bl      SYM(store_load)

        /*
         * The video widget is built and works - see gfx/video.S and the demo
         * clip - but it is scenery while the editor is the thing being used,
         * and a square bouncing in the corner of a text screen is a
         * distraction. Left out rather than deleted: applet_add is one call.
         */

        mov     w23, #1                 /* redraw wanted */
        bl      platform_ticks
        mov     x24, x0                 /* when the caret last flipped */

.Lke_loop:
        cbz     w23, 1f
        bl      kedit_paint

/*
 * And only now, with the reply on the glass, does the machine agree to stop.
 *
 * Somebody types "reboot" and presses Cmd+P. The answer comes back, is drawn,
 * and then this happens - in that order, because a restart that beats the
 * redraw looks exactly like a crash.
 */
        bl      SYM(backend_reboot_wanted)
        cbz     w0, 1f
        LEA     x0, m_restarting
        bl      platform_puts
        bl      platform_reboot
        LEA     x0, m_declined
        bl      platform_puts
1:
        mov     w23, #0
/*
 * Every key that is waiting, before drawing anything.
 *
 * Redrawing between one keystroke and the next sounds harmless and is not: a
 * frame is a million pixels, and while it is being painted the keyboard's ring
 * fills and the device starts dropping events. Typed at speed, "hello from the
 * window" arrived as "te". So the loop takes everything that has arrived,
 * applies it, and paints once - which is also what makes typing feel like
 * typing rather than like a teletype.
 */
1:
        bl      platform_getc
        cmn     w0, #1
        b.eq    .Lke_tick
        bl      SYM(editor_key)         /* platform_getc hands us keys, not
                                           bytes: whatever this machine has for
                                           a keyboard is the port's business */
        mov     w23, #1
        cmp     w0, #ED_AIFY
        b.eq    .Lke_ask
        cmp     w0, #ED_SUBMIT
        b.eq    .Lke_ask
        b       1b

/*
 * The two keys that need a far side.
 *
 * Synchronously, unlike the hosted builds, which hand this to another thread
 * and keep drawing. There is no other thread here - no scheduler, one stack -
 * so the editor stops until the answer comes back. The caret stops blinking,
 * which is the same signal it gives everywhere else, and for the same reason.
 */
.Lke_ask:
        mov     w25, w0                 /* not w19: that is the framebuffer */
        LEA     x0, m_asking            /* a wait with no sign of life is
                                           indistinguishable from a key that
                                           did nothing, and the two get
                                           reported the same way */
        bl      platform_puts
        mov     w0, #1
        bl      SYM(editor_busy)
/*
 * And put that on the glass before waiting, not after.
 *
 * editor_busy stops the blink with the caret shown, which is the right state -
 * but marking a redraw as wanted only means it happens at the top of the loop,
 * and the loop does not come round again until the answer has arrived. So for
 * the forty seconds a model takes, the screen kept whatever phase the blink
 * had left it in, which half the time is no caret at all. The one moment the
 * machine most needs to look like it is still there was the one moment it
 * showed nothing.
 */
        bl      kedit_paint

        mov     w23, #1
/*
 * Asking, and the machine still answering to its keyboard while it does.
 *
 * There is one stack here and nothing to schedule, so the request is made from
 * this frame and does not come back for as long as the far side takes. What
 * changes is what happens underneath: everything that waits calls
 * platform_idle, platform_idle calls kedit_waiting, and kedit_waiting is this
 * editor - reading keys, moving the caret, and painting. The wait is still a
 * wait. It is no longer a freeze.
 */
        bl      SYM(editor_screen_info) /* which screen this is about */
        LEA     x9, ke_screen
        str     w0, [x9]
        LEA     x9, ke_asking
        mov     w10, #1
        str     w10, [x9]
        mov     w0, w25
        bl      SYM(backend_fetch)
        LEA     x9, ke_asking
        str     wzr, [x9]
/*
 * Back to the screen that asked, before the answer is put anywhere.
 *
 * Cmd+Left and Cmd+Right change screens, and they are among the keys that now
 * work while the machine is thinking - which is the point, but it means the
 * screen in front of the person when the answer lands need not be the one they
 * asked about. The answer replaces a span of text measured on that screen. Put
 * it anywhere else and it overwrites something unrelated, chosen by where they
 * happened to be looking.
 */
        bl      kedit_screen_back
        mov     w0, w25
        bl      SYM(backend_apply)
        bl      SYM(backend_status)     /* and say which way it ended */
        mov     w25, w0
        LEA     x0, m_answered
        bl      platform_puts
        bl      ke_outcome
        mov     w0, #0
        bl      SYM(editor_busy)
        mov     w23, #1
/*
 * And now what was typed while the answer was on its way.
 *
 * Held rather than applied, because the reply lands on a span of text measured
 * before the question was asked - let somebody insert a line in the middle of
 * it and the answer replaces the wrong words. Moving about changes no offsets,
 * so that was allowed to happen live; everything else waited here, in order,
 * and goes in now that the document is settled.
 *
 * A key that needs the far side again starts the whole thing over, and the
 * rest stay held behind it. That is the same order they were typed in, which
 * is the only property this has to preserve.
 */
        bl      kedit_replay
        cmp     w0, #ED_AIFY
        b.eq    .Lke_ask
        cmp     w0, #ED_SUBMIT
        b.eq    .Lke_ask
        b       1b

/*
 * kedit_screen_back - to whichever screen the outstanding question came from.
 *
 * Stepped rather than set, because stepping is what the editor exports and
 * because the count can have changed underneath: the bound is what makes this
 * safe against a screen that is no longer there, and landing somewhere near it
 * beats spinning.
 */
kedit_screen_back:
        stp     x29, x30, [sp, #-48]!
        mov     x29, sp
        stp     x19, x20, [sp, #16]
        LEA     x9, ke_screen
        ldr     w19, [x9]
        mov     w20, #MAX_SCREENS
1:      cbz     w20, 9f
        sub     w20, w20, #1
        bl      SYM(editor_screen_info) /* w0 = where we are now */
        cmp     w0, w19
        b.eq    9f
        mov     w9, w0
        mov     w0, #1
        cmp     w9, w19
        b.lo    2f
        mov     w0, #-1
2:      bl      SYM(editor_screen_step)
        b       1b
9:      ldp     x19, x20, [sp, #16]
        ldp     x29, x30, [sp], #48
        ret

/*
 * kedit_paint - the screen, as it currently is.
 *
 * Was three copies of the same nine instructions: the top of the loop, the
 * moment before a wait, and the wait itself. They have to agree - a screen
 * painted without its widgets in one of the three is a widget that blinks out
 * whenever the machine is busy - so there is one of them.
 */
kedit_paint:
        stp     x29, x30, [sp, #-16]!
        mov     x29, sp
        LEA     x9, ke_fb
        ldr     x0, [x9]
        cbz     x0, 9f
        LEA     x9, ke_w
        ldr     w1, [x9]                /* this board's stride is its width */
        mov     w2, w1
        LEA     x9, ke_h
        ldr     w3, [x9]
        bl      SYM(editor_render)
        LEA     x9, ke_fb
        ldr     x0, [x9]                /* widgets sit on top of the text */
        LEA     x9, ke_w
        ldr     w1, [x9]
        mov     w2, w1
        LEA     x9, ke_h
        ldr     w3, [x9]
        bl      SYM(applet_render)
        bl      platform_present        /* and only now to the display */
9:      ldp     x29, x30, [sp], #16
        ret

/*
 * kedit_waiting - the editor, from inside somebody else's wait.
 *
 * Called by platform_idle, which is called by every loop in the network stack
 * that has nothing to do but go round again. So this runs a few hundred times
 * a second while an answer is outstanding, and does nothing at all the rest of
 * the time: outside an ask there is a real loop doing this properly, and two
 * of them reading the same keyboard would each get half the keys.
 *
 * Only movement is applied. A caret that answers to arrows and Cmd+arrows is
 * the difference between a machine that is thinking and a machine that has
 * died, and it is enough - the document is not editable here for a reason
 * spelled out where the held keys are replayed.
 */
kedit_waiting:
        stp     x29, x30, [sp, #-48]!
        mov     x29, sp
        stp     x19, x20, [sp, #16]
        str     x21, [sp, #32]
        LEA     x9, ke_asking
        ldr     w9, [x9]
        cbz     w9, .Lkw_out
/*
 * Painting waits for nothing, but it is not free either, and a hook that can
 * be entered from inside its own repaint is a hook that will be. The flag is
 * cheaper than reasoning about whether every path below here is clean.
 */
        LEA     x9, ke_inhook
        ldr     w9, [x9]
        cbnz    w9, .Lkw_out
        mov     w10, #1
        LEA     x9, ke_inhook
        str     w10, [x9]

        mov     w19, #0                 /* has anything moved? */
1:      bl      platform_getc
        cmn     w0, #1
        b.eq    4f
        mov     w20, w0
        and     w21, w20, #0xFFF        /* modifiers off: Cmd+Left is a Left */
        sub     w21, w21, #K_LEFT
        cmp     w21, #(K_END - K_LEFT)
        b.hi    2f
        mov     w0, w20
        bl      SYM(editor_key)
        mov     w19, #1
        b       1b
2:      LEA     x9, ke_held_n           /* not a movement: it waits its turn */
        ldr     w9, [x9]
        cmp     w9, #KE_HELD_MAX
        b.hs    1b                      /* full, and dropped - the same thing
                                           the device ring did with them */
        LEA     x10, ke_held
        str     w20, [x10, w9, uxtw #2]
        add     w9, w9, #1
        LEA     x10, ke_held_n
        str     w9, [x10]
        b       1b

4:      cbz     w19, 5f
        bl      kedit_paint
5:      LEA     x9, ke_inhook
        str     wzr, [x9]
.Lkw_out:
        ldr     x21, [sp, #32]
        ldp     x19, x20, [sp, #16]
        ldp     x29, x30, [sp], #48
        ret

/*
 * kedit_replay - the held keys, in order.
 *   out w0 = ED_AIFY or ED_SUBMIT if one of them wants the backend, else 0.
 *
 * Stops at the first key that does, leaving the rest held: they were typed
 * after it and belong after it, and the next wait will hold whatever is typed
 * during that one behind them.
 */
kedit_replay:
        stp     x29, x30, [sp, #-48]!
        mov     x29, sp
        stp     x19, x20, [sp, #16]
        str     x21, [sp, #32]
        mov     w19, #0
        mov     w21, #0
        LEA     x9, ke_held_n
        ldr     w20, [x9]
1:      cmp     w19, w20
        b.hs    3f
        LEA     x9, ke_held
        ldr     w0, [x9, w19, uxtw #2]
        add     w19, w19, #1
        bl      SYM(editor_key)
        cmp     w0, #ED_AIFY
        b.eq    2f
        cmp     w0, #ED_SUBMIT
        b.ne    1b
2:      mov     w21, w0
3:      LEA     x9, ke_held             /* whatever is left moves to the front */
        mov     w10, #0
4:      cmp     w19, w20
        b.hs    5f
        ldr     w11, [x9, w19, uxtw #2]
        str     w11, [x9, w10, uxtw #2]
        add     w19, w19, #1
        add     w10, w10, #1
        b       4b
5:      LEA     x9, ke_held_n
        str     w10, [x9]
        mov     w0, w21
        ldr     x21, [sp, #32]
        ldp     x19, x20, [sp, #16]
        ldp     x29, x30, [sp], #48
        ret

/*
 * ke_outcome - w25 = an ST_ number; print what it means.
 *
 * Compared rather than indexed. A table of pointers would be shorter and it
 * would be wrong: nothing in this kernel may hold an absolute address, because
 * it does not run where it was linked - the EFI loader copies it to whatever
 * the firmware allocated. Every other reference here goes through LEA, which
 * is PC-relative, and a .quad full of link-time addresses is the one shape
 * that assembles cleanly and then reads from nowhere.
 */
ke_outcome:
        stp     x29, x30, [sp, #-16]!
        mov     x29, sp
        cmp     w25, #0
        b.ne    1f
        LEA     x0, m_out_ok
        b       9f
1:      cmp     w25, #1
        b.ne    2f
        LEA     x0, m_out_nokey
        b       9f
2:      cmp     w25, #2
        b.ne    3f
        LEA     x0, m_out_badaddr
        b       9f
3:      cmp     w25, #3
        b.ne    4f
        LEA     x0, m_out_unreach
        b       9f
4:      cmp     w25, #4
        b.ne    5f
        LEA     x0, m_out_badreply
        b       9f
5:      LEA     x0, m_out_empty
9:      bl      platform_puts
        ldp     x29, x30, [sp], #16
        ret

        RODATA
m_out_ok:       .asciz "answered\n"
m_out_nokey:    .asciz "there is no key, so there was nobody to ask\n"
m_out_badaddr:  .asciz "the key does not carry an address this understands\n"
m_out_unreach:  .asciz "nothing at that address answered\n"
m_out_badreply: .asciz "it answered with neither text nor a reason\n"
m_out_empty:    .asciz "it answered with nothing at all\n"

        CODE
        .p2align 2
.Lke_tick:
        bl      platform_ticks
        sub     x9, x0, x24
        mov     x10, #BLINK_MS
        cmp     x9, x10
        b.lo    .Lke_loop
        mov     x24, x0
        bl      SYM(editor_caret_blink)
        orr     w23, w23, w0            /* it says nothing changed while busy */
/*
 * The document is written on the same tick, when it has stopped changing.
 * Cheap: it is a checksum unless something is different, and the disk is only
 * touched when it is.
 */
        bl      SYM(store_tick)
        bl      SYM(video_ok)           /* the clip advances on the same tick */
        cbz     w0, .Lke_loop
        bl      SYM(video_next)
        mov     w23, #1
        b       .Lke_loop

.Lke_out:
        ldp     x25, x26, [sp, #64]
        ldp     x23, x24, [sp, #48]
        ldp     x21, x22, [sp, #32]
        ldp     x19, x20, [sp, #16]
        ldp     x29, x30, [sp], #128
        ret

        BSS
        .p2align 3
ke_fb:          .space 8        /* the framebuffer, reachable from a wait */
        .p2align 2
ke_w:           .space 4
ke_h:           .space 4
ke_asking:      .space 4        /* an answer is outstanding */
ke_screen:      .space 4        /* and this is the screen that asked */
ke_inhook:      .space 4        /* and we are already inside the hook */
ke_held_n:      .space 4
ke_held:        .space (4 * KE_HELD_MAX)

        .section .rodata
m_restarting: .asciz "maos: restarting\n"
m_declined:   .asciz "maos: the machine declined to restart\n"
/* A video answers to its kind, which is what a shared rule is written against. */
s_unlabelled:
        .asciz ""
m_asking:   .asciz "maos: asking the backend...\n"
m_answered: .asciz "maos: "
m_nolink:
        .asciz "maos: no link yet - the editor works, the far side does not\n"
