<script lang="ts">
  // The kind pill of a backup: a hue per kind and an icon. Android draws the same pill
  // (AdminBackupsScreen.kt); the kind-to-tone map is in backups-present.ts.
  import NavIcon from "#lib/components/NavIcon.svelte";
  import { t } from "#lib/i18n.svelte.ts";
  import type { BackupKind } from "./backups-api.ts";
  import { kindStyle } from "./backups-present.ts";

  let { kind, locale }: { kind: BackupKind; locale: "en" | "de" } = $props();
  const style = $derived(kindStyle(kind));

  function label(value: BackupKind): string {
    switch (value) {
      case "nightly":
        return t("Nightly", locale);
      case "predeploy":
        return t("Before update", locale);
      case "pre_migrate":
        return t("Before migration", locale);
      case "pre_restore":
        return t("Before restore", locale);
      default:
        return t("Manual", locale);
    }
  }
</script>

<span class="chip chip--{style.tone}" data-kind={kind}>
  <NavIcon name={style.icon} />
  {label(kind)}
</span>

<style>
  .chip {
    display: inline-flex;
    align-items: center;
    gap: 0.3rem;
    border-radius: 999px;
    padding: 0.15rem 0.65rem 0.15rem 0.5rem;
    background: var(--chip-wash);
    color: var(--chip-ink);
    font-size: 0.78rem;
    font-weight: 700;
    line-height: 1.4;
    letter-spacing: 0.01em;
    white-space: nowrap;
  }

  .chip :global(svg) {
    width: 0.95rem;
    height: 0.95rem;
  }

  .chip--nightly {
    --chip-ink: var(--tone-backup-nightly);
    --chip-wash: var(--tone-backup-nightly-wash);
  }

  .chip--update {
    --chip-ink: var(--tone-yarns);
    --chip-wash: var(--tone-yarns-wash);
  }

  .chip--migration {
    --chip-ink: var(--tone-audit);
    --chip-wash: var(--tone-audit-wash);
  }

  .chip--restore {
    --chip-ink: var(--tone-projects);
    --chip-wash: var(--tone-projects-wash);
  }

  .chip--manual {
    --chip-ink: var(--tone-home);
    --chip-wash: var(--tone-home-wash);
  }
</style>
