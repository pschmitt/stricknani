#!/usr/bin/env node
// Validate the reviewable Google Play listing inputs without contacting Google Play.

import { existsSync, readFileSync, readdirSync, statSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { parseArgs } from "node:util";

const PNG_SIGNATURE = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
const SCREENSHOT_BUCKETS = ["phoneScreenshots", "sevenInchScreenshots", "tenInchScreenshots"];
const DESCRIPTION = "Validate the reviewable Google Play listing inputs without contacting Google Play.";

const isFile = (path) => existsSync(path) && statSync(path).isFile();
const isDir = (path) => existsSync(path) && statSync(path).isDirectory();
const isPlainObject = (value) =>
  typeof value === "object" && value !== null && !Array.isArray(value);

// Approximation of Python's repr() for JSON values, used in one message.
function pyRepr(value) {
  if (value === null) return "None";
  if (value === true) return "True";
  if (value === false) return "False";
  if (typeof value === "string") return `'${value.replaceAll("\\", "\\\\").replaceAll("'", "\\'")}'`;
  if (Array.isArray(value)) return `[${value.map(pyRepr).join(", ")}]`;
  if (isPlainObject(value)) {
    return `{${Object.entries(value)
      .map(([key, item]) => `${pyRepr(key)}: ${pyRepr(item)}`)
      .join(", ")}}`;
  }
  return String(value);
}

class Validation {
  constructor({ requireScreenshots, requireReady }) {
    this.requireScreenshots = requireScreenshots;
    this.requireReady = requireReady;
    this.errors = [];
    this.warnings = [];
  }

  error(message) {
    this.errors.push(message);
  }

  warning(message) {
    this.warnings.push(message);
  }

  pngDimensions(path, label) {
    let data;
    try {
      data = readFileSync(path);
    } catch (error) {
      this.error(`${label}: cannot read ${path}: ${error.message}`);
      return null;
    }

    if (data.length < 26 || !data.subarray(0, 8).equals(PNG_SIGNATURE)) {
      this.error(`${label}: ${path} is not a PNG`);
      return null;
    }
    const chunkLength = data.readUInt32BE(8);
    if (data.subarray(12, 16).toString("latin1") !== "IHDR" || chunkLength < 13) {
      this.error(`${label}: ${path} has no valid PNG IHDR chunk`);
      return null;
    }

    const width = data.readUInt32BE(16);
    const height = data.readUInt32BE(20);
    const bitDepth = data.readUInt8(24);
    const colorType = data.readUInt8(25);
    if (width <= 0 || height <= 0) {
      this.error(`${label}: ${path} has invalid dimensions ${width}x${height}`);
      return null;
    }
    if (![8, 16].includes(bitDepth)) {
      this.error(`${label}: ${path} uses unsupported ${bitDepth}-bit color`);
    }
    if (![0, 2, 3, 4, 6].includes(colorType)) {
      this.error(`${label}: ${path} uses unsupported PNG color type ${colorType}`);
    }
    return [width, height];
  }

  checkListingText(metadataDir) {
    const limits = [
      ["title.txt", 30],
      ["short_description.txt", 80],
      ["full_description.txt", 4000],
    ];
    for (const [filename, limit] of limits) {
      const path = join(metadataDir, filename);
      if (!isFile(path)) {
        this.error(`Missing listing metadata: ${path}`);
        continue;
      }
      const text = readFileSync(path, "utf8").trim();
      const length = [...text].length;
      if (!text) {
        this.error(`Listing metadata is empty: ${path}`);
      }
      if (length > limit) {
        this.error(`${path} is ${length} characters; Play allows at most ${limit}`);
      }
      if (text.includes("\x00")) {
        this.error(`Listing metadata contains a NUL byte: ${path}`);
      }
    }
  }

  checkDeclarations(root) {
    const path = join(root, "android/play-store/declarations.json");
    if (!isFile(path)) {
      this.error(`Missing Play Console declarations: ${path}`);
      return;
    }
    let declarations;
    try {
      declarations = JSON.parse(readFileSync(path, "utf8"));
    } catch (error) {
      this.error(`Cannot parse ${path}: ${error.message}`);
      return;
    }
    if (!isPlainObject(declarations)) {
      this.error(`${path} must contain a JSON object`);
      return;
    }

    const required = [
      "schema_version",
      "status",
      "privacy_policy_url",
      "content_rating",
      "data_safety",
    ];
    const missing = required.filter((key) => !(key in declarations)).sort();
    if (missing.length > 0) {
      this.error(`${path} is missing keys: ${missing.join(", ")}`);
      return;
    }
    if (declarations.schema_version !== 1) {
      this.error(
        `${path} has unsupported schema_version: ${pyRepr(declarations.schema_version)}`,
      );
    }
    if (!["draft", "ready"].includes(declarations.status)) {
      this.error(`${path} status must be 'draft' or 'ready'`);
    }

    for (const section of ["content_rating", "data_safety"]) {
      const value = declarations[section];
      if (!isPlainObject(value)) {
        this.error(`${path} field '${section}' must be an object`);
        continue;
      }
      if (typeof value.completed_in_play_console !== "boolean") {
        this.error(`${path} field ${section}.completed_in_play_console must be boolean`);
      }
    }

    const privacyUrl = declarations.privacy_policy_url;
    if (privacyUrl === null) {
      const message =
        "Play listing privacy_policy_url is not set; the public privacy-policy " +
        "route is still required";
      if (this.requireReady) {
        this.error(message);
      } else {
        this.warning(message);
      }
    } else if (
      typeof privacyUrl !== "string" ||
      !(privacyUrl.startsWith("https://") || privacyUrl.startsWith("http://"))
    ) {
      this.error(`${path} privacy_policy_url must be an HTTP(S) URL or null`);
    }

    if (this.requireReady) {
      if (declarations.status !== "ready") {
        this.error(`${path} is still marked draft`);
      }
      for (const section of ["content_rating", "data_safety"]) {
        const value = declarations[section];
        if (isPlainObject(value) && value.completed_in_play_console !== true) {
          this.error(`${path} ${section} is not marked completed in Play Console`);
        }
      }
    }
  }

  checkImages(root) {
    const icon = join(root, "branding/playstore/icon-512.png");
    const featureGraphic = join(root, "branding/playstore/feature-graphic.png");
    for (const [path, expected, label] of [
      [icon, [512, 512], "Play Store icon"],
      [featureGraphic, [1024, 500], "Play Store feature graphic"],
    ]) {
      if (!isFile(path)) {
        this.error(`Missing ${label}: ${path}`);
        continue;
      }
      const dimensions = this.pngDimensions(path, label);
      if (dimensions !== null && (dimensions[0] !== expected[0] || dimensions[1] !== expected[1])) {
        this.error(
          `${label}: expected ${expected[0]}x${expected[1]}, got ${dimensions[0]}x${dimensions[1]}`,
        );
      }
    }

    const imageRoot = join(root, "fastlane/metadata/android/en-US/images");
    for (const bucket of SCREENSHOT_BUCKETS) {
      const directory = join(imageRoot, bucket);
      const images = isDir(directory)
        ? readdirSync(directory)
            .filter((name) => name.endsWith(".png") && isFile(join(directory, name)))
            .sort()
            .map((name) => join(directory, name))
        : [];
      if (images.length === 0) {
        const message = `No screenshots found in ${directory}`;
        if (this.requireScreenshots) {
          this.error(message);
        } else {
          this.warning(message);
        }
        continue;
      }
      if (images.length > 8) {
        this.error(
          `${directory} contains ${images.length} screenshots; ` +
            "Play allows at most 8 per device bucket",
        );
      }
      for (const image of images) {
        const dimensions = this.pngDimensions(image, `Screenshot ${image}`);
        if (dimensions === null) continue;
        const [width, height] = dimensions;
        if (Math.min(width, height) < 320 || Math.max(width, height) > 3840) {
          this.error(
            `Screenshot ${image}: dimensions ${width}x${height} are outside ` +
              "Play's 320..3840 range",
          );
        }
        if (Math.max(width / height, height / width) > 2) {
          this.error(
            `Screenshot ${image}: aspect ratio ${width}:${height} exceeds Play's 2:1 limit`,
          );
        }
      }
    }
  }
}

function parseCli() {
  const usage =
    "usage: validate-play-store-metadata.mjs [-h] [--root ROOT] [--require-screenshots] [--require-ready]";
  try {
    const { values } = parseArgs({
      options: {
        root: { type: "string" },
        "require-screenshots": { type: "boolean", default: false },
        "require-ready": { type: "boolean", default: false },
        help: { type: "boolean", short: "h", default: false },
      },
      allowPositionals: false,
    });
    if (values.help) {
      console.log(`${usage}\n\n${DESCRIPTION}`);
      process.exit(0);
    }
    return values;
  } catch (error) {
    console.error(`${usage}\nvalidate-play-store-metadata.mjs: error: ${error.message}`);
    process.exit(2);
  }
}

function main() {
  const args = parseCli();
  const root = resolve(
    args.root ?? join(dirname(fileURLToPath(import.meta.url)), "..", ".."),
  );
  const validation = new Validation({
    requireScreenshots: args["require-screenshots"],
    requireReady: args["require-ready"],
  });
  validation.checkListingText(join(root, "fastlane/metadata/android/en-US"));
  validation.checkDeclarations(root);
  validation.checkImages(root);

  for (const warning of validation.warnings) {
    console.error(`warning: ${warning}`);
  }
  for (const error of validation.errors) {
    console.error(`error: ${error}`);
  }
  if (validation.errors.length > 0) {
    return 1;
  }
  console.log("Play Store metadata and committed assets are structurally valid.");
  if (validation.warnings.length > 0) {
    console.log("The listing is not ready for an upload; see the warnings above.");
  }
  return 0;
}

process.exitCode = main();
