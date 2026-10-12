//! TLS 1.3, against the values RFC 8448 prints.
//!
//! These are the most portable assertions in the suite: they are arithmetic on
//! bytes, and an implementation on any architecture that gets a different
//! answer is wrong rather than different. The same file should pass unchanged
//! the day this is built for something that is not aarch64.

use maos::*;

/// RFC 8448 section 3, the IKM of {server} extract secret "handshake".
const ECDHE: &str = "8bd4054fb55b9d63fdfbacf9f04b9f0d35e6d63f537563efd46272900f89492d";
/// The same trace's hash of ClientHello..ServerHello.
const TRANSCRIPT: &str = "860c06edc07858ee8e78f0e7428c58edd6b43f2ca3e6e95f02ed063cf0e1cad8";

#[test]
fn the_early_secret_is_the_same_in_every_connection() {
    let _lock = TLS.lock().unwrap();
    let mut early = [0u8; 32];
    unsafe { tls13_early_secret(early.as_mut_ptr()) };
    assert_eq!(
        hex(&early),
        "33ad0a1c607ec03b09e6cd9893680ce210adf300aa1f2660e1b22e10f170f92a"
    );
}

#[test]
fn the_shared_secret_is_the_only_thing_that_makes_it_this_connection() {
    let _lock = TLS.lock().unwrap();
    let mut early = [0u8; 32];
    let mut hs = [0u8; 32];
    let shared = unhex(ECDHE);
    unsafe {
        tls13_early_secret(early.as_mut_ptr());
        tls13_handshake_secret(early.as_ptr(), shared.as_ptr(), hs.as_mut_ptr());
    }
    assert_eq!(
        hex(&hs),
        "1dc826e93606aa6fdc0aadc12f741b01046aa6b99f691ed221a9f0ca043fbeac"
    );
}

#[test]
fn both_sides_of_the_handshake_get_their_own_key() {
    let _lock = TLS.lock().unwrap();
    let mut early = [0u8; 32];
    let mut hs = [0u8; 32];
    let (mut client, mut server) = ([0u8; 32], [0u8; 32]);
    let shared = unhex(ECDHE);
    let transcript = unhex(TRANSCRIPT);
    unsafe {
        tls13_early_secret(early.as_mut_ptr());
        tls13_handshake_secret(early.as_ptr(), shared.as_ptr(), hs.as_mut_ptr());
        tls13_hs_traffic(
            hs.as_ptr(),
            transcript.as_ptr(),
            client.as_mut_ptr(),
            server.as_mut_ptr(),
        );
    }
    assert_eq!(
        hex(&client),
        "b3eddb126e067f35a780b3abf45e2d8f3b1a950738f52e9600746a0e27a55a21"
    );
    assert_eq!(
        hex(&server),
        "b67b7d690cc16c4e75e54213cb2d37b4e9c912bcded9105d42befd59d391ad38"
    );
    assert_ne!(client, server, "one key for both directions is one key too few");
}

#[test]
fn the_application_keys_do_not_follow_from_the_handshake_ones() {
    let _lock = TLS.lock().unwrap();
    let mut early = [0u8; 32];
    let mut hs = [0u8; 32];
    let mut ms = [0u8; 32];
    let shared = unhex(ECDHE);
    unsafe {
        tls13_early_secret(early.as_mut_ptr());
        tls13_handshake_secret(early.as_ptr(), shared.as_ptr(), hs.as_mut_ptr());
        tls13_master_secret(hs.as_ptr(), ms.as_mut_ptr());
    }
    assert_eq!(
        hex(&ms),
        "18df06843d13a08bf2a449844c5f8a478001bc4d4c627984d5a41da8d0402919"
    );
}

#[test]
fn a_traffic_secret_becomes_a_key_and_an_iv() {
    let _lock = TLS.lock().unwrap();
    let secret = unhex("b67b7d690cc16c4e75e54213cb2d37b4e9c912bcded9105d42befd59d391ad38");
    let mut key = [0u8; 16];
    let mut iv = [0u8; 12];
    unsafe { tls13_traffic_key(secret.as_ptr(), key.as_mut_ptr(), 16, iv.as_mut_ptr()) };
    assert_eq!(hex(&key), "3fce516009c21727d0f2e4e86ee403bc");
    assert_eq!(hex(&iv), "5d313eb2671276ee13000b30");
}

#[test]
fn the_transcript_can_be_asked_for_without_being_ended() {
    let _lock = TLS.lock().unwrap();
    let mut ctx = [0u8; TRANSCRIPT_SZ];
    let (mut first, mut second, mut direct) = ([0u8; 32], [0u8; 32], [0u8; 32]);
    unsafe {
        tls13_transcript_init(ctx.as_mut_ptr());
        tls13_transcript_add(ctx.as_mut_ptr(), b"one".as_ptr(), 3);
        tls13_transcript_hash(ctx.as_mut_ptr(), first.as_mut_ptr());
        tls13_transcript_add(ctx.as_mut_ptr(), b"two".as_ptr(), 3);
        tls13_transcript_hash(ctx.as_mut_ptr(), second.as_mut_ptr());
        sha256(b"onetwo".as_ptr(), 6, direct.as_mut_ptr());
    }
    assert_ne!(first, second, "the conversation went on");
    assert_eq!(
        second, direct,
        "and asking for it halfway did not close it"
    );
}

