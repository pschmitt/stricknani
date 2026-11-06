/**
 * Seed disposable Rust API data for end-to-end runs (port of
 * ci/stricknani/seed_rust.py). Only plain-HTTP numeric loopback origins are
 * accepted, redirects are never followed and server error bodies are never
 * exposed.
 */
import { randomBytes } from "node:crypto";
import {
  closeSync,
  constants,
  lstatSync,
  mkdirSync,
  openSync,
  readFileSync,
  rmSync,
  writeSync,
} from "node:fs";
import { isIP } from "node:net";
import { basename, dirname, join, resolve, sep } from "node:path";
import { homedir } from "node:os";
import { loadFixtureEnvironment, REPO_ROOT } from "./fixture";
import { splitUrl } from "./url";

export const MAX_JSON_BYTES = 1024 * 1024;
export const MAX_MEDIA_BYTES = 5 * 1024 * 1024;
export const REQUEST_TIMEOUT = 8;
export const PROJECT_NAME = "Heirloom Baby Blanket";
export const YARN_NAME = "Riverbend Merino DK";
export const SCREENSHOT_PROJECT_DETAIL = {
  needles: "4.0 mm circular, 80 cm",
  stitch_sample: "22 stitches and 30 rows = 10 cm in seed stitch, blocked",
  notes:
    "Knit the border first, then pick up along the edge for the centre panel.",
};
export const CATEGORY_NAME = "Schal";

/** An error safe to display without exposing fixture values or API bodies. */
export class SeedError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "SeedError";
  }
}

/** Thrown by an {@link Opener} for any non-successful HTTP status. */
export class HttpError extends Error {
  readonly code: number;
  constructor(code: number) {
    super(`HTTP ${code}`);
    this.name = "HttpError";
    this.code = code;
  }
}

export interface OpenerRequest {
  url: string;
  method: string;
  headers: Record<string, string>;
  body?: Uint8Array;
}

export interface OpenerResponse {
  status: number;
  /** At most `limit` bytes of the body (callers pass the cap plus one). */
  body: Uint8Array;
}

export interface Opener {
  open(
    request: OpenerRequest,
    timeoutSeconds: number,
    limit: number,
  ): Promise<OpenerResponse>;
}

/** Accept only plain HTTP URLs with an explicit numeric loopback address. */
export function validateBaseUrl(value: string): string {
  const fail = (): never => {
    throw new SeedError("fixture URL must use a numeric loopback address");
  };
  let parsed;
  try {
    parsed = splitUrl(value);
  } catch {
    return fail();
  }
  const family = parsed.hostname === null ? 0 : isIP(parsed.hostname);
  if (parsed.hostname === null || family === 0) return fail();
  const loopback =
    family === 4
      ? parsed.hostname.startsWith("127.")
      : parsed.hostname === "::1";
  if (
    parsed.scheme !== "http" ||
    !loopback ||
    parsed.port === null ||
    parsed.port < 1 ||
    parsed.port > 65535 ||
    parsed.userinfo !== null ||
    (parsed.path !== "" && parsed.path !== "/") ||
    parsed.query ||
    parsed.fragment
  ) {
    throw new SeedError("fixture URL must be a plain HTTP loopback origin");
  }
  return `http://${parsed.netloc}`;
}

async function readLimited(
  response: Response,
  limit: number,
): Promise<Uint8Array> {
  const reader = response.body?.getReader();
  if (reader === undefined) return new Uint8Array();
  const chunks: Uint8Array[] = [];
  let total = 0;
  while (total < limit) {
    const { done, value } = await reader.read();
    if (done) break;
    chunks.push(value);
    total += value.length;
  }
  await reader.cancel().catch(() => undefined);
  return Buffer.concat(chunks).subarray(0, limit);
}

/**
 * Opener without proxy support that never follows redirects, so fixture
 * credentials and bearer tokens cannot be forwarded elsewhere.
 */
