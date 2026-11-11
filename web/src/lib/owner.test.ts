import { describe, expect, it } from "vitest";
import { foreignOwner, nameFromEmail, ownerField, parseOwner } from "./owner";

const anika = {
  id: 7,
  email: "anika@example.test",
  name: "Anika",
  avatar_url: "/api/v2/admin/users/7/avatar/gravatar",
};

describe("resource owner", () => {
  it("parses the server owner object", () => {
    expect(parseOwner(anika)).toEqual(anika);
  });

  it("derives a name from the email and drops foreign avatar URLs", () => {
    expect(
      parseOwner({
        id: 3,
        email: "bo@example.test",
        name: " ",
        avatar_url: "https://evil.test/a.png",
      }),
    ).toEqual({
      id: 3,
      email: "bo@example.test",
      name: "Bo",
      avatar_url: null,
    });
  });

  it("ignores missing or malformed owners", () => {
    expect(parseOwner(undefined)).toBeNull();
    expect(parseOwner({ id: "7", email: "a@b" })).toBeNull();
    expect(parseOwner({ id: 0, email: "a@b" })).toBeNull();
    expect(ownerField(null)).toEqual({});
    expect(ownerField(anika)).toEqual({ owner: anika });
  });

  it("is foreign only for another user's resource", () => {
    expect(foreignOwner(anika, 7)).toBeNull();
    expect(foreignOwner(anika, 1)).toEqual(anika);
    expect(foreignOwner(anika, null)).toBeNull();
    expect(foreignOwner(null, 1)).toBeNull();
    expect(foreignOwner(undefined, 1)).toBeNull();
  });
});

describe("nameFromEmail", () => {
  it("turns the local part into a readable name", () => {
    expect(nameFromEmail("anika.bergmann@example.test")).toBe("Anika Bergmann");
    expect(nameFromEmail("play_r-bw@example.test")).toBe("Play R Bw");
    expect(nameFromEmail("ai@example.test")).toBe("Ai");
    expect(nameFromEmail("..@example.test")).toBe("..@example.test");
  });
});
