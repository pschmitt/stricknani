import { apiRequest } from "#lib/api.ts";
import { t } from "#lib/i18n.svelte.ts";

export interface GaugeInput {
  pattern_gauge_stitches: number;
  pattern_gauge_rows: number;
  user_gauge_stitches: number;
  user_gauge_rows: number;
  pattern_cast_on_stitches: number;
  pattern_row_count?: number;
}

export interface GaugeResult {
  adjusted_stitches: string;
  adjusted_rows: string | null;
}

interface DataEnvelope<T> {
  data: T;
}

export async function calculateGauge(
  input: GaugeInput,
  signal?: AbortSignal,
): Promise<GaugeResult> {
  const envelope = await apiRequest<unknown>("/api/v2/tools/gauge", {
    method: "POST",
    body: input,
    signal,
  });
  if (!isDataEnvelope(envelope) || !isGaugeResult(envelope.data)) {
    throw new Error(t("The calculator returned an unreadable response."));
  }
  return envelope.data;
}

function isDataEnvelope(value: unknown): value is DataEnvelope<unknown> {
  return (
    typeof value === "object" &&
    value !== null &&
    !Array.isArray(value) &&
    "data" in value
  );
}

function isGaugeResult(value: unknown): value is GaugeResult {
  if (typeof value !== "object" || value === null || Array.isArray(value))
    return false;
  const candidate = value as Record<string, unknown>;
  return (
    typeof candidate.adjusted_stitches === "string" &&
    /^\d+$/.test(candidate.adjusted_stitches) &&
    (candidate.adjusted_rows === null ||
      (typeof candidate.adjusted_rows === "string" &&
        /^\d+$/.test(candidate.adjusted_rows)))
  );
}
