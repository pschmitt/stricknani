import { msg } from "./i18n.svelte.ts";
import type { NavIconName } from "./components/NavIcon.svelte";

/** The destinations of the desktop rail and the phone bar; the command palette lists the same. */
export interface NavItem {
  href:
    | "/"
    | "/projects"
    | "/yarns"
    | "/create"
    | "/categories"
    | "/gauge";
  label: string;
  icon: NavIconName;
}

export const mainNavigation: readonly NavItem[] = [
  { href: "/", label: msg("Home"), icon: "home" },
  { href: "/projects", label: msg("Projects"), icon: "projects" },
  { href: "/yarns", label: msg("Yarns"), icon: "yarns" },
  { href: "/create", label: msg("Create"), icon: "create" },
];

export const secondaryNavigation: readonly NavItem[] = [
  { href: "/categories", label: msg("Categories"), icon: "categories" },
  { href: "/gauge", label: msg("Gauge calculator"), icon: "gauge" },
];
