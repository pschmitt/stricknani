import { describe, expect, it, vi } from "vitest";
import { shareableUrl, shareLink } from "./share-link";

const url = "https://example.test/app/projects/7";

describe("shareLink", () => {
  it("uses the share sheet when available", async () => {
    const share = vi.fn().mockResolvedValue(undefined);
    const writeText = vi.fn();
    await expect(
      shareLink(url, "Sock", { share, clipboard: { writeText } }),
    ).resolves.toBe("shared");
    expect(share).toHaveBeenCalledWith({ title: "Sock", url });
    expect(writeText).not.toHaveBeenCalled();
  });

  it("treats a dismissed share sheet as cancelled, not as a failure", async () => {
    const share = vi
      .fn()
      .mockRejectedValue(new DOMException("dismissed", "AbortError"));
    const writeText = vi.fn();
    await expect(
      shareLink(url, "Sock", { share, clipboard: { writeText } }),
    ).resolves.toBe("cancelled");
    expect(writeText).not.toHaveBeenCalled();
  });

  it("copies the link when there is no share sheet", async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    await expect(
      shareLink(url, "Sock", { clipboard: { writeText } }),
    ).resolves.toBe("copied");
    expect(writeText).toHaveBeenCalledWith(url);
  });

  it("copies the link when the share sheet fails", async () => {
    const share = vi.fn().mockRejectedValue(new Error("not allowed"));
    const writeText = vi.fn().mockResolvedValue(undefined);
    await expect(
      shareLink(url, "Sock", { share, clipboard: { writeText } }),
    ).resolves.toBe("copied");
  });

  it("fails when neither works", async () => {
    await expect(shareLink(url, "Sock", {})).resolves.toBe("failed");
    const writeText = vi.fn().mockRejectedValue(new Error("denied"));
    await expect(
      shareLink(url, "Sock", { clipboard: { writeText } }),
    ).resolves.toBe("failed");
  });
});

describe("shareableUrl", () => {
  it("drops query and fragment", () => {
    expect(
      shareableUrl(new URL("https://example.test/app/yarns/3?x=1#top")),
    ).toBe("https://example.test/app/yarns/3");
  });
});
