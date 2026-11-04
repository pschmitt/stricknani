// Pure helpers for the synthetic mobile performance journey: the gzip
// reverse proxy and the lab INP estimation. They have no Playwright runtime
// dependency so that vitest can exercise them directly.
import { request as httpRequest, createServer } from "node:http";
import type { IncomingHttpHeaders, Server } from "node:http";
import type { AddressInfo } from "node:net";
import { gzipSync } from "node:zlib";

export const SAMPLE_COUNT = 5;
export const JS_BUDGET = 150 * 1024;
export const CSS_BUDGET = 50 * 1024;
export const EVENT_DURATION_THRESHOLD_MS = 16;
export const LAB_INP_TARGET_MS = 200;
export const MIN_OBSERVED_INTERACTION_COVERAGE = 0.5;
export const GZIP_TYPES = [
  "application/javascript",
  "application/json",
  "application/manifest+json",
  "application/x-javascript",
  "image/svg+xml",
  "text/css",
  "text/html",
  "text/plain",
  "text/javascript",
];

/** A safe failure with no page content or fixture credentials. */
export class PerformanceFailure extends Error {
  constructor(message: string) {
    super(message);
    this.name = "PerformanceFailure";
  }
}

/** Python-style round(value, digits). */
export function roundTo(value: number, digits: number): number {
  const factor = 10 ** digits;
  return Math.round(value * factor) / factor;
}

/** gzip level 6 with a zero mtime, as the Python runner measured it. */
export function gzipLevel6(body: Uint8Array): Buffer {
  return gzipSync(body, { level: 6 });
}

/** Return gzip bytes when this synthetic proxy should compress a response. */
export function gzipResponse(
  acceptEncoding: string,
  contentType: string,
  body: Uint8Array,
): Buffer | null {
  let acceptsGzip = false;
  for (const token of acceptEncoding.split(",")) {
    const [encoding = "", ...parameters] = token.trim().split(";");
    if (encoding.toLowerCase() !== "gzip") {
      continue;
    }
    let quality = "1";
    for (const parameter of parameters) {
      const separator = parameter.indexOf("=");
      const name = (separator < 0 ? parameter : parameter.slice(0, separator))
        .trim()
        .toLowerCase();
      if (name === "q") {
        quality = separator < 0 ? "" : parameter.slice(separator + 1).trim();
        break;
      }
    }
    const parsed = quality === "" ? Number.NaN : Number(quality);
    acceptsGzip = Number.isFinite(parsed) && parsed > 0;
    break;
  }
  const mediaType = (contentType.split(";", 1)[0] ?? "").trim().toLowerCase();
  if (!acceptsGzip || !GZIP_TYPES.includes(mediaType) || body.length < 1024) {
    return null;
  }
  return gzipLevel6(body);
}

const HOP_HEADERS = [
  "connection",
  "keep-alive",
  "proxy-authenticate",
  "proxy-authorization",
  "te",
  "trailers",
  "transfer-encoding",
  "upgrade",
];

function headerValue(headers: IncomingHttpHeaders, name: string): string {
  const value = headers[name];
  if (Array.isArray(value)) {
    return value.join(", ");
  }
  return value ?? "";
}

/** Local reverse proxy that models gzip transport without external tools. */
export class LoopbackGzipProxy {
  readonly origin: string;
  private readonly server: Server;

  private constructor(server: Server, port: number) {
    this.server = server;
    this.origin = `http://127.0.0.1:${port}`;
  }

