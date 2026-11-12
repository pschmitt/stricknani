import { readFileSync } from "node:fs";
import { render } from "svelte/server";
import { describe, expect, it } from "vitest";
import CloseButton from "./CloseButton.svelte";

const css = readFileSync(new URL("../../app.css", import.meta.url), "utf8");

describe("CloseButton", () => {
  it("is named Close by default", () => {
    const en = render(CloseButton, { props: { locale: "en" as const } }).body;
    expect(en).toContain('aria-label="Close"');
    expect(en).toContain('type="button"');
    expect(en).toContain("close-button");
  });

  it("takes a more specific accessible name and extra classes", () => {
    const { body } = render(CloseButton, {
      props: {
        locale: "en" as const,
        label: "Dismiss message",
        class: "extra",
      },
    });
    expect(body).toContain('aria-label="Dismiss message"');
    expect(body).toContain("close-button extra");
  });

  it("keeps a 44px square hit area and no resting shape", () => {
    const rule = /\n\.close-button \{([^}]*)\}/.exec(css)?.[1] ?? "";
    expect(rule).toMatch(/width:\s*44px/);
    expect(rule).toMatch(/height:\s*44px/);
    expect(rule).toMatch(/border:\s*0/);
    expect(rule).toMatch(/background:\s*transparent/);
  });
});
