import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError, setUnauthorizedHandler } from "#lib/api.ts";
import {
  assertionToJson,
  attestationToJson,
  base64UrlToBytes,
  bytesToBase64Url,
  conditionalMediationSupported,
  deletePasskey,
  getLoginAssertion,
  isCancelled,
  listPasskeys,
  PasskeyCeremonyError,
  passkeysSupported,
  reauthWithPasskey,
  registerPasskey,
  renamePasskey,
  toCreationOptions,
  toRequestOptions,
} from "./passkey-api";

vi.mock("#lib/i18n.svelte.ts", () => ({
  t: (message: string) => message,
}));

afterEach(() => {
  vi.unstubAllGlobals();
  setUnauthorizedHandler(() => undefined);
});

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });
}

const buffer = (...bytes: number[]) => new Uint8Array(bytes).buffer;

const passkey = {
  id: 3,
  nickname: "Pixel",
  created_at: "2026-11-12T07:00:00.000000Z",
  last_used_at: null,
  transports: ["internal"],
  backup_eligible: true,
  backup_state: true,
};

const creationOptions = {
  challenge_id: "abc",
  options: {
    publicKey: {
      rp: { name: "Stricknani", id: "wolle.example.org" },
      user: { id: "AQID", name: "a@example.test", displayName: "a" },
      challenge: "BAUG",
      pubKeyCredParams: [{ type: "public-key", alg: -7 }],
      excludeCredentials: [{ type: "public-key", id: "CQoL" }],
      authenticatorSelection: { residentKey: "preferred" },
    },
  },
};

const requestOptions = {
  challenge_id: "xyz",
  options: {
    publicKey: {
      challenge: "BAUG",
      rpId: "wolle.example.org",
      allowCredentials: [],
      userVerification: "required",
    },
  },
};

function credential(response: Record<string, unknown>) {
  return {
    id: "AQID",
    rawId: buffer(1, 2, 3),
    type: "public-key",
    response,
  };
}

describe("base64url", () => {
  it("round-trips bytes without padding", () => {
    for (const bytes of [
      [],
      [255],
      [250, 251],
      [1, 2, 3, 4],
      [251, 255, 190],
    ]) {
      const encoded = bytesToBase64Url(new Uint8Array(bytes));
      expect(encoded).not.toMatch(/[+/=]/);
      expect([...base64UrlToBytes(encoded)]).toEqual(bytes);
    }
    expect(bytesToBase64Url(buffer(251, 255, 190))).toBe("-_--");
  });
});

describe("option conversion", () => {
  it("turns the creation options into binary form", () => {
    const options = toCreationOptions(creationOptions.options.publicKey);
    expect([...(options.challenge as Uint8Array)]).toEqual([4, 5, 6]);
    expect([...(options.user.id as Uint8Array)]).toEqual([1, 2, 3]);
    expect([...(options.excludeCredentials?.[0].id as Uint8Array)]).toEqual([
      9, 10, 11,
    ]);
    expect(options.rp.id).toBe("wolle.example.org");
  });

  it("turns the request options into binary form", () => {
    const options = toRequestOptions({
      ...requestOptions.options.publicKey,
      allowCredentials: [
        { type: "public-key", id: "CQoL", transports: ["usb"] },
      ],
    });
    expect([...(options.challenge as Uint8Array)]).toEqual([4, 5, 6]);
    expect(options.allowCredentials?.[0].transports).toEqual(["usb"]);
    expect(options.rpId).toBe("wolle.example.org");
  });

  it("serializes attestations and assertions for the server", () => {
    const attestation = attestationToJson(
      credential({
        attestationObject: buffer(1),
        clientDataJSON: buffer(2),
        getTransports: () => ["internal", "hybrid"],
      }) as unknown as PublicKeyCredential,
    );
    expect(attestation).toEqual({
      id: "AQID",
      rawId: "AQID",
      type: "public-key",
      response: {
        attestationObject: "AQ",
        clientDataJSON: "Ag",
        transports: ["internal", "hybrid"],
      },
    });

    const assertion = assertionToJson(
      credential({
        authenticatorData: buffer(3),
        clientDataJSON: buffer(4),
        signature: buffer(5),
        userHandle: buffer(6),
      }) as unknown as PublicKeyCredential,
    );
    expect(assertion.response).toEqual({
      authenticatorData: "Aw",
      clientDataJSON: "BA",
      signature: "BQ",
      userHandle: "Bg",
    });
    expect(
      (
        assertionToJson(
          credential({
            authenticatorData: buffer(3),
            clientDataJSON: buffer(4),
            signature: buffer(5),
            userHandle: null,
          }) as unknown as PublicKeyCredential,
        ).response as Record<string, unknown>
      ).userHandle,
    ).toBeUndefined();
  });
});