  static async start(backendOrigin: string): Promise<LoopbackGzipProxy> {
    let parsed: URL;
    try {
      parsed = new URL(backendOrigin);
    } catch {
      throw new PerformanceFailure("fixture origin cannot be proxied safely");
    }
    if (parsed.protocol !== "http:" || !parsed.hostname || !parsed.port) {
      throw new PerformanceFailure("fixture origin cannot be proxied safely");
    }
    const backendHost = parsed.hostname;
    const backendPort = Number(parsed.port);

    const server = createServer((req, res) => {
      const chunks: Buffer[] = [];
      let received = 0;
      let rejected = false;
      const declared = Number(req.headers["content-length"] ?? "0");
      if (!Number.isFinite(declared) || declared < 0) {
        req.socket.destroy();
        return;
      }
      if (declared > 10 * 1024 * 1024) {
        res.writeHead(413, { Connection: "close" });
        res.end();
        return;
      }
      req.on("data", (chunk: Buffer) => {
        received += chunk.length;
        if (received > 10 * 1024 * 1024) {
          rejected = true;
          return;
        }
        chunks.push(chunk);
      });
      req.on("error", () => {
        req.socket.destroy();
      });
      req.on("end", () => {
        if (rejected) {
          res.writeHead(413, { Connection: "close" });
          res.end();
          return;
        }
        const requestBody = Buffer.concat(chunks);
        const requestHeaders: Record<string, string | string[]> = {};
        for (const [name, value] of Object.entries(req.headers)) {
          if (
            value === undefined ||
            HOP_HEADERS.includes(name) ||
            name === "accept-encoding"
          ) {
            continue;
          }
          requestHeaders[name] = value;
        }
        // Keep the browser-facing Host, Origin, Cookie and CSRF headers
        // intact while asking the backend for identity-encoded bytes.
        requestHeaders["accept-encoding"] = "identity";
        const upstreamRequest = httpRequest(
          {
            host: backendHost,
            port: backendPort,
            method: req.method,
            path: req.url,
            headers: requestHeaders,
            agent: false,
            timeout: 20_000,
          },
          (upstream) => {
            const bodyChunks: Buffer[] = [];
            upstream.on("data", (chunk: Buffer) => bodyChunks.push(chunk));
            upstream.on("error", () => req.socket.destroy());
            upstream.on("end", () => {
              let responseBody: Buffer = Buffer.concat(bodyChunks);
              const compressed = gzipResponse(
                headerValue(req.headers, "accept-encoding"),
                headerValue(upstream.headers, "content-type"),
                responseBody,
              );
              if (compressed !== null) {
                responseBody = compressed;
              }
              const skipped = new Set([...HOP_HEADERS, "content-length"]);
              if (compressed !== null) {
                skipped.add("content-encoding");
                skipped.add("vary");
              }
              const outgoing: Record<string, string | string[]> = {};
              for (const [name, value] of Object.entries(upstream.headers)) {
                if (value !== undefined && !skipped.has(name)) {
                  outgoing[name] = value;
                }
              }
              if (compressed !== null) {
                outgoing["content-encoding"] = "gzip";
                let vary = headerValue(upstream.headers, "vary");
                const tokens = vary
                  .split(",")
                  .map((item) => item.trim().toLowerCase());
                if (!tokens.includes("accept-encoding")) {
                  vary = vary ? `${vary}, Accept-Encoding` : "Accept-Encoding";
                }
                outgoing["vary"] = vary;
              }
              outgoing["content-length"] = String(responseBody.length);
              outgoing["connection"] = "close";
              res.writeHead(
                upstream.statusCode ?? 502,
                upstream.statusMessage ?? "",
                outgoing,
              );
              res.end(req.method === "HEAD" ? undefined : responseBody);
            });
          },
        );
        upstreamRequest.on("timeout", () => upstreamRequest.destroy());
        upstreamRequest.on("error", () => req.socket.destroy());
        upstreamRequest.end(requestBody.length > 0 ? requestBody : undefined);
      });
    });
    await new Promise<void>((resolve, reject) => {
      server.once("error", reject);
      server.listen(0, "127.0.0.1", () => resolve());
    });
    const address = server.address() as AddressInfo;
    return new LoopbackGzipProxy(server, address.port);
  }

  async close(): Promise<void> {
    await new Promise<void>((resolve) => {
      this.server.close(() => resolve());
      this.server.closeAllConnections();
    });
  }
}

export type InteractionGroup = {
  interaction_id: number;
  duration_ms: number;
  event_count: number;
  event_types: Set<string>;
};

export type LabInp = {
  lab_inp_candidate_ms: number;
  lab_inp_candidate_index: number;
  lab_inp_target_ms: number;
  lab_inp_target_pass: boolean;
  document_interaction_count: number;
  observed_interaction_count: number;
  observed_interaction_coverage: number;
  lab_inp_candidate_assessable: boolean;
  event_timing_entry_count: number;
  observed_duration_threshold_ms: number;
  interaction_duration_buckets: Record<string, number>;
  event_type_interaction_counts: Record<string, number>;
};

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

export function interactionGroups(entries: unknown): InteractionGroup[] {
  if (!Array.isArray(entries)) {
    throw new PerformanceFailure(
      "browser Event Timing entries were unavailable",
    );
  }
  const groups = new Map<number, InteractionGroup>();
  for (const entry of entries as unknown[]) {
    if (!isRecord(entry)) {
      throw new PerformanceFailure("browser Event Timing entry was invalid");
    }
    const interactionId = entry["interaction_id"];
    const duration = entry["duration_ms"];
    const eventType = entry["event_type"];
    if (
      typeof interactionId !== "number" ||
      !Number.isInteger(interactionId) ||
      interactionId <= 0 ||
      typeof duration !== "number" ||
      !Number.isFinite(duration) ||
      duration < EVENT_DURATION_THRESHOLD_MS ||
      typeof eventType !== "string"
    ) {
      throw new PerformanceFailure("browser Event Timing entry was invalid");
    }
    let group = groups.get(interactionId);
    if (group === undefined) {
      group = {
        interaction_id: interactionId,
        duration_ms: duration,
        event_count: 0,
        event_types: new Set<string>(),
      };
      groups.set(interactionId, group);
    }
    group.duration_ms = Math.max(group.duration_ms, duration);
    group.event_count += 1;
    group.event_types.add(eventType);
  }
  return [...groups.values()];
}

