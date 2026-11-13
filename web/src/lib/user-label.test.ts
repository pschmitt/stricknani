import { describe, expect, it } from "vitest";
import {
  accountUser,
  activityActor,
  nameUser,
  personUser,
  historyActor,
  sentenceParts,
  subjectUser,
  userByText,
  userLabelText,
} from "./user-label";

describe("userLabelText", () => {
  it("says You, the name, an administrator, System or Unknown", () => {
    const ref = (kind: Parameters<typeof userLabelText>[0]["kind"]) => ({
      kind,
      name: "Anika Bergmann",
      avatarUrl: null,
    });
    expect(userLabelText(ref("self"), "en")).toBe("You");
    expect(userLabelText(ref("user"), "en")).toBe("Anika Bergmann");
    expect(userLabelText(ref("admin"), "en")).toBe("an administrator");
    expect(userLabelText(ref("system"), "en")).toBe("System");
    expect(userLabelText(ref("unknown"), "en")).toBe("Unknown");
    expect(userLabelText({ ...ref("user"), name: " " }, "en")).toBe("Unknown");
    expect(userByText(ref("self"), "en")).toBe("You");
    expect(userByText(ref("system"), "en")).toBe("System");
    expect(userByText(ref("unknown"), "en")).toBe("Unknown");
    expect(userByText(ref("user"), "en")).toBe("Anika Bergmann");
    expect(userByText(ref("admin"), "en")).toBe("an administrator");
  });
});

describe("historyActor", () => {
  const base = {
    actor_email: null,
    actor_avatar_url: null,
  };
  it("is You for the viewer's own actions, whatever the email says", () => {
    expect(
      historyActor({ ...base, actor_is_self: true, actor_name: "Philipp" }),
    ).toMatchObject({ kind: "self" });
  });

  it("uses the display name, never the email", () => {
    const actor = historyActor({
      ...base,
      actor_email: "anika.bergmann@mailbox.org",
      actor_name: "Anika Bergmann",
      actor_avatar_url: "/a.png",
    });
    expect(actor).toEqual({
      kind: "user",
      name: "Anika Bergmann",
      avatarUrl: "/a.png",
    });
    // An older server sends the email only: the shared fallback names it.
    expect(
      historyActor({ ...base, actor_email: "anika.bergmann@mailbox.org" }).name,
    ).toBe("Anika Bergmann");
  });

  it("is System for the server and Unknown for a legacy row", () => {
    expect(historyActor({ ...base, system: true }).kind).toBe("system");
    expect(historyActor({ ...base }).kind).toBe("unknown");
    expect(historyActor({ ...base, actor_name: "  " }).kind).toBe("unknown");
  });
});

describe("activityActor", () => {
  it("keeps names and avatars for people only", () => {
    expect(
      activityActor({
        kind: "admin",
        name: "Leak",
        is_self: false,
        avatar_url: "/x.png",
      }),
    ).toEqual({ kind: "admin", name: null, avatarUrl: null });
    expect(
      activityActor({
        kind: "user",
        name: "Anna",
        is_self: false,
        avatar_url: "/x.png",
      }),
    ).toEqual({ kind: "user", name: "Anna", avatarUrl: "/x.png" });
  });
});

describe("subjectUser and sentenceParts", () => {
  it("names a deleted account and splits a sentence at its people", () => {
    expect(subjectUser(null)).toBeNull();
    expect(subjectUser({ name: null }, "en")?.name).toBe("a deleted account");
    const owner = subjectUser({ name: "Anna", avatar_url: "/a.png" }, "en");
    expect(owner).toEqual({ kind: "user", name: "Anna", avatarUrl: "/a.png" });
    expect(sentenceParts("Owner: {name}", { name: owner! })).toEqual([
      { text: "Owner: " },
      { user: owner },
    ]);
    const admin = { kind: "admin", name: null, avatarUrl: null } as const;
    expect(
      sentenceParts("by {admin} as {user}", { admin, user: owner! }),
    ).toEqual([
      { text: "by " },
      { user: admin },
      { text: " as " },
      { user: owner },
    ]);
    expect(sentenceParts("x {other}", {})).toEqual([
      { text: "x " },
      { text: "{other}" },
    ]);
  });
});

describe("person helpers", () => {
  it("shows owners, shares and picked people by name, the viewer as You", () => {
    const anna = {
      id: 4,
      name: "Anna",
      avatar_url: "/api/v2/people/4/avatar/g",
    };
    expect(personUser(anna)).toEqual({
      kind: "user",
      name: "Anna",
      avatarUrl: "/api/v2/people/4/avatar/g",
    });
    expect(personUser(anna, 4).kind).toBe("self");
    expect(personUser(anna, 5).kind).toBe("user");
    // Only same-origin paths become images.
    expect(
      personUser({ ...anna, avatar_url: "//evil.example/a.png" }).avatarUrl,
    ).toBeNull();
  });

  it("derives the name of an email-identified account, never showing the email", () => {
    expect(
      accountUser({
        email: "anika.bergmann@example.invalid",
        avatar_url: null,
      }),
    ).toEqual({ kind: "user", name: "Anika Bergmann", avatarUrl: null });
    expect(
      accountUser({
        email: "x@example.invalid",
        name: "Xandra",
        avatar_url: "/a",
      }).name,
    ).toBe("Xandra");
  });

  it("falls back to Unknown for a nameless person", () => {
    expect(nameUser(" ").kind).toBe("unknown");
    expect(nameUser("Bea", "/b.png")).toEqual({
      kind: "user",
      name: "Bea",
      avatarUrl: "/b.png",
    });
  });
});
