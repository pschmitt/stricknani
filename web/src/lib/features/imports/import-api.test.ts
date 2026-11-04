import { afterEach, describe, expect, it, vi } from "vitest";
import {
  MAX_IMPORT_SOURCE_BYTES,
  importImageSrc,
  previewImportFile,
  previewImportText,
  previewImportUrl,
} from "./import-api";

const session = {
  user: {
    id: 5,
    email: "fixture@example.test",
    is_admin: false,
    avatar_url: null,
  },
  csrf_token: "fixture-csrf",
  capabilities: {
    signup: false,
    external_imports: false,
    ai: false,
    ocr: false,
    archive: false,
    media_upload: false,
    media_delete: false,
    import_preview: false,
    archive_state: false,
    account_actions: true,
    admin_actions: false,
  },
  locale: "en" as const,
};

const response = {
  data: {
    content_sha256: "a".repeat(64),
    ai_requested: false,
    is_ai_enhanced: false,
    ai_fallback: false,
    draft: {
      kind: "plain_text",
      sha256: "a".repeat(64),
      sourceUrl: null,
      image: null,
      project: {
        name: "Fixture pattern",
        description: "Cast on 30 stitches.",
        category: null,
        yarn: null,
        yarns: [],
        needles: null,
        otherMaterials: null,
        stitchSample: null,
        gaugeWidth: null,
        gaugeHeight: null,
        fiberContent: null,
        colorway: null,
        weightCategory: null,
        steps: [],
        imageUrls: [],
        link: null,
        brand: null,
        extras: {},
      },
    },
  },
};

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("import preview API", () => {
  it("sends text through the same-origin API with AI explicitly off", async () => {
    const fetchMock = vi.fn().mockResolvedValue(Response.json(response));
    vi.stubGlobal("fetch", fetchMock);

    const result = await previewImportText("A test pattern", false, session);

    expect(fetchMock.mock.calls[0]?.[0]).toBe("/api/v2/imports/preview");
    const init = fetchMock.mock.calls[0]?.[1] as RequestInit;
    expect(init.method).toBe("POST");
    expect(new Headers(init.headers).get("x-csrf-token")).toBe("fixture-csrf");
    expect(JSON.parse(String(init.body))).toEqual({
      type: "text",
      text: "A test pattern",
      use_ai: false,
    });
    expect(result.draft.project.name).toBe("Fixture pattern");
    expect(result.ai_fallback).toBe(false);
  });

  it("sends a file as multipart form data and rejects files over 5 MiB before fetch", async () => {
    const fetchMock = vi.fn().mockResolvedValue(Response.json(response));
    vi.stubGlobal("fetch", fetchMock);
    const file = new File(["<p>Pattern</p>"], "pattern.html", {
      type: "text/html",
    });

    await previewImportFile(file, false, session);

    expect(fetchMock.mock.calls[0]?.[0]).toBe("/api/v2/imports/preview-file");
    const init = fetchMock.mock.calls[0]?.[1] as RequestInit;
    expect(init.body).toBeInstanceOf(FormData);
    expect((init.body as FormData).get("file")).toBe(file);
    expect((init.body as FormData).get("use_ai")).toBe("false");

    await expect(
      previewImportFile(
        new File([new Uint8Array(MAX_IMPORT_SOURCE_BYTES + 1)], "large.pdf"),
        false,
        session,
      ),
    ).rejects.toMatchObject({ status: 413, code: "payload_too_large" });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("sends the import target for links and reads yarn details and provenance", async () => {
    const withYarn = structuredClone(response);
    Object.assign(withYarn.data.draft, {
      pageKind: "yarn",
      provenance: { "yarn.brand": "structured", "yarn.care": "ai", bad: "x" },
      warnings: ["no_structured_data"],
      yarn: { name: "Air", brand: "Drops", weightGrams: 50, imageUrls: [] },
    });
    const fetchMock = vi.fn().mockResolvedValue(Response.json(withYarn));
    vi.stubGlobal("fetch", fetchMock);

    const result = await previewImportUrl(
      "https://shop.example.test/air",
      true,
      session,
      undefined,
      "yarn",
    );

    const init = fetchMock.mock.calls[0]?.[1] as RequestInit;
    expect(JSON.parse(String(init.body))).toEqual({
      type: "url",
      url: "https://shop.example.test/air",
      use_ai: true,
      target: "yarn",
    });
    expect(result.draft.pageKind).toBe("yarn");
    expect(result.draft.yarn?.weightGrams).toBe(50);
    expect(result.draft.yarn?.care).toBeNull();
    expect(result.draft.provenance).toEqual({
      "yarn.brand": "structured",
      "yarn.care": "ai",
    });
    expect(result.draft.warnings).toEqual(["no_structured_data"]);
    // Older servers omit the new fields.
    expect(result.draft.project.designer).toBeNull();
  });

  it("builds a same-origin thumbnail URL", () => {
    expect(importImageSrc("https://a.example/x.jpg?w=1&h=2")).toBe(
      "/api/v2/imports/image?url=https%3A%2F%2Fa.example%2Fx.jpg%3Fw%3D1%26h%3D2",
    );
  });
});
