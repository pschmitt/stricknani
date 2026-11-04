import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { REPOSITORY_URL, SETUP_PROMPT } from "./setup-prompt";

const root = new URL("../../../", import.meta.url);
const read = (path: string): string =>
  readFileSync(new URL(path, root), "utf8");

describe("setup prompt", () => {
  it("is the same text in the README", () => {
    const readme = read("README.md");
    expect(readme).toContain(`\`\`\`text\n${SETUP_PROMPT}\n\`\`\``);
  });

  it("is the same text in the Android strings", () => {
    const xml = read("android/app/src/main/res/values/strings.xml");
    const start = xml.indexOf('<string name="setup_prompt"');
    expect(start).toBeGreaterThan(-1);
    const from = xml.indexOf(">", start) + 1;
    const raw = xml.slice(from, xml.indexOf("</string>", from));
    const decoded = raw
      .replaceAll("\\n", "\n")
      .replaceAll('\\"', '"')
      .replaceAll("\\'", "'")
      .replaceAll("&amp;", "&");
    expect(decoded).toBe(SETUP_PROMPT);
  });

  it("names the real install knobs and a placeholder host only", () => {
    expect(SETUP_PROMPT).toContain("docs/install.md");
    expect(SETUP_PROMPT).toContain("TRUSTED_HOSTS");
    expect(SETUP_PROMPT).toContain("SESSION_COOKIE_SECURE=true");
    expect(SETUP_PROMPT).toContain("RUST_SIGNUP_ENABLED");
    expect(SETUP_PROMPT).toContain("example.com");
    expect(SETUP_PROMPT).toContain(REPOSITORY_URL);
  });
});