#[test]
fn a_record_goes_out_and_comes_back() {
    let _lock = TLS.lock().unwrap();
    let key = unhex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
    let iv = unhex("404142434445464748494a4b");
    let (mut send, mut recv) = ([0u8; TLS_KEYS_SZ], [0u8; TLS_KEYS_SZ]);
    let mut record = [0u8; 256];
    let mut plain = [0u8; 256];
    let mut kind = 0u8;

    unsafe {
        tls_keys_set(send.as_mut_ptr(), key.as_ptr(), iv.as_ptr());
        tls_keys_set(recv.as_mut_ptr(), key.as_ptr(), iv.as_ptr());
        let n = tls_seal(send.as_mut_ptr(), 0x17, b"hello from maos".as_ptr(), 15,
                         record.as_mut_ptr());
        assert!(n > 0);
        assert_eq!(
            hex(&record[..n as usize]),
            "1703030020903110ed1d6a887337a2d66dda801eda32b5c4b7c02e6afe2a4b130202963590"
        );
        let got = tls_open(recv.as_mut_ptr(), record.as_ptr(), n as u32,
                           plain.as_mut_ptr(), &mut kind);
        assert_eq!(got, 15);
        assert_eq!(&plain[..15], b"hello from maos");
        assert_eq!(kind, 0x17, "the real type lives inside the encryption");
    }
}

#[test]
fn the_second_record_is_not_encrypted_like_the_first() {
    let _lock = TLS.lock().unwrap();
    let key = unhex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
    let iv = unhex("404142434445464748494a4b");
    let mut send = [0u8; TLS_KEYS_SZ];
    let (mut a, mut b) = ([0u8; 256], [0u8; 256]);
    unsafe {
        tls_keys_set(send.as_mut_ptr(), key.as_ptr(), iv.as_ptr());
        let n = tls_seal(send.as_mut_ptr(), 0x17, b"same".as_ptr(), 4, a.as_mut_ptr()) as usize;
        let m = tls_seal(send.as_mut_ptr(), 0x17, b"same".as_ptr(), 4, b.as_mut_ptr()) as usize;
        assert_eq!(n, m);
        assert_ne!(
            a[..n], b[..m],
            "the same plaintext twice under one nonce hands anybody watching \
             the exclusive-or of two messages"
        );
    }
}

#[test]
fn one_altered_byte_and_it_does_not_open() {
    let _lock = TLS.lock().unwrap();
    let key = unhex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
    let iv = unhex("404142434445464748494a4b");
    let (mut send, mut recv) = ([0u8; TLS_KEYS_SZ], [0u8; TLS_KEYS_SZ]);
    let mut record = [0u8; 256];
    let mut plain = [0u8; 256];
    let mut kind = 0u8;
    unsafe {
        tls_keys_set(send.as_mut_ptr(), key.as_ptr(), iv.as_ptr());
        let n = tls_seal(send.as_mut_ptr(), 0x17, b"secret".as_ptr(), 6,
                         record.as_mut_ptr()) as u32;
        record[7] ^= 1;
        tls_keys_set(recv.as_mut_ptr(), key.as_ptr(), iv.as_ptr());
        assert_eq!(
            tls_open(recv.as_mut_ptr(), record.as_ptr(), n, plain.as_mut_ptr(), &mut kind),
            -1
        );
    }
}

#[test]
fn a_record_read_out_of_order_does_not_open_either() {
    let _lock = TLS.lock().unwrap();
    let key = unhex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
    let iv = unhex("404142434445464748494a4b");
    let (mut send, mut recv) = ([0u8; TLS_KEYS_SZ], [0u8; TLS_KEYS_SZ]);
    let (mut first, mut second) = ([0u8; 256], [0u8; 256]);
    let mut plain = [0u8; 256];
    let mut kind = 0u8;
    unsafe {
        tls_keys_set(send.as_mut_ptr(), key.as_ptr(), iv.as_ptr());
        tls_seal(send.as_mut_ptr(), 0x17, b"one".as_ptr(), 3, first.as_mut_ptr());
        let n = tls_seal(send.as_mut_ptr(), 0x17, b"two".as_ptr(), 3,
                         second.as_mut_ptr()) as u32;
        tls_keys_set(recv.as_mut_ptr(), key.as_ptr(), iv.as_ptr());
        assert_eq!(
            tls_open(recv.as_mut_ptr(), second.as_ptr(), n, plain.as_mut_ptr(), &mut kind),
            -1,
            "the counter is how a conversation cannot be rearranged"
        );
    }
}

#[test]
fn a_record_shorter_than_its_own_tag_is_refused() {
    let _lock = TLS.lock().unwrap();
    let key = unhex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
    let iv = unhex("404142434445464748494a4b");
    let mut recv = [0u8; TLS_KEYS_SZ];
    let mut plain = [0u8; 256];
    let mut kind = 0u8;
    let runt = [0x17u8, 0x03, 0x03, 0x00, 0x02, 0x00, 0x00];
    unsafe {
        tls_keys_set(recv.as_mut_ptr(), key.as_ptr(), iv.as_ptr());
        assert_eq!(
            tls_open(recv.as_mut_ptr(), runt.as_ptr(), runt.len() as u32,
                     plain.as_mut_ptr(), &mut kind),
            -1
        );
    }
}
