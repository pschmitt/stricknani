//! Container-level removal of personal metadata from uploaded images.
//!
//! When a user opts in (`users.strip_photo_metadata`), new uploads pass through [`strip`] before
//! they are stored. The filters never decode or re-encode pixel data, so quality is untouched and
//! animated GIF/WebP/APNG files stay animated. Each format uses an allowlist: only the structures
//! needed to display the image survive, everything else (EXIF incl. GPS, camera make/model/serial
//! and dates, maker notes, embedded thumbnails, XMP, IPTC/Photoshop blocks, comments, text chunks,
//! unknown vendor blocks, trailing data such as MPF secondary images) is dropped.
//!
//! * The ICC colour profile is kept (JPEG APP2 `ICC_PROFILE`, PNG `iCCP`, WebP `ICCP`, GIF
//!   `ICCRGBG1012`), together with other colour-rendering chunks (`sRGB`, `gAMA`, `cHRM`, Adobe
//!   APP14 for CMYK/YCCK JPEG, ...).
//! * Orientation: the *only* EXIF datum that is kept is the Orientation tag. It is rewritten into
//!   a freshly built, minimal EXIF block (JPEG APP1, PNG `eXIf`, WebP `EXIF`), never copied. The
//!   pixels are not rotated, so the stripping stays lossless and the file displays exactly as
//!   before (the server's own decoder, thumbnails and stored dimensions already apply the tag).
//!   GIF and BMP carry no orientation. TIFF cannot be filtered at container level, so it is
//!   re-encoded as PNG with the orientation baked into the pixels (the one format that needs it).
//!
//! Malformed input never panics: every read is bounds-checked and any structural problem is a
//! [`StripError`], which the caller turns into a rejected upload (metadata is never kept silently).

use image::{ImageFormat, metadata::Orientation};

/// Why an image could not be cleaned. The upload is rejected.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum StripError {
    /// The container structure is truncated, inconsistent or otherwise unparseable.
    Malformed,
    /// The format cannot be cleaned by this module.
    Unsupported,
}

type Result<T> = std::result::Result<T, StripError>;

/// Remove personal metadata from `bytes`, which are known to be `format`.
///
/// `orientation` is the EXIF orientation the decoder found in the source; a value other than
/// [`Orientation::NoTransforms`] is kept as the only EXIF tag.
pub fn strip(bytes: &[u8], format: ImageFormat, orientation: Orientation) -> Result<Vec<u8>> {
    let orientation = orientation_value(orientation);
    match format {
        ImageFormat::Jpeg => strip_jpeg(bytes, orientation),
        ImageFormat::Png => strip_png(bytes, orientation),
        ImageFormat::WebP => strip_webp(bytes, orientation),
        ImageFormat::Gif => strip_gif(bytes),
        // BMP has no metadata container (no EXIF, XMP or comments).
        ImageFormat::Bmp => {
            if bytes.len() < 14 || &bytes[..2] != b"BM" {
                return Err(StripError::Malformed);
            }
            Ok(bytes.to_vec())
        }
        _ => Err(StripError::Unsupported),
    }
}

/// `Some(1..=8)` for a transform that must be kept, `None` for an upright image.
fn orientation_value(orientation: Orientation) -> Option<u16> {
    match u16::from(orientation.to_exif()) {
        value @ 2..=8 => Some(value),
        _ => None,
    }
}

/// A minimal little-endian TIFF/EXIF structure whose only entry is the Orientation tag.
fn orientation_tiff(value: u16) -> [u8; 26] {
    let mut tiff = [0_u8; 26];
    tiff[0..2].copy_from_slice(b"II");
    tiff[2..4].copy_from_slice(&42_u16.to_le_bytes());
    tiff[4..8].copy_from_slice(&8_u32.to_le_bytes()); // IFD0 offset
    tiff[8..10].copy_from_slice(&1_u16.to_le_bytes()); // one entry
    tiff[10..12].copy_from_slice(&0x0112_u16.to_le_bytes()); // Orientation
    tiff[12..14].copy_from_slice(&3_u16.to_le_bytes()); // SHORT
    tiff[14..18].copy_from_slice(&1_u32.to_le_bytes()); // count
    tiff[18..20].copy_from_slice(&value.to_le_bytes());
    // bytes 20..22 value padding, 22..26 next IFD offset (none)
    tiff
}

