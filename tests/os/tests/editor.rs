//! The editor, as a person uses it.
//!
//! Nothing here mentions aarch64. These are the properties a port has to keep:
//! that typing puts the characters in and the caret after them, that backspace
//! takes one back, that a screen can be rewritten whole, and that a run of
//! typing is remembered as one thing rather than as a key at a time.

use maos::*;

const K_BS: u32 = 0x08;
const K_LEFT: u32 = 0x100;

#[test]
fn typing_puts_the_characters_in_and_the_caret_after_them() {
    let _lock = EDITOR.lock().unwrap();
    unsafe { editor_init() };
    typed("hello");
    assert_eq!(screen(), "hello");
    assert_eq!(unsafe { editor_length() }, 5);
    assert_eq!(unsafe { editor_cursor_get() }, 5);
}

#[test]
fn backspace_takes_one_back_and_stops_at_the_start() {
    let _lock = EDITOR.lock().unwrap();
    unsafe { editor_init() };
    typed("ab");
    unsafe { editor_key(K_BS) };
    assert_eq!(screen(), "a");
    unsafe { editor_key(K_BS) };
    unsafe { editor_key(K_BS) };
    assert_eq!(screen(), "", "deleting past the start deletes nothing");
    assert_eq!(unsafe { editor_cursor_get() }, 0);
}

#[test]
fn typing_happens_where_the_caret_is() {
    let _lock = EDITOR.lock().unwrap();
    unsafe { editor_init() };
    typed("ac");
    unsafe { editor_cursor_set(1) };
    typed("b");
    assert_eq!(screen(), "abc");
}

#[test]
fn a_range_can_be_replaced_by_something_of_another_length() {
    let _lock = EDITOR.lock().unwrap();
    unsafe { editor_init() };
    typed("one two three");
    let new = std::ffi::CString::new("TWO").unwrap();
    unsafe { editor_replace(4, 7, new.as_ptr() as *const u8) };
    assert_eq!(screen(), "one TWO three");

    let longer = std::ffi::CString::new("a much longer word").unwrap();
    unsafe { editor_replace(4, 7, longer.as_ptr() as *const u8) };
    assert_eq!(screen(), "one a much longer word three");
}

#[test]
fn the_whole_screen_can_be_replaced_including_with_nothing() {
    let _lock = EDITOR.lock().unwrap();
    unsafe { editor_init() };
    typed("a screen full of text");
    let len = unsafe { editor_length() };
    let empty = std::ffi::CString::new("").unwrap();
    unsafe { editor_replace(0, len, empty.as_ptr() as *const u8) };
    assert_eq!(screen(), "", "which is what answering #CLEAR comes to");
    assert_eq!(unsafe { editor_length() }, 0);
}

#[test]
fn a_run_of_typing_is_one_thing_that_happened() {
    let _lock = EDITOR.lock().unwrap();
    unsafe { editor_init() };
    journal();
    typed("hi");
    assert_eq!(journal(), "ins 0 hi\n", "two keys, one act of typing");
}

#[test]
fn a_run_of_deleting_is_one_thing_too() {
    let _lock = EDITOR.lock().unwrap();
    unsafe { editor_init() };
    typed("hello");
    journal();
    unsafe { editor_key(K_BS) };
    unsafe { editor_key(K_BS) };
    assert_eq!(journal(), "del 3 2\n");
}

#[test]
fn typing_somewhere_else_starts_something_new() {
    let _lock = EDITOR.lock().unwrap();
    unsafe { editor_init() };
    journal();
    typed("ab");
    unsafe { editor_cursor_set(0) };
    typed("z");
    assert_eq!(journal(), "ins 0 ab\nins 0 z\n");
}

#[test]
fn reading_the_journal_empties_it() {
    let _lock = EDITOR.lock().unwrap();
    unsafe { editor_init() };
    typed("something");
    assert!(!journal().is_empty());
    assert_eq!(journal(), "", "or every request would carry the whole session");
}

#[test]
fn moving_about_is_not_something_that_happened_to_the_document() {
    let _lock = EDITOR.lock().unwrap();
    unsafe { editor_init() };
    typed("abc");
    journal();
    unsafe { editor_key(K_LEFT) };
    unsafe { editor_key(K_LEFT) };
    assert_eq!(journal(), "", "the caret moved; the text did not");
    assert_eq!(unsafe { editor_cursor_get() }, 1);
}

const K_CMD: u32 = 0x1000;

#[test]
fn a_command_is_recorded_as_what_it_did() {
    let _lock = EDITOR.lock().unwrap();
    unsafe { editor_init() };
    journal();
    unsafe { editor_key(K_CMD | b't' as u32) };
    assert_eq!(journal(), "key new-screen\n",
               "named for what it does, because the key differs by machine");
}

#[test]
fn asking_for_a_rewrite_is_part_of_the_history() {
    let _lock = EDITOR.lock().unwrap();
    unsafe { editor_init() };
    typed("make it faster");
    journal();
    unsafe { editor_key(K_CMD | b'p' as u32) };
    assert_eq!(journal(), "key asked-you-to-rewrite\n");
}

#[test]
fn two_presses_are_two_things_that_happened() {
    let _lock = EDITOR.lock().unwrap();
    unsafe { editor_init() };
    journal();
    unsafe {
        editor_key(K_CMD | b'=' as u32);
        editor_key(K_CMD | b'=' as u32);
    }
    assert_eq!(journal(), "key made-the-text-bigger\nkey made-the-text-bigger\n",
               "unlike two letters, which are one word");
}

#[test]
fn typing_and_commands_keep_their_order() {
    let _lock = EDITOR.lock().unwrap();
    unsafe { editor_init() };
    journal();
    typed("hi");
    unsafe { editor_key(K_CMD | b't' as u32) };
    typed("there");
    assert_eq!(journal(), "ins 0 hi\nkey new-screen\nins 0 there\n",
               "the commands are what explain the gaps between the typing");
}