export function defaultOpener(): Opener {
  return {
    async open(request, timeoutSeconds, limit) {
      const response = await fetch(request.url, {
        method: request.method,
        headers: request.headers,
        body:
          request.body === undefined ? undefined : Buffer.from(request.body),
        redirect: "manual",
        signal: AbortSignal.timeout(timeoutSeconds * 1000),
      });
      if (response.status >= 300) {
        // Do not read or expose server-provided error bodies.
        await response.body?.cancel().catch(() => undefined);
        throw new HttpError(response.status);
      }
      return {
        status: response.status,
        body: await readLimited(response, limit),
      };
    },
  };
}

export class ApiClient {
  readonly baseUrl: string;
  readonly opener: Opener;
  readonly timeout: number;
  token: string | null = null;

  constructor(
    baseUrl: string,
    opener: Opener,
    opts: { timeout?: number } = {},
  ) {
    this.baseUrl = validateBaseUrl(baseUrl);
    this.opener = opener;
    this.timeout = opts.timeout ?? REQUEST_TIMEOUT;
  }

  async request(
    method: string,
    path: string,
    opts: { payload?: Record<string, unknown>; binary?: boolean } = {},
  ): Promise<unknown> {
    if (!path.startsWith("/api/v1/") && !path.startsWith("/media/")) {
      throw new SeedError("invalid internal API path");
    }
    const binary = opts.binary ?? false;
    const headers: Record<string, string> = {
      Accept: binary ? "application/octet-stream" : "application/json",
    };
    let body: Uint8Array | undefined;
    if (opts.payload !== undefined) {
      body = Buffer.from(JSON.stringify(opts.payload), "utf-8");
      headers["Content-Type"] = "application/json";
    }
    if (this.token !== null) headers.Authorization = `Bearer ${this.token}`;
    const limit = binary ? MAX_MEDIA_BYTES : MAX_JSON_BYTES;
    let status: number;
    let responseBody: Uint8Array;
    try {
      ({ status, body: responseBody } = await this.opener.open(
        { url: `${this.baseUrl}${path}`, method, headers, body },
        this.timeout,
        limit + 1,
      ));
    } catch (error) {
      if (error instanceof HttpError) {
        throw new SeedError(`${method} ${path} returned HTTP ${error.code}`);
      }
      throw new SeedError(`${method} ${path} failed`);
    }
    if (status < 200 || status >= 300) {
      throw new SeedError(`${method} ${path} returned HTTP ${status}`);
    }
    if (responseBody.length > limit) {
      throw new SeedError(`${method} ${path} response exceeded the size limit`);
    }
    if (binary) {
      if (responseBody.length === 0) {
        throw new SeedError(
          `${method} ${path} returned an empty media response`,
        );
      }
      return responseBody;
    }
    let decoded: unknown;
    try {
      decoded = JSON.parse(Buffer.from(responseBody).toString("utf-8"));
    } catch {
      throw new SeedError(`${method} ${path} returned invalid JSON`);
    }
    if (typeof decoded !== "object" || decoded === null) {
      throw new SeedError(`${method} ${path} returned an invalid JSON value`);
    }
    return decoded;
  }
}

export type JsonObject = Record<string, unknown>;

export function asObject(payload: unknown, path: string): JsonObject {
  if (
    typeof payload !== "object" ||
    payload === null ||
    Array.isArray(payload)
  ) {
    throw new SeedError(`${path} returned an invalid object`);
  }
  return payload as JsonObject;
}

export function positiveId(payload: JsonObject, path: string): number {
  const identifier = payload.id;
  if (
    typeof identifier !== "number" ||
    !Number.isInteger(identifier) ||
    identifier <= 0
  ) {
    throw new SeedError(`${path} returned an invalid identifier`);
  }
  return identifier;
}

