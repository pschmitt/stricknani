<script lang="ts">
  import { resolve } from "$app/paths";
  import { ApiError } from "#lib/api.ts";
  import Button from "#lib/components/Button.svelte";
  import { publishFeedback } from "#lib/feedback.ts";
  import type { Locale } from "#lib/i18n.svelte.ts";
  import { t } from "#lib/i18n.svelte.ts";
  import { session } from "#lib/session.svelte.ts";
  import { restoreTrashItem } from "../trash/trash-api";
  import { restoreErrorMessage } from "../trash/trash-undo";
  import { lifecycleSubjectText, trashedStateText } from "../trash/trash-state";
  import type { AuditEvent } from "./admin-api";

  // What became of the item a "moved to trash" entry is about, and who it belonged to:
  // the owner of an item an administrator or the system acted on, the current state
  // (in the trash with the days left, restored, or deleted permanently), a link to the
  // Trash page and a Restore button while the item can still be restored.
  let {
    event,
    locale,
    onChanged,
  }: {
    event: AuditEvent;
    locale: Locale;
    /** Called after a restore so the entry can be loaded again. */
    onChanged: () => void;
  } = $props();

  let busy = $state(false);
  let error = $state("");

  const owner = $derived(lifecycleSubjectText(event.subject, locale));
  // The resource state of a file or step event describes its project or yarn, not the item:
  // those only link to the Trash page, where they are restored.
  // The server sends `trash` only for events about the item itself.
  const itemEvent = $derived(
    event.resource.trash === null &&
      (event.media !== null || event.action.startsWith("step_")),
  );
  const lifecycleAction = $derived(itemEvent ? "" : event.action);
  const stateText = $derived(
    trashedStateText(
      lifecycleAction,
      event.resource.state,
      event.resource.trash,
      locale,
    ),
  );
  const inTrash = $derived(
    event.action === "trashed" && event.resource.state === "in_trash",
  );
  const showTrashLink = $derived(
    inTrash || (itemEvent && /trashed$/.test(event.action)),
  );
  const canRestore = $derived(
    inTrash &&
      !itemEvent &&
      event.resource.trash?.can_restore === true &&
      event.resource.kind !== null,
  );
  const trashHref = $derived(`${resolve("/trash")}?scope=all`);

  async function restore(): Promise<void> {
    const currentSession = session.data;
    const kind = event.resource.kind;
    if (!currentSession?.user || kind === null || busy) return;
    const ownerId = currentSession.user.id;
    busy = true;
    error = "";
    try {
      await restoreTrashItem(kind, event.resource.id, currentSession);
      if (session.data?.user?.id !== ownerId) return;
      publishFeedback({
        ownerId,
        kind: "success",
        message: t("Restored from the trash.", locale),
      });
      onChanged();
    } catch (failure) {
      if (session.data?.user?.id !== ownerId) return;
      error = restoreErrorMessage(failure, locale);
      if (failure instanceof ApiError && failure.status === 404) onChanged();
    } finally {
      busy = false;
    }
  }
</script>

{#if owner || stateText || showTrashLink}
  <div class="audit-lifecycle" data-testid="audit-lifecycle">
    {#if owner}<p class="audit-lifecycle__owner">{owner}</p>{/if}
    {#if stateText}
      <p class="audit-lifecycle__state" data-state={event.resource.state}>
        {stateText}
        {#if showTrashLink}
          <span aria-hidden="true"> · </span>
          <a href={trashHref} data-testid="audit-open-trash"
            >{t("Open trash", locale)}</a
          >
        {/if}
      </p>
    {:else if showTrashLink}
      <p class="audit-lifecycle__state">
        <a href={trashHref} data-testid="audit-open-trash"
          >{t("Open trash", locale)}</a
        >
      </p>
    {/if}
    {#if canRestore}
      <Button
        variant="secondary"
        size="sm"
        icon="reset"
        type="button"
        disabled={busy}
        data-testid="audit-restore"
        onclick={() => void restore()}
      >
        {busy ? t("Restoring…", locale) : t("Restore", locale)}
      </Button>
    {/if}
    {#if error}<p class="audit-lifecycle__error" role="alert">{error}</p>{/if}
  </div>
{/if}

<style>
  .audit-lifecycle {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 0.25rem 0.75rem;
    min-width: 0;
    color: var(--ink-muted);
    font-size: 0.88rem;
  }

  .audit-lifecycle p {
    margin: 0;
    overflow-wrap: anywhere;
  }

  .audit-lifecycle__state[data-state="in_trash"] {
    color: var(--danger);
    font-weight: 650;
  }

  .audit-lifecycle__state a {
    color: var(--accent-strong);
    font-weight: 650;
  }

  .audit-lifecycle__error {
    flex-basis: 100%;
    color: var(--danger);
  }
</style>
