import { describe, expect, it } from "vitest";
import {
  mergeArchiveDetailPreservingFavorite,
  mergeFavoriteMutationIntoCurrent,
} from "./reader-state";

interface TestProject {
  id: number;
  is_favorite: boolean;
  link_archive: string | null;
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((accept) => {
    resolve = accept;
  });
  return { promise, resolve };
}

describe("archive detail refresh and favorite races", () => {
  it("keeps an archive link merged while a favorite mutation is pending", async () => {
    const favoriteRequest = deferred<boolean>();
    const oldProject: TestProject = {
      id: 12,
      is_favorite: false,
      link_archive: null,
    };
    let latestProject = oldProject;
    const favoriteCompletion = favoriteRequest.promise.then((favorite) =>
      mergeFavoriteMutationIntoCurrent(latestProject, favorite),
    );

    latestProject = {
      ...latestProject,
      link_archive: "https://archive.example.test/item",
    };
    favoriteRequest.resolve(true);

    await expect(favoriteCompletion).resolves.toEqual({
      id: 12,
      is_favorite: true,
      link_archive: "https://archive.example.test/item",
    });
  });

  it("keeps a favorite update that completes while the detail GET is pending", async () => {
    const currentProject: TestProject = {
      id: 12,
      is_favorite: false,
      link_archive: null,
    };
    const request = deferred<TestProject>();
    const stateAtRequest = { generation: 3, busy: false };
    let latestProject = currentProject;
    let latestState = stateAtRequest;

    const refreshedProject = request.promise.then((refreshed) =>
      mergeArchiveDetailPreservingFavorite(
        refreshed,
        latestProject,
        stateAtRequest,
        latestState,
      ),
    );

    latestProject = { ...latestProject, is_favorite: true };
    latestState = { generation: 4, busy: false };
    request.resolve({
      id: 12,
      is_favorite: false,
      link_archive: "https://archive.example.test/item",
    });

    await expect(refreshedProject).resolves.toEqual({
      id: 12,
      is_favorite: true,
      link_archive: "https://archive.example.test/item",
    });
  });

  it("preserves favorite state for an in-flight mutation while merging archive fields", () => {
    expect(
      mergeArchiveDetailPreservingFavorite(
        {
          id: 12,
          is_favorite: false,
          link_archive: "https://archive.example.test/item",
        },
        { id: 12, is_favorite: true, link_archive: null },
        { generation: 4, busy: true },
        { generation: 4, busy: true },
      ),
    ).toEqual({
      id: 12,
      is_favorite: true,
      link_archive: "https://archive.example.test/item",
    });
  });

  it("uses refreshed favorite state when no favorite mutation overlaps", () => {
    expect(
      mergeArchiveDetailPreservingFavorite(
        {
          id: 12,
          is_favorite: true,
          link_archive: "https://archive.example.test/item",
        },
        { id: 12, is_favorite: false, link_archive: null },
        { generation: 4, busy: false },
        { generation: 4, busy: false },
      ).is_favorite,
    ).toBe(true);
  });
});
