import { describe, expect, it, vi } from "vitest";
import { ApiError } from "#lib/api.ts";
import {
  applyOptimisticReorder,
  canMakeCover,
  deltaForKey,
  keyboardTarget,
  moveItem,
  nearestIndex,
  projectStepOrderPath,
  projectTitleOrderPath,
  replaceGroup,
  yarnPhotoOrderPath,
} from "./media-reorder";

const items = [{ id: 1 }, { id: 2 }, { id: 3 }, { id: 4 }];
const ids = (list: { id: number }[]) => list.map((item) => item.id);

describe("moveItem", () => {
  it("moves forward and backward without mutating the input", () => {
    expect(ids(moveItem(items, 0, 2))).toEqual([2, 3, 1, 4]);
    expect(ids(moveItem(items, 3, 0))).toEqual([4, 1, 2, 3]);
    expect(ids(items)).toEqual([1, 2, 3, 4]);
  });

  it("ignores no-op and out-of-range moves", () => {
    expect(ids(moveItem(items, 1, 1))).toEqual([1, 2, 3, 4]);
    expect(ids(moveItem(items, 0, 9))).toEqual([1, 2, 3, 4]);
    expect(ids(moveItem(items, -1, 2))).toEqual([1, 2, 3, 4]);
  });
});

describe("keyboard helpers", () => {
  it("maps arrow keys and clamps at the edges", () => {
    expect(deltaForKey("ArrowLeft")).toBe(-1);
    expect(deltaForKey("ArrowRight")).toBe(1);
    expect(deltaForKey("ArrowLeft", true)).toBe(1);
    expect(deltaForKey("Enter")).toBeNull();
    expect(keyboardTarget(0, -1, 4)).toBe(0);
    expect(keyboardTarget(3, 1, 4)).toBe(3);
    expect(keyboardTarget(1, 1, 4)).toBe(2);
  });
});

describe("nearestIndex", () => {
  const centers = [
    { x: 50, y: 50 },
    { x: 150, y: 50 },
    { x: 50, y: 150 },
  ];
  it("finds the closest tile in a wrapping grid", () => {
    expect(nearestIndex(centers, 140, 60)).toBe(1);
    expect(nearestIndex(centers, 40, 160)).toBe(2);
    expect(nearestIndex([], 0, 0)).toBe(-1);
  });
});

describe("replaceGroup", () => {
  it("keeps other groups in place", () => {
    const all = [
      { id: 1, g: "a" },
      { id: 2, g: "b" },
      { id: 3, g: "a" },
      { id: 4, g: "b" },
    ];
    const group = all.filter((item) => item.g === "a");
    const result = replaceGroup(all, group, [group[1], group[0]]);
    expect(ids(result)).toEqual([3, 2, 1, 4]);
  });
});

describe("applyOptimisticReorder", () => {
  function setup(send: (ids: number[]) => Promise<never>) {
    let current = [...items];
    const options = {
      get items() {
        return current;
      },
      from: 0,
      to: 2,
      setItems: vi.fn((next: typeof items) => {
        current = next;
      }),
      send: vi.fn(send),
      refetch: vi.fn(async () => {}),
      onError: vi.fn(),
    };
    return { options, current: () => current };
  }

  it("applies the order optimistically and sends exactly the new ids", async () => {
    const { options, current } = setup(async () => ({}) as never);
    expect(await applyOptimisticReorder(options)).toBe(true);
    expect(options.send).toHaveBeenCalledWith([2, 3, 1, 4]);
    expect(ids(current())).toEqual([2, 3, 1, 4]);
    expect(options.refetch).not.toHaveBeenCalled();
    expect(options.onError).not.toHaveBeenCalled();
  });

  it("reverts and reports on a generic failure", async () => {
    const failure = new ApiError("boom", 500);
    const { options, current } = setup(async () => {
      throw failure;
    });
    expect(await applyOptimisticReorder(options)).toBe(false);
    expect(ids(current())).toEqual([1, 2, 3, 4]);
    expect(options.refetch).not.toHaveBeenCalled();
    expect(options.onError).toHaveBeenCalledWith(failure);
  });

  it("reverts, re-fetches and reports on 422", async () => {
    const { options, current } = setup(async () => {
      throw new ApiError("stale", 422, "validation_failed");
    });
    expect(await applyOptimisticReorder(options)).toBe(false);
    expect(ids(current())).toEqual([1, 2, 3, 4]);
    expect(options.refetch).toHaveBeenCalledTimes(1);
    expect(options.onError).toHaveBeenCalledTimes(1);
  });

  it("does nothing for a no-op move", async () => {
    const { options } = setup(async () => ({}) as never);
    options.to = 0;
    expect(await applyOptimisticReorder(options)).toBe(false);
    expect(options.send).not.toHaveBeenCalled();
    expect(options.setItems).not.toHaveBeenCalled();
  });
});

describe("order endpoints", () => {
  it("matches the frozen server contract", () => {
    expect(projectTitleOrderPath(5)).toBe("/api/v2/projects/5/images/order");
    expect(projectStepOrderPath(5, 9)).toBe(
      "/api/v2/projects/5/steps/9/images/order",
    );
    expect(yarnPhotoOrderPath(3)).toBe("/api/v2/yarns/3/photos/order");
  });
});

describe("canMakeCover", () => {
  it("allows every photo except the cover itself", () => {
    expect(canMakeCover(0, 4)).toBe(false);
    expect(canMakeCover(1, 4)).toBe(true);
    expect(canMakeCover(3, 4)).toBe(true);
  });

  it("rejects out-of-range and fractional indexes", () => {
    expect(canMakeCover(4, 4)).toBe(false);
    expect(canMakeCover(-1, 4)).toBe(false);
    expect(canMakeCover(1.5, 4)).toBe(false);
  });

  it("moves the photo to the front in one reorder", () => {
    expect(moveItem(items, 2, 0).map((item) => item.id)).toEqual([3, 1, 2, 4]);
  });
});
