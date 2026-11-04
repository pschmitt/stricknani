// Self-contained playful error pages: 404 (served by the Rust server for unknown paths)
// and 502/503/504 (served by nginx when the server is down). Each page is one HTML file
// with inline CSS, the inline mascot, and a tiny inline script that picks English or
// German from the browser language. Without JavaScript both languages are shown.
//
// They are written into the build as `errors/<status>.html` before the CSP manifest is
// computed, so their inline style and script are covered by the same hash list.
import { mkdir, writeFile } from "node:fs/promises";
import { join } from "node:path";

/** @typedef {"confused" | "dizzy" | "tired" | "sleepy" | "sad"} Mood */
/** @typedef {{ kicker: string, title: string, body: string, quip: string, fine: string, retry: string }} Copy */
/**
 * @typedef {{
 *   status: number,
 *   mood: Mood,
 *   refresh: boolean,
 *   en: Copy,
 *   de: Copy,
 * }} ErrorPage
 */

/** @type {readonly ErrorPage[]} */
export const ERROR_PAGES = [
  {
    status: 404,
    mood: "confused",
    refresh: false,
    en: {
      kicker: "404 · Page not found",
      title: "This page dropped a stitch",
      body: "We checked the whole basket: under the sofa, behind the cat, inside that suspiciously lumpy sock. Nothing. Whatever lived here has been frogged.",
      quip: "Knit one, purl none.",
      fine: "Rows searched: 404 · Rows found: 0",
      retry: "",
    },
    de: {
      kicker: "404 · Seite nicht gefunden",
      title: "Hier ist eine Masche gefallen",
      body: "Wir haben den ganzen Wollkorb durchwühlt: unterm Sofa, hinter der Katze, im verdächtig dicken Socken. Nichts. Was hier mal wohnte, wurde aufgeribbelt.",
      quip: "Eine rechts, keine links.",
      fine: "Reihen durchsucht: 404 · Reihen gefunden: 0",
      retry: "",
    },
  },
  {
    status: 502,
    mood: "dizzy",
    refresh: true,
    en: {
      kicker: "502 · Bad gateway",
      title: "Our server dropped the yarn",
      body: "Something at the back of the shop answered with pure wool salad. It is probably tangled. We are untangling it, so try again in a minute. Your projects are safe in the basket.",
      quip: "Have you tried turning the yarn off and on again?",
      fine: "Yarn ball status: tangled",
      retry: "This page tries again by itself every minute.",
    },
    de: {
      kicker: "502 · Fehlerhaftes Gateway",
      title: "Dem Server ist das Garn runtergefallen",
      body: "Hinten im Laden kam nur Wollsalat an. Vermutlich hat sich etwas verheddert. Wir entwirren das gerade, versuch es in einer Minute noch einmal. Deine Projekte liegen sicher im Wollkorb.",
      quip: "Schon mal versucht, das Garn aus- und wieder einzuschalten?",
      fine: "Wollknäuel-Status: verheddert",
      retry: "Diese Seite versucht es jede Minute von selbst noch einmal.",
    },
  },
  {
    status: 503,
    mood: "tired",
    refresh: true,
    en: {
      kicker: "503 · Service unavailable",
      title: "Stricknani is on a yarn break",
      body: "The server is catching its breath, or getting a fresh update. Back in a few minutes. Your projects and yarn are safe in the basket.",
      quip: "Even the best knitters need a cup of tea.",
      fine: "Estimated return: after one more cup of tea",
      retry: "This page tries again by itself every minute.",
    },
    de: {
      kicker: "503 · Dienst nicht verfügbar",
      title: "Stricknani macht Strickpause",
      body: "Der Server holt kurz Luft oder bekommt gerade ein Update. In ein paar Minuten geht es weiter. Deine Projekte und Garne liegen sicher im Wollkorb.",
      quip: "Auch die besten Stricker:innen brauchen mal einen Tee.",
      fine: "Voraussichtliche Rückkehr: nach einer weiteren Tasse Tee",
      retry: "Diese Seite versucht es jede Minute von selbst noch einmal.",
    },
  },
  {
    status: 504,
    mood: "sleepy",
    refresh: true,
    en: {
      kicker: "504 · Gateway timeout",
      title: "The server nodded off mid-row",
      body: "We waited and waited, but it fell asleep halfway through the row. Give it a gentle poke in a moment.",
      quip: "Zzz… knit… zzz… purl…",
      fine: "Rows knitted before nodding off: 7½",
      retry: "This page tries again by itself every minute.",
    },
    de: {
      kicker: "504 · Zeitüberschreitung",
      title: "Der Server ist mitten in der Reihe eingenickt",
      body: "Wir haben gewartet und gewartet, aber er ist auf halber Reihe eingeschlafen. Stups ihn gleich noch einmal sanft an.",
      quip: "Zzz… rechts… zzz… links…",
      fine: "Reihen gestrickt vor dem Einnicken: 7½",
      retry: "Diese Seite versucht es jede Minute von selbst noch einmal.",
    },
  },
];