// ---------------------------------------------------------------------------------------------
// JPEG
// ---------------------------------------------------------------------------------------------

fn strip_jpeg(bytes: &[u8], orientation: Option<u16>) -> Result<Vec<u8>> {
    if bytes.len() < 4 || bytes[0] != 0xFF || bytes[1] != 0xD8 {
        return Err(StripError::Malformed);
    }
    let mut out = Vec::with_capacity(bytes.len());
    out.extend_from_slice(&[0xFF, 0xD8]);
    let mut exif_pending = orientation;
    let mut pos = 2;
    loop {
        // Find the next marker: 0xFF, optional 0xFF fill bytes, then the marker code.
        if pos >= bytes.len() {
            return Err(StripError::Malformed);
        }
        if bytes[pos] != 0xFF {
            return Err(StripError::Malformed);
        }
        while pos < bytes.len() && bytes[pos] == 0xFF {
            pos += 1;
        }
        let Some(&marker) = bytes.get(pos) else {
            return Err(StripError::Malformed);
        };
        pos += 1;
        match marker {
            0x00 => return Err(StripError::Malformed),
            // EOI: everything after it (MPF secondary images, trailers) is dropped.
            0xD9 => {
                insert_exif_if_pending(&mut out, &mut exif_pending);
                out.extend_from_slice(&[0xFF, 0xD9]);
                return Ok(out);
            }
            // Markers without a length field.
            0x01 | 0xD0..=0xD8 => out.extend_from_slice(&[0xFF, marker]),
            _ => {
                let length = bytes
                    .get(pos..pos + 2)
                    .map(|raw| usize::from(u16::from_be_bytes([raw[0], raw[1]])))
                    .ok_or(StripError::Malformed)?;
                if length < 2 {
                    return Err(StripError::Malformed);
                }
                let end = pos.checked_add(length).ok_or(StripError::Malformed)?;
                let data = bytes.get(pos + 2..end).ok_or(StripError::Malformed)?;
                if marker != 0xE0 {
                    insert_exif_if_pending(&mut out, &mut exif_pending);
                }
                match marker {
                    0xE0 => {
                        if let Some(jfif) = clean_jfif(data) {
                            out.extend_from_slice(&[0xFF, 0xE0, 0x00, 16]);
                            out.extend_from_slice(&jfif);
                        }
                    }
                    0xE2 if data.starts_with(b"ICC_PROFILE\0") => {
                        copy_segment(&mut out, bytes, pos, end, marker)
                    }
                    0xEE if data.starts_with(b"Adobe") => {
                        copy_segment(&mut out, bytes, pos, end, marker)
                    }
                    // Other APPn (EXIF, XMP, IPTC/Photoshop, MPF, JUMBF, ...) and comments.
                    0xE1..=0xEF | 0xFE => {}
                    _ => copy_segment(&mut out, bytes, pos, end, marker),
                }
                pos = end;
                if marker == 0xDA {
                    // Entropy-coded data up to the next real marker is copied verbatim.
                    let start = pos;
                    pos = scan_entropy_data(bytes, pos)?;
                    out.extend_from_slice(&bytes[start..pos]);
                }
            }
        }
    }
}

fn copy_segment(out: &mut Vec<u8>, bytes: &[u8], length_pos: usize, end: usize, marker: u8) {
    out.extend_from_slice(&[0xFF, marker]);
    out.extend_from_slice(&bytes[length_pos..end]);
}

/// Put the minimal orientation-only APP1 segment into the output once.
fn insert_exif_if_pending(out: &mut Vec<u8>, pending: &mut Option<u16>) {
    let Some(value) = pending.take() else {
        return;
    };
    let tiff = orientation_tiff(value);
    let length = u16::try_from(2 + 6 + tiff.len()).expect("segment length fits");
    out.extend_from_slice(&[0xFF, 0xE1]);
    out.extend_from_slice(&length.to_be_bytes());
    out.extend_from_slice(b"Exif\0\0");
    out.extend_from_slice(&tiff);
}

