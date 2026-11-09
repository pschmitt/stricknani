import { describe, expect, it } from "vitest";
import { readImpersonateId, withoutImpersonate } from "./impersonate-link";

describe("readImpersonateId", () => {
  it("reads a positive integer id", () => {
    expect(readImpersonateId("7")).toBe(7);
    expect(readImpersonateId("1234")).toBe(1234);
  });

  it("rejects everything else", () => {
    for (const value of [
      null,
      "",
      "0",
      "-3",
      "1.5",
      "07",
      "abc",
      "1e3",
      "7 ",
      "99999999999999999999",
    ]) {
      expect(readImpersonateId(value)).toBeNull();
    }
  });
});

describe("withoutImpersonate", () => {
  it("drops only the impersonate parameter", () => {
    expect(withoutImpersonate("?impersonate=7&page=2")).toBe("page=2");
    expect(withoutImpersonate("?impersonate=7")).toBe("");
    expect(withoutImpersonate("")).toBe("");
  });
});
