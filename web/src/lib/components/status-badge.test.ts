import { render } from "svelte/server";
import { describe, expect, it } from "vitest";
import StatusBadge from "./StatusBadge.svelte";

describe("StatusBadge", () => {
  it("shows a check and Done for a finished project", () => {
    const { body } = render(StatusBadge, {
      props: { status: "done" as const, locale: "en" as const },
    });
    expect(body).toContain('data-status="done"');
    expect(body).toContain("<svg");
    expect(body).toContain("Done");
  });

  it("shows a plain pill without a check while in progress", () => {
    const { body } = render(StatusBadge, {
      props: { status: "in_progress" as const, locale: "en" as const },
    });
    expect(body).toContain('data-status="in_progress"');
    expect(body).not.toContain("<svg");
    expect(body).not.toContain("Done");
  });
});
