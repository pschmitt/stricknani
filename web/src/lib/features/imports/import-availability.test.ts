import { describe, expect, it } from "vitest";
import { importAvailability } from "./import-availability";

describe("importAvailability", () => {
  it("allows both when the server offers both", () => {
    expect(
      importAvailability({
        external_imports: true,
        ai: true,
        import_preview: true,
      }),
    ).toEqual({ url: true, ai: true });
  });

  it("turns AI off on its own", () => {
    expect(
      importAvailability({
        external_imports: true,
        ai: false,
        import_preview: true,
      }),
    ).toEqual({ url: true, ai: false });
  });

  it("turns link imports off on their own", () => {
    expect(
      importAvailability({
        external_imports: false,
        ai: true,
        import_preview: true,
      }),
    ).toEqual({ url: false, ai: true });
  });

  it("needs the import service for either", () => {
    expect(
      importAvailability({
        external_imports: true,
        ai: true,
        import_preview: false,
      }),
    ).toEqual({ url: false, ai: false });
  });

  it("treats a missing session as everything off", () => {
    expect(importAvailability(undefined)).toEqual({ url: false, ai: false });
    expect(importAvailability(null)).toEqual({ url: false, ai: false });
  });
});
