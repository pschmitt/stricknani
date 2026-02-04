"""Helpers for detecting near-duplicate images during imports."""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np
from numpy.typing import NDArray
from PIL import Image, ImageOps
from skimage.color import rgb2gray
from skimage.metrics import structural_similarity
from skimage.transform import resize

_MAX_SIMILARITY_SIDE = 128


@dataclass(frozen=True)
class SimilarityImage:
    """Grayscale image payload for similarity checks."""

    gray: NDArray[np.floating]
    width: int
    height: int

    @property
    def pixels(self) -> int:
        """Total pixel count for size comparisons."""
        return self.width * self.height


def build_similarity_image(image: Image.Image) -> SimilarityImage:
    """Convert a PIL image into a grayscale similarity payload."""
    normalized = ImageOps.exif_transpose(image)
    rgb = normalized.convert("RGB")
    width, height = rgb.size
    array = np.asarray(rgb)
    if max(width, height) > _MAX_SIMILARITY_SIDE:
        scale = _MAX_SIMILARITY_SIDE / max(width, height)
        target = (max(1, int(height * scale)), max(1, int(width * scale)), 3)
        array = resize(  # type: ignore[no-untyped-call]
            array,
            target,
            anti_aliasing=True,
            preserve_range=True,
        )
    gray = rgb2gray(array)
    return SimilarityImage(gray=gray, width=width, height=height)


def compute_similarity_score(
    reference: SimilarityImage, candidate: SimilarityImage
) -> float | None:
    """Compute SSIM score between two images, resizing if needed."""
    reference_gray = reference.gray
    candidate_gray = candidate.gray
    if reference_gray.shape != candidate_gray.shape:
        candidate_gray = resize(  # type: ignore[no-untyped-call]
            candidate_gray,
            reference_gray.shape,
            anti_aliasing=True,
            preserve_range=True,
        )
    try:
        score = structural_similarity(  # type: ignore[no-untyped-call]
            reference_gray,
            candidate_gray,
            data_range=1.0,
        )
    except ValueError:
        return None
    return float(score)
