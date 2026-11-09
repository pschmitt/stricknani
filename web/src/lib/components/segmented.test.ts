import { render } from "svelte/server";
import { describe, expect, it } from "vitest";
import SegmentedControl from "./SegmentedControl.svelte";
import { nextIndex, selectedIndex, tabStopIndex } from "./segmented.ts";

const three = [{}, {}, {}];

describe("selectedIndex", () => {
  it("finds the option or answers -1", () => {
    const options = [{ value: "a" }, { value: "b" }];
    expect(selectedIndex(options, "b")).toBe(1);
    expect(selectedIndex(options, "z")).toBe(-1);
  });
});

describe("nextIndex", () => {
  it("moves with the arrows and wraps", () => {
    expect(nextIndex(three, 0, "ArrowRight")).toBe(1);
    expect(nextIndex(three, 2, "ArrowRight")).toBe(0);
    expect(nextIndex(three, 0, "ArrowLeft")).toBe(2);
    expect(nextIndex(three, 1, "ArrowDown")).toBe(2);
    expect(nextIndex(three, 1, "ArrowUp")).toBe(0);
  });

  it("jumps with Home and End", () => {
    expect(nextIndex(three, 1, "Home")).toBe(0);
    expect(nextIndex(three, 1, "End")).toBe(2);
  });

  it("skips disabled options", () => {
    const options = [{}, { disabled: true }, {}];
    expect(nextIndex(options, 0, "ArrowRight")).toBe(2);
    expect(nextIndex(options, 2, "ArrowLeft")).toBe(0);
    const edge = [{ disabled: true }, {}, {}];
    expect(nextIndex(edge, 1, "Home")).toBe(1);
  });

  it("flips the horizontal arrows right to left", () => {
    expect(nextIndex(three, 1, "ArrowRight", true)).toBe(0);
    expect(nextIndex(three, 1, "ArrowLeft", true)).toBe(2);
  });

  it("ignores other keys and has nowhere to go with one enabled option", () => {
    expect(nextIndex(three, 0, "a")).toBeNull();
    expect(nextIndex([{}, { disabled: true }], 0, "ArrowRight")).toBeNull();
  });
});

describe("tabStopIndex", () => {
  it("is the selected option, else the first enabled one", () => {
    expect(tabStopIndex(three, 2)).toBe(2);
    expect(tabStopIndex(three, -1)).toBe(0);
    expect(tabStopIndex([{ disabled: true }, {}], -1)).toBe(1);
    expect(tabStopIndex([{}, { disabled: true }], 1)).toBe(0);
  });
});

describe("SegmentedControl", () => {
  it("renders a radio group with the chosen option checked", () => {
    const { body } = render(SegmentedControl<string>, {
      props: {
        options: [
          { value: "a", label: "Alpha" },
          { value: "b", label: "Beta" },
        ],
        value: "b",
        label: "Letters",
        onchange: () => {},
      },
    });
    expect(body).toContain('role="radiogroup"');
    expect(body).toContain('aria-label="Letters"');
    expect(body).toMatch(/aria-checked="false"[^>]*>(?:(?!<button).)*Alpha/s);
    expect(body).toMatch(/aria-checked="true"[^>]*>(?:(?!<button).)*Beta/s);
    expect(body).toContain("--segment: 1");
  });
});
