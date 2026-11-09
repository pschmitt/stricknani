import { describe, expect, it } from "vitest";
import type { ImportPreview } from "./import-api";
import {
  importedProjectWrite,
  importedStashIds,
  projectFromImport,
} from "./import-editor";

const preview: ImportPreview = {
  content_sha256: "a".repeat(64),
  ai_requested: false,
  is_ai_enhanced: false,
  ai_fallback: false,
  draft: {
    kind: "plain_text",
    sha256: "a".repeat(64),
    sourceUrl: null,
    image: null,
    yarn: null,
    pageKind: "pattern",
    provenance: {},
    warnings: [],
    project: {
      designer: null,
      difficulty: null,
      sizes: null,
      tags: [],
      name: "Winter shawl",
      description: "A simple shawl.",
      category: "Accessories",
      yarn: null,
      yarns: [
        {
          name: "Blue wool",
          brand: "North Mill",
          colorway: "Lake",
          weight: "100 g",
          length: "210 meters",
          weightCategory: "DK",
          fiberContent: "Wool",
          link: null,
          imageUrl: "https://images.example.test/skein.jpg",
        },
      ],
      needles: "4 mm",
      otherMaterials: null,
      stitchSample: null,
      gaugeWidth: 18,
      gaugeHeight: 24,
      fiberContent: null,
      colorway: null,
      weightCategory: null,
      steps: [
        {
          stepNumber: 3,
          title: "Cast on",
          description: "Cast on 60 stitches.",
          images: ["https://images.example.test/step.jpg"],
        },
        {
          stepNumber: 4,
          title: null,
          description: "Knit.",
          images: [],
        },
      ],
      imageUrls: ["https://images.example.test/cover.jpg"],
      assets: [
        {
          url: "https://images.example.test/drops/22-chart.jpg",
          kind: "diagram",
          label: "Diagram for DROPS 273-22",
        },
        {
          url: "https://patterns.example.test/shawl.pdf",
          kind: "pdf",
          label: null,
        },
      ],
      link: "https://patterns.example.test/shawl",
      brand: null,
      extras: { designer_note: "Work loosely" },
    },
  },
};

function load(source: ImportPreview = preview) {
  let key = 0;
  return projectFromImport(
    source,
    7,
    () => ++key,
    (n) => `Step ${n}`,
  );
}

describe("projectFromImport", () => {
  it("fills the editor's own fields", () => {
    const { draft, state } = load();
    expect(draft).toMatchObject({
      name: "Winter shawl",
      category: "Accessories",
      needles: "4 mm",
      description: "A simple shawl.",
      link: "https://patterns.example.test/shawl",
      yarnIds: [],
    });
    expect(draft.notes).toContain("Gauge width: 18");
    expect(draft.steps.map((step) => step.title)).toEqual([
      "Cast on",
      "Step 4",
    ]);
    expect(state.draftId).toBe(7);
    expect(state.yarns).toHaveLength(1);
    expect(Object.keys(state.stepImages)).toHaveLength(1);
  });
});

describe("importedProjectWrite", () => {
  it("keeps suggestions as links when external images are off", () => {
    const { draft, state } = load();
    const write = importedProjectWrite(draft, state, false);
    expect(write.steps[0]?.step_number).toBe(1);
    expect(write.steps[0]?.image_urls).toBeUndefined();
    expect(write.image_urls).toBeUndefined();
    expect(write.attachment_urls).toBeUndefined();
    expect(write.notes).toContain("Project image URL not attached");
    expect(write.notes).toContain("Step image URL not attached (1)");
    expect(write.notes).toContain(
      "Diagram URL not attached: Diagram for DROPS 273-22 - https://images.example.test/drops/22-chart.jpg",
    );
  });

  it("sends only the selected pictures and files", () => {
    const { draft, state } = load();
    state.imageUrls[0]!.selected = false;
    const stepKey = draft.steps[0]!.key;
    state.stepImages[stepKey]![0]!.selected = true;
    state.assets[1]!.selected = false;
    const write = importedProjectWrite(draft, state, true);
    expect(write.image_urls).toBeUndefined();
    expect(write.steps[0]?.image_urls).toEqual([
      "https://images.example.test/step.jpg",
    ]);
    expect(write.attachment_urls).toEqual([
      {
        url: "https://images.example.test/drops/22-chart.jpg",
        kind: "diagram",
        label: "Diagram for DROPS 273-22",
      },
    ]);
    expect(write.notes).toContain(
      "PDF link not attached: https://patterns.example.test/shawl.pdf",
    );
  });

  it("writes the chosen stash yarns, new yarns and the hand-picked ones", () => {
    const { draft, state } = load();
    const first = state.yarns[0]!;
    state.yarns = [
      { ...first, choice: "stash", stashId: 5 },
      { ...first, name: "Second yarn", choice: "new", amount: "250-300 g" },
      { ...first, name: "Third yarn", choice: "skip" },
    ];
    draft.yarnIds = [5, 9];
    state.isAiEnhanced = true;
    const write = importedProjectWrite(draft, state, true);
    expect(importedStashIds(state)).toEqual([5]);
    expect(write.yarn_ids).toEqual([5, 9]);
    expect(write.new_yarns).toHaveLength(1);
    expect(write.new_yarns![0]).toMatchObject({
      name: "Second yarn",
      weight_grams: 100,
      is_ai_enhanced: true,
      image_urls: ["https://images.example.test/skein.jpg"],
      notes: "Amount: 250-300 g",
    });
    expect(write.is_ai_enhanced).toBe(true);
  });

  it("uses the edits of the editor, not the extraction", () => {
    const { draft, state } = load();
    draft.name = "My shawl";
    draft.steps = draft.steps.slice(1);
    const write = importedProjectWrite(draft, state, true);
    expect(write.name).toBe("My shawl");
    expect(write.steps).toHaveLength(1);
    expect(write.steps[0]?.image_urls).toBeUndefined();
  });
});
