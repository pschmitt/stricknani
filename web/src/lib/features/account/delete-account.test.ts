import { describe, expect, it } from "vitest";
import { confirmationMatches } from "./delete-account.ts";

describe("confirmationMatches", () => {
  const email = "Knitter@Example.test";

  it("accepts DELETE exactly and the email in any case", () => {
    expect(confirmationMatches("DELETE", email)).toBe(true);
    expect(confirmationMatches("  DELETE  ", email)).toBe(true);
    expect(confirmationMatches("knitter@example.TEST", email)).toBe(true);
  });

  it("rejects anything else, including an empty box", () => {
    expect(confirmationMatches("", email)).toBe(false);
    expect(confirmationMatches("   ", email)).toBe(false);
    expect(confirmationMatches("delete", email)).toBe(false);
    expect(confirmationMatches("other@example.test", email)).toBe(false);
  });
});
