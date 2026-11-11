//! The opt-in "remove location and camera data from new photos" setting: the metadata filters
//! (JPEG, PNG, WebP, GIF), the upload paths that honour the uploader's setting, the account API
//! and the guarantee that nothing already stored is ever touched.

use std::{
    collections::HashMap,
    io::Cursor,
    path::{Path, PathBuf},
    sync::{Arc, Mutex},
};

use axum::{
    Router,
    body::Body,
    http::{Request, StatusCode},
};
use http_body_util::BodyExt;
use image::{DynamicImage, ImageDecoder, ImageFormat, ImageReader, metadata::Orientation};
use sha2::{Digest, Sha256};
use sqlx::SqlitePool;
use stricknani_server::{
    api_v2, db,
    image_import::{FetchError, FetchFuture, FetchedImage, ImageFetcher},
    image_metadata::{StripError, strip},
    media, projects, yarns,
};
use tower::ServiceExt;
use url::Url;

const TOKEN: &str = "strip-metadata-token";
const OTHER_TOKEN: &str = "strip-metadata-other-token";

const MAKE: &str = "SECRET-CAMERA-MAKER";
const MODEL: &str = "SECRET-CAMERA-MODEL-9000";
const SERIAL: &str = "SERIAL-0123456789";
const COMMENT: &str = "SECRET-COMMENT-TEXT";
const ICC: &[u8] = b"fake-icc-profile-0123456789-abcdefghijklmnopqrstuvwxyz";

// ---------------------------------------------------------------------------------------------
// Fixture images carrying GPS and camera metadata
// ---------------------------------------------------------------------------------------------

fn pixels(width: u32, height: u32) -> DynamicImage {
    DynamicImage::ImageRgb8(image::RgbImage::from_fn(width, height, |x, y| {
        image::Rgb([(x * 5) as u8, (y * 7) as u8, ((x + y) * 3) as u8])
    }))
}

fn encode(image: &DynamicImage, format: ImageFormat) -> Vec<u8> {
    let mut out = Cursor::new(Vec::new());
    image.write_to(&mut out, format).expect("fixture encodes");
    out.into_inner()
}

fn put16(out: &mut Vec<u8>, value: u16) {
    out.extend_from_slice(&value.to_be_bytes());
}

fn put32(out: &mut Vec<u8>, value: u32) {
    out.extend_from_slice(&value.to_be_bytes());
}

/// A big-endian TIFF/EXIF block with make, model, serial, optional orientation and a GPS IFD.
fn exif_tiff(orientation: Option<u16>) -> Vec<u8> {
    let mut entries: Vec<(u16, u16, u32, Vec<u8>)> = Vec::new(); // tag, type, count, inline/offset
    let mut tail: Vec<u8> = Vec::new();
    let entry_count = 5 + usize::from(orientation.is_some());
    let tail_start = 8 + 2 + entry_count * 12 + 4;
    let ascii = |tail: &mut Vec<u8>, text: &str| -> (u32, Vec<u8>) {
        let mut data = text.as_bytes().to_vec();
        data.push(0);
        let offset = (tail_start + tail.len()) as u32;
        tail.extend_from_slice(&data);
        (data.len() as u32, offset.to_be_bytes().to_vec())
    };
    let (count, off) = ascii(&mut tail, MAKE);
    entries.push((0x010F, 2, count, off));
    let (count, off) = ascii(&mut tail, MODEL);
    entries.push((0x0110, 2, count, off));
    if let Some(value) = orientation {
        entries.push((0x0112, 3, 1, vec![(value >> 8) as u8, value as u8, 0, 0]));
    }
    let (count, off) = ascii(&mut tail, SERIAL);
    entries.push((0xA431, 2, count, off)); // BodySerialNumber (listed here for brevity)
    let (count, off) = ascii(&mut tail, "2024:05:17 13:37:00");
    entries.push((0x0132, 2, count, off)); // DateTime
    // GPS IFD: latitude ref, latitude (3 rationals), longitude ref, longitude.
    let gps_offset = (tail_start + tail.len()) as u32;
    entries.push((0x8825, 4, 1, gps_offset.to_be_bytes().to_vec()));
    entries.sort_by_key(|entry| entry.0);

    let mut gps = Vec::new();
    put16(&mut gps, 4);
    let rational_at = gps_offset as usize + 2 + 4 * 12 + 4;
    for (tag, kind, count, value) in [
        (1_u16, 2_u16, 2_u32, vec![b'N', 0, 0, 0]),
        (2, 5, 3, (rational_at as u32).to_be_bytes().to_vec()),
        (3, 2, 2, vec![b'E', 0, 0, 0]),
        (4, 5, 3, ((rational_at + 24) as u32).to_be_bytes().to_vec()),
    ] {
        put16(&mut gps, tag);
        put16(&mut gps, kind);
        put32(&mut gps, count);
        gps.extend_from_slice(&value);
    }
    put32(&mut gps, 0);
    for (numerator, denominator) in [
        (52_u32, 1_u32),
        (31, 1),
        (1234, 100),
        (13, 1),
        (24, 1),
        (5678, 100),
    ] {
        put32(&mut gps, numerator);
        put32(&mut gps, denominator);
    }

    let mut tiff = b"MM\0\x2a".to_vec();
    put32(&mut tiff, 8);
    put16(&mut tiff, entries.len() as u16);
    for (tag, kind, count, value) in entries {
        put16(&mut tiff, tag);
        put16(&mut tiff, kind);
        put32(&mut tiff, count);
        tiff.extend_from_slice(&value);
    }
    put32(&mut tiff, 0);
    tiff.extend_from_slice(&tail);
    tiff.extend_from_slice(&gps);
    tiff
}