/// A JFIF header without its embedded thumbnail, or `None` when the segment is not a usable JFIF.
fn clean_jfif(data: &[u8]) -> Option<[u8; 14]> {
    if data.len() < 14 || !data.starts_with(b"JFIF\0") {
        return None;
    }
    let mut jfif = [0_u8; 14];
    jfif.copy_from_slice(&data[..14]);
    jfif[12] = 0;
    jfif[13] = 0;
    Some(jfif)
}

/// The offset of the next marker after entropy-coded scan data (skipping stuffed `FF 00` bytes,
/// restart markers and fill bytes).
fn scan_entropy_data(bytes: &[u8], mut pos: usize) -> Result<usize> {
    while pos < bytes.len() {
        if bytes[pos] != 0xFF {
            pos += 1;
            continue;
        }
        let Some(&next) = bytes.get(pos + 1) else {
            return Err(StripError::Malformed);
        };
        match next {
            0x00 | 0xD0..=0xD7 => pos += 2,
            0xFF => pos += 1,
            _ => return Ok(pos),
        }
    }
    Err(StripError::Malformed)
}

// ---------------------------------------------------------------------------------------------
// PNG
// ---------------------------------------------------------------------------------------------

const PNG_SIGNATURE: &[u8; 8] = b"\x89PNG\r\n\x1a\n";

/// Ancillary chunks that describe how to display the image. Everything else ancillary (`eXIf`,
/// `tEXt`, `zTXt`, `iTXt`, `tIME`, vendor chunks, ...) is dropped.
const PNG_KEEP: &[&[u8; 4]] = &[
    b"IHDR", b"PLTE", b"IDAT", b"IEND", b"tRNS", b"gAMA", b"cHRM", b"sRGB", b"iCCP", b"sBIT",
    b"bKGD", b"hIST", b"pHYs", b"cICP", b"mDCV", b"cLLI", b"acTL", b"fcTL", b"fdAT",
];

fn strip_png(bytes: &[u8], orientation: Option<u16>) -> Result<Vec<u8>> {
    if bytes.len() < 8 || &bytes[..8] != PNG_SIGNATURE {
        return Err(StripError::Malformed);
    }
    let mut out = Vec::with_capacity(bytes.len());
    out.extend_from_slice(PNG_SIGNATURE);
    let mut pos = 8;
    let mut first = true;
    let mut seen_end = false;
    while pos < bytes.len() {
        let header = bytes.get(pos..pos + 8).ok_or(StripError::Malformed)?;
        let length = u32::from_be_bytes([header[0], header[1], header[2], header[3]]) as usize;
        let kind = [header[4], header[5], header[6], header[7]];
        let end = (pos + 8)
            .checked_add(length)
            .and_then(|value| value.checked_add(4))
            .ok_or(StripError::Malformed)?;
        let chunk = bytes.get(pos..end).ok_or(StripError::Malformed)?;
        if first && &kind != b"IHDR" {
            return Err(StripError::Malformed);
        }
        let keep = PNG_KEEP.iter().any(|name| **name == kind);
        // A lowercase first letter marks an ancillary chunk; an unknown critical one cannot be
        // judged, so refuse the file.
        let ancillary = kind[0] & 0x20 != 0;
        if !keep && !ancillary {
            return Err(StripError::Malformed);
        }
        if keep {
            out.extend_from_slice(chunk);
        }
        if first {
            if let Some(value) = orientation {
                push_png_chunk(&mut out, b"eXIf", &orientation_tiff(value));
            }
            first = false;
        }
        pos = end;
        if &kind == b"IEND" {
            seen_end = true;
            break;
        }
    }
    if first || !seen_end {
        return Err(StripError::Malformed);
    }
    Ok(out)
}

fn push_png_chunk(out: &mut Vec<u8>, kind: &[u8; 4], data: &[u8]) {
    out.extend_from_slice(
        &u32::try_from(data.len())
            .expect("small chunk")
            .to_be_bytes(),
    );
    out.extend_from_slice(kind);
    out.extend_from_slice(data);
    let mut crc = Crc32::new();
    crc.update(kind);
    crc.update(data);
    out.extend_from_slice(&crc.finish().to_be_bytes());
}

struct Crc32(u32);

impl Crc32 {
    fn new() -> Self {
        Self(0xFFFF_FFFF)
    }

