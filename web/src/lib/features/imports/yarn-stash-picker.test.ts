import { render } from "svelte/server";
import { describe, expect, it } from "vitest";
import YarnStashPicker from "./YarnStashPicker.svelte";

describe("YarnStashPicker", () => {
  it("renders a labelled search field that reuses the shared card list", () => {
    const { body } = render(YarnStashPicker, {
      props: {
        locale: "en",
        inputId: "stash-search",
        label: "Find a yarn in your stash",
        onPick: () => {},
      },
    });
    expect(body).toContain('<label for="stash-search">');
    expect(body).toContain('type="search"');
    expect(body).toContain('placeholder="Search your yarns"');
  });
});
