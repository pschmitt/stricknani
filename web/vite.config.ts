/// <reference types="vitest/config" />
import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";
import adapter from "@sveltejs/adapter-static";
import { sveltekit } from "@sveltejs/kit/vite";
import { defineConfig } from "vite";
import { svelteKitAnnouncerStyle } from "./scripts/sveltekit-announcer-style.mjs";

const { hash: announcerStyleHash } = await svelteKitAnnouncerStyle();
const pwaTestVersion = process.env.STRICKNANI_PWA_TEST_VERSION;

/**
 * Short commit of this bundle, plus "-dirty" for uncommitted changes to tracked files. Nix and CI
 * builds have no .git and pass STRICKNANI_GIT_REVISION (or GIT_REVISION); a checkout asks git.
 * Empty when neither is available, which hides the Commit row on the About page.
 */
function gitRevision(): string {
  const fromEnv = (
    process.env.STRICKNANI_GIT_REVISION ??
    process.env.GIT_REVISION ??
    ""
  ).trim();
  if (fromEnv) return fromEnv;
  try {
    const git = (...args: string[]) =>
      execFileSync("git", args, { stdio: ["ignore", "pipe", "ignore"] })
        .toString()
        .trim();
    const sha = git("rev-parse", "--short=8", "HEAD");
    let dirty = false;
    try {
      git("diff", "--quiet", "HEAD", "--");
    } catch {
      dirty = true;
    }
    return dirty ? `${sha}-dirty` : sha;
  } catch {
    return "";
  }
}

/**
 * The release version. Cargo.toml at the repository root is the single source
 * for the server, the web app and the Nix packages. The Nix build of web/ has
 * no Cargo.toml and passes STRICKNANI_VERSION instead.
 */
function appVersion(): string {
  const fromEnv = (process.env.STRICKNANI_VERSION ?? "").trim();
  if (fromEnv) return fromEnv;
  const cargo = readFileSync(new URL("../Cargo.toml", import.meta.url), "utf8");
  const match = /^\[package\][^[]*?^version\s*=\s*"([^"]+)"/ms.exec(cargo);
  if (!match) throw new Error("Cargo.toml has no [package] version");
  return match[1];
}

export default defineConfig({
  test: {
    include: ["src/**/*.{test,spec}.ts", "e2e/lib/**/*.test.ts"],
  },
  define: {
    __STRICKNANI_GIT_REVISION__: JSON.stringify(gitRevision()),
    __STRICKNANI_VERSION__: JSON.stringify(appVersion()),
  },
  plugins: [
    sveltekit({
      adapter: adapter({
        pages: "build",
        assets: "build",
        fallback: "200.html",
      }),
      paths: {
        base: "/app",
      },
      version: {
        ...(pwaTestVersion ? { name: pwaTestVersion } : {}),
        // Tabs look for a new deployment every 10 minutes, and SvelteKit also
        // checks when a tab regains focus or becomes visible.
        pollInterval: 10 * 60 * 1000,
      },
      csp: {
        mode: "hash",
        directives: {
          "style-src-attr": ["unsafe-hashes", announcerStyleHash],
        },
      },
    }),
  ],
});
