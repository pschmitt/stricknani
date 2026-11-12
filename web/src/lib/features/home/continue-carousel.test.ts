import { describe, expect, it } from "vitest";
import type { ProjectSummary } from "#lib/features/projects/project-api.ts";
import {
  CONTINUE_LIMIT,
  activeSlide,
  continueProjects,
  slideForKey,
} from "./continue-carousel";

function project(id: number, updated_at: string): ProjectSummary {
  return {
    id,
    name: `Project ${id}`,
    category: null,
    tags: [],
    is_favorite: false,
    status: "in_progress",
    updated_at,
    preview_url: null,
  } as ProjectSummary;
}

describe("continue carousel", () => {
  it("orders by most recently updated and keeps ties in server order", () => {
    const ordered = continueProjects([
      project(1, "2026-11-01T10:00:00Z"),
      project(2, "2026-11-05T10:00:00Z"),
      project(3, "2026-11-05T10:00:00Z"),
      project(4, "2026-11-03T10:00:00Z"),
    ]);
    expect(ordered.map((item) => item.id)).toEqual([2, 3, 4, 1]);
  });

  it("keeps admin-role projects returned by the mine-scope loader", () => {
    const directlySharedWithAdmin = {
      ...project(3, "2026-11-06T10:00:00Z"),
      access: {
        role: "admin" as const,
        derived: false,
        can_edit: true,
        can_manage: true,
      },
    };

    expect(
      continueProjects([directlySharedWithAdmin]).map((item) => item.id),
    ).toEqual([3]);
  });

  it("is empty for no in-progress projects and caps a long list", () => {
    expect(continueProjects([])).toEqual([]);
    const many = Array.from({ length: CONTINUE_LIMIT + 5 }, (_, index) =>
      project(
        index + 1,
        `2026-10-${String(index + 1).padStart(2, "0")}T00:00:00Z`,
      ),
    );
    const shown = continueProjects(many);
    expect(shown).toHaveLength(CONTINUE_LIMIT);
    expect(shown[0]?.id).toBe(CONTINUE_LIMIT + 5);
  });

  it("finds the card nearest the scroll position and the last at the end", () => {
    const offsets = [0, 300, 600, 900];
    expect(activeSlide(offsets, 0, 340, 1240)).toBe(0);
    expect(activeSlide(offsets, 140, 340, 1240)).toBe(0);
    expect(activeSlide(offsets, 170, 340, 1240)).toBe(1);
    expect(activeSlide(offsets, 900, 340, 1240)).toBe(3);
    expect(activeSlide(offsets, 880, 340, 1240)).toBe(3);
    expect(activeSlide([], 0, 340, 340)).toBe(0);
  });

  it("maps arrow keys to the neighbour and stops at the ends", () => {
    expect(slideForKey("ArrowRight", 0, 3)).toBe(1);
    expect(slideForKey("ArrowRight", 2, 3)).toBe(2);
    expect(slideForKey("ArrowLeft", 0, 3)).toBe(0);
    expect(slideForKey("ArrowLeft", 2, 3)).toBe(1);
    expect(slideForKey("Home", 2, 3)).toBe(0);
    expect(slideForKey("End", 0, 3)).toBe(2);
    expect(slideForKey("ArrowRight", 1, 3, true)).toBe(0);
    expect(slideForKey("ArrowLeft", 1, 3, true)).toBe(2);
    expect(slideForKey("a", 0, 3)).toBeNull();
    expect(slideForKey("ArrowRight", 0, 0)).toBeNull();
  });
});
