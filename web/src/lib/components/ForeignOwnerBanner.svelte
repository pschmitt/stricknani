<script lang="ts">
  import UserAvatar from "#lib/components/UserAvatar.svelte";
  import { t } from "#lib/i18n.svelte.ts";
  import type { ResourceOwner } from "#lib/owner.ts";

  /**
   * An administrator's view of someone else's project or yarn: whose it is (with their
   * picture) and that changes are saved there and audited. Android shows the same banner.
   */
  let {
    kind,
    owner,
    locale = undefined,
  }: {
    kind: "project" | "yarn";
    owner: ResourceOwner;
    locale?: "en" | "de";
  } = $props();

  const title = $derived(
    (kind === "project"
      ? t("{name}'s project", locale)
      : t("{name}'s yarn", locale)
    ).replace("{name}", owner.name),
  );
  const detail = $derived(
    kind === "project"
      ? t(
          "Your changes are saved to their project and recorded in the audit log.",
          locale,
        )
      : t(
          "Your changes are saved to their yarn and recorded in the audit log.",
          locale,
        ),
  );
</script>

<p class="foreign-banner" role="note" data-testid="foreign-owner-banner">
  <span class="foreign-banner__avatar" aria-hidden="true"
    ><UserAvatar avatarUrl={owner.avatar_url} name={owner.name} /></span
  >
  <span class="foreign-banner__copy">
    <strong>{title}</strong>
    <span>{detail}</span>
  </span>
</p>

<style>
  .foreign-banner {
    display: flex;
    align-items: center;
    gap: 0.75rem;
    margin: 0;
    border: 1px solid color-mix(in srgb, var(--accent-strong) 35%, var(--line));
    border-radius: var(--radius-sm, 12px);
    padding: 0.65rem 0.9rem;
    color: var(--ink);
    background: var(--accent-wash);
  }

  .foreign-banner__avatar {
    display: inline-flex;
    flex: none;
  }

  .foreign-banner__copy {
    display: grid;
    min-width: 0;
    gap: 0.1rem;
    line-height: 1.35;
  }

  .foreign-banner__copy strong {
    font-size: 0.98rem;
    font-weight: 750;
  }

  .foreign-banner__copy span {
    color: var(--ink-muted);
    font-size: 0.86rem;
  }
</style>
