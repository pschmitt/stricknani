import { shareableUrl, shareLink, type ShareOutcome } from "./share-link.ts";

/** Shares the current page's address: the system share sheet, else the clipboard. */
export function shareCurrentPage(title: string): Promise<ShareOutcome> {
  return shareLink(shareableUrl(window.location), title);
}
