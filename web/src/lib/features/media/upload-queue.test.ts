import { describe, expect, it } from "vitest";
import { uploadSequentially, type UploadProgress } from "./upload-queue";

const file = (name: string) => new File(["x"], name, { type: "image/png" });

describe("uploadSequentially", () => {
  it("uploads in order and reports progress", async () => {
    const order: string[] = [];
    const progress: UploadProgress[] = [];
    const result = await uploadSequentially(
      [file("a.png"), file("b.png")],
      async (f) => {
        order.push(f.name);
      },
      (p) => progress.push({ ...p }),
    );
    expect(order).toEqual(["a.png", "b.png"]);
    expect(result.uploaded).toBe(2);
    expect(result.failures).toEqual([]);
    expect(progress[0]).toEqual({ done: 0, total: 2, current: 1 });
    expect(progress[1]).toEqual({ done: 1, total: 2, current: 2 });
    expect(progress.at(-1)?.done).toBe(2);
  });

  it("continues after a failure and reports it", async () => {
    const boom = new Error("too large");
    const result = await uploadSequentially(
      [file("a.png"), file("b.png"), file("c.png")],
      async (f) => {
        if (f.name === "b.png") throw boom;
      },
    );
    expect(result.uploaded).toBe(2);
    expect(result.failures).toHaveLength(1);
    expect(result.failures[0]?.file.name).toBe("b.png");
    expect(result.failures[0]?.error).toBe(boom);
  });

  it("stops when asked to", async () => {
    let calls = 0;
    const result = await uploadSequentially(
      [file("a.png"), file("b.png")],
      async () => {
        calls += 1;
      },
      undefined,
      () => calls >= 1,
    );
    expect(calls).toBe(1);
    expect(result.uploaded).toBe(1);
  });
});
