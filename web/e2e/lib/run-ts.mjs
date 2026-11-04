// Run a TypeScript entry point through Vite's module runner.
//
// Node's native type stripping needs explicit `.ts` import extensions, but the
// e2e sources use extensionless relative imports (bundler resolution), so the
// CLIs are executed through Vite instead: `node e2e/lib/run-ts.mjs <file.ts> [args]`.
import { resolve } from "node:path";
import { runnerImport } from "vite";

const [entry, ...args] = process.argv.slice(2);
if (!entry) {
  console.error("usage: run-ts.mjs <entry.ts> [args...]");
  process.exit(2);
}
const { module } = await runnerImport(resolve(entry), {
  configFile: false,
  envFile: false,
  logLevel: "error",
});
if (typeof module.main !== "function") {
  console.error("entry point does not export main()");
  process.exit(2);
}
process.exitCode = await module.main(args);