export function estimateLabInp(
  entries: unknown,
  interactionCount: unknown,
): LabInp {
  const groups = interactionGroups(entries);
  if (typeof interactionCount !== "number") {
    throw new PerformanceFailure("document interaction count was unavailable");
  }
  if (!Number.isFinite(interactionCount) || interactionCount <= 0) {
    throw new PerformanceFailure(
      "document did not record trusted interactions",
    );
  }
  if (groups.length === 0) {
    throw new PerformanceFailure(
      "Event Timing contained no measured interactions",
    );
  }
  if (interactionCount < groups.length) {
    throw new PerformanceFailure(
      "Event Timing groups exceeded document interaction count",
    );
  }

  groups.sort((a, b) => b.duration_ms - a.duration_ms);
  const candidates = groups.slice(0, 10);
  const candidateIndex = Math.min(
    candidates.length - 1,
    Math.floor(interactionCount / 50),
  );
  const candidateMs = (candidates[candidateIndex] as InteractionGroup)
    .duration_ms;
  const coverage = groups.length / interactionCount;
  const buckets: Record<string, number> = {
    "16_to_39_ms": 0,
    "40_to_99_ms": 0,
    "100_to_199_ms": 0,
    "200_ms_or_more": 0,
  };
  const eventTypeCounts = new Map<string, number>();
  let eventCount = 0;
  for (const group of groups) {
    const duration = group.duration_ms;
    if (duration < 40) {
      buckets["16_to_39_ms"] = (buckets["16_to_39_ms"] ?? 0) + 1;
    } else if (duration < 100) {
      buckets["40_to_99_ms"] = (buckets["40_to_99_ms"] ?? 0) + 1;
    } else if (duration < LAB_INP_TARGET_MS) {
      buckets["100_to_199_ms"] = (buckets["100_to_199_ms"] ?? 0) + 1;
    } else {
      buckets["200_ms_or_more"] = (buckets["200_ms_or_more"] ?? 0) + 1;
    }
    for (const eventType of group.event_types) {
      eventTypeCounts.set(eventType, (eventTypeCounts.get(eventType) ?? 0) + 1);
    }
    eventCount += group.event_count;
  }
  const sortedTypes: Record<string, number> = {};
  for (const key of [...eventTypeCounts.keys()].sort()) {
    sortedTypes[key] = eventTypeCounts.get(key) as number;
  }

  return {
    lab_inp_candidate_ms: roundTo(candidateMs, 1),
    lab_inp_candidate_index: candidateIndex,
    lab_inp_target_ms: LAB_INP_TARGET_MS,
    lab_inp_target_pass: candidateMs <= LAB_INP_TARGET_MS,
    document_interaction_count: interactionCount,
    observed_interaction_count: groups.length,
    observed_interaction_coverage: roundTo(coverage, 3),
    lab_inp_candidate_assessable:
      groups.length >= 8 && coverage >= MIN_OBSERVED_INTERACTION_COVERAGE,
    event_timing_entry_count: eventCount,
    observed_duration_threshold_ms: EVENT_DURATION_THRESHOLD_MS,
    interaction_duration_buckets: buckets,
    event_type_interaction_counts: sortedTypes,
  };
}

export function newInteractionLatency(
  before: unknown,
  after: unknown,
): number | null {
  const previous = new Set(
    interactionGroups(before).map((group) => group.interaction_id),
  );
  const durations = interactionGroups(after)
    .filter((group) => !previous.has(group.interaction_id))
    .map((group) => group.duration_ms);
  return durations.length > 0 ? Math.max(...durations) : null;
}

/** Median of the numeric values under `key`, rounded to 0.1, or null. */
export function median(
  samples: Array<Record<string, unknown>>,
  key: string,
): number | null {
  const values = samples
    .map((sample) => sample[key])
    .filter((value): value is number => typeof value === "number")
    .sort((a, b) => a - b);
  if (values.length === 0) {
    return null;
  }
  const middle = Math.floor(values.length / 2);
  const value =
    values.length % 2 === 1
      ? (values[middle] as number)
      : ((values[middle - 1] as number) + (values[middle] as number)) / 2;
  return roundTo(value, 1);
}