fn jpeg_segment(marker: u8, data: &[u8]) -> Vec<u8> {
    let mut out = vec![0xFF, marker];
    out.extend_from_slice(&((data.len() + 2) as u16).to_be_bytes());
    out.extend_from_slice(data);
    out
}

/// A JPEG with EXIF (incl. GPS), a second APP1 holding XMP, an IPTC block, a comment, an ICC
/// profile, and trailing data after EOI (like an MPF secondary image).
fn jpeg_with_metadata(width: u32, height: u32, orientation: Option<u16>) -> Vec<u8> {
    let base = encode(&pixels(width, height), ImageFormat::Jpeg);
    assert_eq!(&base[..2], &[0xFF, 0xD8]);
    let mut exif = b"Exif\0\0".to_vec();
    exif.extend_from_slice(&exif_tiff(orientation));
    let mut icc = b"ICC_PROFILE\0\x01\x01".to_vec();
    icc.extend_from_slice(ICC);
    let mut out = vec![0xFF, 0xD8];
    out.extend(jpeg_segment(0xE1, &exif));
    out.extend(jpeg_segment(
        0xE1,
        format!("http://ns.adobe.com/xap/1.0/\0<x:xmpmeta>{COMMENT}</x:xmpmeta>").as_bytes(),
    ));
    out.extend(jpeg_segment(
        0xED,
        format!("Photoshop 3.0\0{COMMENT}").as_bytes(),
    ));
    out.extend(jpeg_segment(0xFE, COMMENT.as_bytes()));
    out.extend(jpeg_segment(0xE2, &icc));
    out.extend_from_slice(&base[2..]);
    // The encoder ends with EOI; whatever follows must not survive.
    out.extend_from_slice(b"TRAILER-");
    out.extend_from_slice(COMMENT.as_bytes());
    out
}

fn crc32(data: &[u8]) -> u32 {
    let mut crc = 0xFFFF_FFFF_u32;
    for &byte in data {
        crc ^= u32::from(byte);
        for _ in 0..8 {
            crc = (crc >> 1) ^ (0xEDB8_8320 & (crc & 1).wrapping_neg());
        }
    }
    !crc
}

fn png_chunk(kind: &[u8; 4], data: &[u8]) -> Vec<u8> {
    let mut out = (data.len() as u32).to_be_bytes().to_vec();
    out.extend_from_slice(kind);
    out.extend_from_slice(data);
    let mut checked = kind.to_vec();
    checked.extend_from_slice(data);
    out.extend_from_slice(&crc32(&checked).to_be_bytes());
    out
}

fn zlib_stored(data: &[u8]) -> Vec<u8> {
    let mut out = vec![0x78, 0x01, 0x01];
    out.extend_from_slice(&(data.len() as u16).to_le_bytes());
    out.extend_from_slice(&(!(data.len() as u16)).to_le_bytes());
    out.extend_from_slice(data);
    let (mut a, mut b) = (1_u32, 0_u32);
    for &byte in data {
        a = (a + u32::from(byte)) % 65521;
        b = (b + a) % 65521;
    }
    out.extend_from_slice(&((b << 16) | a).to_be_bytes());
    out
}

/// A PNG with eXIf (GPS), text chunks, a timestamp, an ICC profile and a vendor chunk.
fn png_with_metadata(width: u32, height: u32, orientation: Option<u16>) -> Vec<u8> {
    let base = encode(&pixels(width, height), ImageFormat::Png);
    let ihdr_end = 8 + 8 + 13 + 4;
    let mut icc = b"test-profile\0\0".to_vec();
    icc.extend_from_slice(&zlib_stored(ICC));
    let mut out = base[..ihdr_end].to_vec();
    out.extend(png_chunk(b"eXIf", &exif_tiff(orientation)));
    out.extend(png_chunk(b"iCCP", &icc));
    out.extend(png_chunk(b"tEXt", format!("Comment\0{COMMENT}").as_bytes()));
    out.extend(png_chunk(
        b"iTXt",
        format!("XML:com.adobe.xmp\0\0\0\0\0{COMMENT}").as_bytes(),
    ));
    out.extend(png_chunk(b"zTXt", b"Author\0\0x"));
    out.extend(png_chunk(b"tIME", &[0x07, 0xE8, 5, 17, 13, 37, 0]));
    out.extend(png_chunk(b"vpAg", b"vendor-private"));
    out.extend_from_slice(&base[ihdr_end..]);
    out.extend_from_slice(COMMENT.as_bytes()); // trailing garbage after IEND
    out
}

fn riff_chunk(kind: &[u8; 4], data: &[u8]) -> Vec<u8> {
    let mut out = kind.to_vec();
    out.extend_from_slice(&(data.len() as u32).to_le_bytes());
    out.extend_from_slice(data);
    if data.len() % 2 == 1 {
        out.push(0);
    }
    out
}