export function mediaPath(
  value: unknown,
  path: string,
  expectedPrefix: string,
): string {
  if (typeof value !== "string") {
    throw new SeedError(`${path} returned an invalid media link`);
  }
  // Only root-relative references qualify: no scheme, no authority.
  if (!value.startsWith("/") || value.startsWith("//")) {
    throw new SeedError(`${path} returned an invalid media link`);
  }
  let parsed;
  try {
    parsed = splitUrl(`x://h${value}`);
  } catch {
    throw new SeedError(`${path} returned an invalid media link`);
  }
  const mediaSegments = parsed.path.split("/").slice(1);
  if (
    parsed.query ||
    parsed.fragment ||
    !parsed.path.startsWith("/media/") ||
    parsed.path.includes("\\") ||
    parsed.path.includes("%") ||
    mediaSegments.some((segment) => ["", ".", ".."].includes(segment)) ||
    !parsed.path.startsWith(expectedPrefix)
  ) {
    throw new SeedError(`${path} returned an invalid media link`);
  }
  return parsed.path;
}

async function upload(
  client: ApiClient,
  path: string,
  asset: string,
  opts: { altText: string | null },
): Promise<JsonObject> {
  let content: Buffer;
  try {
    content = readFileSync(asset);
  } catch {
    throw new SeedError("checked-in demo image could not be read");
  }
  if (content.length === 0 || content.length > MAX_MEDIA_BYTES) {
    throw new SeedError("checked-in demo image has an invalid size");
  }
  const boundary = `----stricknani-${randomBytes(16).toString("hex")}`;
  // The part name and filename are fixed constants, so neither can inject headers.
  const parts: Buffer[] = [
    Buffer.from(
      `--${boundary}\r\nContent-Disposition: form-data; ` +
        `name="file"; filename="${basename(asset)}"\r\n` +
        "Content-Type: image/jpeg\r\n\r\n",
    ),
    content,
  ];
  if (opts.altText !== null) {
    parts.push(
      Buffer.from(
        `\r\n--${boundary}\r\nContent-Disposition: form-data; ` +
          'name="alt_text"\r\n\r\n' +
          opts.altText,
      ),
    );
  }
  parts.push(Buffer.from(`\r\n--${boundary}--\r\n`));
  let status: number;
  let responseBody: Uint8Array;
  try {
    ({ status, body: responseBody } = await client.opener.open(
      {
        url: `${client.baseUrl}${path}`,
        method: "POST",
        headers: {
          Accept: "application/json",
          Authorization: `Bearer ${client.token}`,
          "Content-Type": `multipart/form-data; boundary=${boundary}`,
        },
        body: Buffer.concat(parts),
      },
      client.timeout,
      MAX_JSON_BYTES + 1,
    ));
  } catch (error) {
    if (error instanceof HttpError) {
      throw new SeedError(`POST ${path} returned HTTP ${error.code}`);
    }
    throw new SeedError(`POST ${path} failed`);
  }
  if (status < 200 || status >= 300) {
    throw new SeedError(`POST ${path} returned HTTP ${status}`);
  }
  if (responseBody.length > MAX_JSON_BYTES) {
    throw new SeedError(`POST ${path} response exceeded the size limit`);
  }
  try {
    return asObject(
      JSON.parse(Buffer.from(responseBody).toString("utf-8")),
      path,
    );
  } catch (error) {
    if (error instanceof SeedError) throw error;
    throw new SeedError(`POST ${path} returned invalid JSON`);
  }
}

function expandUser(path: string): string {
  if (path === "~") return homedir();
  if (path.startsWith("~/")) return join(homedir(), path.slice(2));
  return path;
}

function insideRepository(path: string): boolean {
  return path === REPO_ROOT || path.startsWith(REPO_ROOT + sep);
}

export function writeToken(path: string, token: string): void {
  const resolved = resolve(expandUser(path));
  if (insideRepository(resolved)) {
    throw new SeedError("token file must be outside the repository");
  }
  try {
    mkdirSync(dirname(resolved), { mode: 0o700, recursive: true });
    const descriptor = openSync(
      resolved,
      constants.O_WRONLY | constants.O_CREAT | constants.O_EXCL,
      0o600,
    );
    try {
      writeSync(descriptor, JSON.stringify({ token }));
    } catch (error) {
      closeSync(descriptor);
      rmSync(resolved, { force: true });
      throw error;
    }
    closeSync(descriptor);
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "EEXIST") {
      throw new SeedError("token file already exists");
    }
    throw new SeedError("private token file could not be written");
  }
}

