// Ports of ci/tests/test_rust_e2e_seed.py.
import {
  chmodSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  statSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { writePrivateJson } from "./fixture";
import {
  ApiClient,
  HttpError,
  MAX_JSON_BYTES,
  REQUEST_TIMEOUT,
  SeedError,
  defaultOpener,
  mediaPath,
  seedFixture,
  validateBaseUrl,
  writeToken,
  type Opener,
  type OpenerRequest,
  type OpenerResponse,
} from "./seed";

const TEST_PAT = `sna_${"a".repeat(64)}`;
const PEER_PAT = `sna_${"b".repeat(64)}`;

let tmp: string;

beforeEach(() => {
  tmp = mkdtempSync(join(tmpdir(), "stricknani-seed-test-"));
});

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  rmSync(tmp, { recursive: true, force: true });
});

function pathOf(request: OpenerRequest): string {
  return new URL(request.url).pathname;
}

function jsonBody(request: OpenerRequest): Record<string, unknown> {
  expect(request.body).toBeDefined();
  return JSON.parse(Buffer.from(request.body ?? []).toString("utf-8"));
}

function json(value: unknown, status: number): OpenerResponse {
  return { status, body: Buffer.from(JSON.stringify(value)) };
}

class FakeOpener implements Opener {
  readonly calls: { request: OpenerRequest; timeout: number }[] = [];
  private readonly failure?: Error;
  private readonly failureAt: number;
  private readonly response?: OpenerResponse;

  constructor(
    opts: {
      failure?: Error;
      failureAt?: number;
      response?: OpenerResponse;
    } = {},
  ) {
    this.failure = opts.failure;
    this.failureAt = opts.failureAt ?? 1;
    this.response = opts.response;
  }

  async open(
    request: OpenerRequest,
    timeout: number,
    limit: number,
  ): Promise<OpenerResponse> {
    this.calls.push({ request, timeout });
    if (this.failure !== undefined && this.calls.length === this.failureAt) {
      throw this.failure;
    }
    if (this.response !== undefined) {
      return {
        status: this.response.status,
        body: this.response.body.subarray(0, limit),
      };
    }
    const path = pathOf(request);
    const post = request.method === "POST";
    let value: unknown;
    if (path === "/api/v2/auth/register") {
      value = { data: { token: PEER_PAT } };
    } else if (path === "/api/v2/meta") {
      expect(request.headers.Authorization).toBe(`Bearer ${PEER_PAT}`);
      value = { data: { user: { id: 42, is_admin: false } } };
    } else if (path.endsWith("/auth/token")) {
      value = { token: TEST_PAT };
    } else if (path === "/api/v1/categories") {
      value = { id: 3, name: "Schal" };
    } else if (path === "/api/v1/yarns" && post) {
      value = { id: 8, name: "Riverbend Merino DK", photos: [] };
    } else if (path === "/api/v1/projects" && post) {
      value = {
        id: 11,
        name: "Heirloom Baby Blanket",
        category: "Schal",
        description: jsonBody(request).description,
        yarn_ids: [8],
      };
    } else if (path === "/api/v1/yarns/8/photos") {
      value = {
        id: 20,
        url: "/media/yarns/8/yarn.jpg",
        thumbnail_url: "/media/thumbnails/yarns/8/yarn.jpg",
        alt_text: "Riverbend Merino DK yarn",
        is_primary: true,
      };
    } else if (path === "/api/v1/projects/11/images/title") {
      value = {
        id: 21,
        url: "/media/projects/11/project.jpg",
        thumbnail_url: "/media/thumbnails/projects/11/project.jpg",
        alt_text: "Heirloom baby blanket",
      };
    } else if (path === "/api/v1/yarns/8") {
      value = {
        id: 8,
        name: "Riverbend Merino DK",
        photos: [{ url: "/media/yarns/8/yarn.jpg" }],
      };
    } else if (path === "/api/v1/projects/11") {
      value = {
        id: 11,
        name: "Heirloom Baby Blanket",
        yarn_ids: [8],
        images: [{ url: "/media/projects/11/project.jpg" }],
      };
    } else if (path === "/api/v1/projects" && request.method === "GET") {
      value = {
        items: [
          {
            id: 11,
            name: "Heirloom Baby Blanket",
            preview_url: "/media/thumbnails/projects/11/project.jpg",
          },
        ],
      };
    } else if (path === "/api/v1/yarns" && request.method === "GET") {
      value = {
        items: [
          {
            id: 8,
            name: "Riverbend Merino DK",
            preview_url: "/media/thumbnails/yarns/8/yarn.jpg",
          },
        ],
      };
    } else if (path.startsWith("/media/")) {
      return { status: 200, body: Buffer.from("image bytes") };
    } else {
      throw new Error(`unexpected request path: ${path}`);
    }

    if (path.startsWith("/api/v1/") && path !== "/api/v1/auth/token") {
      expect(request.headers.Authorization).toBe(`Bearer ${TEST_PAT}`);
    }
    return json(value, post ? 201 : 200);
  }
}

