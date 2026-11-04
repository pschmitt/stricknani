// Where the Stricknani Android app lives. Change the download target here only.

/** Signed APKs from the latest GitHub release. */
export const androidDownloadUrl =
  "https://github.com/pschmitt/stricknani/releases/latest";

/**
 * Google Play listing. Keep null while the app is an internal test only; once it is public, set
 * the listing URL and every placement offers a "Get it on Google Play" text button instead.
 */
export const playStoreUrl: string | null = null;

/** The account page section that issues API tokens and pairs the app. */
export const connectAppHash = "#tokens-title";

export const appCardDismissedKey = "stricknani.appCardDismissed";

export type AndroidAction = { kind: "play" | "apk"; url: string };

export function androidAction(
  play: string | null = playStoreUrl,
  apk: string = androidDownloadUrl,
): AndroidAction {
  return play ? { kind: "play", url: play } : { kind: "apk", url: apk };
}

export type AppCardMode = "hidden" | "prominent" | "compact";

export interface AppCardEnvironment {
  userAgent: string;
  /** Running as an installed PWA (display-mode: standalone). */
  standalone: boolean;
  dismissed: boolean;
}

export function isAndroidUserAgent(userAgent: string): boolean {
  return /android/i.test(userAgent);
}

/**
 * How the Home card shows: prominent in an Android browser, compact elsewhere, gone once dismissed
 * and gone in an installed Android PWA (the More entry still offers the app there).
 */
export function appCardMode(env: AppCardEnvironment): AppCardMode {
  if (env.dismissed) return "hidden";
  const android = isAndroidUserAgent(env.userAgent);
  if (android && env.standalone) return "hidden";
  return android ? "prominent" : "compact";
}

export function readAppCardDismissed(): boolean {
  try {
    if (typeof localStorage === "undefined") return false;
    return localStorage.getItem(appCardDismissedKey) === "1";
  } catch {
    return false;
  }
}

export function writeAppCardDismissed(): void {
  try {
    if (typeof localStorage === "undefined") return;
    localStorage.setItem(appCardDismissedKey, "1");
  } catch {
    // Storage unavailable: the card stays dismissed for this visit only.
  }
}

export function isStandaloneDisplay(): boolean {
  try {
    return (
      typeof matchMedia === "function" &&
      matchMedia("(display-mode: standalone)").matches
    );
  } catch {
    return false;
  }
}
