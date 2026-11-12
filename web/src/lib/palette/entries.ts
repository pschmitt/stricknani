import type { NavIconName } from "../components/NavIcon.svelte";
import { msg, t, type Locale } from "../i18n.svelte.ts";
import { mainNavigation, secondaryNavigation } from "../nav-items.ts";
import type { PaletteGroupId } from "./match.ts";

/** One thing the palette can open or run. */
export interface PaletteEntry {
  id: string;
  group: PaletteGroupId;
  title: string;
  sub?: string;
  /** Extra words that find the entry (English, whatever the language is). */
  keywords: string[];
  icon: NavIconName;
  /** An app path (already with the base path) or null for a command. */
  href: string | null;
  command?: "toggle-theme";
  imageUrl?: string | null;
}

/**
 * The Account page sections that have an anchor: the id is the element id in
 * AccountSettings.svelte (a test keeps both in step).
 */
export const accountSections: ReadonlyArray<{
  anchor: string;
  label: string;
  icon: NavIconName;
  keywords: string[];
}> = [
  {
    anchor: "profile-title",
    label: msg("Profile"),
    icon: "account",
    keywords: ["avatar picture photo gravatar email"],
  },
  {
    anchor: "pref-language",
    label: msg("Language"),
    icon: "sliders",
    keywords: ["deutsch english german locale"],
  },
  {
    anchor: "pref-theme",
    label: msg("Appearance"),
    icon: "sun",
    keywords: ["theme dark light mode colour color night"],
  },
  {
    anchor: "pref-text-size",
    label: msg("Text size"),
    icon: "sliders",
    keywords: ["font larger smaller reading"],
  },
  {
    anchor: "pref-project-timers",
    label: msg("Project timers"),
    icon: "history",
    keywords: ["stopwatch time reminder"],
  },
  {
    anchor: "pref-notifications",
    label: msg("Notifications"),
    icon: "flag",
    keywords: ["push alerts"],
  },
  {
    anchor: "pref-error-reports",
    label: msg("Send error reports"),
    icon: "code",
    keywords: ["sentry crash privacy"],
  },
  {
    anchor: "security-title",
    label: msg("Sign-in and security"),
    icon: "lock",
    keywords: ["password change login"],
  },
  {
    anchor: "tokens-title",
    label: msg("Apps and devices"),
    icon: "key",
    keywords: ["api token android connect app"],
  },
  {
    anchor: "backups",
    label: msg("Your data"),
    icon: "download",
    keywords: ["backup backups export restore download import"],
  },
  {
    anchor: "delete-account-heading",
    label: msg("Delete account"),
    icon: "action-delete",
    keywords: ["remove close erase"],
  },
];

const PAGE_KEYWORDS: Record<string, string[]> = {
  "/": ["dashboard start"],
  "/projects": ["knitting patterns"],
  "/yarns": ["stash wool"],
  "/create": ["import new"],
  "/categories": ["tags labels organize"],
  "/gauge": ["swatch stitches rows calculator"],
  "/trash": ["deleted restore undo bin recycle"],
};

export interface StaticEntryOptions {
  locale: Locale;
  isAdmin: boolean;
  /** Prefixes an app path with the base path. */
  resolvePath: (path: string) => string;
  /** The link in the query, when it is one. */
  url: string | null;
}

/** Actions, pages and settings: everything that does not come from the server. */
export function buildStaticEntries(
  options: StaticEntryOptions,
): PaletteEntry[] {
  const { locale, isAdmin, resolvePath, url } = options;
  const entries: PaletteEntry[] = [];
  const add = (entry: PaletteEntry) => entries.push(entry);

  if (url) {
    const encoded = encodeURIComponent(url);
    add({
      id: "action:import-project",
      group: "actions",
      title: t("Import this pattern", locale),
      sub: url,
      keywords: [],
      icon: "download",
      href: `${resolvePath("/projects/new")}?url=${encoded}&start=1`,
    });
    add({
      id: "action:import-yarn",
      group: "actions",
      title: t("Import as yarn", locale),
      sub: url,
      keywords: [],
      icon: "yarns",
      href: `${resolvePath("/yarns/new")}?url=${encoded}&start=1`,
    });
  }
  add({
    id: "action:new-project",
    group: "actions",
    title: t("New project", locale),
    keywords: ["create add knit"],
    icon: "add",
    href: resolvePath("/projects/new"),
  });
  add({
    id: "action:new-yarn",
    group: "actions",
    title: t("New yarn", locale),
    keywords: ["create add stash"],
    icon: "add",
    href: resolvePath("/yarns/new"),
  });
  add({
    id: "action:toggle-theme",
    group: "actions",
    title: t("Toggle dark mode", locale),
    keywords: ["theme light night appearance"],
    icon: "moon",
    href: null,
    command: "toggle-theme",
  });

  for (const item of [...mainNavigation, ...secondaryNavigation]) {
    add({
      id: `page:${item.href}`,
      group: "pages",
      title: t(item.label, locale),
      keywords: [item.label, ...(PAGE_KEYWORDS[item.href] ?? [])],
      icon: item.icon,
      href: resolvePath(item.href),
    });
  }
  add({
    id: "page:/account",
    group: "pages",
    title: t("Account", locale),
    keywords: ["profile settings"],
    icon: "account",
    href: resolvePath("/account"),
  });
  if (isAdmin) {
    add({
      id: "page:/admin/users",
      group: "pages",
      title: t("Admin", locale),
      keywords: ["users accounts audit log backups restore"],
      icon: "admin",
      href: resolvePath("/admin/users"),
    });
  }
  add({
    id: "page:/about",
    group: "pages",
    title: t("About", locale),
    keywords: ["version credits privacy"],
    icon: "info",
    href: resolvePath("/about"),
  });

  for (const section of accountSections) {
    add({
      id: `setting:${section.anchor}`,
      group: "settings",
      title: t(section.label, locale),
      sub: t("Account", locale),
      keywords: [section.label, ...section.keywords],
      icon: section.icon,
      href: `${resolvePath("/account")}#${section.anchor}`,
    });
  }
  return entries;
}

/** Whether the page is dark now, for the toggle: the choice, else the system. */
export function isDarkNow(choice: "system" | "light" | "dark"): boolean {
  if (choice !== "system") return choice === "dark";
  return (
    typeof matchMedia === "function" &&
    matchMedia("(prefers-color-scheme: dark)").matches
  );
}
