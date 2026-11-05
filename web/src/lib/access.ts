import { ApiError } from "#lib/api.ts";

/** What the signed-in user is to a project or yarn. */
export type AccessRole = "owner" | "admin" | "editor" | "viewer";

/** The `access` field every v2 project and yarn resource carries. */
export interface ResourceAccess {
  role: AccessRole;
  /** A yarn read only through a shared project: read-only, nothing to leave. */
  derived: boolean;
  can_edit: boolean;
  can_manage: boolean;
}

const roles: readonly AccessRole[] = ["owner", "admin", "editor", "viewer"];

/** Parse an optional `access` field; anything malformed becomes `null`. */
export function parseAccess(value: unknown): ResourceAccess | null {
  if (typeof value !== "object" || value === null) return null;
  const access = value as Record<string, unknown>;
  const role = roles.find((candidate) => candidate === access.role);
  if (
    role === undefined ||
    typeof access.derived !== "boolean" ||
    typeof access.can_edit !== "boolean" ||
    typeof access.can_manage !== "boolean"
  )
    return null;
  return {
    role,
    derived: access.derived,
    can_edit: access.can_edit,
    can_manage: access.can_manage,
  };
}

/** Spread into a parsed resource: adds `access` only when the server sent a valid one. */
export function accessField(value: unknown): { access?: ResourceAccess } {
  const access = parseAccess(value);
  return access ? { access } : {};
}

/** Servers that predate sharing send no `access`; those callers could always edit. */
export function mayEdit(access: ResourceAccess | null | undefined): boolean {
  return access ? access.can_edit : true;
}

/** May share, transfer and delete (owner or administrator). */
export function mayManage(access: ResourceAccess | null | undefined): boolean {
  return access ? access.can_manage : true;
}

/** Someone the resource was shared with, directly or through a project. */
export function isRecipient(
  access: ResourceAccess | null | undefined,
): access is ResourceAccess {
  return access?.role === "editor" || access?.role === "viewer";
}

/** A recipient who may leave: derived access ends only with the project share. */
export function mayLeave(access: ResourceAccess | null | undefined): boolean {
  return isRecipient(access) && !access.derived;
}

/** 404 after a revocation: the resource is gone for this user. */
export function isAccessLost(error: unknown): boolean {
  return error instanceof ApiError && error.status === 404;
}

/** 403 where the resource is still visible: the role was lowered. */
export function isAccessLowered(error: unknown): boolean {
  return (
    error instanceof ApiError &&
    error.status === 403 &&
    error.code === "forbidden"
  );
}
