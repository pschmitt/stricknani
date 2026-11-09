/**
 * Deep link for the admin users page: `/app/admin/users?impersonate=<id>` opens the "Impersonate
 * this account?" confirmation for that user. The native app uses it so the impersonation happens in
 * the browser (the app's own session never becomes another account); the confirmation click is
 * still required.
 */
export const IMPERSONATE_PARAM = "impersonate";

/** The user id of a valid `impersonate` value, otherwise null. */
export function readImpersonateId(value: string | null): number | null {
  if (value === null || !/^[1-9][0-9]{0,15}$/.test(value)) return null;
  const parsed = Number(value);
  return Number.isSafeInteger(parsed) ? parsed : null;
}

/** The query string without the `impersonate` parameter, so a reload does not reopen it. */
export function withoutImpersonate(search: string): string {
  const params = new URLSearchParams(search);
  params.delete(IMPERSONATE_PARAM);
  return params.toString();
}
