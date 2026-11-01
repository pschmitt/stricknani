import adapter from "@sveltejs/adapter-static";
import { sveltekit } from "@sveltejs/kit/vite";
import { defineConfig } from "vite";

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
      csp: {
        mode: "hash",
      },
    }),
  ],
});