    fn update(&mut self, data: &[u8]) {
        for &byte in data {
            self.0 ^= u32::from(byte);
            for _ in 0..8 {
                let mask = (self.0 & 1).wrapping_neg();
                self.0 = (self.0 >> 1) ^ (0xEDB8_8320 & mask);
            }
        }
    }

    fn finish(&self) -> u32 {
        !self.0
    }
}

// ---------------------------------------------------------------------------------------------
// WebP (RIFF)
// ---------------------------------------------------------------------------------------------

const VP8X_ICC: u8 = 0x20;
const VP8X_EXIF: u8 = 0x08;
const VP8X_XMP: u8 = 0x04;

fn strip_webp(bytes: &[u8], orientation: Option<u16>) -> Result<Vec<u8>> {
    if bytes.len() < 12 || &bytes[..4] != b"RIFF" || &bytes[8..12] != b"WEBP" {
        return Err(StripError::Malformed);
    }
    let riff_size = u32::from_le_bytes([bytes[4], bytes[5], bytes[6], bytes[7]]) as usize;
    // The RIFF size covers "WEBP" and the chunks; bytes after it are not part of the file.
    let declared_end = riff_size
        .checked_add(8)
        .filter(|end| *end >= 12)
        .ok_or(StripError::Malformed)?;
    if declared_end > bytes.len() {
        return Err(StripError::Malformed);
    }
    let mut body = Vec::with_capacity(bytes.len());
    let mut pos = 12;
    let mut vp8x_at = None;
    let mut has_icc = false;
    while pos < declared_end {
        let header = bytes.get(pos..pos + 8).ok_or(StripError::Malformed)?;
        let kind = [header[0], header[1], header[2], header[3]];
        let length = u32::from_le_bytes([header[4], header[5], header[6], header[7]]) as usize;
        let payload_end = (pos + 8).checked_add(length).ok_or(StripError::Malformed)?;
        // Chunks are padded to an even size; the padding byte may be missing on the last chunk.
        let padded_end = payload_end + (length & 1);
        if payload_end > declared_end || padded_end > bytes.len() {
            return Err(StripError::Malformed);
        }
        let keep = matches!(
            &kind,
            b"VP8X" | b"ICCP" | b"ANIM" | b"ANMF" | b"ALPH" | b"VP8 " | b"VP8L"
        );
        if keep {
            if &kind == b"VP8X" {
                if length < 10 {
                    return Err(StripError::Malformed);
                }
                vp8x_at = Some(body.len() + 8);
            }
            has_icc |= &kind == b"ICCP";
            body.extend_from_slice(&bytes[pos..payload_end]);
            if length & 1 == 1 {
                body.push(0);
            }
        }
        pos = padded_end;
    }
    if body.is_empty() {
        return Err(StripError::Malformed);
    }
    // A simple (VP8/VP8L only) file has no metadata containers, so it never carried an EXIF
    // orientation either and needs no VP8X header.
    if let Some(at) = vp8x_at {
        body[at] &= !(VP8X_EXIF | VP8X_XMP);
        if has_icc {
            body[at] |= VP8X_ICC;
        }
        if let Some(value) = orientation {
            body[at] |= VP8X_EXIF;
            let tiff = orientation_tiff(value);
            body.extend_from_slice(b"EXIF");
            body.extend_from_slice(&u32::try_from(tiff.len()).expect("small").to_le_bytes());
            body.extend_from_slice(&tiff);
        }
    }
    let mut out = Vec::with_capacity(body.len() + 12);
    out.extend_from_slice(b"RIFF");
    out.extend_from_slice(
        &u32::try_from(body.len() + 4)
            .map_err(|_| StripError::Malformed)?
            .to_le_bytes(),
    );
    out.extend_from_slice(b"WEBP");
    out.extend_from_slice(&body);
    Ok(out)
}

// ---------------------------------------------------------------------------------------------
// GIF
// ---------------------------------------------------------------------------------------------

