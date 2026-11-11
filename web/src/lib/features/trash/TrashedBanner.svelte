<script lang="ts">
  import { goto } from "$app/navigation";
  import { resolve } from "$app/paths";
  import Button from "#lib/components/Button.svelte";
  import ConfirmDialog from "#lib/components/ConfirmDialog.svelte";
  import NavIcon from "#lib/components/NavIcon.svelte";
  import { publishFeedback } from "#lib/feedback.ts";
  import { t, type Locale } from "#lib/i18n.svelte.ts";
  import { session } from "#lib/session.svelte.ts";
  import { ApiError } from "#lib/api.ts";
  import { purgeTrashItem, restoreTrashItem } from "./trash-api";
  import { daysUntilPurge } from "./trash-state";
  import { restoreErrorMessage } from "./trash-undo";

  // Shown on the page of a project or yarn that sits in the trash (only its owner and
  // administrators can open it): read-only, with Restore and Delete permanently.
  let {
    kind,
    id,
    name,
    purgeAt,
    locale,
    onrestored,
  }: {
    kind: "project" | "yarn";
    id: number;
    name: string;
    purgeAt: string | null;
    locale: Locale;
    /** Called after a restore so the page can load the live item. */
    onrestored: () => void;
  } = $props();

  let busy = $state(false);
  let error = $state("");
  let purgeDialog = $state<ReturnType<typeof ConfirmDialog> | null>(null);

  const days = $derived(purgeAt === null ? null : daysUntilPurge(purgeAt));
  const base = $derived(
    kind === "project"
      ? t("This project is in the trash.", locale)
      : t("This yarn is in the trash.", locale),
  );
  const dueNote = $derived(t("It is due for deletion.", locale));
  const timeNote = $derived(
    days === null || days <= 0
      ? ""
      : t("It will be deleted permanently in {time}.", locale).replace(
          "{time}",
          days === 1
            ? t("1 day", locale)
            : t("{n} days", locale).replace("{n}", String(days)),
        ),
  );
  const message = $derived(
    days === null
      ? base
      : days <= 0
        ? `${base} ${dueNote}`
        : `${base} ${timeNote}`,
  );

  async function restore(): Promise<void> {
    const currentSession = session.data;
    if (!currentSession?.user || busy) return;
    const ownerId = currentSession.user.id;
    busy = true;
    error = "";
    try {
      await restoreTrashItem(kind, id, currentSession);
      if (session.data?.user?.id !== ownerId) return;
      publishFeedback({
        ownerId,
        kind: "success",
        message: t("Restored from the trash.", locale),
      });
      onrestored();
    } catch (failure) {
      if (session.data?.user?.id !== ownerId) return;
      error = restoreErrorMessage(failure, locale);
    } finally {
      busy = false;
    }
  }

  async function purge(): Promise<void> {
    const currentSession = session.data;
    if (!currentSession?.user || busy) return;
    const ownerId = currentSession.user.id;
    busy = true;
    error = "";
    try {
      await purgeTrashItem(kind, id, currentSession);
      if (session.data?.user?.id !== ownerId) return;
      purgeDialog?.close();
      publishFeedback({
        ownerId,
        kind: "success",
        message: t("Deleted permanently.", locale),
      });
      await goto(resolve("/trash"));
    } catch (failure) {
      if (session.data?.user?.id !== ownerId) return;
      error =
        failure instanceof ApiError && failure.status === 404
          ? t("This item is no longer in the trash.", locale)
          : failure instanceof Error
            ? failure.message
            : t("The item could not be deleted.", locale);
    } finally {
      busy = false;
    }
  }
</script>

<section
  class="trashed-banner"
  aria-label={t("Trash", locale)}
  data-testid="trashed-banner"
>
  <p class="trashed-banner__message">
    <NavIcon name="trash" />
    <span>{message}</span>
  </p>
  {#if error}<p class="trashed-banner__error" role="alert">{error}</p>{/if}
  <div class="trashed-banner__actions">
    <Button
      variant="primary"
      icon="reset"
      type="button"
      disabled={busy}
      data-testid="trashed-restore"
      onclick={() => void restore()}
    >
      {busy ? t("Restoring…", locale) : t("Restore", locale)}
    </Button>
    <Button
      variant="danger"
      icon="action-delete"
      type="button"
      disabled={busy}
      data-testid="trashed-purge"
      onclick={() => purgeDialog?.showModal()}
    >
      {t("Delete permanently", locale)}
    </Button>
  </div>
</section>

<ConfirmDialog
  bind:this={purgeDialog}
  titleId={`purge-${kind}-${id}-title`}
  title={t("Delete forever?", locale)}
  {busy}
>
  {#snippet children()}
    <p>
      {t(
        "{name} will be deleted permanently. This cannot be undone.",
        locale,
      ).replace("{name}", name)}
    </p>
  {/snippet}
  {#snippet actions()}
    <Button
      variant="secondary"
      icon="cancel"
      type="button"
      disabled={busy}
      onclick={() => purgeDialog?.close()}
    >
      {t("Keep in trash", locale)}
    </Button>
    <Button
      variant="danger"
      icon="action-delete"
      type="button"
      disabled={busy}
      data-testid="trashed-purge-confirm"
      onclick={() => void purge()}
    >
      {busy ? t("Deleting…", locale) : t("Delete forever", locale)}
    </Button>
  {/snippet}
</ConfirmDialog>

<style>
  .trashed-banner {
    display: grid;
    gap: 0.75rem;
    border: 1px solid var(--danger);
    border-radius: var(--radius-sm);
    padding: 0.9rem 1rem;
    background: var(--surface);
  }

  .trashed-banner__message {
    display: flex;
    align-items: flex-start;
    gap: 0.6rem;
    margin: 0;
    color: var(--danger);
    font-weight: 650;
  }

  .trashed-banner__message :global(svg) {
    flex: none;
    width: 1.3rem;
    height: 1.3rem;
  }

  .trashed-banner__error {
    margin: 0;
    color: var(--danger);
  }

  .trashed-banner__actions {
    display: flex;
    flex-wrap: wrap;
    gap: 0.5rem;
  }
</style>
