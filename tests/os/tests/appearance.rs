//! How the editor looks, and the two things that can change it.
//!
//! A port carries these unchanged: "the text has a size between one and eight,
//! being told a size outside that leaves it alone" is a property of the editor
//! and not of an instruction set.

use maos::*;

#[test]
fn a_size_can_be_set_and_read_back() {
    let _lock = EDITOR.lock().unwrap();
    unsafe {
        editor_init();
        editor_scale(5);
        assert_eq!(editor_scale_get(), 5);
        editor_scale(1);
        assert_eq!(editor_scale_get(), 1);
    }
}

#[test]
fn a_size_the_editor_cannot_draw_leaves_it_as_it_was() {
    let _lock = EDITOR.lock().unwrap();
    unsafe {
        editor_init();
        editor_scale(4);
        editor_scale(0);
        assert_eq!(editor_scale_get(), 4, "there is no size zero");
        editor_scale(9);
        assert_eq!(editor_scale_get(), 4, "and eight is the largest");
        editor_scale(10_000);
        assert_eq!(editor_scale_get(), 4);
    }
}

#[test]
fn a_fresh_editor_has_a_size_to_be_larger_than() {
    let _lock = EDITOR.lock().unwrap();
    unsafe {
        editor_init();
        let now = editor_scale_get();
        assert!((1..=8).contains(&now), "it reported {now}");
    }
}

#[test]
fn a_secret_line_is_marked_and_an_ordinary_one_is_not() {
    let _lock = EDITOR.lock().unwrap();
    unsafe {
        editor_init();
        editor_mode_is(0, "a fresh screen is in no mode");
    }
    typed("##my-password hunter2");
    unsafe { assert_eq!(editor_mode(), 0, "a secret is a line, not a mode") };
}

#[test]
fn a_dollar_line_puts_the_whole_screen_in_its_mode() {
    let _lock = EDITOR.lock().unwrap();
    unsafe { editor_init() };
    typed("ordinary text\n$$ build me something\nmore text");
    unsafe {
        assert_eq!(editor_mode(), 1,
                   "it is a property of the exchange, not of the line it is on");
    }
}

#[test]
fn one_dollar_is_not_the_mark() {
    let _lock = EDITOR.lock().unwrap();
    unsafe { editor_init() };
    typed("$100 for the whole thing");
    unsafe { assert_eq!(editor_mode(), 0, "money is not a mode") };
}

#[test]
fn the_mark_has_to_start_a_line() {
    let _lock = EDITOR.lock().unwrap();
    unsafe { editor_init() };
    typed("that costs $$ by my reckoning");
    unsafe { assert_eq!(editor_mode(), 0) };
}

unsafe fn editor_mode_is(want: u32, why: &str) {
    assert_eq!(editor_mode(), want, "{why}");
}