const LABELS = {
  en: { retry: "Try again", home: "Back to Home" },
  de: { retry: "Nochmal versuchen", home: "Zurück zur Startseite" },
};

/** @param {string} value */
function escapeHtml(value) {
  return value.replace(/[&<>"']/g, (character) => {
    /** @type {Record<string, string>} */
    const entities = {
      "&": "&amp;",
      "<": "&lt;",
      ">": "&gt;",
      '"': "&quot;",
      "'": "&#39;",
    };
    return entities[character] ?? character;
  });
}

/** The mascot "Winky Pom" with a lasting expression, as inline SVG (no style attributes). */
/** @param {Mood} mood */
export function mascotSvg(mood) {
  const eyes = {
    confused: `<circle cx="45" cy="51" r="4.4" fill="#16233f" stroke="none"/><circle cx="62.5" cy="51.5" r="2.6" fill="#16233f" stroke="none"/><path d="M57 44Q62 41 67 45" stroke-width="2.4"/>`,
    dizzy: `<path d="M42 48L48 54M48 48L42 54" stroke-width="2.6"/><path d="M59.5 48L65.5 54M65.5 48L59.5 54" stroke-width="2.6"/>`,
    tired: `<path d="M41 52H49"/><path d="M58 52H67"/>`,
    sleepy: `<path d="M40.5 51Q45 56 49.5 51"/><path d="M58 51Q62.5 56 67 51"/>`,
    sad: `<circle cx="45" cy="52" r="3.8" fill="#16233f" stroke="none"/><circle cx="63" cy="52" r="3.8" fill="#16233f" stroke="none"/><path d="M39.5 49L49 45.5M68.5 49L59 45.5" stroke-width="2.4"/>`,
  }[mood];
  const mouth = {
    confused: `<path d="M47 66Q50.5 62 54 66T61 66" fill="none" stroke="#16233f" stroke-width="2.6" stroke-linecap="round"/>`,
    dizzy: `<path d="M46 65Q50 61 54 65T62 65" fill="none" stroke="#16233f" stroke-width="2.8" stroke-linecap="round"/>`,
    tired: `<path d="M48 65H60" fill="none" stroke="#16233f" stroke-width="2.8" stroke-linecap="round"/>`,
    sleepy: `<ellipse cx="54" cy="65" rx="3" ry="3.6" fill="#16233f"/>`,
    sad: `<path d="M46 67Q54 59 62 67" fill="none" stroke="#16233f" stroke-width="2.8" stroke-linecap="round"/>`,
  }[mood];
  const extra = {
    confused: `<text class="mark" x="76" y="34" font-size="19" font-weight="800" fill="#2f6fed" text-anchor="middle">?</text>`,
    dizzy: `<path class="thread" d="M66 78Q80 82 76 92T62 100T74 108" fill="none" stroke="#2f6fed" stroke-width="3.4" stroke-linecap="round"/>`,
    tired: `<path class="drop" d="M72 39Q68.5 44 72 46.5Q75.5 44 72 39Z" fill="#7fd0ff"/>`,
    sleepy: `<g class="zzz" fill="#2f6fed" font-weight="800" text-anchor="middle"><text x="72" y="36" font-size="12">z</text><text x="80" y="27" font-size="9">z</text></g>`,
    sad: `<path class="drop" d="M40 57Q36.5 62 40 64.5Q43.5 62 40 57Z" fill="#7fd0ff"/><path d="M70 79Q76 84 74 90" fill="none" stroke="#2f6fed" stroke-width="3.4" stroke-linecap="round" stroke-dasharray="1 5.5"/>`,
  }[mood];
  // Only the dizzy mascot trails a long thread; the others need less room below.
  const height = mood === "dizzy" ? 90 : 72;
  return `<svg class="mascot mascot-${mood}" viewBox="21 21 66 ${height}" width="140" height="${Math.round((height * 140) / 66)}" aria-hidden="true" focusable="false">
<defs><clipPath id="ball"><circle cx="54" cy="54" r="27"/></clipPath></defs>
<ellipse class="shadow" cx="54" cy="84" rx="22" ry="3.4"/>
<g class="ball"><g transform="rotate(-8 54 54)">
<circle cx="54" cy="54" r="27" fill="#8cb8ff"/>
<g clip-path="url(#ball)"><g fill="none" stroke="#2f6fed" stroke-width="7" stroke-linecap="round"><path d="M24 38Q54 27 86 42"/><path d="M22 70Q56 88 88 64"/></g></g>
<g fill="none" stroke="#16233f" stroke-width="3.6" stroke-linecap="round">${eyes}</g>
${mouth}
<circle cx="38" cy="60" r="4.2" fill="#ff6f9f"/><circle cx="70" cy="60" r="4.2" fill="#ff6f9f"/>
</g></g>
${extra}
</svg>`;
}