describe("feature detection", () => {
  it("needs a secure context, PublicKeyCredential and the credentials API", () => {
    vi.stubGlobal("window", {
      isSecureContext: true,
      PublicKeyCredential: function PublicKeyCredential() {},
    });
    vi.stubGlobal("navigator", {
      credentials: { get: vi.fn(), create: vi.fn() },
    });
    expect(passkeysSupported()).toBe(true);

    vi.stubGlobal("window", { isSecureContext: true });
    expect(passkeysSupported()).toBe(false);

    vi.stubGlobal("window", {
      isSecureContext: false,
      PublicKeyCredential: function PublicKeyCredential() {},
    });
    expect(passkeysSupported()).toBe(false);

    vi.stubGlobal("window", undefined);
    expect(passkeysSupported()).toBe(false);
  });

  it("asks the browser whether autofill passkeys work", async () => {
    const supported = (value: unknown) => {
      const PublicKeyCredential = Object.assign(function () {}, {
        isConditionalMediationAvailable: () => value,
      });
      vi.stubGlobal("window", { isSecureContext: true, PublicKeyCredential });
      vi.stubGlobal("navigator", {
        credentials: { get: vi.fn(), create: vi.fn() },
      });
    };
    supported(Promise.resolve(true));
    expect(await conditionalMediationSupported()).toBe(true);
    supported(Promise.resolve(false));
    expect(await conditionalMediationSupported()).toBe(false);
    supported(Promise.reject(new Error("boom")));
    expect(await conditionalMediationSupported()).toBe(false);
    vi.stubGlobal("window", { isSecureContext: true });
    expect(await conditionalMediationSupported()).toBe(false);
  });
});

describe("sign-in", () => {
  it("fetches options, asks the authenticator and returns the assertion", async () => {
    const get = vi.fn().mockResolvedValue(
      credential({
        authenticatorData: buffer(3),
        clientDataJSON: buffer(4),
        signature: buffer(5),
        userHandle: buffer(6),
      }),
    );
    vi.stubGlobal("navigator", { credentials: { get } });
    const fetchMock = vi
      .fn()
      .mockResolvedValue(json(200, { data: requestOptions }));
    vi.stubGlobal("fetch", fetchMock);
    const controller = new AbortController();

    const assertion = await getLoginAssertion("csrf", {
      conditional: true,
      signal: controller.signal,
    });

    expect(fetchMock.mock.calls[0][0]).toBe("/api/v2/auth/passkey/options");
    expect(
      new Headers((fetchMock.mock.calls[0][1] as RequestInit).headers).get(
        "x-csrf-token",
      ),
    ).toBe("csrf");
    expect(get).toHaveBeenCalledTimes(1);
    const arguments_ = get.mock.calls[0][0];
    expect(arguments_.mediation).toBe("conditional");
    expect(arguments_.signal).toBe(controller.signal);
    expect([...arguments_.publicKey.challenge]).toEqual([4, 5, 6]);
    expect(assertion.challengeId).toBe("xyz");
    expect(
      (assertion.credential.response as Record<string, string>).userHandle,
    ).toBe("Bg");
  });

  it("uses the modal prompt without mediation for the button", async () => {
    const get = vi.fn().mockResolvedValue(
      credential({
        authenticatorData: buffer(3),
        clientDataJSON: buffer(4),
        signature: buffer(5),
      }),
    );
    vi.stubGlobal("navigator", { credentials: { get } });
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(json(200, { data: requestOptions })),
    );
    await getLoginAssertion("csrf");
    expect("mediation" in get.mock.calls[0][0]).toBe(false);
  });

  it.each([
    ["NotAllowedError", "cancelled"],
    ["AbortError", "cancelled"],
    ["InvalidStateError", "already_registered"],
    ["NotSupportedError", "unsupported"],
    ["SecurityError", "unavailable"],
    ["UnknownError", "unknown"],
  ])("classifies %s as %s", async (name, failure) => {
    vi.stubGlobal("navigator", {
      credentials: {
        get: vi.fn().mockRejectedValue(new DOMException("x", name)),
      },
    });
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(json(200, { data: requestOptions })),
    );
    const error = await getLoginAssertion("csrf").catch((reason) => reason);
    expect(error).toBeInstanceOf(PasskeyCeremonyError);
    expect(error.failure).toBe(failure);
    expect(isCancelled(error)).toBe(failure === "cancelled");
  });

  it("treats an empty authenticator answer as a cancel", async () => {
    vi.stubGlobal("navigator", {
      credentials: { get: vi.fn().mockResolvedValue(null) },
    });
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(json(200, { data: requestOptions })),
    );
    await expect(getLoginAssertion("csrf")).rejects.toMatchObject({
      failure: "cancelled",
    });
  });

  it("rejects a malformed challenge response", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(json(200, { data: { options: {} } })),
    );
    await expect(getLoginAssertion("csrf")).rejects.toMatchObject({
      code: "invalid_response",
    });
  });

  it("does not sign the person out when the server rejects a passkey", async () => {
    const handler = vi.fn();
    setUnauthorizedHandler(handler);
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        json(401, {
          error: {
            code: "invalid_passkey",
            message: "The passkey could not be verified",
          },
        }),
      ),
    );
    await expect(getLoginAssertion("csrf")).rejects.toBeInstanceOf(ApiError);
    expect(handler).not.toHaveBeenCalled();
  });
});

