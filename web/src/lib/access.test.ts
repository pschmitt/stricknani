import { describe, expect, it } from "vitest";
import { ApiError } from "./api";
import {
  accessField,
  isAccessLost,
  isAccessLowered,
  isRecipient,
  mayEdit,
  mayLeave,
  mayManage,
  parseAccess,
} from "./access";

const viewer = {
  role: "viewer",
  derived: false,
  can_edit: false,
  can_manage: false,
};

describe("resource access", () => {
  it("parses the server access object", () => {
    expect(parseAccess(viewer)).toEqual(viewer);
    expect(accessField(viewer)).toEqual({ access: viewer });
  });

  it("rejects unknown roles and missing flags", () => {
    expect(parseAccess({ ...viewer, role: "root" })).toBeNull();
    expect(parseAccess({ role: "owner", derived: false })).toBeNull();
    expect(parseAccess("owner")).toBeNull();
    expect(accessField(null)).toEqual({});
  });

  it("trusts the flags rather than the role name", () => {
    const admin = parseAccess({
      role: "admin",
      derived: false,
      can_edit: true,
      can_manage: true,
    });
    expect(mayEdit(admin)).toBe(true);
    expect(mayManage(admin)).toBe(true);
    expect(mayEdit(parseAccess(viewer))).toBe(false);
    expect(mayManage(parseAccess(viewer))).toBe(false);
  });

  it("lets a caller without access info behave as before", () => {
    expect(mayEdit(undefined)).toBe(true);
    expect(mayManage(null)).toBe(true);
    expect(isRecipient(undefined)).toBe(false);
  });

  it("only direct recipients may leave", () => {
    const editor = parseAccess({
      role: "editor",
      derived: false,
      can_edit: true,
      can_manage: false,
    });
    expect(isRecipient(editor)).toBe(true);
    expect(mayLeave(editor)).toBe(true);
    expect(mayLeave(parseAccess({ ...viewer, derived: true }))).toBe(false);
    expect(
      mayLeave(
        parseAccess({
          role: "owner",
          derived: false,
          can_edit: true,
          can_manage: true,
        }),
      ),
    ).toBe(false);
  });

  it("tells a revoked share from a lowered role", () => {
    expect(isAccessLost(new ApiError("gone", 404, "not_found"))).toBe(true);
    expect(isAccessLost(new ApiError("no", 403, "forbidden"))).toBe(false);
    expect(isAccessLowered(new ApiError("no", 403, "forbidden"))).toBe(true);
    expect(isAccessLowered(new ApiError("csrf", 403, "csrf_failed"))).toBe(
      false,
    );
    expect(isAccessLost(new Error("x"))).toBe(false);
  });
});
