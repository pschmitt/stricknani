import { createRawSnippet } from "svelte";
import { render } from "svelte/server";
import { describe, expect, it } from "vitest";
import StickyNote from "./StickyNote.svelte";

const children = createRawSnippet(() => ({
  render: () => `<div class="rich-text"><a href="/x">Gauge</a> swatch</div>`,
}));

describe("StickyNote notes FAB markup", () => {
  const { body } = render(StickyNote, {
    props: {
      title: "Notes",
      headingId: "notes-heading",
      headingLevel: 3,
      closeLabel: "Close notes",
      children,
    },
  });

  it("renders a collapsed FAB button wired to the panel", () => {
    expect(body).toContain('<button type="button" class="notes__fab ');
    expect(body).toContain('aria-expanded="false"');
    expect(body).toContain('aria-controls="notes-heading-panel"');
    expect(body).toContain('aria-label="Notes"');
  });

  it("renders the card as a labelled region with its heading and content", () => {
    expect(body).toContain('id="notes-heading-panel"');
    expect(body).toContain('aria-labelledby="notes-heading"');
    expect(body).toContain('<h3 id="notes-heading" class="sticky__title ');
    expect(body).toContain('<a href="/x">Gauge</a>');
  });

  it("offers the pin and a cross as labelled close buttons", () => {
    expect(body.split('aria-label="Close notes"')).toHaveLength(3);
  });

  it("starts closed", () => {
    expect(body).toContain('data-phase="closed"');
  });
});

describe("StickyNote empty invitation", () => {
  it("keeps the inline Add a note link and shows no FAB", () => {
    const { body } = render(StickyNote, {
      props: {
        title: "Notes",
        headingId: "notes-heading",
        emptyLabel: "Add a note",
        emptyHref: "/projects/1/edit#project-notes",
      },
    });
    expect(body).toContain('href="/projects/1/edit#project-notes"');
    expect(body).toContain("Add a note");
    expect(body).not.toContain("notes__fab");
  });
});
