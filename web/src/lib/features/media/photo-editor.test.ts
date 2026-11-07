import { afterEach, describe, expect, it, vi } from "vitest";
import {
  arrowDelta,
  buildEditBody,
  clampCrop,
  editPhoto,
  fitSize,
  fullCrop,
  isFullCrop,
  isUnchanged,
  minCropFractions,
  moveCrop,
  normalizeRotation,
  nudgeBox,
  nudgeHandle,
  pointToFraction,
  projectImageEditPath,
  resizeCrop,
  rotateBy,
  rotatedSize,
  yarnPhotoEditPath,
} from "./photo-editor";

const min = { width: 0.1, height: 0.1 };

describe("rotation", () => {
  it("normalizes to quarter turns", () => {
    expect(normalizeRotation(-90)).toBe(270);
    expect(normalizeRotation(360)).toBe(0);
    expect(normalizeRotation(450)).toBe(90);
  });

  it("turns clockwise and counter-clockwise", () => {
    expect(rotateBy(0, 1)).toBe(90);
    expect(rotateBy(0, -1)).toBe(270);
    expect(rotateBy(270, 1)).toBe(0);
    expect(rotateBy(90, -1)).toBe(0);
    expect(rotateBy(90, 2)).toBe(270);
  });

  it("swaps the size for odd quarter turns", () => {
    const natural = { width: 400, height: 300 };
    expect(rotatedSize(natural, 0)).toEqual(natural);
    expect(rotatedSize(natural, 180)).toEqual(natural);
    expect(rotatedSize(natural, 90)).toEqual({ width: 300, height: 400 });
    expect(rotatedSize(natural, 270)).toEqual({ width: 300, height: 400 });
  });
});

describe("fitSize", () => {
  it("fits inside the box keeping the aspect", () => {
    expect(
      fitSize({ width: 400, height: 200 }, { width: 100, height: 100 }),
    ).toEqual({ width: 100, height: 50 });
  });

  it("caps the upscale and handles empty input", () => {
    expect(
      fitSize({ width: 10, height: 10 }, { width: 1000, height: 1000 }),
    ).toEqual({ width: 40, height: 40 });
    expect(
      fitSize({ width: 0, height: 10 }, { width: 10, height: 10 }),
    ).toEqual({ width: 0, height: 0 });
  });
});

describe("minCropFractions", () => {
  it("uses the UI minimum on big images", () => {
    expect(minCropFractions({ width: 4000, height: 3000 })).toEqual({
      width: 0.08,
      height: 0.08,
    });
  });

  it("guarantees 16 px on small images", () => {
    const result = minCropFractions({ width: 100, height: 40 });
    expect(result.width * 100).toBeGreaterThanOrEqual(16);
    expect(result.height * 40).toBeGreaterThanOrEqual(16);
    expect(minCropFractions({ width: 8, height: 8 })).toEqual({
      width: 1,
      height: 1,
    });
  });
});

describe("pointToFraction", () => {
  const rect = { left: 10, top: 20, width: 200, height: 100 };

  it("maps client coordinates into the rectangle", () => {
    expect(pointToFraction(110, 70, rect)).toEqual({ x: 0.5, y: 0.5 });
  });

  it("clamps outside points to the image", () => {
    expect(pointToFraction(-50, 500, rect)).toEqual({ x: 0, y: 1 });
    expect(pointToFraction(1, 1, { ...rect, width: 0 })).toEqual({
      x: 0,
      y: 0,
    });
  });
});

describe("resizeCrop", () => {
  const crop = { x: 0.2, y: 0.2, width: 0.6, height: 0.6 };

  it("moves the dragged corner and keeps the opposite one", () => {
    expect(resizeCrop(crop, "nw", { x: 0.1, y: 0.05 }, min)).toEqual({
      x: 0.1,
      y: 0.05,
      width: expect.closeTo(0.7, 9),
      height: expect.closeTo(0.75, 9),
    });
    const se = resizeCrop(crop, "se", { x: 0.9, y: 0.95 }, min);
    expect(se.x).toBe(0.2);
    expect(se.y).toBe(0.2);
    expect(se.width).toBeCloseTo(0.7, 9);
    expect(se.height).toBeCloseTo(0.75, 9);
  });

  it("enforces the minimum size instead of flipping", () => {
    const small = resizeCrop(crop, "nw", { x: 0.95, y: 0.95 }, min);
    expect(small.width).toBeCloseTo(0.1, 9);
    expect(small.height).toBeCloseTo(0.1, 9);
    expect(small.x + small.width).toBeCloseTo(0.8, 9);
    const ne = resizeCrop(crop, "ne", { x: 0, y: 1 }, min);
    expect(ne.width).toBeCloseTo(0.1, 9);
    expect(ne.y + ne.height).toBeCloseTo(0.8, 9);
  });

  it("stays inside the image", () => {
    const outside = resizeCrop(crop, "se", { x: 5, y: 5 }, min);
    expect(outside.x + outside.width).toBe(1);
    expect(outside.y + outside.height).toBe(1);
  });
});