function fixtureFile(url = "http://127.0.0.1:7674"): string {
  const fixture = join(tmp, "fixture.json");
  writePrivateJson(fixture, {
    url,
    email: "seed@example.invalid",
    password: "disposable-fixture-password",
  });
  return fixture;
}

describe("validateBaseUrl", () => {
  it.each([
    "https://127.0.0.1:7674",
    "http://example.invalid:7674",
    "http://10.0.0.2:7674",
    "http://127.0.0.1:7674/path",
    "http://127.0.0.1:7674/?token=secret",
    "http://user:pass@127.0.0.1:7674",
  ])("rejects non-loopback or non-origin URL %s", (url) => {
    expect(() => validateBaseUrl(url)).toThrow(SeedError);
  });

  it("accepts a numeric loopback origin", () => {
    expect(validateBaseUrl("http://127.0.0.1:7674")).toBe(
      "http://127.0.0.1:7674",
    );
    expect(validateBaseUrl("http://127.0.0.1:7674/")).toBe(
      "http://127.0.0.1:7674",
    );
  });
});

describe("opener", () => {
  it("does not follow redirects and uses no proxy dispatcher", async () => {
    const fetchMock = vi.fn(async () => new Response(null, { status: 302 }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(
      defaultOpener().open(
        { url: "http://127.0.0.1:7674/api/v1/x", method: "GET", headers: {} },
        REQUEST_TIMEOUT,
        10,
      ),
    ).rejects.toMatchObject({ code: 302 });

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const init = (fetchMock.mock.calls[0] as unknown[])[1] as Record<
      string,
      unknown
    >;
    expect(init.redirect).toBe("manual");
    expect(init).not.toHaveProperty("dispatcher");
  });
});

describe("seedFixture", () => {
  it("does not follow a redirect and does not report the error body", async () => {
    const opener = new FakeOpener({
      failure: new HttpError(302),
      failureAt: 1,
    });

    const error = await seedFixture(fixtureFile(), join(tmp, "token.txt"), {
      opener,
    }).catch((caught: unknown) => caught);

    expect(error).toBeInstanceOf(SeedError);
    expect(opener.calls).toHaveLength(1);
    const message = (error as Error).message;
    expect(message).toContain("302");
    expect(message).not.toContain("private-fixture-secret");
    expect(message).not.toContain("seed@example.invalid");
    expect(message).not.toContain("disposable-fixture-password");
  });

  it("does not expose the PAT or response body after authentication", async () => {
    const opener = new FakeOpener({
      failure: new HttpError(500),
      failureAt: 2,
    });

    const error = await seedFixture(fixtureFile(), join(tmp, "token.json"), {
      opener,
    }).catch((caught: unknown) => caught);

    expect((error as Error).message).toBe(
      "POST /api/v1/categories returned HTTP 500",
    );
    expect((error as Error).message).not.toContain(TEST_PAT);
    expect((error as Error).message).not.toContain(
      "server echoed private data",
    );
  });

  it("seeds linked records and media and writes only the private token", async () => {
    const tokenFile = join(tmp, "secrets", "pat.txt");
    const opener = new FakeOpener();
    const written: string[] = [];
    vi.spyOn(process.stdout, "write").mockImplementation(((
      chunk: string | Uint8Array,
    ) => {
      written.push(String(chunk));
      return true;
    }) as typeof process.stdout.write);

    await seedFixture(fixtureFile(), tokenFile, { opener });

    expect(JSON.parse(readFileSync(tokenFile, "utf-8"))).toEqual({
      token: TEST_PAT,
    });
    expect(statSync(tokenFile).mode & 0o777).toBe(0o600);
    expect(written.join("")).not.toContain(TEST_PAT);
    const requests = opener.calls.map(({ request }) => request);
    expect(requests.map((r) => [r.method, pathOf(r)])).toEqual([
      ["POST", "/api/v1/auth/token"],
      ["POST", "/api/v1/categories"],
      ["POST", "/api/v1/yarns"],
      ["POST", "/api/v1/projects"],
      ["POST", "/api/v1/yarns/8/photos"],
      ["POST", "/api/v1/projects/11/images/title"],
      ["GET", "/api/v1/yarns/8"],
      ["GET", "/api/v1/projects/11"],
      ["GET", "/api/v1/projects"],
      ["GET", "/api/v1/yarns"],
      ["GET", "/media/yarns/8/yarn.jpg"],
      ["GET", "/media/thumbnails/yarns/8/yarn.jpg"],
      ["GET", "/media/projects/11/project.jpg"],
      ["GET", "/media/thumbnails/projects/11/project.jpg"],
    ]);
    expect(
      opener.calls.every(({ timeout }) => timeout === REQUEST_TIMEOUT),
    ).toBe(true);
    const [auth, , yarn, project] = requests as [
      OpenerRequest,
      OpenerRequest,
      OpenerRequest,
      OpenerRequest,
    ];
    expect(jsonBody(auth)).toEqual({
      email: "seed@example.invalid",
      password: "disposable-fixture-password",
      token_name: "Android E2E",
    });
    expect(jsonBody(yarn).name).toBe("Riverbend Merino DK");
    const projectPayload = jsonBody(project);
    expect(projectPayload.name).toBe("Heirloom Baby Blanket");
    expect(projectPayload.category).toBe("Schal");
    expect(projectPayload.description).toBeTruthy();
    expect(projectPayload.yarn_ids).toEqual([8]);
    for (const [index, assetName] of [
      [4, "demo_image_1.jpg"],
      [5, "demo_image_3.jpg"],
    ] as const) {
      const request = requests[index] as OpenerRequest;
      expect(request.headers.Authorization).toBe(`Bearer ${TEST_PAT}`);
      expect(request.headers["Content-Type"]).toMatch(
        /^multipart\/form-data; boundary=/,
      );
      const body = Buffer.from(request.body ?? []).toString("latin1");
      expect(body).toContain(assetName);
      expect(body).toContain('name="file"');
      expect(body.includes('name="alt_text"')).toBe(index === 5);
    }
  });

  it("creates a peer account and writes its private token file", async () => {
    const tokenFile = join(tmp, "secrets", "pat.json");
    const peerFile = join(tmp, "secrets", "peer.json");
    const opener = new FakeOpener();
    vi.spyOn(process.stdout, "write").mockImplementation(
      (() => true) as typeof process.stdout.write,
    );

    await seedFixture(fixtureFile(), tokenFile, {
      opener,
      peerTokenFile: peerFile,
    });

    expect(JSON.parse(readFileSync(peerFile, "utf-8"))).toEqual({
      token: PEER_PAT,
      user_id: 42,
    });
    expect(statSync(peerFile).mode & 0o777).toBe(0o600);
    expect(JSON.parse(readFileSync(tokenFile, "utf-8"))).toEqual({
      token: TEST_PAT,
    });
    const requests = opener.calls.map(({ request }) => request);
    const register = requests.find(
      (r) => pathOf(r) === "/api/v2/auth/register",
    ) as OpenerRequest;
    const body = jsonBody(register);
    expect(String(body.email)).toMatch(
      /^rust-peer-[0-9a-f]{16}@example\.invalid$/,
    );
    expect(String(body.password).length).toBeGreaterThanOrEqual(32);
    expect(readFileSync(peerFile, "utf-8")).not.toContain(
      String(body.password),
    );
    expect(requests.slice(-2).map((r) => [r.method, pathOf(r)])).toEqual([
      ["POST", "/api/v2/auth/register"],
      ["GET", "/api/v2/meta"],
    ]);
  });

  it("rejects a peer token file equal to the token file", async () => {
    const opener = new FakeOpener();
    const file = join(tmp, "same.json");
    await expect(
      seedFixture(fixtureFile(), file, { opener, peerTokenFile: file }),
    ).rejects.toThrow("peer token file must differ");
    expect(opener.calls).toEqual([]);
  });

  it("reports a refused peer registration without the response body", async () => {
    const opener = new FakeOpener({
      failure: new HttpError(404),
      failureAt: 15,
    });
    const peerFile = join(tmp, "peer.json");
    const tokenFile = join(tmp, "pat.json");
    await expect(
      seedFixture(fixtureFile(), tokenFile, {
        opener,
        peerTokenFile: peerFile,
      }),
    ).rejects.toThrow("POST /api/v2/auth/register returned HTTP 404");
    expect(() => statSync(peerFile)).toThrow();
    expect(() => statSync(tokenFile)).toThrow();
  });

  it("preflights the token path before any API mutation", async () => {
    const fixture = fixtureFile();
    const opener = new FakeOpener();

    await expect(seedFixture(fixture, fixture, { opener })).rejects.toThrow(
      /must not replace/,
    );

    expect(opener.calls).toEqual([]);
  });
});

describe("ApiClient", () => {
  it("bounds the response body and uses the request timeout", async () => {
    const opener = new FakeOpener({
      response: {
        status: 200,
        body: Buffer.from("{" + " ".repeat(MAX_JSON_BYTES)),
      },
    });
    const client = new ApiClient("http://127.0.0.1:7674", opener);

    await expect(client.request("GET", "/api/v1/example")).rejects.toThrow(
      /size limit/,
    );
    expect(opener.calls[0]?.timeout).toBe(REQUEST_TIMEOUT);
  });
});

describe("token and media paths", () => {
  it("does not overwrite an existing token file", () => {
    const token = join(tmp, "pat.txt");
    writeFileSync(token, "existing");
    chmodSync(token, 0o600);

    expect(() => writeToken(token, "new-secret")).toThrow(/already exists/);
    expect(readFileSync(token, "utf-8")).toBe("existing");
  });

  it("requires a local media path", () => {
    for (const url of [
      "https://attacker.invalid/image.jpg",
      "//attacker.invalid/image.jpg",
      "/other/path/image.jpg",
      "/media/image.jpg?token=secret",
    ]) {
      expect(() => mediaPath(url, "media", "/media/projects/1/")).toThrow(
        SeedError,
      );
    }
  });
});
