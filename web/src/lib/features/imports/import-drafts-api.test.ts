import { describe, expect, it } from "vitest";
import { ApiError } from "#lib/api.ts";
import {
  draftIdFromSearch,
  draftFailureMessage,
  importStartMessage,
  parseImportDraft,
} from "./import-drafts-api";

const running = {
  id: 4,
  target: "project",
  source_kind: "url",
  source_url: "https://example.test/pattern",
  use_ai: true,
  status: "running",
  title: null,
  image_url: null,
  created_at: 1_700_000_000,
  finished_at: null,
};

describe("parseImportDraft", () => {
  it("reads a running draft without a preview", () => {
    const draft = parseImportDraft(running);
    expect(draft).toMatchObject({
      id: 4,
      target: "project",
      status: "running",
      title: null,
      preview: null,
      error: null,
    });
  });

  it("reads the error body of a failed draft", () => {
    const draft = parseImportDraft({
      ...running,
      status: "failed",
      finished_at: 1_700_000_050,
      error_status: 422,
      error: { error: { code: "fetch_failed", message: "Page not found." } },
    });
    expect(draft.status).toBe("failed");
    expect(draft.error).toEqual({
      code: "fetch_failed",
      message: "Page not found.",
    });
    expect(draftFailureMessage(draft, "en")).toBe("Page not found.");
  });

  it("falls back to a generic line for a failure without a body", () => {
    const draft = parseImportDraft({ ...running, status: "failed" });
    expect(draftFailureMessage(draft, "en")).toContain("did not work");
  });

  it("rejects an unknown status", () => {
    expect(() => parseImportDraft({ ...running, status: "done" })).toThrow(
      ApiError,
    );
    expect(() => parseImportDraft(null)).toThrow(ApiError);
  });
});

describe("draftIdFromSearch", () => {
  it("accepts only a positive whole number", () => {
    expect(draftIdFromSearch("?draft=12")).toBe(12);
    expect(draftIdFromSearch("?draft=0")).toBeNull();
    expect(draftIdFromSearch("?draft=abc")).toBeNull();
    expect(draftIdFromSearch("?draft=1.5")).toBeNull();
    expect(draftIdFromSearch("")).toBeNull();
  });
});

describe("importStartMessage", () => {
  it("explains the limits of the server", () => {
    expect(
      importStartMessage(new ApiError("x", 429, "too_many_imports"), "en"),
    ).toContain("Three imports");
    expect(
      importStartMessage(new ApiError("x", 409, "too_many_drafts"), "en"),
    ).toContain("waiting for review");
    expect(
      importStartMessage(
        new ApiError("x", 429, "rate_limited", undefined, undefined, 12),
        "en",
      ),
    ).toContain("12 seconds");
  });
});
