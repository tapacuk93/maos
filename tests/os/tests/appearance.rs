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
