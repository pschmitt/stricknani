<script lang="ts">
  import { resolve } from "$app/paths";
  import { t, type Locale } from "#lib/i18n.svelte.ts";

  let { active, locale }: { active: "users" | "audit"; locale: Locale } =
    $props();
</script>

<nav class="admin-tabs" aria-label={t("Admin sections", locale)}>
  <a
    class="admin-tab"
    href={resolve("/admin/users")}
    aria-current={active === "users" ? "page" : undefined}
    >{t("Users", locale)}</a
  >
  <a
    class="admin-tab"
    href={resolve("/admin/audit")}
    aria-current={active === "audit" ? "page" : undefined}
    >{t("Audit log", locale)}</a
  >
</nav>

<style>
  .admin-tabs {
    display: flex;
    gap: 0.25rem;
    margin-bottom: 1.1rem;
    border-bottom: 1px solid var(--line);
  }

  .admin-tab {
    display: inline-flex;
    min-height: 44px;
    align-items: center;
    margin-bottom: -1px;
    border-bottom: 3px solid transparent;
    padding-inline: 0.9rem;
    color: var(--ink-muted);
    font-weight: 700;
    text-decoration: none;
    -webkit-tap-highlight-color: transparent;
  }

  .admin-tab:hover {
    color: var(--ink);
  }

  .admin-tab[aria-current="page"] {
    border-bottom-color: var(--accent-strong);
    color: var(--accent-strong);
  }

  .admin-tab:focus-visible {
    outline: 3px solid var(--focus);
    outline-offset: -3px;
    border-radius: var(--radius-xs);
  }
</style>
