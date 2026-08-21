#!/usr/bin/env python3
"""Validate the reviewable Google Play listing inputs without contacting Google Play."""

from __future__ import annotations

import argparse
import json
import struct
import sys
from pathlib import Path
from typing import Any

PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
SCREENSHOT_BUCKETS = ("phoneScreenshots", "sevenInchScreenshots", "tenInchScreenshots")


class Validation:
    def __init__(self, *, require_screenshots: bool, require_ready: bool) -> None:
        self.require_screenshots = require_screenshots
        self.require_ready = require_ready
        self.errors: list[str] = []
        self.warnings: list[str] = []

    def error(self, message: str) -> None:
        self.errors.append(message)

    def warning(self, message: str) -> None:
        self.warnings.append(message)

    def png_dimensions(self, path: Path, label: str) -> tuple[int, int] | None:
        try:
            data = path.read_bytes()
        except OSError as error:
            self.error(f"{label}: cannot read {path}: {error}")
            return None

        if len(data) < 26 or data[:8] != PNG_SIGNATURE:
            self.error(f"{label}: {path} is not a PNG")
            return None
        chunk_length = struct.unpack(">I", data[8:12])[0]
        if data[12:16] != b"IHDR" or chunk_length < 13:
            self.error(f"{label}: {path} has no valid PNG IHDR chunk")
            return None

        width, height, bit_depth, color_type = struct.unpack(">IIBB", data[16:26])
        if width <= 0 or height <= 0:
            self.error(f"{label}: {path} has invalid dimensions {width}x{height}")
            return None
        if bit_depth not in (8, 16):
            self.error(f"{label}: {path} uses unsupported {bit_depth}-bit color")
        if color_type not in (0, 2, 3, 4, 6):
            self.error(f"{label}: {path} uses unsupported PNG color type {color_type}")
        return width, height

    def check_listing_text(self, metadata_dir: Path) -> None:
        limits = {
            "title.txt": 30,
            "short_description.txt": 80,
            "full_description.txt": 4000,
        }
        for filename, limit in limits.items():
            path = metadata_dir / filename
            if not path.is_file():
                self.error(f"Missing listing metadata: {path}")
                continue
            text = path.read_text(encoding="utf-8").strip()
            if not text:
                self.error(f"Listing metadata is empty: {path}")
            if len(text) > limit:
                self.error(
                    f"{path} is {len(text)} characters; Play allows at most {limit}"
                )
            if "\x00" in text:
                self.error(f"Listing metadata contains a NUL byte: {path}")

    def check_declarations(self, root: Path) -> None:
        path = root / "android/play-store/declarations.json"
        if not path.is_file():
            self.error(f"Missing Play Console declarations: {path}")
            return
        try:
            declarations: Any = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as error:
            self.error(f"Cannot parse {path}: {error}")
            return
        if not isinstance(declarations, dict):
            self.error(f"{path} must contain a JSON object")
            return

        required = {
            "schema_version",
            "status",
            "privacy_policy_url",
            "content_rating",
            "data_safety",
        }
        missing = sorted(required - declarations.keys())
        if missing:
            self.error(f"{path} is missing keys: {', '.join(missing)}")
            return
        if declarations["schema_version"] != 1:
            self.error(
                f"{path} has unsupported schema_version: "
                f"{declarations['schema_version']!r}"
            )
        if declarations["status"] not in ("draft", "ready"):
            self.error(f"{path} status must be 'draft' or 'ready'")

        for section in ("content_rating", "data_safety"):
            value = declarations[section]
            if not isinstance(value, dict):
                self.error(f"{path} field {section!r} must be an object")
                continue
            if not isinstance(value.get("completed_in_play_console"), bool):
                self.error(
                    f"{path} field {section}.completed_in_play_console must be boolean"
                )

        privacy_url = declarations["privacy_policy_url"]
        if privacy_url is None:
            message = (
                "Play listing privacy_policy_url is not set; the public privacy-policy "
                "route is still required"
            )
            if self.require_ready:
                self.error(message)
            else:
                self.warning(message)
        elif not isinstance(privacy_url, str) or not privacy_url.startswith(
            ("https://", "http://")
        ):
            self.error(f"{path} privacy_policy_url must be an HTTP(S) URL or null")

        if self.require_ready:
            if declarations["status"] != "ready":
                self.error(f"{path} is still marked draft")
            for section in ("content_rating", "data_safety"):
                value = declarations.get(section)
                if (
                    isinstance(value, dict)
                    and value.get("completed_in_play_console") is not True
                ):
                    self.error(
                        f"{path} {section} is not marked completed in Play Console"
                    )

    def check_images(self, root: Path) -> None:
        icon = root / "branding/playstore/icon-512.png"
        feature_graphic = root / "branding/playstore/feature-graphic.png"
        for path, expected, label in (
            (icon, (512, 512), "Play Store icon"),
            (feature_graphic, (1024, 500), "Play Store feature graphic"),
        ):
            if not path.is_file():
                self.error(f"Missing {label}: {path}")
                continue
            dimensions = self.png_dimensions(path, label)
            if dimensions is not None and dimensions != expected:
                self.error(
                    f"{label}: expected {expected[0]}x{expected[1]}, "
                    f"got {dimensions[0]}x{dimensions[1]}"
                )

        image_root = root / "fastlane/metadata/android/en-US/images"
        for bucket in SCREENSHOT_BUCKETS:
            directory = image_root / bucket
            images = sorted(directory.glob("*.png")) if directory.is_dir() else []
            if not images:
                message = f"No screenshots found in {directory}"
                if self.require_screenshots:
                    self.error(message)
                else:
                    self.warning(message)
                continue
            if len(images) > 8:
                self.error(
                    f"{directory} contains {len(images)} screenshots; "
                    "Play allows at most 8 per device bucket"
                )
            for image in images:
                dimensions = self.png_dimensions(image, f"Screenshot {image}")
                if dimensions is None:
                    continue
                width, height = dimensions
                if min(width, height) < 320 or max(width, height) > 3840:
                    self.error(
                        f"Screenshot {image}: dimensions {width}x{height} are outside "
                        "Play's 320..3840 range"
                    )
                if max(width / height, height / width) > 2:
                    self.error(
                        f"Screenshot {image}: aspect ratio {width}:{height} exceeds "
                        "Play's 2:1 limit"
                    )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--root", type=Path, default=Path(__file__).resolve().parents[2]
    )
    parser.add_argument("--require-screenshots", action="store_true")
    parser.add_argument("--require-ready", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    root = args.root.resolve()
    validation = Validation(
        require_screenshots=args.require_screenshots,
        require_ready=args.require_ready,
    )
    validation.check_listing_text(root / "fastlane/metadata/android/en-US")
    validation.check_declarations(root)
    validation.check_images(root)

    for warning in validation.warnings:
        print(f"warning: {warning}", file=sys.stderr)
    for error in validation.errors:
        print(f"error: {error}", file=sys.stderr)
    if validation.errors:
        return 1
    print("Play Store metadata and committed assets are structurally valid.")
    if validation.warnings:
        print("The listing is not ready for an upload; see the warnings above.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