/// An extended WebP (VP8X) with ICCP, EXIF (GPS), XMP and an unknown chunk around a lossless image.
fn webp_with_metadata(width: u32, height: u32, orientation: Option<u16>) -> Vec<u8> {
    let simple = encode(&pixels(width, height), ImageFormat::WebP);
    assert_eq!(&simple[8..12], b"WEBP");
    let vp8l = &simple[12..]; // the VP8L chunk of a simple lossless file
    assert_eq!(&vp8l[..4], b"VP8L");
    let mut vp8x = vec![0x20 | 0x08 | 0x04, 0, 0, 0];
    vp8x.extend_from_slice(&(width - 1).to_le_bytes()[..3]);
    vp8x.extend_from_slice(&(height - 1).to_le_bytes()[..3]);
    let mut body = b"WEBP".to_vec();
    body.extend(riff_chunk(b"VP8X", &vp8x));
    body.extend(riff_chunk(b"ICCP", ICC));
    body.extend_from_slice(vp8l);
    // WebP stores the raw TIFF block, without the JPEG "Exif\0\0" prefix.
    body.extend(riff_chunk(b"EXIF", &exif_tiff(orientation)));
    body.extend(riff_chunk(
        b"XMP ",
        format!("<x:xmpmeta>{COMMENT}</x:xmpmeta>").as_bytes(),
    ));
    body.extend(riff_chunk(b"vndr", COMMENT.as_bytes()));
    let mut out = b"RIFF".to_vec();
    out.extend_from_slice(&(body.len() as u32).to_le_bytes());
    out.extend(body);
    out
}

/// A two-frame animated GIF with a comment and an XMP application extension.
fn gif_with_metadata() -> Vec<u8> {
    let mut out = Cursor::new(Vec::new());
    {
        let mut encoder = image::codecs::gif::GifEncoder::new(&mut out);
        encoder
            .set_repeat(image::codecs::gif::Repeat::Infinite)
            .unwrap();
        for shade in [10_u8, 200] {
            let frame = image::RgbaImage::from_pixel(12, 8, image::Rgba([shade, 40, 90, 255]));
            encoder.encode_frame(image::Frame::new(frame)).unwrap();
        }
    }
    let base = out.into_inner();
    // Insert a comment extension and an XMP application extension right after the header and
    // logical screen descriptor (+ global colour table when present).
    let packed = base[10];
    let mut at = 13;
    if packed & 0x80 != 0 {
        at += 3 * (1 << ((packed & 7) + 1));
    }
    let mut injected = vec![0x21, 0xFE, COMMENT.len() as u8];
    injected.extend_from_slice(COMMENT.as_bytes());
    injected.push(0);
    injected.extend_from_slice(&[0x21, 0xFF, 11]);
    injected.extend_from_slice(b"XMP DataXMP");
    injected.extend_from_slice(&[COMMENT.len() as u8]);
    injected.extend_from_slice(COMMENT.as_bytes());
    injected.push(0);
    let mut result = base[..at].to_vec();
    result.extend(injected);
    result.extend_from_slice(&base[at..]);
    result
}

// ---------------------------------------------------------------------------------------------
// Assertions about files
// ---------------------------------------------------------------------------------------------

fn contains(haystack: &[u8], needle: &[u8]) -> bool {
    haystack
        .windows(needle.len())
        .any(|window| window == needle)
}

fn assert_no_personal_data(bytes: &[u8]) {
    for secret in [
        MAKE,
        MODEL,
        SERIAL,
        COMMENT,
        "2024:05:17",
        "TRAILER-",
        "Photoshop",
        "adobe",
    ] {
        assert!(
            !contains(bytes, secret.as_bytes()),
            "{secret} must not survive stripping"
        );
    }
}

fn decoder_of(bytes: &[u8]) -> Box<dyn ImageDecoder + '_> {
    let reader = ImageReader::new(Cursor::new(bytes))
        .with_guessed_format()
        .unwrap();
    Box::new(reader.into_decoder().expect("decodes"))
}

fn rgb_pixels(bytes: &[u8]) -> (u32, u32, Vec<u8>) {
    let image = image::load_from_memory(bytes)
        .expect("image decodes")
        .to_rgb8();
    (image.width(), image.height(), image.into_raw())
}

fn icc_of(bytes: &[u8]) -> Option<Vec<u8>> {
    decoder_of(bytes).icc_profile().unwrap()
}

fn orientation_of(bytes: &[u8]) -> Orientation {
    decoder_of(bytes)
        .orientation()
        .unwrap_or(Orientation::NoTransforms)
}

/// Strip as the server does for an image whose decoder reports `orientation`.
fn strip_like_server(bytes: &[u8], format: ImageFormat) -> Result<Vec<u8>, StripError> {
    strip(bytes, format, orientation_of(bytes))
}

// ---------------------------------------------------------------------------------------------
// Filter tests
// ---------------------------------------------------------------------------------------------

#[test]
fn jpeg_is_stripped_losslessly_and_keeps_icc() {
    let source = jpeg_with_metadata(40, 30, None);
    assert!(contains(&source, MAKE.as_bytes()));
    assert!(contains(&source, COMMENT.as_bytes()));
    let stripped = strip_like_server(&source, ImageFormat::Jpeg).unwrap();
    assert_no_personal_data(&stripped);
    assert!(stripped.len() < source.len());
    assert_eq!(
        icc_of(&stripped).as_deref(),
        Some(ICC),
        "ICC profile is kept"
    );
    assert_eq!(
        rgb_pixels(&stripped),
        rgb_pixels(&source),
        "pixels are untouched"
    );
    assert_eq!(orientation_of(&stripped), Orientation::NoTransforms);
    // No APP1 at all for an upright photo.
    assert!(!contains(&stripped, b"Exif\0\0"));
    assert!(stripped.ends_with(&[0xFF, 0xD9]));
}

