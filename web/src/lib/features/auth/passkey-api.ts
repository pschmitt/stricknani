import { ApiError, apiRequest } from "#lib/api.ts";
import type { ReauthRequest } from "./oidc-api";

/**
 * Passkey (WebAuthn) client: the browser half of the ceremonies served by
 * `/api/v2/auth/passkey/*`, `/api/v2/account/passkeys*` and
 * `/api/v2/account/reauth/passkey*`. The server owns the challenge; this module
 * turns its JSON options into `navigator.credentials` calls and the answers
 * back into JSON. Nothing is stored here.
 */

export interface Passkey {
  id: number;
  nickname: string;
  created_at: string;
  last_used_at: string | null;
  transports: string[];
  backup_eligible: boolean;
  backup_state: boolean;
}

export interface PasskeyList {
  passkeys: Passkey[];
  limit: number;
}

export type PasskeyFailure =
  | "cancelled"
  | "already_registered"
  | "unsupported"
  | "unavailable"
  | "unknown";

/** A WebAuthn call that did not produce a credential, classified for the UI. */
export class PasskeyCeremonyError extends Error {
  readonly failure: PasskeyFailure;

  constructor(failure: PasskeyFailure, cause?: unknown) {
    super(`passkey ceremony failed: ${failure}`, { cause });
    this.name = "PasskeyCeremonyError";
    this.failure = failure;
  }
}

interface ServerChallenge {
  challengeId: string;
  options: Record<string, unknown>;
}

const record = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null && !Array.isArray(value);

function invalidResponse(): ApiError {
  return new ApiError(
    "The server returned an unexpected response.",
    200,
    "invalid_response",
  );
}

/** Whether this browser can create and use passkeys at all. */
export function passkeysSupported(): boolean {
  return (
    typeof window !== "undefined" &&
    window.isSecureContext !== false &&
    typeof window.PublicKeyCredential === "function" &&
    typeof navigator !== "undefined" &&
    typeof navigator.credentials?.get === "function" &&
    typeof navigator.credentials?.create === "function"
  );
}

/** Whether the browser can offer passkeys in the email field's autofill. */
export async function conditionalMediationSupported(): Promise<boolean> {
  if (!passkeysSupported()) return false;
  try {
    return (
      (await window.PublicKeyCredential.isConditionalMediationAvailable?.()) ===
      true
    );
  } catch {
    return false;
  }
}

export function base64UrlToBytes(value: string): Uint8Array<ArrayBuffer> {
  const padded = value.replaceAll("-", "+").replaceAll("_", "/");
  const binary = atob(padded + "=".repeat((4 - (padded.length % 4)) % 4));
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index += 1)
    bytes[index] = binary.charCodeAt(index);
  return bytes;
}

export function bytesToBase64Url(value: ArrayBuffer | ArrayBufferView): string {
  const bytes =
    value instanceof ArrayBuffer
      ? new Uint8Array(value)
      : new Uint8Array(value.buffer, value.byteOffset, value.byteLength);
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary)
    .replaceAll("+", "-")
    .replaceAll("/", "_")
    .replace(/=+$/, "");
}

function descriptorList(value: unknown): PublicKeyCredentialDescriptor[] {
  if (!Array.isArray(value)) return [];
  return value.filter(record).map((entry) => ({
    type: "public-key",
    id: base64UrlToBytes(String(entry.id)),
    ...(Array.isArray(entry.transports)
      ? { transports: entry.transports as AuthenticatorTransport[] }
      : {}),
  }));
}

/** Server JSON (base64url strings) to the binary form `credentials.create` wants. */
export function toCreationOptions(
  publicKey: Record<string, unknown>,
): PublicKeyCredentialCreationOptions {
  const user = publicKey.user;
  if (!record(user)) throw invalidResponse();
  return {
    ...(publicKey as unknown as PublicKeyCredentialCreationOptions),
    challenge: base64UrlToBytes(String(publicKey.challenge)),
    user: {
      ...(user as unknown as PublicKeyCredentialUserEntity),
      id: base64UrlToBytes(String(user.id)),
    },
    excludeCredentials: descriptorList(publicKey.excludeCredentials),
  };
}

