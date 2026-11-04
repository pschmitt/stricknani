import { render } from "svelte/server";
import { describe, expect, it } from "vitest";
import { cameraCaptureLikely } from "./camera-capture.ts";
import TakePhotoInput from "./TakePhotoInput.svelte";

const media = (coarse: boolean) => ({
  matchMedia: (query: string) =>
    ({ matches: query === "(pointer: coarse)" && coarse }) as MediaQueryList,
});

describe("cameraCaptureLikely", () => {
  it("is true on a coarse (touch) pointer", () => {
    expect(cameraCaptureLikely(media(true))).toBe(true);
  });

  it("is false with a mouse or trackpad", () => {
    expect(cameraCaptureLikely(media(false))).toBe(false);
  });

  it("is false without a window or matchMedia", () => {
    expect(cameraCaptureLikely(undefined)).toBe(false);
    expect(cameraCaptureLikely({} as Pick<Window, "matchMedia">)).toBe(false);
  });
});

describe("TakePhotoInput", () => {
  it("renders nothing until the browser is known to be a phone", () => {
    const { body } = render(TakePhotoInput, {
      props: { locale: "en", onchange: () => {} },
    });
    expect(body).not.toContain("capture");
    expect(body).not.toContain("Take photo");
  });
});
