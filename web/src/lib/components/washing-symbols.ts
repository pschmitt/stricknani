export const washTemperatures = ["30", "40", "60"] as const;
export const dryingOptions = ["flat", "line", "no_tumble"] as const;

export type WashTemperature = (typeof washTemperatures)[number];
export type Drying = (typeof dryingOptions)[number];

export interface WashingSymbols {
  temperature?: WashTemperature | null;
  drying?: Drying | null;
}

export function isWashingSymbols(value: unknown): value is WashingSymbols {
  if (!value || typeof value !== "object" || Array.isArray(value)) return false;
  const { temperature, drying } = value as Record<string, unknown>;
  return (
    (temperature == null ||
      washTemperatures.some((option) => option === temperature)) &&
    (drying == null || dryingOptions.some((option) => option === drying))
  );
}
