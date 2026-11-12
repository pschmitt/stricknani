import { describe, expect, it } from "vitest";
import type { AuditEvent } from "./admin-api";
import {
  auditActor,
  auditImpersonatedBy,
  auditImpersonator,
  auditUserRoleSentence,
  impersonationTemplate,
} from "./audit-people";

const user = { id: 7, email: "ann.lee@example.invalid", avatar_url: "/a/7" };

function event(over: Partial<AuditEvent>): AuditEvent {
  return {
    actor_email: "bea@example.invalid",
    actor_avatar_url: "/a/2",
    entity_type: "project",
    impersonated_by: null,
    legacy_actor_user_id: null,
    system: false,
    server: null,
    subject: null,
    ...over,
  } as AuditEvent;
}

describe("auditActor", () => {
  it("shows the account by display name with its avatar, never the email", () => {
    expect(auditActor(event({}), "en")).toEqual({
      kind: "user",
      name: "Bea",
      avatarUrl: "/a/2",
    });
  });

  it("names the server's actor, the purge job and a legacy row", () => {
    expect(
      auditActor(
        event({
          actor_email: null,
          server: { actor_email: "root.admin@example.invalid" } as never,
        }),
        "en",
      ).name,
    ).toBe("Root Admin");
    expect(
      auditActor(event({ actor_email: null, system: true }), "en").kind,
    ).toBe("system");
    expect(
      auditActor(event({ actor_email: null, legacy_actor_user_id: 9 }), "en")
        .name,
    ).toBe("Legacy user #9");
    expect(auditActor(event({ actor_email: null }), "en").kind).toBe("unknown");
  });
});

describe("impersonation", () => {
  it("shows the administrator behind a change with their avatar", () => {
    const admin = {
      id: 1,
      name: "Root",
      email: "root@example.invalid",
      avatar_url: "/a/1",
    };
    expect(auditImpersonator(admin)).toEqual({
      kind: "user",
      name: "Root",
      avatarUrl: "/a/1",
    });
    expect(auditImpersonator({ id: 1, name: null, email: null }).kind).toBe(
      "admin",
    );
    const actor = auditActor(event({ impersonated_by: admin }), "en");
    const sentence = auditImpersonatedBy(
      event({ impersonated_by: admin }),
      actor,
      "en",
    );
    expect(sentence?.template).toBe("by {admin} as {user}");
    expect(sentence?.users.user).toBe(actor);
    expect(auditImpersonatedBy(event({}), actor, "en")).toBeNull();
  });

  it("words the two impersonation headlines", () => {
    expect(impersonationTemplate("impersonation_started", "en")).toBe(
      "Impersonation by {admin} started",
    );
    expect(impersonationTemplate("impersonation_stopped", "en")).toBe(
      "Impersonation by {admin} stopped",
    );
    expect(impersonationTemplate("updated", "en")).toBeNull();
  });
});

describe("auditUserRoleSentence", () => {
  it("fills each role with the filtered account and the actor", () => {
    const actor = auditActor(event({}), "en");
    const by = auditUserRoleSentence(
      event({ actor_email: user.email }),
      user,
      actor,
      "en",
    );
    expect(by?.template).toBe("By {user}");
    expect(by?.users.user.name).toBe("Ann Lee");
    expect(by?.users.user.avatarUrl).toBe("/a/7");
    expect(
      auditUserRoleSentence(
        event({ impersonated_by: { id: 7, name: null, email: null } }),
        user,
        actor,
        "en",
      )?.template,
    ).toBe("{user} acting as {actor}");
    expect(
      auditUserRoleSentence(
        event({ entity_type: "user", subject: { id: 7, name: "ann" } }),
        user,
        actor,
        "en",
      )?.template,
    ).toBe("On {user}'s account");
    expect(auditUserRoleSentence(event({}), user, actor, "en")).toBeNull();
  });
});