/** Server JSON to the binary form `credentials.get` wants. */
export function toRequestOptions(
  publicKey: Record<string, unknown>,
): PublicKeyCredentialRequestOptions {
  return {
    ...(publicKey as unknown as PublicKeyCredentialRequestOptions),
    challenge: base64UrlToBytes(String(publicKey.challenge)),
    allowCredentials: descriptorList(publicKey.allowCredentials),
  };
}

/** The attestation as the server's `RegisterPublicKeyCredential`. */
export function attestationToJson(
  credential: PublicKeyCredential,
): Record<string, unknown> {
  const response = credential.response as AuthenticatorAttestationResponse;
  const transports =
    typeof response.getTransports === "function"
      ? response.getTransports()
      : [];
  return {
    id: credential.id,
    rawId: bytesToBase64Url(credential.rawId),
    type: credential.type,
    response: {
      attestationObject: bytesToBase64Url(response.attestationObject),
      clientDataJSON: bytesToBase64Url(response.clientDataJSON),
      transports,
    },
  };
}

/** The assertion as the server's `PublicKeyCredential`. */
export function assertionToJson(
  credential: PublicKeyCredential,
): Record<string, unknown> {
  const response = credential.response as AuthenticatorAssertionResponse;
  return {
    id: credential.id,
    rawId: bytesToBase64Url(credential.rawId),
    type: credential.type,
    response: {
      authenticatorData: bytesToBase64Url(response.authenticatorData),
      clientDataJSON: bytesToBase64Url(response.clientDataJSON),
      signature: bytesToBase64Url(response.signature),
      ...(response.userHandle
        ? { userHandle: bytesToBase64Url(response.userHandle) }
        : {}),
    },
  };
}

function classify(error: unknown): PasskeyCeremonyError {
  if (error instanceof PasskeyCeremonyError) return error;
  const name = error instanceof DOMException ? error.name : "";
  switch (name) {
    case "NotAllowedError":
    case "AbortError":
      return new PasskeyCeremonyError("cancelled", error);
    case "InvalidStateError":
      return new PasskeyCeremonyError("already_registered", error);
    case "NotSupportedError":
      return new PasskeyCeremonyError("unsupported", error);
    case "SecurityError":
      return new PasskeyCeremonyError("unavailable", error);
    default:
      return new PasskeyCeremonyError("unknown", error);
  }
}

/** True for a user-dismissed prompt, a timeout or an aborted conditional request. */
export function isCancelled(error: unknown): boolean {
  return error instanceof PasskeyCeremonyError && error.failure === "cancelled";
}

function parseChallenge(payload: unknown): ServerChallenge {
  if (!record(payload) || !record(payload.data)) throw invalidResponse();
  const { challenge_id: challengeId, options } = payload.data;
  if (typeof challengeId !== "string" || !record(options))
    throw invalidResponse();
  return { challengeId, options };
}

function publicKeyOf(challenge: ServerChallenge): Record<string, unknown> {
  const publicKey = challenge.options.publicKey;
  if (!record(publicKey)) throw invalidResponse();
  return publicKey;
}

export interface Assertion {
  challengeId: string;
  credential: Record<string, unknown>;
}

async function requestAssertion(
  challenge: ServerChallenge,
  mediation: "conditional" | undefined,
  signal: AbortSignal | undefined,
): Promise<Assertion> {
  let credential: Credential | null;
  try {
    credential = await navigator.credentials.get({
      publicKey: toRequestOptions(publicKeyOf(challenge)),
      ...(mediation ? { mediation } : {}),
      signal,
    });
  } catch (error) {
    throw classify(error);
  }
  if (!credential) throw new PasskeyCeremonyError("cancelled");
  return {
    challengeId: challenge.challengeId,
    credential: assertionToJson(credential as PublicKeyCredential),
  };
}

