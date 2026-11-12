<script lang="ts">
  import { goto } from "$app/navigation";
  import { resolve } from "$app/paths";
  import { ApiError } from "#lib/api.ts";
  import SharedButton from "#lib/components/Button.svelte";
  import { stopImpersonation } from "#lib/features/admin/admin-api.ts";
  import { t } from "#lib/i18n.svelte.ts";
  import UserSentence from "#lib/components/UserSentence.svelte";
  import { refreshSession, session } from "#lib/session.svelte.ts";
  import { accountUser } from "#lib/user-label.ts";

  // Shown at the top of every page while an administrator acts as another account. It cannot
  // be dismissed: the only way out is to stop, which returns to Admin, Users.
  const locale = $derived(session.data?.locale ?? "en");
  const impersonation = $derived(session.data?.impersonation ?? null);
  // While acting as someone, the session's user is that account, so its avatar is theirs.
  const target = $derived(
    impersonation
      ? accountUser({
          ...impersonation.user,
          avatar_url: session.data?.user?.avatar_url ?? null,
        })
      : null,
  );
  let busy = $state(false);
  let failed = $state(false);

  // A thin stripe stays at the top edge even when the banner has scrolled away.
  $effect(() => {
    if (!impersonation) return;
    document.documentElement.setAttribute("data-impersonating", "");
    return () => document.documentElement.removeAttribute("data-impersonating");
  });

  async function stop() {
    const current = session.data;
    if (!current || busy) return;
    busy = true;
    failed = false;
    try {
      await stopImpersonation(current);
    } catch (error) {
      // An impersonation that already expired is over either way.
      if (!(
        error instanceof ApiError && error.code === "impersonation_expired"
      )) {
        failed = true;
        busy = false;
        return;
      }
    }
    await refreshSession();
    busy = false;
    await goto(resolve("/admin/users"));
  }
</script>

{#if impersonation && target}
  <div class="impersonation-banner" role="alert" data-impersonation-banner>
    <p class="impersonation-text">
      <strong
        ><UserSentence
          template={t("You are acting as {name}", locale)}
          users={{ name: target }}
          {locale}
          size="regular"
        /></strong
      >
      <span
        >{t(
          "Everything you do is recorded as done by them and logged.",
          locale,
        )}</span
      >
      {#if failed}<span class="impersonation-failed"
          >{t("Impersonation could not be stopped. Try again.", locale)}</span
        >{/if}
    </p>
    <SharedButton
      variant="secondary"
      type="button"
      disabled={busy}
      aria-busy={busy}
      onclick={() => void stop()}
      icon="cancel"
    >
      {t("Stop impersonating", locale)}
    </SharedButton>
  </div>
{/if}

<style>
  .impersonation-banner {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    justify-content: space-between;
    gap: 0.6rem 1rem;
    padding: max(0.75rem, env(safe-area-inset-top)) 1rem 0.75rem;
    color: #fff;
    background: #8a1c14;
    border-bottom: 4px solid #ffd54a;
  }

  .impersonation-text {
    display: grid;
    min-width: 0;
    gap: 0.15rem;
    margin: 0;
    overflow-wrap: anywhere;
  }

  .impersonation-text strong {
    font-size: 1.15rem;
  }

  .impersonation-failed {
    font-weight: 700;
    color: #ffd54a;
  }

  .impersonation-banner :global(.ui-button) {
    flex: none;
    color: #1b1b1b;
    background: #fff;
    border-color: #fff;
  }

  @media (max-width: 760px) {
    .impersonation-banner :global(.ui-button) {
      width: 100%;
    }
  }
</style>
