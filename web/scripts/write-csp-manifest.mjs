import { createHash } from "node:crypto";
import { readdir, readFile, writeFile } from "node:fs/promises";
import { extname, join } from "node:path";
import { fileURLToPath } from "node:url";

const buildRoot = fileURLToPath(new URL("../build/", import.meta.url));
const htmlFiles = await listHtmlFiles(buildRoot);
const scriptHashes = new Set();
const styleHashes = new Set();

for (const file of htmlFiles) {
  const html = await readFile(file, "utf8");
  if (/<[a-z][^>]*\sstyle\s*=/i.test(html)) {
    throw new Error(
      `Inline style attributes are incompatible with strict style-src CSP: ${file}`,
    );
  }
  for (const [, attributes, body] of html.matchAll(
    /<script\b([^>]*)>([\s\S]*?)<\/script\s*>/gi,
  )) {
    if (!/\bsrc\s*=/i.test(attributes)) scriptHashes.add(hash(body));
  }
  for (const [, , body] of html.matchAll(
    /<style\b([^>]*)>([\s\S]*?)<\/style\s*>/gi,
  )) {
    styleHashes.add(hash(body));
  }
}

const manifest = {
  script_hashes: [...scriptHashes].sort(),
  style_hashes: [...styleHashes].sort(),
};

await writeFile(
  new URL("../build/csp.json", import.meta.url),
  `${JSON.stringify(manifest, null, 2)}\n`,
);

/** @param {string} directory @returns {Promise<string[]>} */
async function listHtmlFiles(directory) {
  const files = [];
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) files.push(...(await listHtmlFiles(path)));
    else if (entry.isFile() && extname(entry.name) === ".html")
      files.push(path);
  }
  return files;
}

/** @param {string} content @returns {string} */
function hash(content) {
  return `sha256-${createHash("sha256").update(content, "utf8").digest("base64")}`;
}