/** Ask the server for a sign-in challenge and have the authenticator sign it. */
export async function getLoginAssertion(
  csrfToken: string,
  options: { conditional?: boolean; signal?: AbortSignal } = {},
): Promise<Assertion> {
  const challenge = parseChallenge(
    await apiRequest("/api/v2/auth/passkey/options", {
      method: "POST",
      csrfToken,
      body: {},
      signal: options.signal,
    }),
  );
  return requestAssertion(
    challenge,
    options.conditional ? "conditional" : undefined,
    options.signal,
  );
}

function parsePasskey(value: unknown): Passkey {
  if (
    !record(value) ||
    !Number.isSafeInteger(value.id) ||
    typeof value.nickname !== "string" ||
    typeof value.created_at !== "string" ||
    !(value.last_used_at === null || typeof value.last_used_at === "string") ||
    !Array.isArray(value.transports) ||
    typeof value.backup_eligible !== "boolean" ||
    typeof value.backup_state !== "boolean"
  )
    throw invalidResponse();
  return value as unknown as Passkey;
}

export async function listPasskeys(signal?: AbortSignal): Promise<PasskeyList> {
  const payload = await apiRequest("/api/v2/account/passkeys", { signal });
  if (!record(payload) || !Array.isArray(payload.data)) throw invalidResponse();
  const meta = record(payload.meta) ? payload.meta : {};
  return {
    passkeys: payload.data.map(parsePasskey),
    limit: Number.isSafeInteger(meta.limit) ? Number(meta.limit) : 10,
  };
}

/**
 * Create a passkey on this device. The server wants recent authentication and
 * answers `reauth_required` first: the caller shows the step-up dialog and runs
 * this again.
 */
export async function registerPasskey(
  nickname: string,
  csrfToken: string,
): Promise<Passkey> {
  const challenge = parseChallenge(
    await apiRequest("/api/v2/account/passkeys/registration/options", {
      method: "POST",
      csrfToken,
      body: {},
    }),
  );
  let credential: Credential | null;
  try {
    credential = await navigator.credentials.create({
      publicKey: toCreationOptions(publicKeyOf(challenge)),
    });
  } catch (error) {
    throw classify(error);
  }
  if (!credential) throw new PasskeyCeremonyError("cancelled");
  const payload = await apiRequest(
    "/api/v2/account/passkeys/registration/verify",
    {
      method: "POST",
      csrfToken,
      body: {
        challenge_id: challenge.challengeId,
        credential: attestationToJson(credential as PublicKeyCredential),
        nickname,
      },
    },
  );
  if (!record(payload)) throw invalidResponse();
  return parsePasskey(payload.data);
}

export async function renamePasskey(
  id: number,
  nickname: string,
  csrfToken: string,
): Promise<Passkey> {
  const payload = await apiRequest(`/api/v2/account/passkeys/${id}`, {
    method: "PATCH",
    csrfToken,
    body: { nickname },
  });
  if (!record(payload)) throw invalidResponse();
  return parsePasskey(payload.data);
}

export async function deletePasskey(
  id: number,
  csrfToken: string,
): Promise<void> {
  await apiRequest(`/api/v2/account/passkeys/${id}`, {
    method: "DELETE",
    csrfToken,
  });
}

/** Confirm identity for a sensitive action with one of the account's passkeys. */
export async function reauthWithPasskey(
  request: ReauthRequest,
  csrfToken: string,
): Promise<void> {
  const challenge = parseChallenge(
    await apiRequest("/api/v2/account/reauth/passkey/options", {
      method: "POST",
      csrfToken,
      body: {},
    }),
  );
  const assertion = await requestAssertion(challenge, undefined, undefined);
  const payload = await apiRequest("/api/v2/account/reauth/passkey", {
    method: "POST",
    csrfToken,
    body: {
      challenge_id: assertion.challengeId,
      credential: assertion.credential,
      action: request.action,
      ...(request.target ? { target: request.target } : {}),
    },
  });
  if (
    !record(payload) ||
    !record(payload.data) ||
    payload.data.reauthenticated !== true
  )
    throw invalidResponse();
}