#[test]
fn jpeg_keeps_only_the_orientation_tag() {
    let source = jpeg_with_metadata(80, 40, Some(6));
    assert_eq!(orientation_of(&source), Orientation::Rotate90);
    let stripped = strip_like_server(&source, ImageFormat::Jpeg).unwrap();
    assert_no_personal_data(&stripped);
    assert_eq!(orientation_of(&stripped), Orientation::Rotate90);
    assert_eq!(icc_of(&stripped).as_deref(), Some(ICC));
    assert_eq!(rgb_pixels(&stripped), rgb_pixels(&source));
    // Exactly one APP1, and it is the minimal 34-byte Exif block.
    let marker = [0xFF, 0xE1, 0x00, 34];
    assert_eq!(
        stripped
            .windows(4)
            .filter(|window| *window == marker)
            .count(),
        1
    );
}

#[test]
fn png_is_stripped_and_keeps_icc_and_pixels() {
    let source = png_with_metadata(24, 16, None);
    assert!(contains(&source, b"eXIf"));
    let stripped = strip_like_server(&source, ImageFormat::Png).unwrap();
    assert_no_personal_data(&stripped);
    for chunk in [&b"eXIf"[..], b"tEXt", b"iTXt", b"zTXt", b"tIME", b"vpAg"] {
        assert!(
            !contains(&stripped, chunk),
            "{} dropped",
            String::from_utf8_lossy(chunk)
        );
    }
    assert!(contains(&stripped, b"iCCP"));
    assert_eq!(icc_of(&stripped).as_deref(), Some(ICC));
    assert_eq!(rgb_pixels(&stripped), rgb_pixels(&source));
    assert!(
        stripped.ends_with(&[0xAE, 0x42, 0x60, 0x82]),
        "ends at IEND"
    );
}

#[test]
fn png_orientation_survives_as_a_minimal_exif_chunk() {
    let source = png_with_metadata(24, 16, Some(3));
    let orientation = orientation_of(&source);
    assert_ne!(
        orientation,
        Orientation::NoTransforms,
        "the fixture carries an orientation"
    );
    let stripped = strip(&source, ImageFormat::Png, orientation).unwrap();
    assert_no_personal_data(&stripped);
    assert_eq!(orientation_of(&stripped), orientation);
    assert_eq!(rgb_pixels(&stripped), rgb_pixels(&source));
}

#[test]
fn webp_is_stripped_and_keeps_icc_and_pixels() {
    let source = webp_with_metadata(20, 14, None);
    assert!(contains(&source, b"EXIF"));
    let stripped = strip_like_server(&source, ImageFormat::WebP).unwrap();
    assert_no_personal_data(&stripped);
    assert!(
        !contains(&stripped, b"EXIF")
            && !contains(&stripped, b"XMP ")
            && !contains(&stripped, b"vndr")
    );
    assert_eq!(icc_of(&stripped).as_deref(), Some(ICC));
    assert_eq!(rgb_pixels(&stripped), rgb_pixels(&source));
    // The RIFF size is consistent and the VP8X EXIF/XMP flags are cleared.
    let riff = u32::from_le_bytes(stripped[4..8].try_into().unwrap()) as usize;
    assert_eq!(riff + 8, stripped.len());
    assert_eq!(stripped[20] & (0x08 | 0x04), 0);
    assert_eq!(stripped[20] & 0x20, 0x20);
}

#[test]
fn webp_orientation_is_kept_as_a_minimal_exif_chunk() {
    let source = webp_with_metadata(20, 14, Some(8));
    let orientation = orientation_of(&source);
    assert_ne!(orientation, Orientation::NoTransforms);
    let stripped = strip(&source, ImageFormat::WebP, orientation).unwrap();
    assert_no_personal_data(&stripped);
    assert_eq!(orientation_of(&stripped), orientation);
    assert_eq!(rgb_pixels(&stripped), rgb_pixels(&source));
}

#[test]
fn simple_webp_without_extension_chunks_is_unchanged() {
    let simple = encode(&pixels(10, 10), ImageFormat::WebP);
    let stripped = strip(&simple, ImageFormat::WebP, Orientation::NoTransforms).unwrap();
    assert_eq!(stripped, simple);
}

#[test]
fn animated_gif_stays_animated_without_comments_or_xmp() {
    use image::AnimationDecoder;
    let source = gif_with_metadata();
    assert!(contains(&source, COMMENT.as_bytes()));
    let stripped = strip(&source, ImageFormat::Gif, Orientation::NoTransforms).unwrap();
    assert_no_personal_data(&stripped);
    assert!(!contains(&stripped, b"XMP DataXMP"));
    assert!(
        contains(&stripped, b"NETSCAPE2.0"),
        "the loop extension is kept"
    );
    let frames = |bytes: &[u8]| {
        image::codecs::gif::GifDecoder::new(Cursor::new(bytes.to_vec()))
            .unwrap()
            .into_frames()
            .collect_frames()
            .unwrap()
    };
    let (before, after) = (frames(&source), frames(&stripped));
    assert_eq!(after.len(), 2);
    for (a, b) in before.iter().zip(&after) {
        assert_eq!(a.buffer(), b.buffer());
        assert_eq!(a.delay(), b.delay());
    }
}

#[test]
fn bmp_has_no_metadata_and_is_kept() {
    let bmp = encode(&pixels(9, 9), ImageFormat::Bmp);
    assert_eq!(
        strip(&bmp, ImageFormat::Bmp, Orientation::NoTransforms).unwrap(),
        bmp
    );
}

#[test]
fn unsupported_formats_are_refused() {
    assert_eq!(
        strip(b"II*\0", ImageFormat::Tiff, Orientation::NoTransforms),
        Err(StripError::Unsupported)
    );
}

