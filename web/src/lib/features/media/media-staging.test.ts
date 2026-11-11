import { describe, expect, it } from "vitest";
import {
  applyStagedImages,
  emptyStaged,
  groupKeyOf,
  hasPendingMedia,
  hasStagedMedia,
  planMediaSave,
  stageAttachmentRemoval,
  stageImageRemoval,
  stageMove,
  stagedRemovalCount,
  unstageAttachmentRemoval,
  unstageImageRemoval,
} from "./media-staging";

const images = [
  { id: 1, step_id: null },
  { id: 2, step_id: null },
  { id: 3, step_id: 7 },
  { id: 4, step_id: null },
  { id: 5, step_id: 7 },
];

describe("staged media", () => {
  it("starts clean and is dirty after a removal or a move", () => {
    expect(hasStagedMedia(emptyStaged())).toBe(false);
    expect(hasStagedMedia(stageImageRemoval(emptyStaged(), 2))).toBe(true);
    expect(hasStagedMedia(stageAttachmentRemoval(emptyStaged(), 9))).toBe(true);
    const moved = stageMove(emptyStaged(), "title", images.slice(0, 2), 0, 1);
    expect(hasStagedMedia(moved)).toBe(true);
  });

  it("is not pending when a photo was moved away and back", () => {
    const title = images.filter((i) => groupKeyOf(i) === "title");
    const away = stageMove(emptyStaged(), "title", title, 0, 1);
    expect(hasPendingMedia(images, away)).toBe(true);
    const back = stageMove(
      away,
      "title",
      [title[1]!, title[0]!, title[2]!],
      1,
      0,
    );
    expect(hasStagedMedia(back)).toBe(true);
    expect(hasPendingMedia(images, back)).toBe(false);
    expect(hasPendingMedia(images, stageImageRemoval(emptyStaged(), 1))).toBe(
      true,
    );
  });

  it("counts removals and undoes them", () => {
    let staged = stageImageRemoval(emptyStaged(), 2);
    staged = stageImageRemoval(staged, 2);
    staged = stageAttachmentRemoval(staged, 9);
    expect(stagedRemovalCount(staged)).toBe(2);
    staged = unstageAttachmentRemoval(unstageImageRemoval(staged, 2), 9);
    expect(hasStagedMedia(staged)).toBe(false);
  });

  it("hides removed photos and keeps the others where they are", () => {
    const staged = stageImageRemoval(emptyStaged(), 2);
    expect(applyStagedImages(images, staged).map((i) => i.id)).toEqual([
      1, 3, 4, 5,
    ]);
  });

  it("applies a staged order to its group only", () => {
    const group = images.filter((i) => groupKeyOf(i) === "title");
    const staged = stageMove(emptyStaged(), "title", group, 0, 2);
    expect(staged.order.title).toEqual([2, 4, 1]);
    expect(applyStagedImages(images, staged).map((i) => i.id)).toEqual([
      2, 4, 3, 1, 5,
    ]);
  });

  it("ignores a move that goes nowhere", () => {
    const staged = emptyStaged();
    expect(stageMove(staged, "title", images, 1, 1)).toBe(staged);
    expect(stageMove(staged, "title", images, 0, 9)).toBe(staged);
  });

  it("plans deletions first and orders without the removed photos", () => {
    let staged = stageMove(
      emptyStaged(),
      "title",
      images.filter((i) => groupKeyOf(i) === "title"),
      0,
      2,
    );
    staged = stageImageRemoval(staged, 4);
    staged = stageAttachmentRemoval(staged, 9);
    const plan = planMediaSave(images, [9, 10], staged);
    expect(plan.deleteImageIds).toEqual([4]);
    expect(plan.deleteAttachmentIds).toEqual([9]);
    expect(plan.orders).toEqual([{ group: "title", ids: [2, 1] }]);
  });

  it("sends no order that matches the server, and skips vanished photos", () => {
    const back = stageMove(
      stageMove(emptyStaged(), "title", images.slice(0, 2), 0, 1),
      "title",
      [images[1]!, images[0]!],
      0,
      1,
    );
    expect(planMediaSave(images, [], back).orders).toEqual([]);
    const gone = stageImageRemoval(emptyStaged(), 99);
    expect(planMediaSave(images, [], gone).deleteImageIds).toEqual([]);
  });

  it("leaves photos of a removed step to the step", () => {
    const staged = stageImageRemoval(emptyStaged(), 3);
    const plan = planMediaSave(images, [], staged, new Set(["step:7"]));
    expect(plan.deleteImageIds).toEqual([]);
  });
});