/** Mascot rules shared with the offline page (which keeps its own stylesheet). */
export const MASCOT_CSS = `
.mascot { display: block; overflow: visible; margin-bottom: 0.2rem; filter: drop-shadow(0 6px 10px rgb(63 20 80 / 18%)); }
.shadow { fill: rgb(63 20 80 / 14%); }
@media (prefers-reduced-motion: no-preference) {
  .mascot-confused { animation: tilt 3200ms ease-in-out infinite; transform-origin: 50% 80%; }
  .mascot-dizzy { animation: woozy 2800ms ease-in-out infinite; transform-origin: 50% 80%; }
  .mascot-tired { animation: droop 3400ms ease-in-out infinite; transform-origin: 50% 80%; }
  .mascot-sad { animation: droop 3400ms ease-in-out infinite; transform-origin: 50% 80%; }
  .mascot-sleepy { animation: breathe 3600ms ease-in-out infinite; transform-origin: 50% 80%; }
  .mark { transform-box: fill-box; transform-origin: center; animation: question 2200ms ease-in-out infinite; }
  .thread { stroke-dasharray: 90; animation: unspool 2600ms ease-in-out infinite alternate; }
  .zzz { animation: zzz 2600ms ease-in-out infinite; }
  .drop { animation: drip 2400ms ease-in infinite; }
}
@keyframes tilt { 0%, 100% { transform: rotate(-4deg); } 50% { transform: rotate(5deg); } }
@keyframes woozy { 0%, 100% { transform: rotate(-6deg) translateX(-2px); } 50% { transform: rotate(6deg) translateX(2px); } }
@keyframes droop { 0%, 100% { transform: scale(1, 1); } 50% { transform: scale(1.03, 0.95); } }
@keyframes breathe { 0%, 100% { transform: scale(1, 1); } 50% { transform: scale(1.04, 0.96); } }
@keyframes question { 0%, 100% { transform: translateY(0) rotate(-8deg); } 50% { transform: translateY(-3px) rotate(8deg); } }
@keyframes unspool { from { stroke-dashoffset: 70; } to { stroke-dashoffset: 0; } }
@keyframes zzz { 0%, 100% { opacity: 0.35; transform: translateY(2px); } 50% { opacity: 1; transform: translateY(-3px); } }
@keyframes drip { 0%, 55% { opacity: 0; transform: translateY(-2px); } 65% { opacity: 1; } 100% { opacity: 0; transform: translateY(9px); } }
`;

