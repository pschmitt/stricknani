import { msg } from "#lib/i18n.svelte.ts";
import type { MascotMood } from "#lib/components/Mascot.svelte";

export type ErrorKind =
  | "not-found"
  | "project-missing"
  | "yarn-missing"
  | "forbidden"
  | "server"
  | "offline";

export type ErrorCopy = {
  /** Small label above the headline, usually the status in plain words. */
  kicker: string;
  title: string;
  body: string;
  mood: MascotMood;
  quips: readonly string[];
};

// Every string is a literal msg() key so the i18n extractor finds it.
export const ERROR_COPY: Record<ErrorKind, ErrorCopy> = {
  "not-found": {
    kicker: msg("404 · Page not found"),
    title: msg("This page dropped a stitch"),
    body: msg(
      "We checked the whole basket: under the sofa, behind the cat, inside that suspiciously lumpy sock. Nothing. Whatever lived here has been frogged.",
    ),
    mood: "confused",
    quips: [
      msg("Knit one, purl none."),
      msg(
        "In the great ball of yarn that is the internet, this is a loose end.",
      ),
      msg("Rumour has it a cat was involved."),
      msg("Tip: a missing page is just a very large yarn over."),
    ],
  },
  "project-missing": {
    kicker: msg("404 · Project not found"),
    title: msg("This project has unravelled"),
    body: msg(
      "We pulled and pulled, but there is no project at the end of this thread. It may have been frogged, or it lives in someone else’s basket.",
    ),
    mood: "dizzy",
    quips: [
      msg("Do not worry, ripping back is a perfectly respectable hobby."),
      msg("Somewhere, a tension swatch is laughing."),
    ],
  },
  "yarn-missing": {
    kicker: msg("404 · Yarn not found"),
    title: msg("This yarn rolled away"),
    body: msg(
      "It probably rolled under the sofa. Cats are suspects. It may also have been used up, or it was never in your stash.",
    ),
    mood: "confused",
    quips: [
      msg("Stash rule one: you can never have too much, only too little."),
      msg("Check the pockets of your other cardigan."),
    ],
  },
  forbidden: {
    kicker: msg("403 · Not allowed"),
    title: msg("Hands off the stash!"),
    body: msg(
      "This basket belongs to someone else. You will need an invitation, or at least a very good cookie.",
    ),
    mood: "tired",
    quips: [msg("Looking is free. Touching the wool is not.")],
  },
  server: {
    kicker: msg("Something went wrong"),
    title: msg("Well, that got tangled"),
    body: msg(
      "Something went wrong on our side. The yarn ball rolled off the table and the server got itself wrapped up in it. Give it a moment, then try again.",
    ),
    mood: "dizzy",
    quips: [
      msg("Have you tried turning the yarn off and on again?"),
      msg("Do not worry, your projects are safe in the basket."),
    ],
  },
  offline: {
    kicker: msg("No connection"),
    title: msg("Winky lost the thread"),
    body: msg(
      "We cannot reach Stricknani right now. Check your connection, because Wi-Fi loves hiding in the yarn basket.",
    ),
    mood: "sad",
    quips: [
      msg("Wi-Fi is just invisible yarn. Check that it has not snapped."),
    ],
  },
};

const NETWORK_FAILURE =
  /failed to fetch|load failed|networkerror|network request failed|dynamically imported module|importing a module script failed/i;

/** Pick the page that fits a SvelteKit error: a status code, or a dropped connection. */
export function errorKindForStatus(
  status: number,
  message: string,
  online: boolean,
): ErrorKind {
  if (status === 404) return "not-found";
  if (status === 401 || status === 403) return "forbidden";
  if (!online || status === 0 || NETWORK_FAILURE.test(message)) {
    return "offline";
  }
  return "server";
}

/** The same seed always gets the same quip, so a page does not change under the reader. */
export function pickQuip(seed: string, quips: readonly string[]): string {
  if (quips.length === 0) return "";
  let hash = 0;
  for (const char of seed) hash = (hash * 31 + char.charCodeAt(0)) >>> 0;
  return quips[hash % quips.length];
}
