import { render } from "svelte/server";
import { describe, expect, it } from "vitest";
import FilterChip from "./FilterChip.svelte";
import FilterChipRow from "./FilterChipRow.svelte";
import OwnerFilterChips from "./OwnerFilterChips.svelte";

const noop = () => {};

describe("FilterChip", () => {
  it("is an unpressed toggle button while off", () => {
    const { body } = render(FilterChip, {
      props: { label: "Done", selected: false, onclick: noop, testid: "x" },
    });
    expect(body).toContain('aria-pressed="false"');
    expect(body).toContain('type="button"');
    expect(body).toContain('data-testid="x"');
    expect(body).not.toContain("filter-chip--selected");
  });

  it("is pressed and styled as selected while on, without a check mark", () => {
    const { body } = render(FilterChip, {
      props: { label: "Done", selected: true, onclick: noop },
    });
    expect(body).toContain('aria-pressed="true"');
    expect(body).toContain("filter-chip--selected");
    expect(body).not.toContain("<svg");
  });

  it("keeps the whole label for a screen reader and as a tooltip", () => {
    const { body } = render(FilterChip, {
      props: { label: "Play Rbw", selected: false, onclick: noop },
    });
    expect(body).toContain('title="Play Rbw"');
    expect(body).toContain('data-label="Play Rbw"');
  });
});

describe("FilterChipRow", () => {
  it("is a labelled group", () => {
    const { body } = render(FilterChipRow, {
      props: { label: "Filter", testid: "row", children: (() => {}) as never },
    });
    expect(body).toContain('role="group"');
    expect(body).toContain('aria-label="Filter"');
    expect(body).toContain('data-testid="row"');
  });
});

describe("OwnerFilterChips", () => {
  const owners = [
    { id: 7, email: "a@x.test", name: "Anika Bergmann", avatar_url: null },
  ];

  it("lists Mine, Shared with me, the owners by name, then Trash", () => {
    const { body } = render(OwnerFilterChips, {
      props: {
        choice: "7",
        owners,
        locale: "en",
        kind: "project",
        onselect: noop,
        trash: { selected: false, ontoggle: noop },
      },
    });
    const order = ["Mine", "Shared with me", "Anika Bergmann", "Trash"].map(
      (label) => body.indexOf(`data-label="${label}"`),
    );
    expect(order.every((index) => index >= 0)).toBe(true);
    expect([...order].sort((a, b) => a - b)).toEqual(order);
    expect(body).not.toContain("a@x.test");
  });

  it("presses only the chosen owner", () => {
    const { body } = render(OwnerFilterChips, {
      props: {
        choice: "7",
        owners,
        locale: "en",
        kind: "yarn",
        onselect: noop,
      },
    });
    expect(body.match(/aria-pressed="true"/g)).toHaveLength(1);
    expect(body).toMatch(
      /aria-pressed="true"[^>]*data-testid="yarn-filter-owner"/,
    );
  });
});