function lexists(path: string): boolean {
  try {
    lstatSync(path);
    return true;
  } catch {
    return false;
  }
}

export function validateTokenPath(path: string, fixtureFile: string): string {
  const candidate = expandUser(path);
  const resolved = resolve(candidate);
  const fixture = resolve(expandUser(fixtureFile));
  if (insideRepository(resolved)) {
    throw new SeedError("token file must be outside the repository");
  }
  if (resolved === fixture) {
    throw new SeedError("token file must not replace the fixture file");
  }
  if (lexists(candidate)) throw new SeedError("token file already exists");
  return resolved;
}

function isRecordArray(value: unknown): value is JsonObject[] {
  return Array.isArray(value);
}

/**
 * Create the demo records and private PAT in an existing disposable fixture.
 *
 * `screenshotDetail` also fills the project's needles, stitch sample and notes,
 * which the Android screenshot lane captures as detail cards. E2E lanes leave
 * them empty.
 */
export async function seedFixture(
  fixtureFile: string,
  tokenFile: string,
  opts: { screenshotDetail?: boolean; opener?: Opener } = {},
): Promise<void> {
  const environment = loadFixtureEnvironment(fixtureFile);
  const tokenTarget = validateTokenPath(tokenFile, fixtureFile);
  const client = new ApiClient(
    environment.STRICKNANI_TEST_URL,
    opts.opener ?? defaultOpener(),
  );

  const tokenPayload = asObject(
    await client.request("POST", "/api/v1/auth/token", {
      payload: {
        email: environment.STRICKNANI_TEST_EMAIL,
        password: environment.STRICKNANI_TEST_PASSWORD,
        token_name: "Android E2E",
      },
    }),
    "/api/v1/auth/token",
  );
  const token = tokenPayload.token;
  if (typeof token !== "string" || !/^sna_[0-9a-f]{64}$/.test(token)) {
    throw new SeedError("/api/v1/auth/token returned an invalid token");
  }
  client.token = token;

  const category = asObject(
    await client.request("POST", "/api/v1/categories", {
      payload: { name: CATEGORY_NAME },
    }),
    "/api/v1/categories",
  );
  if (category.name !== CATEGORY_NAME) {
    throw new SeedError(
      "/api/v1/categories did not create the expected category",
    );
  }

  const yarn = asObject(
    await client.request("POST", "/api/v1/yarns", {
      payload: {
        name: YARN_NAME,
        description: "Soft merino yarn for the blanket sample.",
        brand: "Riverbend Spinning Co.",
        colorway: "Cloud Blue",
        fiber_content: "100% merino wool",
        weight_category: "DK",
        link: "https://example.invalid/yarns/riverbend-merino-dk",
      },
    }),
    "/api/v1/yarns",
  );
  const yarnId = positiveId(yarn, "/api/v1/yarns");
  if (yarn.name !== YARN_NAME) {
    throw new SeedError("/api/v1/yarns returned an unexpected yarn");
  }

  const project = asObject(
    await client.request("POST", "/api/v1/projects", {
      payload: {
        name: PROJECT_NAME,
        category: CATEGORY_NAME,
        description:
          "A soft heirloom blanket knitted in a simple textured stitch.",
        link: "https://example.invalid/projects/heirloom-baby-blanket",
        yarn_ids: [yarnId],
        ...(opts.screenshotDetail ? SCREENSHOT_PROJECT_DETAIL : {}),
      },
    }),
    "/api/v1/projects",
  );
  const projectId = positiveId(project, "/api/v1/projects");
  if (
    project.name !== PROJECT_NAME ||
    project.category !== CATEGORY_NAME ||
    typeof project.description !== "string" ||
    !project.description.trim() ||
    !sameIds(project.yarn_ids, [yarnId])
  ) {
    throw new SeedError("/api/v1/projects returned an invalid demo project");
  }

  const assetRoot = join(REPO_ROOT, "demo_assets");
  const yarnPhoto = await upload(
    client,
    `/api/v1/yarns/${yarnId}/photos`,
    join(assetRoot, "demo_image_1.jpg"),
    { altText: null },
  );
  const projectImage = await upload(
    client,
    `/api/v1/projects/${projectId}/images/title`,
    join(assetRoot, "demo_image_3.jpg"),
    { altText: "Heirloom baby blanket" },
  );
  const yarnUrl = mediaPath(
    yarnPhoto.url,
    "yarn photo",
    `/media/yarns/${yarnId}/`,
  );
  const yarnThumbnail = mediaPath(
    yarnPhoto.thumbnail_url,
    "yarn photo",
    `/media/thumbnails/yarns/${yarnId}/`,
  );
  const projectUrl = mediaPath(
    projectImage.url,
    "project image",
    `/media/projects/${projectId}/`,
  );
  const projectThumbnail = mediaPath(
    projectImage.thumbnail_url,
    "project image",
    `/media/thumbnails/projects/${projectId}/`,
  );
  if (!yarnPhoto.alt_text || !projectImage.alt_text) {
    throw new SeedError("media upload returned an invalid alternative text");
  }

  const yarnDetail = asObject(
    await client.request("GET", `/api/v1/yarns/${yarnId}`),
    "/api/v1/yarns/{id}",
  );
  const projectDetail = asObject(
    await client.request("GET", `/api/v1/projects/${projectId}`),
    "/api/v1/projects/{id}",
  );
  if (
    yarnDetail.name !== YARN_NAME ||
    !isRecordArray(yarnDetail.photos) ||
    !yarnDetail.photos.some(
      (photo) => isObject(photo) && photo.url === yarnUrl,
    ) ||
    projectDetail.name !== PROJECT_NAME ||
    !sameIds(projectDetail.yarn_ids, [yarnId]) ||
    !isRecordArray(projectDetail.images) ||
    !projectDetail.images.some(
      (image) => isObject(image) && image.url === projectUrl,
    )
  ) {
    throw new SeedError(
      "detail API did not retain seeded media and associations",
    );
  }

  const projectList = asObject(
    await client.request("GET", "/api/v1/projects?page=1"),
    "/api/v1/projects",
  );
  const yarnList = asObject(
    await client.request("GET", "/api/v1/yarns?page=1"),
    "/api/v1/yarns",
  );
  const projectItem = findItem(projectList.items, projectId);
  const yarnItem = findItem(yarnList.items, yarnId);
  if (
    projectItem === undefined ||
    yarnItem === undefined ||
    projectItem.name !== PROJECT_NAME ||
    yarnItem.name !== YARN_NAME ||
    mediaPath(
      projectItem.preview_url,
      "project list",
      `/media/thumbnails/projects/${projectId}/`,
    ) !== projectThumbnail ||
    mediaPath(
      yarnItem.preview_url,
      "yarn list",
      `/media/thumbnails/yarns/${yarnId}/`,
    ) !== yarnThumbnail
  ) {
    throw new SeedError("list API did not expose the seeded previews");
  }

  // Verify the returned paths are actually served by this loopback fixture.
  await client.request("GET", yarnUrl, { binary: true });
  await client.request("GET", yarnThumbnail, { binary: true });
  await client.request("GET", projectUrl, { binary: true });
  await client.request("GET", projectThumbnail, { binary: true });
  writeToken(tokenTarget, token);
}

function isObject(value: unknown): value is JsonObject {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function sameIds(value: unknown, expected: number[]): boolean {
  return (
    Array.isArray(value) &&
    value.length === expected.length &&
    value.every((item, index) => item === expected[index])
  );
}

function findItem(items: unknown, id: number): JsonObject | undefined {
  if (!Array.isArray(items)) return undefined;
  return items.find(
    (item): item is JsonObject => isObject(item) && item.id === id,
  );
}