describe("moveCrop and clampCrop", () => {
  const crop = { x: 0.2, y: 0.2, width: 0.5, height: 0.5 };

  it("moves but never leaves the image", () => {
    expect(moveCrop(crop, 0.1, -0.1)).toEqual({
      x: expect.closeTo(0.3, 9),
      y: expect.closeTo(0.1, 9),
      width: 0.5,
      height: 0.5,
    });
    expect(moveCrop(crop, 5, 5)).toEqual({
      x: 0.5,
      y: 0.5,
      width: 0.5,
      height: 0.5,
    });
    expect(moveCrop(crop, -5, -5)).toEqual({
      x: 0,
      y: 0,
      width: 0.5,
      height: 0.5,
    });
  });

  it("clamps oversized and tiny boxes", () => {
    expect(clampCrop({ x: -1, y: 2, width: 3, height: 0.01 }, min)).toEqual({
      x: 0,
      y: 0.9,
      width: 1,
      height: 0.1,
    });
  });
});

describe("keyboard", () => {
  const crop = { x: 0.2, y: 0.2, width: 0.5, height: 0.5 };

  it("maps arrow keys", () => {
    expect(arrowDelta("ArrowLeft")).toEqual({ dx: -1, dy: 0 });
    expect(arrowDelta("ArrowDown")).toEqual({ dx: 0, dy: 1 });
    expect(arrowDelta("a")).toBeNull();
  });

  it("nudges a corner", () => {
    const next = nudgeHandle(crop, "nw", "ArrowLeft", min);
    expect(next.x).toBeCloseTo(0.19, 9);
    expect(next.width).toBeCloseTo(0.51, 9);
    const large = nudgeHandle(crop, "se", "ArrowDown", min, true);
    expect(large.height).toBeCloseTo(0.55, 9);
    expect(nudgeHandle(crop, "se", "x", min)).toBe(crop);
  });

  it("nudges the box", () => {
    expect(nudgeBox(crop, "ArrowRight").x).toBeCloseTo(0.21, 9);
    expect(nudgeBox(crop, "ArrowUp", true).y).toBeCloseTo(0.15, 9);
    expect(nudgeBox(crop, "Tab")).toBe(crop);
  });
});

describe("buildEditBody", () => {
  it("leaves out a whole-image crop", () => {
    expect(buildEditBody(90, fullCrop())).toEqual({ rotate: 90 });
    expect(
      buildEditBody(0, { x: 0.0001, y: 0, width: 0.9999, height: 1 }),
    ).toEqual({ rotate: 0 });
  });

  it("sends a rounded crop in fractions", () => {
    expect(
      buildEditBody(270, {
        x: 1 / 3,
        y: 0.25,
        width: 0.5,
        height: 0.5,
      }),
    ).toEqual({
      rotate: 270,
      crop: { x: 0.333333, y: 0.25, width: 0.5, height: 0.5 },
    });
  });

  it("never exceeds the image", () => {
    const body = buildEditBody(0, { x: 0.5, y: 0.5, width: 0.7, height: 0.7 });
    expect(body.crop).toEqual({ x: 0.5, y: 0.5, width: 0.5, height: 0.5 });
  });
});

describe("isUnchanged", () => {
  it("is true only for no rotation and the whole image", () => {
    expect(isUnchanged(0, fullCrop())).toBe(true);
    expect(isUnchanged(90, fullCrop())).toBe(false);
    expect(isUnchanged(0, { x: 0.1, y: 0, width: 0.9, height: 1 })).toBe(false);
    expect(isFullCrop(fullCrop())).toBe(true);
  });
});

describe("paths and request", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("builds the endpoints", () => {
    expect(projectImageEditPath(3, 9)).toBe("/api/v2/projects/3/images/9/edit");
    expect(yarnPhotoEditPath(4, 7)).toBe("/api/v2/yarns/4/photos/7/edit");
  });

  it("posts JSON with the CSRF token", async () => {
    const fetchMock = vi.fn(
      async () =>
        new Response(JSON.stringify({ data: { id: 9 } }), {
          status: 200,
          headers: { "content-type": "application/json" },
        }),
    );
    vi.stubGlobal("fetch", fetchMock);
    await editPhoto("/api/v2/projects/3/images/9/edit", { rotate: 90 }, "tok");
    const [path, init] = fetchMock.mock.calls[0] as unknown as [
      string,
      RequestInit,
    ];
    expect(path).toBe("/api/v2/projects/3/images/9/edit");
    expect(init.method).toBe("POST");
    expect(new Headers(init.headers).get("x-csrf-token")).toBe("tok");
    expect(init.body).toBe(JSON.stringify({ rotate: 90 }));
  });

  it("rejects with the server error", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(
        async () =>
          new Response(
            JSON.stringify({
              error: { code: "invalid_edit", message: "Too small" },
            }),
            { status: 422, headers: { "content-type": "application/json" } },
          ),
      ),
    );
    await expect(
      editPhoto(
        "/x",
        { rotate: 0, crop: { x: 0, y: 0, width: 0.1, height: 0.1 } },
        "t",
      ),
    ).rejects.toMatchObject({ status: 422 });
  });
});
