import type { GaugeInput } from "./gauge-api";

export type GaugeField =
  | "pattern_gauge_stitches"
  | "pattern_gauge_rows"
  | "user_gauge_stitches"
  | "user_gauge_rows"
  | "pattern_cast_on_stitches"
  | "pattern_row_count";
export type GaugeValue = number | string | null | undefined;
/** `empty` is not an error until the user leaves the field; `invalid` is one right away. */
export type GaugeFieldState = "ok" | "empty" | "invalid";

export const REQUIRED_GAUGE_FIELDS = [
  "pattern_gauge_stitches",
  "pattern_gauge_rows",
  "user_gauge_stitches",
  "user_gauge_rows",
  "pattern_cast_on_stitches",
] as const;

export const GAUGE_FIELDS: readonly GaugeField[] = [
  ...REQUIRED_GAUGE_FIELDS,
  "pattern_row_count",
];

export interface GaugeParse {
  /** The request body when every required field (and the optional one, if present) is valid. */
  input: GaugeInput | null;
  states: Record<GaugeField, GaugeFieldState>;
}

function isBlank(value: GaugeValue): boolean {
  return (
    value === undefined ||
    value === null ||
    (typeof value === "string" && !value.trim())
  );
}

export function parsePositiveU32(value: GaugeValue): number | null {
  if (isBlank(value)) return null;
  const parsed = typeof value === "number" ? value : Number(value);
  return Number.isSafeInteger(parsed) && parsed > 0 && parsed <= 0xffff_ffff
    ? parsed
    : null;
}

export function parseGaugeValues(
  values: Record<GaugeField, GaugeValue>,
): GaugeParse {
  const states = {} as Record<GaugeField, GaugeFieldState>;
  const parsed: Partial<Record<GaugeField, number>> = {};
  for (const field of GAUGE_FIELDS) {
    const value = values[field];
    const number = parsePositiveU32(value);
    if (number !== null) {
      states[field] = "ok";
      parsed[field] = number;
    } else if (isBlank(value)) {
      // The optional field may stay empty.
      states[field] = field === "pattern_row_count" ? "ok" : "empty";
    } else {
      states[field] = "invalid";
    }
  }
  if (GAUGE_FIELDS.some((field) => states[field] !== "ok")) {
    return { input: null, states };
  }
  const input: GaugeInput = {
    pattern_gauge_stitches: parsed.pattern_gauge_stitches!,
    pattern_gauge_rows: parsed.pattern_gauge_rows!,
    user_gauge_stitches: parsed.user_gauge_stitches!,
    user_gauge_rows: parsed.user_gauge_rows!,
    pattern_cast_on_stitches: parsed.pattern_cast_on_stitches!,
  };
  if (parsed.pattern_row_count !== undefined)
    input.pattern_row_count = parsed.pattern_row_count;
  return { input, states };
}
