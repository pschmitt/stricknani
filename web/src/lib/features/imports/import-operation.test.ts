import { describe, expect, it } from "vitest";
import type { ImportPreview } from "./import-api";
import {
  isCurrentImportOperation,
  type ImportOperationIdentity,
} from "./import-operation";
import type { ImportProjectEditor } from "./import-draft";

function identity(): ImportOperationIdentity {
  return {
    userId: 8,
    userGeneration: 2,
    operationGeneration: 4,
    draft: {} as ImportProjectEditor,
    preview: {} as ImportPreview,
  };
}

function deferred(): { promise: Promise<void>; resolve: () => void } {
  let resolve!: () => void;
  const promise = new Promise<void>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

describe("import operation identity", () => {
  it("ignores a delayed preview after the user replaces the reviewed draft", async () => {
    const response = deferred();
    const captured = identity();
    let current = captured;
    let displayedTitle = "Reviewed title";

    const applyResponse = response.promise.then(() => {
      if (isCurrentImportOperation(captured, current))
        displayedTitle = "Stale preview title";
    });
    current = { ...current, draft: {} as ImportProjectEditor };
    response.resolve();
    await applyResponse;

    expect(displayedTitle).toBe("Reviewed title");
  });

  it("ignores a delayed mutation after the session user changes", async () => {
    const response = deferred();
    const captured = identity();
    let current = captured;
    let createdYarnId: number | null = null;

    const applyResponse = response.promise.then(() => {
      if (isCurrentImportOperation(captured, current)) createdYarnId = 99;
    });
    current = { ...current, userId: 9, userGeneration: 3 };
    response.resolve();
    await applyResponse;

    expect(createdYarnId).toBeNull();
  });

  it("rejects an older action after another request starts on the same draft", () => {
    const captured = identity();
    const current = {
      ...captured,
      operationGeneration: captured.operationGeneration + 1,
    };
    expect(isCurrentImportOperation(captured, current)).toBe(false);
  });
});
