import { render } from "svelte/server";
import { afterEach, describe, expect, it } from "vitest";
import type { SessionData } from "#lib/api.ts";
import { session } from "#lib/session.svelte.ts";
import ForeignOwnerBanner from "./ForeignOwnerBanner.svelte";
import OwnerBadge from "./OwnerBadge.svelte";
import ScopeFilter from "./ScopeFilter.svelte";

const owner = {
  id: 7,
  email: "anika@example.test",
  name: "Anika",
  avatar_url: null,
};

function sessionFor(isAdmin: boolean): SessionData {
  return {
    user: {
      id: 1,
      email: "me@example.test",
      is_admin: isAdmin,
      avatar_url: null,
    },
    csrf_token: "t",
    capabilities: {} as SessionData["capabilities"],
    locale: "en",
  };
}

afterEach(() => {
  session.data = null;
});

describe("OwnerBadge", () => {
  it("names the owner for assistive technology and as a tooltip", () => {
    const { body } = render(OwnerBadge, { props: { owner } });
    expect(body).toContain('aria-label="Owned by Anika"');
    expect(body).toContain('title="Owned by Anika"');
    expect(body).toContain(">A<");
  });
});

describe("ForeignOwnerBanner", () => {
  it("says whose project it is and that changes are audited", () => {
    const { body } = render(ForeignOwnerBanner, {
      props: { kind: "project", owner },
    });
    expect(body).toContain('role="note"');
    expect(body).toContain("Anika's project");
    expect(body).toContain(
      "Your changes are saved to their project and recorded in the audit log.",
    );
  });

  it("has a yarn variant", () => {
    const { body } = render(ForeignOwnerBanner, {
      props: { kind: "yarn", owner },
    });
    expect(body).toContain("Anika's yarn");
    expect(body).toContain("saved to their yarn");
  });
});

describe("ScopeFilter", () => {
  it("shows Mine and All to administrators, All selected by default", () => {
    session.data = sessionFor(true);
    const { body } = render(ScopeFilter, { props: {} });
    expect(body).toContain("Mine");
    expect(body).toContain(">All<");
    expect(body).toMatch(/aria-pressed="true"[^>]*>(?:(?!<button).)*All/s);
  });

  it("is hidden from regular users", () => {
    session.data = sessionFor(false);
    const { body } = render(ScopeFilter, { props: {} });
    expect(body).not.toContain(">All<");
    expect(body).not.toContain("scope-filter");
  });
});