#[test]
fn malformed_containers_are_errors_and_never_panic() {
    let samples: Vec<(ImageFormat, Vec<u8>)> = vec![
        (ImageFormat::Jpeg, jpeg_with_metadata(24, 16, Some(6))),
        (ImageFormat::Png, png_with_metadata(24, 16, Some(6))),
        (ImageFormat::WebP, webp_with_metadata(20, 14, Some(6))),
        (ImageFormat::Gif, gif_with_metadata()),
        (ImageFormat::Bmp, encode(&pixels(8, 8), ImageFormat::Bmp)),
    ];
    for (format, sample) in &samples {
        // Every truncation is handled; none may panic.
        for length in 0..sample.len() {
            let _ = strip(&sample[..length], *format, Orientation::Rotate90);
        }
        // Single flipped bytes and rewritten length fields are handled as well.
        for index in 0..sample.len().min(600) {
            for value in [0x00_u8, 0x01, 0x7F, 0x80, 0xFF] {
                let mut mutated = sample.clone();
                mutated[index] = value;
                let _ = strip(&mutated, *format, Orientation::Rotate90);
            }
        }
        assert!(strip(&[], *format, Orientation::NoTransforms).is_err());
        assert!(strip(b"\xFF", *format, Orientation::NoTransforms).is_err());
    }
}

#[test]
fn jpeg_with_inconsistent_segment_lengths_is_rejected() {
    let good = jpeg_with_metadata(24, 16, None);
    // A segment that claims to run past the end of the file.
    let mut bad = vec![0xFF, 0xD8, 0xFF, 0xE1, 0xFF, 0xF0, b'E', b'x'];
    bad.extend_from_slice(&good[2..40]);
    assert_eq!(
        strip(&bad, ImageFormat::Jpeg, Orientation::NoTransforms),
        Err(StripError::Malformed)
    );
    // A length below the minimum of two.
    let short = [0xFF, 0xD8, 0xFF, 0xE1, 0x00, 0x01, 0xFF, 0xD9];
    assert_eq!(
        strip(&short, ImageFormat::Jpeg, Orientation::NoTransforms),
        Err(StripError::Malformed)
    );
    // No EOI.
    let mut cut = good.clone();
    let eoi = cut.windows(2).rposition(|w| w == [0xFF, 0xD9]).unwrap();
    cut.truncate(eoi);
    assert_eq!(
        strip(&cut, ImageFormat::Jpeg, Orientation::NoTransforms),
        Err(StripError::Malformed)
    );
}

#[test]
fn png_with_a_bad_chunk_length_or_unknown_critical_chunk_is_rejected() {
    let good = png_with_metadata(24, 16, None);
    let mut huge = good.clone();
    huge[8..12].copy_from_slice(&0xFFFF_FFF0_u32.to_be_bytes());
    assert_eq!(
        strip(&huge, ImageFormat::Png, Orientation::NoTransforms),
        Err(StripError::Malformed)
    );
    let mut critical = good[..33].to_vec();
    critical.extend(png_chunk(b"ZZZZ", b"x"));
    critical.extend_from_slice(&good[33..]);
    assert_eq!(
        strip(&critical, ImageFormat::Png, Orientation::NoTransforms),
        Err(StripError::Malformed)
    );
    let truncated = &good[..good.len() - 20];
    assert!(strip(truncated, ImageFormat::Png, Orientation::NoTransforms).is_err());
}

// ---------------------------------------------------------------------------------------------
// Upload paths
// ---------------------------------------------------------------------------------------------

#[derive(Default)]
struct FixtureFetcher {
    responses: Mutex<HashMap<String, FetchedImage>>,
}

impl ImageFetcher for FixtureFetcher {
    fn fetch<'a>(&'a self, url: &'a Url) -> FetchFuture<'a> {
        let response = self.responses.lock().unwrap().get(url.as_str()).cloned();
        Box::pin(async move { response.ok_or(FetchError::Transport) })
    }
}

struct Harness {
    v1: Router,
    v2: Router,
    pool: SqlitePool,
    user_id: i64,
    other_id: i64,
    project_id: i64,
    yarn_id: i64,
    root: tempfile::TempDir,
    fetcher: Arc<FixtureFetcher>,
}

async fn add_user(pool: &SqlitePool, email: &str, token: &str) -> i64 {
    let user_id = db::create_user(pool, email, "strip-metadata-password".to_owned())
        .await
        .unwrap();
    sqlx::query(
        "INSERT INTO api_tokens (user_id, name, token_hash, purpose, created_at) \
         VALUES (?, 'fixture', ?, 'api', unixepoch())",
    )
    .bind(user_id)
    .bind(hex::encode(Sha256::digest(token.as_bytes())))
    .execute(pool)
    .await
    .unwrap();
    user_id
}

