export type ShareOutcome = "shared" | "copied" | "cancelled" | "failed";

interface ShareEnvironment {
  share?: (data: { title?: string; url: string }) => Promise<void>;
  clipboard?: { writeText: (text: string) => Promise<void> };
}

/**
 * Shares a link: the system share sheet where the browser has one, otherwise the clipboard.
 * Dismissing the share sheet is not an error; a failed share falls back to copying.
 */
export async function shareLink(
  url: string,
  title: string,
  env: ShareEnvironment = typeof navigator === "undefined" ? {} : navigator,
): Promise<ShareOutcome> {
  if (typeof env.share === "function") {
    try {
      await env.share.call(env, { title, url });
      return "shared";
    } catch (error) {
      if (error instanceof DOMException && error.name === "AbortError")
        return "cancelled";
    }
  }
  try {
    if (!env.clipboard) return "failed";
    await env.clipboard.writeText(url);
    return "copied";
  } catch {
    return "failed";
  }
}

/** The address of the current page without query or fragment, suitable for sharing. */
export function shareableUrl(location: Pick<URL, "origin" | "pathname">) {
  return `${location.origin}${location.pathname}`;
}
