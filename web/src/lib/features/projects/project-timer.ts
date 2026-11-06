import { ApiError, apiRequest, type SessionData } from "#lib/api.ts";

export const MAX_TIMER_SECONDS = 315_576_000;

export interface ProjectTimer {
  total_seconds: number;
  started_at: string | null;
  server_time: string;
}

export type ProjectTimerAction = "start" | "stop" | "set_total";

/** An older server returns a bare 404 for the timer route it does not know. */
export function isUnsupportedTimerError(error: unknown): boolean {
  return (
    error instanceof ApiError &&
    error.status === 404 &&
    error.code === "invalid_response"
  );
}

export function parseProjectTimer(value: unknown): ProjectTimer {
  if (
    !isRecord(value) ||
    !Number.isSafeInteger(value.total_seconds) ||
    (value.total_seconds as number) < 0 ||
    (value.total_seconds as number) > MAX_TIMER_SECONDS ||
    !(value.started_at === null || isTimestamp(value.started_at)) ||
    !isTimestamp(value.server_time)
  )
    throw invalidResponse();
  return {
    total_seconds: value.total_seconds as number,
    started_at: value.started_at as string | null,
    server_time: value.server_time as string,
  };
}

export async function getProjectTimer(
  projectId: number,
  signal: AbortSignal,
): Promise<ProjectTimer> {
  const payload: unknown = await apiRequest(
    `/api/v2/projects/${projectId}/timer`,
    { signal },
  );
  if (!isRecord(payload) || !("data" in payload)) throw invalidResponse();
  return parseProjectTimer(payload.data);
}

export async function updateProjectTimer(
  projectId: number,
  session: SessionData,
  action: ProjectTimerAction,
  totalSeconds?: number,
  signal?: AbortSignal,
): Promise<ProjectTimer> {
  if (
    action === "set_total" &&
    (!Number.isSafeInteger(totalSeconds) ||
      totalSeconds! < 0 ||
      totalSeconds! > MAX_TIMER_SECONDS)
  )
    throw new RangeError("Invalid project timer total");
  const payload: unknown = await apiRequest(
    `/api/v2/projects/${projectId}/timer`,
    {
      method: "PUT",
      csrfToken: session.csrf_token,
      body:
        action === "set_total"
          ? { action, total_seconds: totalSeconds }
          : { action },
      signal,
    },
  );
  if (!isRecord(payload) || !("data" in payload)) throw invalidResponse();
  return parseProjectTimer(payload.data);
}

export function secondsFromParts(
  hours: string,
  minutes: string,
  seconds: string,
): number | null {
  if (![hours, minutes, seconds].every((part) => /^\d{1,9}$/.test(part)))
    return null;
  const [h, m, s] = [hours, minutes, seconds].map(Number);
  if (m > 59 || s > 59) return null;
  const total = h * 3600 + m * 60 + s;
  return Number.isSafeInteger(total) && total <= MAX_TIMER_SECONDS
    ? total
    : null;
}

export function timerParts(totalSeconds: number): [string, string, string] {
  const safe = Math.min(
    MAX_TIMER_SECONDS,
    Math.max(0, Math.floor(totalSeconds)),
  );
  return [
    String(Math.floor(safe / 3600)),
    String(Math.floor((safe % 3600) / 60)),
    String(safe % 60),
  ];
}

export function elapsedSeconds(
  timer: ProjectTimer,
  nowMilliseconds: number,
  sampleMilliseconds = 0,
): number {
  if (timer.started_at === null) return timer.total_seconds;
  return Math.min(
    MAX_TIMER_SECONDS,
    timer.total_seconds +
      Math.max(0, Math.floor((nowMilliseconds - sampleMilliseconds) / 1000)),
  );
}

export function currentRunSeconds(
  timer: ProjectTimer,
  nowMilliseconds: number,
  sampleMilliseconds = 0,
): number {
  if (timer.started_at === null) return 0;
  const startedAt = Date.parse(timer.started_at);
  const serverAt = Date.parse(timer.server_time);
  if (!Number.isFinite(startedAt) || !Number.isFinite(serverAt)) return 0;
  return Math.max(
    0,
    Math.floor((serverAt - startedAt) / 1000) +
      Math.floor((nowMilliseconds - sampleMilliseconds) / 1000),
  );
}

function isTimestamp(value: unknown): value is string {
  return typeof value === "string" && Number.isFinite(Date.parse(value));
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function invalidResponse(): ApiError {
  return new ApiError(
    "The server returned an invalid response.",
    502,
    "invalid_response",
  );
}
