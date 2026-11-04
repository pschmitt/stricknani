import { render } from "svelte/server";
import { describe, expect, it } from "vitest";
import StickyNoteField from "./StickyNoteField.svelte";

describe("StickyNoteField", () => {
  const { body } = render(StickyNoteField, {
    props: { id: "import-notes", label: "Notes", value: "Designer: DROPS" },
  });

  it("is one real, labelled textarea on the sticky paper", () => {
    expect(body).toMatch(/<label for="import-notes"[^>]*>Notes<\/label>/);
    expect(body.match(/<textarea/g)).toHaveLength(1);
    expect(body).toContain('id="import-notes"');
    expect(body).toContain("Designer: DROPS");
    expect(body).toContain("sticky-field__paper");
  });

  it("hides the decorative paper from assistive technology", () => {
    expect(body).toMatch(/sticky-field__paper[^>]*aria-hidden="true"/);
  });
});