async fn harness() -> Harness {
    let pool = db::connect("sqlite::memory:").await.unwrap();
    let user_id = add_user(&pool, "strip@example.test", TOKEN).await;
    let other_id = add_user(&pool, "other@example.test", OTHER_TOKEN).await;
    let project_id: i64 = sqlx::query_scalar(
        "INSERT INTO projects (owner_id, name, tags, created_at, updated_at) \
         VALUES (?, 'Photo project', '[]', '2020-01-01T00:00:00Z', '2020-01-01T00:00:00Z') RETURNING id",
    )
    .bind(user_id)
    .fetch_one(&pool)
    .await
    .unwrap();
    let yarn_id: i64 = sqlx::query_scalar(
        "INSERT INTO yarns (owner_id, name, created_at, updated_at) \
         VALUES (?, 'Photo wool', '2020-01-01T00:00:00Z', '2020-01-01T00:00:00Z') RETURNING id",
    )
    .bind(user_id)
    .fetch_one(&pool)
    .await
    .unwrap();
    let root = tempfile::tempdir().unwrap();
    let fetcher = Arc::new(FixtureFetcher::default());
    let state = db::AppState::new(pool.clone())
        .with_media_root(root.path().to_path_buf())
        .with_external_imports(true)
        .with_image_fetcher(fetcher.clone());
    let v1 = Router::new()
        .nest(
            "/api/v1",
            projects::router()
                .merge(yarns::router())
                .with_state(state.clone()),
        )
        .merge(media::router().with_state(state.clone()));
    Harness {
        v1,
        v2: api_v2::router(state, false),
        pool,
        user_id,
        other_id,
        project_id,
        yarn_id,
        root,
        fetcher,
    }
}

async fn set_setting(h: &Harness, user_id: i64, enabled: bool) {
    sqlx::query("UPDATE users SET strip_photo_metadata = ? WHERE id = ?")
        .bind(i64::from(enabled))
        .bind(user_id)
        .execute(&h.pool)
        .await
        .unwrap();
}

fn multipart(
    method: &str,
    path: &str,
    token: &str,
    file_name: &str,
    bytes: &[u8],
) -> Request<Body> {
    let boundary = "strip-metadata-boundary";
    let mut body = format!(
        "--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{file_name}\"\r\nContent-Type: application/octet-stream\r\n\r\n"
    )
    .into_bytes();
    body.extend_from_slice(bytes);
    body.extend_from_slice(format!("\r\n--{boundary}--\r\n").as_bytes());
    Request::builder()
        .method(method)
        .uri(path)
        .header("authorization", format!("Bearer {token}"))
        .header(
            "content-type",
            format!("multipart/form-data; boundary={boundary}"),
        )
        .body(Body::from(body))
        .unwrap()
}

fn json_request(
    method: &str,
    path: &str,
    token: &str,
    body: Option<serde_json::Value>,
) -> Request<Body> {
    let mut builder = Request::builder()
        .method(method)
        .uri(path)
        .header("authorization", format!("Bearer {token}"));
    if body.is_some() {
        builder = builder.header("content-type", "application/json");
    }
    builder
        .body(body.map_or_else(Body::empty, |value| Body::from(value.to_string())))
        .unwrap()
}

async fn json(response: axum::response::Response) -> serde_json::Value {
    let bytes = response.into_body().collect().await.unwrap().to_bytes();
    serde_json::from_slice(&bytes).unwrap_or(serde_json::Value::Null)
}

fn files_below(dir: &Path) -> Vec<PathBuf> {
    let mut found = Vec::new();
    if let Ok(entries) = std::fs::read_dir(dir) {
        for entry in entries.flatten() {
            let path = entry.path();
            if path.is_dir() {
                found.extend(files_below(&path));
            } else {
                found.push(path);
            }
        }
    }
    found.sort();
    found
}

/// The stored originals below `subdir` (not the thumbnails).
fn stored(h: &Harness, subdir: &str) -> Vec<Vec<u8>> {
    files_below(&h.root.path().join(subdir))
        .into_iter()
        .map(|path| std::fs::read(path).unwrap())
        .collect()
}

async fn upload_title(h: &Harness, token: &str, name: &str, bytes: &[u8]) -> StatusCode {
    h.v1.clone()
        .oneshot(multipart(
            "POST",
            &format!("/api/v1/projects/{}/images/title", h.project_id),
            token,
            name,
            bytes,
        ))
        .await
        .unwrap()
        .status()
}

#[tokio::test]
async fn setting_off_stores_the_uploaded_bytes_unchanged() {
    let h = harness().await;
    let jpeg = jpeg_with_metadata(40, 30, Some(6));
    assert_eq!(
        upload_title(&h, TOKEN, "a.jpg", &jpeg).await,
        StatusCode::CREATED
    );
    let png = png_with_metadata(24, 16, None);
    assert_eq!(
        upload_title(&h, TOKEN, "b.png", &png).await,
        StatusCode::CREATED
    );
    let webp = webp_with_metadata(20, 14, None);
    assert_eq!(
        upload_title(&h, TOKEN, "c.webp", &webp).await,
        StatusCode::CREATED
    );
    let mut found = stored(&h, "projects");
    found.sort();
    let mut expected = vec![jpeg, png, webp];
    expected.sort();
    assert_eq!(found, expected, "default behaviour keeps every byte");
}