const STYLE = `
:root {
  color-scheme: light dark;
  --canvas: #f7f3fc;
  --surface: #ffffff;
  --ink: #2a2033;
  --ink-muted: #5e5268;
  --line: #ddd3e8;
  --accent: #c23a6e;
  --accent-strong: #a82c5b;
  --on-accent: #ffffff;
  --chip: #ffd9e5;
  --on-chip: #3f0020;
  --focus: #286fba;
  --shadow: 0 16px 42px rgb(63 20 80 / 12%);
}
@media (prefers-color-scheme: dark) {
  :root:not([data-theme="light"]) {
    --canvas: #1a1421;
    --surface: #271f30;
    --ink: #ede4f4;
    --ink-muted: #c9bdd3;
    --line: #473d51;
    --accent: #ffb0cb;
    --accent-strong: #ffb0cb;
    --on-accent: #3f0020;
    --chip: #7d1d49;
    --on-chip: #ffd9e5;
    --focus: #ffc768;
    --shadow: 0 16px 42px rgb(0 0 0 / 32%);
  }
}
:root[data-theme="dark"] {
  color-scheme: dark;
  --canvas: #1a1421;
  --surface: #271f30;
  --ink: #ede4f4;
  --ink-muted: #c9bdd3;
  --line: #473d51;
  --accent: #ffb0cb;
  --accent-strong: #ffb0cb;
  --on-accent: #3f0020;
  --chip: #7d1d49;
  --on-chip: #ffd9e5;
  --focus: #ffc768;
  --shadow: 0 16px 42px rgb(0 0 0 / 32%);
}
:root[data-theme="light"] { color-scheme: light; }
* { box-sizing: border-box; }
html[data-lang="en"] [lang="de"], html[data-lang="de"] [lang="en"] { display: none; }
body {
  min-width: 320px;
  min-height: 100vh;
  min-height: 100svh;
  margin: 0;
  display: grid;
  place-items: center;
  padding: max(1rem, env(safe-area-inset-top)) 1rem max(1rem, env(safe-area-inset-bottom));
  background: var(--canvas);
  color: var(--ink);
  font-family: Inter, ui-sans-serif, system-ui, -apple-system, "Segoe UI", sans-serif;
  line-height: 1.55;
}
main {
  width: min(100%, 36rem);
  display: grid;
  justify-items: center;
  gap: 0.7rem;
  padding: clamp(1.5rem, 6vw, 2.5rem) clamp(1.25rem, 5vw, 2.5rem);
  border: 1px solid var(--line);
  border-radius: 1.75rem;
  background: var(--surface);
  box-shadow: var(--shadow);
  text-align: center;
}
section { display: grid; justify-items: center; gap: 0.7rem; }
html:not([data-lang]) section + section { margin-top: 1.5rem; padding-top: 1.5rem; border-top: 1px dashed var(--line); width: 100%; }
.kicker {
  margin: 0;
  padding: 0.25rem 0.85rem;
  border-radius: 999px;
  background: var(--chip);
  color: var(--on-chip);
  font-size: 0.85rem;
  font-weight: 700;
}
h1 {
  margin: 0.15rem 0 0;
  font-size: clamp(1.7rem, 7vw, 2.4rem);
  line-height: 1.1;
  letter-spacing: -0.04em;
  text-wrap: balance;
}
p { margin: 0; }
.body { color: var(--ink-muted); text-wrap: pretty; }
.quip { color: var(--accent-strong); font-style: italic; font-weight: 600; text-wrap: balance; }
.fine { color: var(--ink-muted); font-size: 0.82rem; font-variant-numeric: tabular-nums; }
.actions { display: flex; flex-wrap: wrap; justify-content: center; gap: 0.6rem; margin-top: 0.6rem; }
.button {
  display: inline-flex;
  min-height: 2.75rem;
  align-items: center;
  justify-content: center;
  padding: 0.6rem 1.15rem;
  border: 1px solid var(--accent);
  border-radius: 0.8rem;
  background: transparent;
  color: var(--accent-strong);
  font-weight: 700;
  text-decoration: none;
}
.button.primary { background: var(--accent); color: var(--on-accent); }
.button:hover { filter: brightness(0.94); }
.button:focus-visible { outline: 3px solid var(--focus); outline-offset: 3px; }
.stitches {
  width: 100%;
  height: 0;
  margin-top: 0.8rem;
  border-top: 3px dashed var(--accent);
  opacity: 0.35;
}
`;

