import { execFileSync } from "node:child_process";
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

export default defineConfig({
  define: {
    __STRICKNANI_GIT_REVISION__: JSON.stringify(gitRevision()),
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
