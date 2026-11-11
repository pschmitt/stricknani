import { render } from "svelte/server";
import { describe, expect, it } from "vitest";
import LibraryCard from "./LibraryCard.svelte";

const base = {
  kind: "project" as const,
  name: "Cozy hoodie",
  href: "/projects/7",
  imageUrl: null,
  meta: "Sweaters",
  updated: "Updated Nov 3, 2026",
  favorite: false,
  locale: "en" as const,
  onfavorite: () => {},
};

describe("LibraryCard", () => {
  it("links the whole card and keeps the heart outside the link", () => {
    const { body } = render(LibraryCard, { props: base });
    expect(body).toContain('href="/projects/7"');
    expect(body).toContain("resource-card__link");
    const link = body.slice(body.indexOf("<a "), body.indexOf("</a>"));
    expect(link).not.toContain("<button");
    expect(body).toContain('aria-label="Add to favorites"');
    expect(body).toContain('aria-pressed="false"');
  });

  it("shows a placeholder icon without a photo and a lazy 4:3 image with one", () => {
    const none = render(LibraryCard, { props: base }).body;
    expect(none).toContain("library-card__placeholder");
    expect(none).not.toContain("<img");
    const withImage = render(LibraryCard, {
      props: { ...base, imageUrl: "/media/thumbnails/projects/7/a.jpg" },
    }).body;
    expect(withImage).toContain('loading="lazy"');
    expect(withImage).toContain('decoding="async"');
    expect(withImage).toContain('width="400"');
    expect(withImage).toContain('height="300"');
  });

  it("renders at most three chips and reflects the favorite state", () => {
    const { body } = render(LibraryCard, {
      props: {
        ...base,
        kind: "yarn" as const,
        chips: ["a", "b", "c", "d"],
        favorite: true,
      },
    });
    expect(body.split('class="library-card__chip ').length - 1).toBe(3);
    expect(body).not.toContain(">d<");
    expect(body).toContain('aria-label="Remove from favorites"');
    expect(body).toContain("library-card--yarn");
  });

  it("badges a done project and leaves one in progress plain", () => {
    const done = render(LibraryCard, {
      props: { ...base, status: "done" as const },
    }).body;
    expect(done).toContain("project-status-badge");
    expect(done).toContain("Done");
    const open = render(LibraryCard, {
      props: { ...base, status: "in_progress" as const },
    }).body;
    expect(open).not.toContain("project-status-badge");
    expect(render(LibraryCard, { props: base }).body).not.toContain(
      "project-status-badge",
    );
  });
});
