//! The string and number helpers every other part writes through.
//!
//! Dull and worth testing precisely because of that: a wrong base64 decoder
//! turns machine code the server sent into a shorter, plausible program the
//! device then executes, and a wrong JSON escape puts a stray quote in a
//! request body and the backend answers 400 to something nobody can see is
//! malformed.

use maos::*;
use std::ffi::CString;

fn c(s: &str) -> CString {
    CString::new(s).unwrap()
}

#[test]
fn a_length_is_the_bytes_before_the_nul() {
    assert_eq!(unsafe { str_len(c("").as_ptr() as *const u8) }, 0);
    assert_eq!(unsafe { str_len(c("hello").as_ptr() as *const u8) }, 5);
}

#[test]
fn finding_gives_back_the_place_just_past_the_match() {
    let hay = c("{\"code\":\"abc\"}");
    let needle = c("\"code\":\"");
    let at = unsafe { str_find(hay.as_ptr() as *const u8, needle.as_ptr() as *const u8) };
    assert!(!at.is_null());
    assert_eq!(unsafe { *at }, b'a', "which is what lets the decoder start there");
}

#[test]
fn finding_nothing_gives_back_nothing() {
    let hay = c("a short string");
    let needle = c("not in it");
    assert!(unsafe { str_find(hay.as_ptr() as *const u8, needle.as_ptr() as *const u8) }.is_null());
}

#[test]
fn a_prefix_is_matched_only_at_the_front() {
    let s = c("colours blue");
    assert_eq!(unsafe { str_starts(s.as_ptr() as *const u8, c("colours").as_ptr() as *const u8) }, 1);
    assert_eq!(unsafe { str_starts(s.as_ptr() as *const u8, c("blue").as_ptr() as *const u8) }, 0);
}

#[test]
fn base64_goes_both_ways() {
    for case in ["", "f", "fo", "foo", "foob", "fooba", "foobar"] {
        let mut encoded = vec![0u8; case.len() * 2 + 8];
        let n = unsafe { base64_encode(case.as_ptr(), case.len() as u32, encoded.as_mut_ptr()) };
        let mut back = vec![0u8; 64];
        let m = unsafe { base64_decode(encoded.as_ptr(), back.as_mut_ptr(), back.len() as u32) };
        assert_eq!(m, case.len() as i32, "round trip of {case:?} (encoded {n} bytes)");
        assert_eq!(&back[..case.len()], case.as_bytes());
    }
}

#[test]
fn anything_that_is_not_base64_is_a_refusal_and_not_an_ending() {
    // This is the contract, and it is stricter than it looks: a decoder whose
    // output is executed must not quietly accept a truncated or corrupted
    // reply, because what comes out is a shorter program that runs. So a
    // trailing quote from a JSON reply is not a terminator - the caller has to
    // end the string itself before asking.
    let embedded = c("Zm9vYmFy\"}");
    let mut out = vec![0u8; 32];
    assert_eq!(
        unsafe { base64_decode(embedded.as_ptr() as *const u8, out.as_mut_ptr(), 32) },
        -1
    );

    let ended = c("Zm9vYmFy");
    let n = unsafe { base64_decode(ended.as_ptr() as *const u8, out.as_mut_ptr(), 32) };
    assert_eq!(n, 6);
    assert_eq!(&out[..6], b"foobar");
}

#[test]
fn base64_will_not_write_past_what_it_was_given() {
    let text = c("Zm9vYmFyYmF6");
    let mut out = [0u8; 3];
    let n = unsafe { base64_decode(text.as_ptr() as *const u8, out.as_mut_ptr(), 3) };
    assert!(n <= 3, "wrote {n} bytes into three");
}

#[test]
fn json_escaping_covers_what_would_break_a_body() {
    let input = c("a \"quote\", a \\slash and a\nnewline");
    let mut out = vec![0u8; 256];
    let end = unsafe {
        json_escape(out.as_mut_ptr(), input.as_ptr() as *const u8, out.len() as u32)
    };
    let n = end as usize - out.as_ptr() as usize;
    let escaped = String::from_utf8_lossy(&out[..n]).into_owned();
    assert!(escaped.contains("\\\""), "{escaped}");
    assert!(escaped.contains("\\\\"), "{escaped}");
    assert!(escaped.contains("\\n"), "{escaped}");
    assert!(!escaped.contains('\n'), "a raw newline ends the request early");
}

#[test]
fn hex_is_lowercase_and_two_characters_a_byte() {
    assert_eq!(hex(&[0x00, 0x0f, 0xff]), "000fff");
    assert_eq!(hex(&[]), "");
}

#[test]
fn the_hash_everything_rests_on_knows_the_empty_string() {
    let mut out = [0u8; 32];
    unsafe { sha256(b"".as_ptr(), 0, out.as_mut_ptr()) };
    assert_eq!(
        hex(&out),
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        "the value the key schedule writes down as a constant"
    );
}

#[test]
fn and_the_one_every_reference_prints() {
    let mut out = [0u8; 32];
    unsafe { sha256(b"abc".as_ptr(), 3, out.as_mut_ptr()) };
    assert_eq!(
        hex(&out),
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    );
}
