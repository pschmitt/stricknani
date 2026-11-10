import { describe, expect, it } from "vitest";
import {
  blankProjectDraft,
  projectDraftFromDetail,
  projectWriteFromDraft,
  validateProjectDraft,
} from "./project-editor-state";
import type { ProjectDetail } from "./project-api";

const project: ProjectDetail = {
  id: 42,
  name: "River path socks",
  category: "Socks",
  tags: ["cables", "weekend"],
  is_favorite: false,
  updated_at: "2026-10-31T12:00:00.000000Z",
  preview_url: null,
  yarn: "Legacy wool note",
  needles: "3.5 mm",
  stitch_sample: "24 stitches over 10 cm",
  stitch_sample_html: null,
  description: "**Knit in the round.**",
  description_html: "<p><strong>Knit in the round.</strong></p>\n",
  notes: "Check the cuff length.",
  notes_html: "<p>Check the cuff length.</p>\n",
  other_materials: null,
  other_materials_html: null,
  link: "https://patterns.example.test/socks",
  link_archive: "https://archive.example.test/socks",
  is_ai_enhanced: true,
  created_at: "2026-10-30T12:00:00.000000Z",
  yarn_ids: [7, 8],
  steps: [
    {
      id: 81,
      title: "Cuff",
      description: "Work the ribbing.",
      description_html: "<p>Work the ribbing.</p>\n",
      step_number: 2,
    },
    {
      id: 80,
      title: "Cast on",
      description: null,
      description_html: null,
      step_number: 1,
    },
  ],
  images: [],
  attachments: [],
};

describe("project editor draft conversion", () => {
  it("loads markdown and fields without putting sanitized HTML in editable text", () => {
    let key = 0;
    const draft = projectDraftFromDetail(project, () => ++key);

    expect(draft).toMatchObject({
      name: "River path socks",
      category: "Socks",
      description: "**Knit in the round.**",
      notes: "Check the cuff length.",
      yarnIds: [7, 8],
    });
    expect(draft.description).not.toContain("<strong>");
    expect(draft.steps.map((step) => [step.id, step.title])).toEqual([
      [80, "Cast on"],
      [81, "Cuff"],
    ]);
    expect(new Set(draft.steps.map((step) => step.key)).size).toBe(2);
  });

  it("keeps the list key of a saved step across a reload", () => {
    let key = 100;
    const draft = projectDraftFromDetail(
      project,
      () => ++key,
      (stepId) => (stepId === 81 ? 7 : undefined),
    );

    expect(draft.steps.map((step) => [step.id, step.key])).toEqual([
      [80, 101],
      [81, 7],
    ]);
  });

  it("serializes edited fields, exact tags, selected yarns, and ordered steps", () => {
    const draft = blankProjectDraft();
    draft.name = "  River socks  ";
    draft.category = " Socks ";
    draft.tags = ["Cables", "weekend"];
    draft.yarnIds = [7, 7, 8];
    draft.steps = [
      { key: 10, id: 81, title: "Cuff", description: "  Work ribbing. " },
      { key: 11, title: "Toe", description: "Close the stitches." },
    ];

    expect(projectWriteFromDraft(draft, true)).toMatchObject({
      name: "River socks",
      category: "Socks",
      tags: ["Cables", "weekend"],
      yarn_ids: [7, 8],
      is_ai_enhanced: true,
      steps: [
        {
          id: 81,
          title: "Cuff",
          description: "Work ribbing.",
          step_number: 1,
        },
        {
          title: "Toe",
          description: "Close the stitches.",
          step_number: 2,
        },
      ],
    });
  });

  it("round-trips tag labels containing commas without changing their contents", () => {
    const projectWithCommaTag = {
      ...project,
      tags: ["color, texture", "  label with surrounding spaces  "],
    };
    const draft = projectDraftFromDetail(projectWithCommaTag, () => 1);

    expect(draft.tags).toEqual([
      "color, texture",
      "  label with surrounding spaces  ",
    ]);
    expect(projectWriteFromDraft(draft, false).tags).toEqual([
      "color, texture",
      "  label with surrounding spaces  ",
    ]);
  });

  it("validates names, external URL schemes, and every ordered step title", () => {
    const draft = blankProjectDraft();
    draft.link = "javascript:alert(1)";
    draft.steps = [
      { key: 1, title: "", description: "Details" },
      { key: 2, title: "Second", description: "Details" },
    ];
    expect(validateProjectDraft(draft)).toEqual({
      name: "required",
      link: "invalid_url",
      steps: "invalid_steps",
    });

    draft.name = "A project";
    draft.link = "https://example.test/pattern";
    draft.steps[0]!.title = "Cast on";
    expect(validateProjectDraft(draft)).toEqual({});
  });
});
