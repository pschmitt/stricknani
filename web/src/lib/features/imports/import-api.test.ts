import { afterEach, describe, expect, it, vi } from "vitest";
import {
  MAX_IMPORT_SOURCE_BYTES,
  previewImportFile,
  previewImportText,
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
});