#[tokio::test]
async fn setting_on_strips_title_step_and_yarn_uploads_for_every_format() {
    let h = harness().await;
    set_setting(&h, h.user_id, true).await;
    let step_id: i64 = sqlx::query_scalar(
        "INSERT INTO project_steps (project_id, title, step_number) VALUES (?, 'step', 1) RETURNING id",
    )
    .bind(h.project_id)
    .fetch_one(&h.pool)
    .await
    .unwrap();
    let jpeg = jpeg_with_metadata(40, 30, None);
    let png = png_with_metadata(24, 16, None);
    let webp = webp_with_metadata(20, 14, None);
    let gif = gif_with_metadata();

    assert_eq!(
        upload_title(&h, TOKEN, "a.jpg", &jpeg).await,
        StatusCode::CREATED
    );
    let response =
        h.v1.clone()
            .oneshot(multipart(
                "POST",
                &format!("/api/v1/projects/{}/steps/{step_id}/images", h.project_id),
                TOKEN,
                "b.png",
                &png,
            ))
            .await
            .unwrap();
    assert_eq!(response.status(), StatusCode::CREATED);
    for (name, bytes) in [("c.webp", &webp), ("d.gif", &gif)] {
        let response =
            h.v1.clone()
                .oneshot(multipart(
                    "POST",
                    &format!("/api/v1/yarns/{}/photos", h.yarn_id),
                    TOKEN,
                    name,
                    bytes,
                ))
                .await
                .unwrap();
        assert_eq!(response.status(), StatusCode::CREATED);
    }

    let projects_stored = stored(&h, "projects");
    assert_eq!(projects_stored.len(), 2);
    for bytes in projects_stored.iter().chain(&stored(&h, "yarns")) {
        assert_no_personal_data(bytes);
    }
    // The ICC profile survived for the formats that carry one.
    assert!(
        projects_stored
            .iter()
            .all(|bytes| icc_of(bytes).as_deref() == Some(ICC))
    );
    // Stored dimensions are those of the source.
    let sizes: Vec<(i64, i64)> =
        sqlx::query_as("SELECT width, height FROM media_files ORDER BY id")
            .fetch_all(&h.pool)
            .await
            .unwrap();
    assert_eq!(sizes, vec![(40, 30), (24, 16), (20, 14), (12, 8)]);
}

#[tokio::test]
async fn setting_on_keeps_orientation_dimensions_and_thumbnails_upright() {
    let h = harness().await;
    set_setting(&h, h.user_id, true).await;
    let jpeg = jpeg_with_metadata(80, 40, Some(6));
    assert_eq!(
        upload_title(&h, TOKEN, "p.jpg", &jpeg).await,
        StatusCode::CREATED
    );
    let stored = stored(&h, "projects");
    assert_eq!(stored.len(), 1);
    assert_no_personal_data(&stored[0]);
    assert_eq!(orientation_of(&stored[0]), Orientation::Rotate90);
    let (width, height): (i64, i64) = sqlx::query_as("SELECT width, height FROM media_files")
        .fetch_one(&h.pool)
        .await
        .unwrap();
    assert_eq!((width, height), (40, 80), "dimensions are the upright ones");
    let thumbnails = files_below(&h.root.path().join("thumbnails"));
    let thumbnail = image::open(&thumbnails[0]).unwrap();
    assert!(thumbnail.height() > thumbnail.width());
}

#[tokio::test]
async fn setting_is_per_uploading_user() {
    let h = harness().await;
    set_setting(&h, h.other_id, true).await; // someone else opting in changes nothing here
    let jpeg = jpeg_with_metadata(40, 30, None);
    assert_eq!(
        upload_title(&h, TOKEN, "a.jpg", &jpeg).await,
        StatusCode::CREATED
    );
    assert_eq!(stored(&h, "projects"), vec![jpeg]);
}

#[tokio::test]
async fn malformed_metadata_is_rejected_when_stripping_and_nothing_is_stored() {
    let h = harness().await;
    set_setting(&h, h.user_id, true).await;
    // A JPEG whose EXIF segment claims to be longer than the file is also an undecodable image
    // only for some decoders, so build one that decodes but cannot be filtered: a valid PNG with
    // an unknown critical chunk spliced in.
    let good = png_with_metadata(24, 16, None);
    let mut bad = good[..33].to_vec();
    bad.extend(png_chunk(b"ZZZZ", b"x"));
    bad.extend_from_slice(&good[33..]);
    assert_eq!(
        upload_title(&h, TOKEN, "bad.png", &bad).await,
        StatusCode::BAD_REQUEST
    );
    assert!(stored(&h, "projects").is_empty());
    let rows: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM media_files")
        .fetch_one(&h.pool)
        .await
        .unwrap();
    assert_eq!(rows, 0);
}

#[tokio::test]
async fn enabling_the_setting_never_touches_photos_that_are_already_stored() {
    let h = harness().await;
    let jpeg = jpeg_with_metadata(40, 30, Some(6));
    assert_eq!(
        upload_title(&h, TOKEN, "old.jpg", &jpeg).await,
        StatusCode::CREATED
    );
    let response =
        h.v2.clone()
            .oneshot(json_request(
                "PATCH",
                "/account",
                TOKEN,
                Some(serde_json::json!({"strip_photo_metadata": true})),
            ))
            .await
            .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(stored(&h, "projects"), vec![jpeg.clone()]);
    // A new upload is stripped, the old file stays exactly as it was.
    let newer = png_with_metadata(24, 16, None);
    assert_eq!(
        upload_title(&h, TOKEN, "new.png", &newer).await,
        StatusCode::CREATED
    );
    let found = stored(&h, "projects");
    assert_eq!(found.len(), 2);
    assert!(found.contains(&jpeg));
    assert!(
        found
            .iter()
            .any(|bytes| !contains(bytes, COMMENT.as_bytes()) && bytes.starts_with(b"\x89PNG"))
    );
}

