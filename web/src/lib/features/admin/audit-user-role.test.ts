import { describe, expect, it } from "vitest";
import { auditUserRole, auditUserRoleText } from "./audit-user-role";
import type { AuditEvent } from "./admin-api";

const user = { id: 7, email: "ann@example.invalid" };

function event(over: Partial<AuditEvent>): AuditEvent {
  return {
    actor_email: "bea@example.invalid",
    entity_type: "project",
    impersonated_by: null,
    subject: null,
    ...over,
  } as AuditEvent;
}

describe("auditUserRole", () => {
  it("tells actor, impersonator and subject apart", () => {
    expect(auditUserRole(event({ actor_email: user.email }), user)).toBe(
      "actor",
    );
    expect(
      auditUserRole(
        event({ impersonated_by: { id: 7, name: null, email: null } }),
        user,
      ),
    ).toBe("impersonator");
    expect(
      auditUserRole(event({ subject: { id: 7, name: "ann" } }), user),
    ).toBe("subject");
    expect(auditUserRole(event({}), user)).toBeNull();
  });

  it("lets the actor win when the user is also the subject", () => {
    expect(
      auditUserRole(
        event({
          actor_email: user.email,
          subject: { id: 7, name: "ann" },
        }),
        user,
      ),
    ).toBe("actor");
  });

  it("words each role", () => {
    expect(
      auditUserRoleText(event({ actor_email: user.email }), user, "x", "en"),
    ).toBe("By ann@example.invalid");
    expect(
      auditUserRoleText(
        event({
          entity_type: "user",
          subject: { id: 7, name: "ann" },
        }),
        user,
        "x",
        "en",
      ),
    ).toBe("On ann@example.invalid's account");
    expect(
      auditUserRoleText(
        event({ impersonated_by: { id: 7, name: null, email: null } }),
        user,
        "bea@example.invalid",
        "en",
      ),
    ).toBe("ann@example.invalid acting as bea@example.invalid");
  });
});