const SCRIPT = `try {
  var l = ((navigator.language || "en") + "").slice(0, 2) === "de" ? "de" : "en";
  var d = document.documentElement;
  d.setAttribute("data-lang", l);
  d.lang = l;
  var theme = localStorage.getItem("stricknani.theme");
  if (theme === "light" || theme === "dark") d.setAttribute("data-theme", theme);
} catch (e) {}
document.addEventListener("click", function (event) {
  var link = event.target && event.target.closest ? event.target.closest("a[data-retry]") : null;
  if (link) { event.preventDefault(); location.reload(); }
});
`;

/**
 * @param {ErrorPage} page
 * @param {"en" | "de"} lang
 */
function section(page, lang) {
  const copy = page[lang];
  const labels = LABELS[lang];
  const home = `<a class="button${page.status === 404 ? " primary" : ""}" href="/app/">${escapeHtml(labels.home)}</a>`;
  const retry = page.refresh
    ? `<a class="button primary" href="/app/" data-retry>${escapeHtml(labels.retry)}</a>`
    : "";
  return `<section lang="${lang}" aria-labelledby="title-${lang}">
<p class="kicker">${escapeHtml(copy.kicker)}</p>
<h1 id="title-${lang}">${escapeHtml(copy.title)}</h1>
<p class="body">${escapeHtml(copy.body)}</p>
<p class="quip">${escapeHtml(copy.quip)}</p>
<div class="actions">${retry}${home}</div>
<p class="fine">${escapeHtml(copy.fine)}${copy.retry ? `<br>${escapeHtml(copy.retry)}` : ""}</p>
</section>`;
}

/** @param {ErrorPage} page */
export function renderErrorPage(page) {
  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta name="color-scheme" content="light dark">
<meta name="robots" content="noindex">
${page.refresh ? '<meta http-equiv="refresh" content="60">\n' : ""}<title>${page.status} · Stricknani</title>
<link rel="icon" href="/app/favicon.svg" type="image/svg+xml">
<style>${STYLE}${MASCOT_CSS}</style>
<script>${SCRIPT}</script>
</head>
<body>
<main id="main-content">
${mascotSvg(page.mood)}
${section(page, "en")}
${section(page, "de")}
<div class="stitches" aria-hidden="true"></div>
</main>
</body>
</html>
`;
}

/** Write every error page to `<buildRoot>/errors/<status>.html`. */
/** @param {string} buildRoot */
export async function renderErrorPages(buildRoot) {
  const directory = join(buildRoot, "errors");
  await mkdir(directory, { recursive: true });
  for (const page of ERROR_PAGES) {
    await writeFile(
      join(directory, `${page.status}.html`),
      renderErrorPage(page),
    );
  }
  return ERROR_PAGES.map((page) => page.status);
}