#[tokio::test]
async fn account_api_exposes_and_updates_the_setting() {
    let h = harness().await;
    let get = |token: &'static str| json_request("GET", "/account", token, None);
    let account = json(h.v2.clone().oneshot(get(TOKEN)).await.unwrap()).await;
    assert_eq!(
        account["data"]["strip_photo_metadata"], false,
        "off by default"
    );

    let response =
        h.v2.clone()
            .oneshot(json_request(
                "PATCH",
                "/account",
                TOKEN,
                Some(serde_json::json!({"strip_photo_metadata": true})),
            ))
            .await
            .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(json(response).await["data"]["strip_photo_metadata"], true);
    let stored: i64 = sqlx::query_scalar("SELECT strip_photo_metadata FROM users WHERE id = ?")
        .bind(h.user_id)
        .fetch_one(&h.pool)
        .await
        .unwrap();
    assert_eq!(stored, 1);
    let other = json(h.v2.clone().oneshot(get(OTHER_TOKEN)).await.unwrap()).await;
    assert_eq!(other["data"]["strip_photo_metadata"], false);

    // Other fields in the same PATCH leave it alone; invalid values are rejected.
    let response =
        h.v2.clone()
            .oneshot(json_request(
                "PATCH",
                "/account",
                TOKEN,
                Some(serde_json::json!({"use_gravatar": false})),
            ))
            .await
            .unwrap();
    assert_eq!(json(response).await["data"]["strip_photo_metadata"], true);
    let invalid =
        h.v2.clone()
            .oneshot(json_request(
                "PATCH",
                "/account",
                TOKEN,
                Some(serde_json::json!({"strip_photo_metadata": "yes"})),
            ))
            .await
            .unwrap();
    assert_eq!(invalid.status(), StatusCode::BAD_REQUEST);

    let off =
        h.v2.clone()
            .oneshot(json_request(
                "PATCH",
                "/account",
                TOKEN,
                Some(serde_json::json!({"strip_photo_metadata": false})),
            ))
            .await
            .unwrap();
    assert_eq!(json(off).await["data"]["strip_photo_metadata"], false);
}

#[tokio::test]
async fn avatar_uploads_follow_the_setting() {
    let h = harness().await;
    let jpeg = jpeg_with_metadata(40, 30, None);
    let upload = |h: &Harness| {
        h.v2.clone()
            .oneshot(multipart("POST", "/account/avatar", TOKEN, "me.jpg", &jpeg))
    };
    assert_eq!(upload(&h).await.unwrap().status(), StatusCode::OK);
    assert_eq!(stored(&h, "users"), vec![jpeg.clone()], "off: unchanged");

    set_setting(&h, h.user_id, true).await;
    assert_eq!(upload(&h).await.unwrap().status(), StatusCode::OK);
    let found = stored(&h, "users");
    assert!(
        found
            .iter()
            .all(|bytes| !contains(bytes, COMMENT.as_bytes()) || *bytes == jpeg)
    );
    assert!(
        found.iter().any(|bytes| bytes != &jpeg
            && !contains(bytes, MAKE.as_bytes())
            && icc_of(bytes).as_deref() == Some(ICC)),
        "the new avatar is stripped and keeps its ICC profile"
    );
}

#[tokio::test]
async fn imported_images_follow_the_importing_users_setting() {
    let h = harness().await;
    let source = png_with_metadata(96, 80, None);
    h.fetcher.responses.lock().unwrap().insert(
        "https://images.example.test/photo.png".to_owned(),
        FetchedImage {
            content_type: Some("image/png".to_owned()),
            bytes: source.clone(),
        },
    );
    let create = |h: &Harness, name: &str| {
        h.v1.clone().oneshot(json_request(
            "POST",
            "/api/v1/projects",
            TOKEN,
            Some(serde_json::json!({
                "name": name,
                "image_urls": ["https://images.example.test/photo.png"]
            })),
        ))
    };
    assert_eq!(
        create(&h, "Plain import").await.unwrap().status(),
        StatusCode::CREATED
    );
    let plain = stored(&h, "projects");
    assert!(
        plain.contains(&source),
        "off: imported bytes are kept as they are"
    );

    set_setting(&h, h.user_id, true).await;
    assert_eq!(
        create(&h, "Clean import").await.unwrap().status(),
        StatusCode::CREATED
    );
    let all = stored(&h, "projects");
    let fresh: Vec<_> = all.iter().filter(|bytes| **bytes != source).collect();
    assert_eq!(fresh.len(), 1);
    assert_no_personal_data(fresh[0]);
    assert_eq!(icc_of(fresh[0]).as_deref(), Some(ICC));

    // The same for a yarn created with image URLs.
    let response =
        h.v1.clone()
            .oneshot(json_request(
                "POST",
                "/api/v1/yarns",
                TOKEN,
                Some(serde_json::json!({
                    "name": "Imported wool",
                    "image_urls": ["https://images.example.test/photo.png"]
                })),
            ))
            .await
            .unwrap();
    assert_eq!(response.status(), StatusCode::CREATED);
    for bytes in stored(&h, "yarns") {
        assert_no_personal_data(&bytes);
    }
}

#[tokio::test]
async fn tiff_uploads_become_png_with_the_setting_on() {
    let h = harness().await;
    set_setting(&h, h.user_id, true).await;
    let tiff = encode(&pixels(16, 12), ImageFormat::Tiff);
    assert_eq!(
        upload_title(&h, TOKEN, "scan.tiff", &tiff).await,
        StatusCode::CREATED
    );
    let found = stored(&h, "projects");
    assert_eq!(found.len(), 1);
    assert!(found[0].starts_with(b"\x89PNG"));
    assert_eq!(rgb_pixels(&found[0]), rgb_pixels(&tiff));
    let content_type: String = sqlx::query_scalar("SELECT content_type FROM media_files")
        .fetch_one(&h.pool)
        .await
        .unwrap();
    assert_eq!(content_type, "image/png");
}
