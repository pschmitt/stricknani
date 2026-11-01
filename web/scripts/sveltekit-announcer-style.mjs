import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";

const rootTemplate = fileURLToPath(
  new URL(
    "../node_modules/@sveltejs/kit/src/runtime/components/root.svelte",
    import.meta.url,
  ),
);

/**
 * Read the one known inline style on SvelteKit's client-side live region.
 * Fail closed when the pinned framework template changes.
 * @returns {Promise<{style: string, hash: string}>}
 */
export async function svelteKitAnnouncerStyle() {
  const source = await readFile(rootTemplate, "utf8");
  const matches = [
    ...source.matchAll(
      /<div\s+id="svelte-announcer"\s+aria-live="assertive"\s+aria-atomic="true"\s+style="([^"]+)"\s*>/g,
    ),
  ];

  if (matches.length !== 1) {
    throw new Error(
      "Expected exactly one SvelteKit announcer with the known static style attribute",
    );
  }

  const style = decodeAttribute(matches[0][1]);
  const hash = `sha256-${createHash("sha256").update(style, "utf8").digest("base64")}`;
  return { style, hash };
}

/** @param {string} value */
function decodeAttribute(value) {
  return value.replace(
    /&(#x[\da-f]+|#\d+|amp|lt|gt|quot|apos);/gi,
    (entity, name) => {
      const named = {
        amp: "&",
        lt: "<",
        gt: ">",
        quot: '"',
        apos: "'",
      };
      const lowerName = name.toLowerCase();
      if (lowerName in named) return named[lowerName];
      const codePoint = lowerName.startsWith("#x")
        ? Number.parseInt(lowerName.slice(2), 16)
        : Number.parseInt(lowerName.slice(1), 10);
      if (
        !Number.isInteger(codePoint) ||
        codePoint <= 0 ||
        codePoint > 0x10ffff
      ) {
        throw new Error(
          "SvelteKit announcer style contains an invalid character reference",
        );
      }
      return String.fromCodePoint(codePoint);
    },
  );
}
