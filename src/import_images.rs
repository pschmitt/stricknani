//! Near-duplicate pictures among the ones a link import found. Sites serve the same photo under
//! several names and sizes (garnstudio: two file names for one picture), which the URL-based
//! `image_identity` cannot see. Each picture gets a 64-bit difference hash ("dHash": a 9x8 grey
//! thumbnail, one bit per neighbour comparison), robust to resizing and recompression; a picture
//! whose hash is close to an earlier one is dropped. Pictures go through the SSRF-safe fetcher;
//! one that cannot be fetched or decoded is kept.

use std::{io::Cursor, time::Duration};

use image::{ImageReader, Limits, imageops::FilterType};

/// Only the first pictures are compared; the rest are kept as they are.
const MAX_CHECKED: usize = 16;
/// Larger downloads are not decoded (the picture is kept).
const MAX_IMAGE_BYTES: usize = 8 * 1024 * 1024;
/// Bits that may differ between two hashes of the same picture.
const MAX_DISTANCE: u32 = 6;
/// The whole comparison may not hold up the import longer than this.
const TIME_LIMIT: Duration = Duration::from_secs(15);

/// [urls] without the near-duplicates of earlier ones, in the same order.
pub async fn drop_near_duplicates(urls: Vec<String>) -> Vec<String> {
    if urls.len() < 2 {
        return urls;
    }
    let checked = urls.len().min(MAX_CHECKED);
    let mut tasks = tokio::task::JoinSet::new();
    for (index, url) in urls.iter().take(checked).enumerate() {
        let url = url.clone();
        tasks.spawn(async move { (index, hash_of(&url).await) });
    }
    let mut hashes = vec![None; checked];
    let collect = async {
        while let Some(joined) = tasks.join_next().await {
            if let Ok((index, hash)) = joined {
                hashes[index] = hash;
            }
        }
    };
    // Pictures still loading at the limit count as unknown (kept).
    let _ = tokio::time::timeout(TIME_LIMIT, collect).await;
    let keep = keep_distinct(&hashes);
    urls.into_iter()
        .enumerate()
        .filter(|(index, _)| *index >= checked || keep[*index])
        .map(|(_, url)| url)
        .collect()
}

/// For each hash whether to keep its picture: unknown ones always, the others unless an earlier
/// kept one is within [MAX_DISTANCE] bits.
fn keep_distinct(hashes: &[Option<u64>]) -> Vec<bool> {
    let mut kept: Vec<u64> = Vec::new();
    hashes
        .iter()
        .map(|hash| match hash {
            None => true,
            Some(hash) => {
                if kept
                    .iter()
                    .any(|other| (other ^ hash).count_ones() <= MAX_DISTANCE)
                {
                    false
                } else {
                    kept.push(*hash);
                    true
                }
            }
        })
        .collect()
}

async fn hash_of(url: &str) -> Option<u64> {
    let fetched = crate::imports::fetch_url(&crate::imports::normalize_input_url(url))
        .await
        .ok()?;
    if fetched.body.len() > MAX_IMAGE_BYTES {
        return None;
    }
    tokio::task::spawn_blocking(move || difference_hash(&fetched.body))
        .await
        .ok()
        .flatten()
}

/// The dHash of an encoded picture, or None when it is not a picture this server decodes.
fn difference_hash(bytes: &[u8]) -> Option<u64> {
    let mut reader = ImageReader::new(Cursor::new(bytes))
        .with_guessed_format()
        .ok()?;
    let mut limits = Limits::default();
    limits.max_image_width = Some(10_000);
    limits.max_image_height = Some(10_000);
    limits.max_alloc = Some(256 * 1024 * 1024);
    reader.limits(limits);
    let picture = reader.decode().ok()?;
    let grey = picture.resize_exact(9, 8, FilterType::Triangle).to_luma8();
    let mut hash = 0u64;
    for y in 0..8 {
        for x in 0..8 {
            hash <<= 1;
            if grey.get_pixel(x, y)[0] > grey.get_pixel(x + 1, y)[0] {
                hash |= 1;
            }
        }
    }
    Some(hash)
}

#[cfg(test)]
mod tests {
    use super::*;
    use image::{ImageFormat, Rgb, RgbImage};

    fn encoded(picture: &RgbImage, format: ImageFormat) -> Vec<u8> {
        let mut bytes = Vec::new();
        picture
            .write_to(&mut Cursor::new(&mut bytes), format)
            .unwrap();
        bytes
    }

    fn gradient(width: u32, height: u32, flip: bool) -> RgbImage {
        RgbImage::from_fn(width, height, |x, y| {
            let x = if flip { width - 1 - x } else { x };
            let value = ((x * 255 / width) as u8).wrapping_add((y * 40 / height) as u8);
            Rgb([value, value / 2, 255 - value])
        })
    }

    #[test]
    fn the_same_picture_in_another_size_and_format_matches() {
        let large = difference_hash(&encoded(&gradient(800, 600, false), ImageFormat::Png));
        let small = difference_hash(&encoded(&gradient(200, 150, false), ImageFormat::Jpeg));
        let other = difference_hash(&encoded(&gradient(800, 600, true), ImageFormat::Png));
        let (large, small, other) = (large.unwrap(), small.unwrap(), other.unwrap());
        assert!((large ^ small).count_ones() <= MAX_DISTANCE);
        assert!((large ^ other).count_ones() > MAX_DISTANCE);
    }

    #[test]
    fn near_duplicates_are_dropped_in_order_and_unknown_pictures_kept() {
        let a = 0b1111_0000u64;
        let a_resized = a ^ 0b1; // one bit off
        let b = !a;
        assert_eq!(
            keep_distinct(&[Some(a), Some(a_resized), None, Some(b), Some(b)]),
            vec![true, false, true, true, false]
        );
    }

    #[test]
    fn something_that_is_not_a_picture_has_no_hash() {
        assert_eq!(difference_hash(b"<html></html>"), None);
    }
}
