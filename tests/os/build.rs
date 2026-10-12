//! Assemble the OS and hand it to the linker.
//!
//! Shelling out to clang and ar rather than taking the `cc` crate, for the
//! reason the rest of this project gives for everything: a dependency here
//! would be a dependency in the one place that is supposed to prove the code
//! stands on its own. Two commands and a loop is not worth a crate.
//!
//! Which files are listed is the only part of this suite that knows about an
//! architecture. A port edits this and nothing else.

use std::path::{Path, PathBuf};
use std::process::Command;

/// Everything the tests reach into, and what each one is needed for.
const SOURCES: &[&str] = &[
    // the editor core and what it draws through
    "editor/editor.S",
    "editor/applet.S",
    "gfx/image.S",
    "gfx/video.S",
    "font/font.S",
    "font/render.S",
    // strings, numbers and base64, shared by everything above and below
    "net/str.S",
    // the cryptography, and the two halves of TLS 1.3 built on it
    "crypto/sha256.S",
    "crypto/hmac.S",
    "crypto/hkdf.S",
    "crypto/chacha20.S",
    "crypto/poly1305.S",
    "crypto/aead.S",
    "crypto/tls13.S",
    "net/tlsrec.S",
];

fn main() {
    let root = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("..")
        .join("..");
    let out = PathBuf::from(std::env::var("OUT_DIR").unwrap());

    let mut objects = Vec::new();
    for src in SOURCES {
        let path = root.join(src);
        println!("cargo:rerun-if-changed={}", path.display());
        let object = out.join(src.replace('/', "_").replace(".S", ".o"));
        run(Command::new("clang")
            .arg("-I")
            .arg(root.join("include"))
            .arg("-c")
            .arg(&path)
            .arg("-o")
            .arg(&object));
        objects.push(object);
    }

    let archive = out.join("libmaosos.a");
    let _ = std::fs::remove_file(&archive);
    let mut ar = Command::new("ar");
    ar.arg("rcs").arg(&archive);
    for o in &objects {
        ar.arg(o);
    }
    run(&mut ar);

    println!("cargo:rustc-link-search=native={}", out.display());
    println!("cargo:rustc-link-lib=static=maosos");
    println!("cargo:rerun-if-changed=build.rs");
    let include = root.join("include");
    println!("cargo:rerun-if-changed={}", include.display());
    let _: &Path = include.as_path();
}

fn run(cmd: &mut Command) {
    let status = cmd
        .status()
        .unwrap_or_else(|e| panic!("could not run {:?}: {e}", cmd.get_program()));
    assert!(status.success(), "{:?} failed: {status}", cmd);
}
