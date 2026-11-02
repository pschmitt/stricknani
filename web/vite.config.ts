import adapter from "@sveltejs/adapter-static";
import { sveltekit } from "@sveltejs/kit/vite";
import { defineConfig } from "vite";
import { svelteKitAnnouncerStyle } from "./scripts/sveltekit-announcer-style.mjs";

const { hash: announcerStyleHash } = await svelteKitAnnouncerStyle();
const pwaTestVersion = process.env.STRICKNANI_PWA_TEST_VERSION;

export default defineConfig({
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
      version: pwaTestVersion ? { name: pwaTestVersion } : {},
      csp: {
        mode: "hash",
        directives: {
          "style-src-attr": ["unsafe-hashes", announcerStyleHash],
        },
      },
    }),
  ],
});