fn strip_gif(bytes: &[u8]) -> Result<Vec<u8>> {
    if bytes.len() < 13 || !(bytes.starts_with(b"GIF87a") || bytes.starts_with(b"GIF89a")) {
        return Err(StripError::Malformed);
    }
    let mut out = Vec::with_capacity(bytes.len());
    let packed = bytes[10];
    let mut pos = 13;
    if packed & 0x80 != 0 {
        pos += 3 * (1_usize << ((packed & 7) + 1));
    }
    out.extend_from_slice(bytes.get(..pos).ok_or(StripError::Malformed)?);
    loop {
        match *bytes.get(pos).ok_or(StripError::Malformed)? {
            0x3B => {
                out.push(0x3B);
                return Ok(out);
            }
            0x21 => {
                let label = *bytes.get(pos + 1).ok_or(StripError::Malformed)?;
                let blocks_start = pos + 2;
                let blocks_end = skip_gif_sub_blocks(bytes, blocks_start)?;
                let keep = match label {
                    // Graphic control extension.
                    0xF9 => true,
                    // Application extension: keep looping and colour-profile blocks only.
                    0xFF => {
                        let id = bytes
                            .get(blocks_start..blocks_end)
                            .ok_or(StripError::Malformed)?;
                        id.len() >= 12
                            && id[0] == 11
                            && matches!(
                                &id[1..12],
                                b"NETSCAPE2.0" | b"ANIMEXTS1.0" | b"ICCRGBG1012"
                            )
                    }
                    // Comments, plain text and unknown extensions.
                    _ => false,
                };
                if keep {
                    out.extend_from_slice(&bytes[pos..blocks_end]);
                }
                pos = blocks_end;
            }
            0x2C => {
                let descriptor = bytes.get(pos..pos + 10).ok_or(StripError::Malformed)?;
                let mut end = pos + 10;
                if descriptor[9] & 0x80 != 0 {
                    end += 3 * (1_usize << ((descriptor[9] & 7) + 1));
                }
                // LZW minimum code size byte, then the data sub-blocks.
                end += 1;
                let end = skip_gif_sub_blocks(bytes, end)?;
                out.extend_from_slice(bytes.get(pos..end).ok_or(StripError::Malformed)?);
                pos = end;
            }
            _ => return Err(StripError::Malformed),
        }
    }
}

/// The offset after a run of length-prefixed sub-blocks and their zero terminator.
fn skip_gif_sub_blocks(bytes: &[u8], mut pos: usize) -> Result<usize> {
    loop {
        let size = usize::from(*bytes.get(pos).ok_or(StripError::Malformed)?);
        pos += 1;
        if size == 0 {
            return Ok(pos);
        }
        pos = pos.checked_add(size).ok_or(StripError::Malformed)?;
        if pos > bytes.len() {
            return Err(StripError::Malformed);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn crc32_matches_the_png_iend_checksum() {
        let mut crc = Crc32::new();
        crc.update(b"IEND");
        assert_eq!(crc.finish(), 0xAE42_6082);
    }

    #[test]
    fn orientation_block_has_exactly_one_entry() {
        let tiff = orientation_tiff(6);
        assert_eq!(&tiff[..4], b"II*\0");
        assert_eq!(u16::from_le_bytes([tiff[8], tiff[9]]), 1);
        assert_eq!(u16::from_le_bytes([tiff[10], tiff[11]]), 0x0112);
        assert_eq!(u16::from_le_bytes([tiff[18], tiff[19]]), 6);
    }

    #[test]
    fn upright_orientation_is_not_kept() {
        assert_eq!(orientation_value(Orientation::NoTransforms), None);
        assert_eq!(orientation_value(Orientation::Rotate90), Some(6));
    }

    #[test]
    fn minimal_jpeg_is_cleaned() {
        let jpeg = [
            0xFF, 0xD8, 0xFF, 0xFE, 0x00, 0x05, b'a', b'b', b'c', 0xFF, 0xDA, 0x00, 0x02, 0x12,
            0xFF, 0x00, 0xFF, 0xD0, 0x34, 0xFF, 0xD9, b'x',
        ];
        let out = strip_jpeg(&jpeg, None).unwrap();
        assert_eq!(
            out,
            [
                0xFF, 0xD8, 0xFF, 0xDA, 0x00, 0x02, 0x12, 0xFF, 0x00, 0xFF, 0xD0, 0x34, 0xFF, 0xD9
            ]
        );
    }
}