describe("management", () => {
  it("lists passkeys with the limit", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(json(200, { data: [passkey], meta: { limit: 10 } })),
    );
    expect(await listPasskeys()).toEqual({ passkeys: [passkey], limit: 10 });
  });

  it("rejects a malformed list", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(json(200, { data: [{ id: "x" }] })),
    );
    await expect(listPasskeys()).rejects.toMatchObject({
      code: "invalid_response",
    });
  });

  it("registers a passkey: options, create(), verify", async () => {
    const create = vi.fn().mockResolvedValue(
      credential({
        attestationObject: buffer(1),
        clientDataJSON: buffer(2),
        getTransports: () => ["internal"],
      }),
    );
    vi.stubGlobal("navigator", { credentials: { create } });
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(json(200, { data: creationOptions }))
      .mockResolvedValueOnce(json(201, { data: passkey }));
    vi.stubGlobal("fetch", fetchMock);

    expect(await registerPasskey("Pixel", "csrf")).toEqual(passkey);

    expect(fetchMock.mock.calls.map((call) => call[0])).toEqual([
      "/api/v2/account/passkeys/registration/options",
      "/api/v2/account/passkeys/registration/verify",
    ]);
    const body = JSON.parse(
      (fetchMock.mock.calls[1][1] as RequestInit).body as string,
    );
    expect(body.challenge_id).toBe("abc");
    expect(body.nickname).toBe("Pixel");
    expect(body.credential.response.attestationObject).toBe("AQ");
    expect([...create.mock.calls[0][0].publicKey.user.id]).toEqual([1, 2, 3]);
  });

  it("asks for step-up when the server wants it, without touching the authenticator", async () => {
    const create = vi.fn();
    vi.stubGlobal("navigator", { credentials: { create } });
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        json(403, {
          error: {
            code: "reauth_required",
            message: "Reauthentication is required",
          },
        }),
      ),
    );
    await expect(registerPasskey("Pixel", "csrf")).rejects.toMatchObject({
      status: 403,
      code: "reauth_required",
    });
    expect(create).not.toHaveBeenCalled();
  });

  it("reports an authenticator that already holds a passkey", async () => {
    vi.stubGlobal("navigator", {
      credentials: {
        create: vi
          .fn()
          .mockRejectedValue(new DOMException("x", "InvalidStateError")),
      },
    });
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(json(200, { data: creationOptions })),
    );
    await expect(registerPasskey("Pixel", "csrf")).rejects.toMatchObject({
      failure: "already_registered",
    });
  });

  it("renames and deletes", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        json(200, { data: { ...passkey, nickname: "Work" } }),
      )
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", fetchMock);
    expect((await renamePasskey(3, "Work", "csrf")).nickname).toBe("Work");
    await deletePasskey(3, "csrf");
    expect(
      fetchMock.mock.calls.map((call) => [
        call[0],
        (call[1] as RequestInit).method,
      ]),
    ).toEqual([
      ["/api/v2/account/passkeys/3", "PATCH"],
      ["/api/v2/account/passkeys/3", "DELETE"],
    ]);
  });
});

describe("step-up", () => {
  it("confirms an action with a passkey", async () => {
    vi.stubGlobal("navigator", {
      credentials: {
        get: vi.fn().mockResolvedValue(
          credential({
            authenticatorData: buffer(3),
            clientDataJSON: buffer(4),
            signature: buffer(5),
          }),
        ),
      },
    });
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(json(200, { data: requestOptions }))
      .mockResolvedValueOnce(
        json(200, { data: { reauthenticated: true, expires_at: "x" } }),
      );
    vi.stubGlobal("fetch", fetchMock);

    await reauthWithPasskey(
      { action: "unlink_identity", target: { identity_id: 4 } },
      "csrf",
    );

    expect(fetchMock.mock.calls.map((call) => call[0])).toEqual([
      "/api/v2/account/reauth/passkey/options",
      "/api/v2/account/reauth/passkey",
    ]);
    const body = JSON.parse(
      (fetchMock.mock.calls[1][1] as RequestInit).body as string,
    );
    expect(body).toMatchObject({
      challenge_id: "xyz",
      action: "unlink_identity",
      target: { identity_id: 4 },
    });
  });

  it("fails on an unexpected answer", async () => {
    vi.stubGlobal("navigator", {
      credentials: {
        get: vi.fn().mockResolvedValue(
          credential({
            authenticatorData: buffer(3),
            clientDataJSON: buffer(4),
            signature: buffer(5),
          }),
        ),
      },
    });
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValueOnce(json(200, { data: requestOptions }))
        .mockResolvedValueOnce(json(200, { data: {} })),
    );
    await expect(
      reauthWithPasskey({ action: "manage_passkeys" }, "csrf"),
    ).rejects.toMatchObject({ code: "invalid_response" });
  });
});
