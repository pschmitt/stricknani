import { describe, expect, it, vi } from "vitest";
import { ApiError } from "#lib/api.ts";
import {
  applyOptimisticReorder,
  canMoveToFirst,
  edgeScrollDelta,
  gridColumns,
  keyboardMoveTarget,
  LONG_PRESS_MS,
  orderByIds,
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

describe("canMoveToFirst", () => {
  it("allows every photo except the cover itself", () => {
    expect(canMoveToFirst(0, 4)).toBe(false);
    expect(canMoveToFirst(1, 4)).toBe(true);
    expect(canMoveToFirst(3, 4)).toBe(true);
  });

  it("rejects out-of-range and fractional indexes", () => {
    expect(canMoveToFirst(4, 4)).toBe(false);
    expect(canMoveToFirst(-1, 4)).toBe(false);
    expect(canMoveToFirst(1.5, 4)).toBe(false);
  });

  it("moves the photo to the front in one reorder", () => {
    expect(moveItem(items, 2, 0).map((item) => item.id)).toEqual([3, 1, 2, 4]);
  });
});

describe("orderByIds", () => {
  it("follows the id order and keeps unknown items at the end", () => {
    expect(ids(orderByIds(items, [3, 1, 2, 4]))).toEqual([3, 1, 2, 4]);
    expect(ids(orderByIds(items, [4, 99, 2]))).toEqual([4, 2, 1, 3]);
    expect(ids(orderByIds(items, []))).toEqual([1, 2, 3, 4]);
  });
});

describe("gridColumns", () => {
  it("counts the tiles of the first row", () => {
    expect(gridColumns([0, 0, 0, 120, 120])).toBe(3);
    expect(gridColumns([10, 10.4, 10])).toBe(3);
    expect(gridColumns([0, 0])).toBe(2);
    expect(gridColumns([0, 90, 180])).toBe(1);
    expect(gridColumns([])).toBe(1);
  });
});

describe("keyboardMoveTarget", () => {
  const key = (name: string, extra = {}) => ({ key: name, ...extra });

  it("moves by one with left and right and by a row with up and down", () => {
    expect(keyboardMoveTarget(key("ArrowRight"), 1, 6, 3)).toBe(2);
    expect(keyboardMoveTarget(key("ArrowLeft"), 1, 6, 3)).toBe(0);
    expect(keyboardMoveTarget(key("ArrowDown"), 1, 6, 3)).toBe(4);
    expect(keyboardMoveTarget(key("ArrowUp"), 4, 6, 3)).toBe(1);
  });

  it("clamps to the first and last slot", () => {
    expect(keyboardMoveTarget(key("ArrowUp"), 1, 6, 3)).toBe(0);
    expect(keyboardMoveTarget(key("ArrowDown"), 4, 6, 3)).toBe(5);
    expect(keyboardMoveTarget(key("ArrowLeft"), 0, 6, 3)).toBe(0);
  });

  it("mirrors left and right in right-to-left layouts", () => {
    expect(keyboardMoveTarget(key("ArrowLeft"), 1, 6, 3, true)).toBe(2);
    expect(keyboardMoveTarget(key("ArrowRight"), 1, 6, 3, true)).toBe(0);
  });

  it("ignores other keys and modified arrows", () => {
    expect(keyboardMoveTarget(key("a"), 1, 6, 3)).toBeNull();
    expect(
      keyboardMoveTarget(key("ArrowRight", { altKey: true }), 1, 6, 3),
    ).toBeNull();
    expect(
      keyboardMoveTarget(key("ArrowRight", { ctrlKey: true }), 1, 6, 3),
    ).toBeNull();
  });
});

describe("edgeScrollDelta", () => {
  it("does not scroll in the middle of the area", () => {
    expect(edgeScrollDelta(400, 0, 800)).toBe(0);
  });

  it("scrolls up near the top and down near the bottom, faster at the edge", () => {
    expect(edgeScrollDelta(10, 0, 800)).toBeLessThan(0);
    expect(edgeScrollDelta(790, 0, 800)).toBeGreaterThan(0);
    expect(edgeScrollDelta(0, 0, 800)).toBe(-18);
    expect(edgeScrollDelta(800, 0, 800)).toBe(18);
    expect(Math.abs(edgeScrollDelta(5, 0, 800))).toBeGreaterThan(
      Math.abs(edgeScrollDelta(60, 0, 800)),
    );
  });

  it("caps the speed when the pointer leaves the area", () => {
    expect(edgeScrollDelta(-200, 0, 800)).toBe(-18);
    expect(edgeScrollDelta(1200, 0, 800)).toBe(18);
  });

  it("respects the offset of a scroll container", () => {
    expect(edgeScrollDelta(120, 100, 500)).toBeLessThan(0);
    expect(edgeScrollDelta(300, 100, 500)).toBe(0);
  });
});

describe("long press", () => {
  it("matches the Android long-press timeout", () => {
    expect(LONG_PRESS_MS).toBe(400);
  });
});
