import { render } from "svelte/server";
import { describe, expect, it } from "vitest";
import PhotoStrip from "./PhotoStrip.svelte";

const item = (id: number) => ({
  id,
  url: `/media/images/${id}.jpg`,
  alt: `Photo ${id}`,
});
const props = (count: number, extra = {}) => ({
  items: Array.from({ length: count }, (_, i) => item(i + 1)),
  label: "Photos",
  openLabel: "Open project photo",
  locale: "en" as const,
  onopen: () => {},
  ...extra,
});

describe("PhotoStrip", () => {
  it("renders nothing without photos", () => {
    expect(render(PhotoStrip, { props: props(0) }).body).not.toContain(
      "photo-strip",
    );
  });

  it("shows a single tile without arrows or keyboard stop", () => {
    const { body } = render(PhotoStrip, { props: props(1) });
    expect(body).toContain("photo-strip__track--single");
    expect(body).not.toContain("photo-strip__arrow");
    expect(body).toContain('aria-label="Open project photo: Photo 1"');
  });

  it("renders every tile lazily with a 4:3 box and prev/next buttons", () => {
    const { body } = render(PhotoStrip, { props: props(5) });
    expect(body.match(/class="photo-strip__tile /g)).toHaveLength(5);
    expect(body.match(/loading="lazy"/g)).toHaveLength(5);
    expect(body).toContain('decoding="async"');
    expect(body).toContain('aria-label="Previous"');
    expect(body).toContain('aria-label="Next"');
    expect(body).toContain('tabindex="0"');
  });

  it("adds a print copy only when printable", () => {
    expect(render(PhotoStrip, { props: props(2) }).body).not.toContain(
      "print-reference-image",
    );
    expect(
      render(PhotoStrip, { props: props(2, { printable: true }) }).body.match(
        /data-print-src/g,
      ),
    ).toHaveLength(2);
  });
});
