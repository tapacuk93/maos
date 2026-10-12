//! The operating system, as a library the tests can call.
//!
//! Every declaration here is a promise about the C ABI and nothing else. The
//! bodies are assembly, the assembly is the real thing the machine boots, and
//! the only thing this file adds is names and types - so a test that passes
//! here is a statement about the code that ships rather than about a model of
//! it.
//!
//! The wrappers are thin on purpose. Where one exists it is because the raw
//! call takes a buffer and a length and gives back a length, and every test
//! would otherwise repeat the same six lines of pointer arithmetic.

use std::ffi::CString;

#[allow(dead_code)]
extern "C" {
    // ---- the editor core
    pub fn editor_init();
    pub fn editor_insert_str(s: *const u8);
    pub fn editor_key(key: u32) -> u32;
    pub fn editor_length() -> u32;
    pub fn editor_cursor_get() -> u32;
    pub fn editor_cursor_set(at: u32);
    pub fn editor_replace(from: u32, to: u32, text: *const u8);
    pub fn editor_context(out: *mut u8, cap: u32) -> u32;
    pub fn editor_journal_text(out: *mut u8, cap: u32) -> u32;
    pub fn editor_screen_info(/* out */) -> u64;
    pub fn editor_screen_step(dir: i32);
    pub fn editor_colour(slot: u32);

    // ---- strings and numbers, which everything above writes through
    pub fn str_len(s: *const u8) -> u32;
    pub fn str_find(haystack: *const u8, needle: *const u8) -> *const u8;
    pub fn str_starts(s: *const u8, prefix: *const u8) -> u32;
    pub fn base64_encode(input: *const u8, len: u32, out: *mut u8) -> u32;
    pub fn base64_decode(text: *const u8, out: *mut u8, cap: u32) -> i32;
    pub fn json_escape(out: *mut u8, input: *const u8, cap: u32) -> *mut u8;
    pub fn hex_encode(bytes: *const u8, count: u32, out: *mut u8);

    // ---- the key schedule
    pub fn tls13_early_secret(out: *mut u8);
    pub fn tls13_handshake_secret(early: *const u8, shared: *const u8, out: *mut u8);
    pub fn tls13_master_secret(handshake: *const u8, out: *mut u8);
    pub fn tls13_hs_traffic(hs: *const u8, transcript: *const u8, client: *mut u8, server: *mut u8);
    pub fn tls13_ap_traffic(ms: *const u8, transcript: *const u8, client: *mut u8, server: *mut u8);
    pub fn tls13_traffic_key(secret: *const u8, key: *mut u8, keylen: u32, iv: *mut u8);
    pub fn tls13_finished_key(secret: *const u8, out: *mut u8);
    pub fn tls13_transcript_init(ctx: *mut u8);
    pub fn tls13_transcript_add(ctx: *mut u8, data: *const u8, len: u32);
    pub fn tls13_transcript_hash(ctx: *mut u8, out: *mut u8);

    // ---- the record layer
    pub fn tls_keys_set(ctx: *mut u8, key: *const u8, iv: *const u8);
    pub fn tls_seal(ctx: *mut u8, kind: u32, plain: *const u8, len: u32, out: *mut u8) -> i32;
    pub fn tls_open(
        ctx: *mut u8,
        record: *const u8,
        len: u32,
        out: *mut u8,
        kind_out: *mut u8,
    ) -> i32;

    // ---- the one-way hash everything else is built on
    pub fn sha256(data: *const u8, len: u32, out: *mut u8);
}

/// What one direction's record keys occupy. See net/tlsrec.S.
pub const TLS_KEYS_SZ: usize = 56;
/// A transcript is a hash context until somebody asks for it.
pub const TRANSCRIPT_SZ: usize = 112;

/// Type a string into the editor, the way a person would.
pub fn typed(s: &str) {
    let c = CString::new(s).unwrap();
    unsafe { editor_insert_str(c.as_ptr() as *const u8) }
}

/// What the editor would send as the screen.
pub fn screen() -> String {
    let mut buf = vec![0u8; 8192];
    let n = unsafe { editor_context(buf.as_mut_ptr(), buf.len() as u32) } as usize;
    String::from_utf8_lossy(&buf[..n]).into_owned()
}

/// What the editor has recorded about what was done, draining it.
pub fn journal() -> String {
    let mut buf = vec![0u8; 2048];
    let n = unsafe { editor_journal_text(buf.as_mut_ptr(), buf.len() as u32) } as usize;
    String::from_utf8_lossy(&buf[..n]).into_owned()
}

/// Bytes as lowercase hex, through the same encoder the device uses.
pub fn hex(bytes: &[u8]) -> String {
    let mut out = vec![0u8; bytes.len() * 2 + 1];
    unsafe { hex_encode(bytes.as_ptr(), bytes.len() as u32, out.as_mut_ptr()) };
    out.pop();
    String::from_utf8(out).unwrap()
}

/// Hex back to bytes, for writing published test vectors down readably.
pub fn unhex(s: &str) -> Vec<u8> {
    let s: String = s.chars().filter(|c| !c.is_whitespace()).collect();
    (0..s.len())
        .step_by(2)
        .map(|i| u8::from_str_radix(&s[i..i + 2], 16).unwrap())
        .collect()
}

/// The editor is one global. Tests that drive it take this first, because two
/// of them typing into the same screen at once would be two tests failing for
/// a reason neither of them is about.
pub static EDITOR: std::sync::Mutex<()> = std::sync::Mutex::new(());

/// And so is every working buffer in the TLS stack.
///
/// Not an oversight in the tests. net/tlsrec.S says plainly that it holds one
/// connection's buffer, because the device it is for talks to its own backend
/// and to nothing else at the same time, and a kernel with no allocator should
/// not grow one for a buffer whose size is known at build time. crypto/hkdf.S
/// does the same with its scratch, one layer down, which means the key
/// schedule is single-connection for the same reason whether or not anybody
/// said so.
///
/// This lock is where that contract is written down from the outside, and it
/// is the thing that found it: run in parallel without it, two handshakes
/// derive each other's keys. A port that gives these per-connection state can
/// delete the lock. Until then, the day somebody opens a second connection the
/// tests that fail are these - which is the right place to find out, rather
/// than in a conversation that silently decrypts to somebody else's bytes.
pub static TLS: std::sync::Mutex<()> = std::sync::Mutex::new(());

extern "C" {
    /// How big the text is, 1 to 8, and what it is now.
    pub fn editor_scale(size: u32);
    pub fn editor_scale_get() -> u32;
}
