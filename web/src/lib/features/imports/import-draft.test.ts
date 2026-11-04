import { describe, expect, it } from "vitest";
import type { ImportPreview } from "./import-api";
import {
  createProjectWrite,
  createYarnImportWrite,
  createYarnWrite,
  fieldHint,
  importProjectEditor,
  importWarningMessage,
  isHttpUrl,
  parseMeasurement,
  yarnImportEditor,
} from "./import-draft";

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
      ],
      imageUrls: ["https://images.example.test/cover.jpg"],
      link: "https://patterns.example.test/shawl",
      brand: null,
      extras: { designer_note: "Work loosely" },
    },
  },
};

describe("import review mapping", () => {
  it("keeps extracted fields editable and preserves image suggestions when disabled", () => {
    const editor = importProjectEditor(preview);
    expect(editor.name).toBe("Winter shawl");
    expect(editor.steps[0]?.stepNumber).toBe(3);
    expect(editor.additionalDetails).toContain("Gauge width: 18");
    expect(editor.additionalDetails).toContain("designer_note: Work loosely");

    const write = createProjectWrite(
      editor,
      false,
      false,
      (number) => `Step ${number}`,
    );
    expect(write.steps[0]?.step_number).toBe(3);
    expect(write.steps[0]?.image_urls).toBeUndefined();
    expect(write.image_urls).toBeUndefined();
    expect(write.notes).toContain("Project image URL not attached");
    expect(write.notes).toContain("Step image URL not attached (3)");
  });

  it("passes only explicitly selected suggestions when server capability is enabled", () => {
    const editor = importProjectEditor(preview);
    editor.imageUrls[0]!.selected = false;
    editor.steps[0]!.images[0]!.selected = true;
    const write = createProjectWrite(
      editor,
      true,
      true,
      (number) => `Step ${number}`,
    );
    expect(write.image_urls).toBeUndefined();
    expect(write.steps[0]?.image_urls).toEqual([
      "https://images.example.test/step.jpg",
    ]);
    expect(write.is_ai_enhanced).toBe(true);
  });

  it("keeps unparsed yarn measurements and suggested image URLs in notes", () => {
    const yarn = importProjectEditor(preview).yarns[0]!;
    expect(yarn.weightGrams).toBe("100");
    expect(yarn.lengthMeters).toBe("210");
    expect(createYarnWrite(yarn, false)).toMatchObject({
      weight_grams: 100,
      length_meters: 210,
      notes: expect.stringContaining("Imported image URL suggestion"),
    });
    expect(parseMeasurement("about 100 g", "g")).toBe("");
  });

  it("accepts only credential-free HTTP(S) URLs", () => {
    expect(isHttpUrl("https://example.test/pattern")).toBe(true);
    expect(isHttpUrl("javascript:alert(1)")).toBe(false);
    expect(isHttpUrl("https://user:pass@example.test")).toBe(false);
  });
});

describe("yarn import review mapping", () => {
  const yarnPreview: ImportPreview = {
    ...preview,
    is_ai_enhanced: true,
    draft: {
      ...preview.draft,
      kind: "html",
      sourceUrl: "https://shop.example.test/air",
      pageKind: "yarn",
      provenance: { "yarn.brand": "structured", "yarn.fiberContent": "ai" },
      warnings: ["looks_like_pattern_page", "ravelry_api_failed"],
      yarn: {
        name: "DROPS Air",
        brand: "DROPS Design",
        colorway: "Natur",
        description: "Airy alpaca blend.",
        fiberContent: "65% Alpaca, 28% Polyamide, 7% Wool",
        weightCategory: "Aran",
        weightGrams: 50,
        lengthMeters: 150,
        recommendedNeedles: "5 mm",
        gauge: "17 sts = 10 cm",
        care: "Hand wash",
        price: "4.50 EUR",
        link: null,
        imageUrls: ["https://a.example/1.jpg", "https://a.example/2.jpg"],
      },
    },
  };

  it("maps yarn details to an editor and keeps gauge, care and price in notes", () => {
    const editor = yarnImportEditor(yarnPreview);
    expect(editor.weightGrams).toBe("50");
    expect(editor.lengthMeters).toBe("150");
    expect(editor.link).toBe("https://shop.example.test/air");
    expect(editor.notes).toContain("Price: 4.50 EUR");
    expect(editor.images.every((image) => image.selected)).toBe(true);
  });

  it("sends only selected photos and only when external images are allowed", () => {
    const editor = yarnImportEditor(yarnPreview);
    editor.images[1]!.selected = false;
    const write = createYarnImportWrite(editor, true, true);
    expect(write.image_urls).toEqual(["https://a.example/1.jpg"]);
    expect(write).toMatchObject({
      name: "DROPS Air",
      weight_grams: 50,
      recommended_needles: "5 mm",
      is_ai_enhanced: true,
    });
    expect(
      createYarnImportWrite(editor, true, false).image_urls,
    ).toBeUndefined();
  });

  it("flags only guessed and AI values and explains known warnings", () => {
    const provenance = { a: "ai", b: "text", c: "structured", d: "site" };
    expect(fieldHint(provenance, "a")).toBe("ai");
    expect(fieldHint(provenance, "b")).toBe("guessed");
    expect(fieldHint(provenance, "c")).toBe("");
    expect(fieldHint(provenance, "missing")).toBe("");
    expect(importWarningMessage("looks_like_yarn_page", "en")).toContain(
      "yarn",
    );
    expect(importWarningMessage("ravelry_api_failed", "en")).toBeNull();
  });
});
