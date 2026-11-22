export const washTemperatures = ["30", "40", "60"] as const;
export const dryingOptions = ["flat", "line", "no_tumble"] as const;

export type WashTemperature = (typeof washTemperatures)[number];
export type Drying = (typeof dryingOptions)[number];

export interface WashingSymbols {
  temperature?: WashTemperature | null;
  hand_wash_only?: boolean | null;
  drying?: Drying | null;
}

export function isWashingSymbols(value: unknown): value is WashingSymbols {
  if (!value || typeof value !== "object" || Array.isArray(value)) return false;
  const { temperature, drying, hand_wash_only } = value as Record<
    string,
    unknown
  >;
  return (
    (temperature == null ||
      washTemperatures.some((option) => option === temperature)) &&
    (hand_wash_only == null || typeof hand_wash_only === "boolean") &&
    (drying == null || dryingOptions.some((option) => option === drying))
  );
}
