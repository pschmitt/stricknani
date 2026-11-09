import { ApiError, apiRequest, type SessionData } from "#lib/api.ts";

export const MAX_TIMER_SECONDS = 315_576_000;

export interface ProjectTimer {
  total_seconds: number;
  started_at: string | null;
  /** The user who started the running timer; null while stopped. Only that user is notified. */
  started_by: number | null;
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
    !(
      value.started_by === undefined ||
      value.started_by === null ||
      Number.isSafeInteger(value.started_by)
    ) ||
    !isTimestamp(value.server_time)
  )
    throw invalidResponse();
  return {
    total_seconds: value.total_seconds as number,
    started_at: value.started_at as string | null,
    started_by: (value.started_by as number | null | undefined) ?? null,
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

/** Whether this user started the running timer; only they get timer reminders. */
export function startedByUser(
  timer: ProjectTimer | null,
  userId: number | undefined,
): boolean {
  return (
    timer !== null &&
    timer.started_at !== null &&
    userId !== undefined &&
    timer.started_by === userId
  );
}

/** `1 h 05 min` / `45 min`; the same in both languages and on the server. */
export function formatElapsed(totalSeconds: number): string {
  const minutes = Math.floor(Math.max(0, totalSeconds) / 60);
  return minutes >= 60
    ? `${Math.floor(minutes / 60)} h ${String(minutes % 60).padStart(2, "0")} min`
    : `${minutes} min`;
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
