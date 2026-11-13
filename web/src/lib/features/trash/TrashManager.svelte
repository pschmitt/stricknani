<script lang="ts">
  import { onMount, type ComponentProps } from "svelte";
  import { page } from "$app/state";
  import { resolve } from "$app/paths";
  import { ApiError } from "#lib/api.ts";
  import Button from "#lib/components/Button.svelte";
  import ConfirmDialog from "#lib/components/ConfirmDialog.svelte";
  import EmptyState from "#lib/components/EmptyState.svelte";
  import NavIcon from "#lib/components/NavIcon.svelte";
  import UserSentence from "#lib/components/UserSentence.svelte";
  import PageHeader from "#lib/components/PageHeader.svelte";
  import ScopeFilter from "#lib/components/ScopeFilter.svelte";
  import { publishFeedback } from "#lib/feedback.ts";
  import {
    fullDate,
    relativeTime,
  } from "#lib/features/history/history-summary.ts";
  import { t } from "#lib/i18n.svelte.ts";
  import { scopeFor, setScope } from "#lib/library-scope.svelte.ts";
  import { registerPwaUpdateGuard } from "#lib/pwa-update-coordinator.ts";
  import { session } from "#lib/session.svelte.ts";
  import { personUser } from "#lib/user-label.ts";
  import {
    DEFAULT_RETENTION_DAYS,
    listTrash,
    purgeTrashItem,
    restoredTarget,
    restoreTrashItem,
    type TrashItem,
  } from "./trash-api";
  import { daysLeftLabel } from "./trash-copy";
  import { trashKindLabelKey } from "./trash-kinds";
  import { restoreErrorMessage } from "./trash-undo";
  import {
    effectiveTrashScope,
    showsOwner,
    sortTrash,
    trashItemKey,
  } from "./trash-view";

  type LoadState = "loading" | "ready" | "error";

  let loadState = $state<LoadState>("loading");
  let items = $state<TrashItem[]>([]);
  let retentionDays = $state(DEFAULT_RETENTION_DAYS);
  let errorMessage = $state("");
  let actionError = $state("");
  let busyKey = $state<string | null>(null);
  let purgeTarget = $state<TrashItem | null>(null);
  let purgeDialog = $state<ReturnType<typeof ConfirmDialog> | null>(null);
  let restored = $state<TrashItem | null>(null);
  let reload = $state(0);

  const locale = $derived(session.data?.locale ?? "en");
  const user = $derived(session.data?.user ?? null);
  const userId = $derived(user?.id ?? null);
  const isAdmin = $derived(user?.is_admin === true);
  const scope = $derived(effectiveTrashScope(isAdmin, scopeFor(user)));
  const now = $derived(new Date());
  const restoredLink = $derived(
    restored
      ? restoredTarget(restored.kind, restored.id, restored.parent)
      : null,
  );

  // The audit log links here with ?scope=all: an administrator lands on everyone's trash.
  onMount(() => {
    const requested = page.url.searchParams.get("scope");
    const current = session.data?.user;
    if (current?.is_admin && (requested === "all" || requested === "mine"))
      setScope(current, requested);
  });

  onMount(() =>
    registerPwaUpdateGuard(() => ({
      dirty: false,
      busy: busyKey !== null,
    })),
  );

  $effect(() => {
    const currentUserId = userId;
    const currentScope = scope;
    reload;
    const controller = new AbortController();
    items = [];
    actionError = "";
    restored = null;
    if (currentUserId === null) {
      loadState = "error";
      errorMessage = t("Sign in to see the trash.", locale);
      return () => controller.abort();
    }
    loadState = "loading";
    errorMessage = "";
    void listTrash(currentScope, controller.signal)
      .then((result) => {
        if (controller.signal.aborted) return;
        items = sortTrash(result.items);
        retentionDays = result.retention_days;
        loadState = "ready";
      })
      .catch((error: unknown) => {
        if (controller.signal.aborted) return;
        loadState = "error";
        errorMessage =
          error instanceof Error
            ? error.message
            : t("The trash could not be loaded.", locale);
      });
    return () => controller.abort();
  });

  function removeFromList(item: TrashItem): void {
    const key = trashItemKey(item);
    items = items.filter((candidate) => trashItemKey(candidate) !== key);
  }

  async function restore(item: TrashItem): Promise<void> {
    const currentSession = session.data;
    if (!currentSession?.user || busyKey !== null) return;
    const ownerId = currentSession.user.id;
    busyKey = trashItemKey(item);
    actionError = "";
    try {
      await restoreTrashItem(item.kind, item.id, currentSession);
      if (session.data?.user?.id !== ownerId) return;
      removeFromList(item);
      restored = item;
      publishFeedback({
        ownerId,
        kind: "restored",
        message: t("Restored from the trash.", locale),
      });
    } catch (error) {
      if (session.data?.user?.id !== ownerId) return;
      actionError = restoreErrorMessage(error, locale);
      if (error instanceof ApiError && error.status === 404)
        removeFromList(item);
    } finally {
      busyKey = null;
    }
  }

  function askPurge(item: TrashItem): void {
    if (busyKey !== null) return;
    purgeTarget = item;
    actionError = "";
    purgeDialog?.showModal();
  }

  async function purge(): Promise<void> {
    const item = purgeTarget;
    const currentSession = session.data;
    if (!item || !currentSession?.user || busyKey !== null) return;
    const ownerId = currentSession.user.id;
    busyKey = trashItemKey(item);
    actionError = "";
    try {
      await purgeTrashItem(item.kind, item.id, currentSession);
      if (session.data?.user?.id !== ownerId) return;
      removeFromList(item);
      purgeDialog?.close();
      purgeTarget = null;
      publishFeedback({
        ownerId,
        kind: "purged",
        message: t("Deleted permanently.", locale),
      });
    } catch (error) {
      if (session.data?.user?.id !== ownerId) return;
      actionError =
        error instanceof ApiError && error.status === 404
          ? t("This item is no longer in the trash.", locale)
          : error instanceof Error
            ? error.message
            : t("The item could not be deleted.", locale);
      if (error instanceof ApiError && error.status === 404) {
        removeFromList(item);
        purgeDialog?.close();
        purgeTarget = null;
      }
    } finally {
      busyKey = null;
    }
  }

  type NavIconName = ComponentProps<typeof NavIcon>["name"];

  /** Pictures that failed to load, so the row falls back to its icon. */
  let failedThumbs = $state<Record<string, string>>({});

  /** The icon of a row without a picture. */
  function kindIcon(kind: TrashItem["kind"]): NavIconName {
    switch (kind) {
      case "project":
        return "projects";
      case "yarn":
        return "yarns";
      case "category":
        return "folder";
      case "step":
        return "list-numbers";
      case "attachment":
        return "paperclip";
      default:
        return "image";
    }
  }

  function kindLabel(item: TrashItem): string {
    return t(trashKindLabelKey(item.kind), locale);
  }

  function parentLabel(item: TrashItem): string {
    if (!item.parent) return "";
    const parent = item.parent.name;
    return item.parent.kind === "project"
      ? t("In project {name}", locale).replace("{name}", parent)
      : t("In yarn {name}", locale).replace("{name}", parent);
  }

  function deletedLabel(item: TrashItem): string {
    return t("Deleted {when}", locale).replace(
      "{when}",
      relativeTime(item.deleted_at, now, locale),
    );
  }
</script>

<svelte:head><title>Stricknani · {t("Trash", locale)}</title></svelte:head>

<section
  class="trash page-container page-container--compact"
  aria-labelledby="trash-title"
>
  <PageHeader title={t("Trash", locale)} titleId="trash-title">
    {#snippet actions()}
      <ScopeFilter {locale} />
    {/snippet}
  </PageHeader>

  <p class="trash__intro">
    {t(
      "Deleted items stay here for {days} days. Restore them, or delete them for good.",
      locale,
    ).replace("{days}", String(retentionDays))}
  </p>

  {#if actionError}
    <p class="notice-error" role="alert">{actionError}</p>
  {/if}

  {#if restored}
    <p class="trash__restored" role="status">
      <NavIcon name="check" />
      <span>
        {t("Restored {name}.", locale).replace("{name}", restored.name)}
      </span>
      {#if restoredLink}
        {#if restoredLink.kind === "project"}
          <a
            href={resolve("/projects/[id]", { id: String(restoredLink.id) })}
            data-testid="trash-open-restored"
          >
            {t("Open project", locale)}
          </a>
        {:else}
          <a
            href={resolve("/yarns/[id]", { id: String(restoredLink.id) })}
            data-testid="trash-open-restored"
          >
            {t("Open yarn", locale)}
          </a>
        {/if}
      {/if}
    </p>
  {/if}

  {#if loadState === "loading"}
    <div class="trash__list" aria-busy="true" aria-live="polite">
      {#each [1, 2, 3] as row (row)}<div
          class="trash__skeleton"
          aria-hidden="true"
        ></div>{/each}
    </div>
  {:else if loadState === "error"}
    <div class="notice-error trash__error" role="alert">
      <p>{errorMessage}</p>
      {#if userId !== null}
        <Button
          variant="secondary"
          icon="refresh"
          type="button"
          onclick={() => (reload += 1)}
        >
          {t("Try again", locale)}
        </Button>
      {/if}
    </div>
  {:else if items.length === 0}
    <EmptyState
      icon="action-delete"
      title={t("The trash is empty.", locale)}
      hint={t(
        "Deleted projects, yarns and more show up here for {days} days.",
        locale,
      ).replace("{days}", String(retentionDays))}
    />
  {:else}
    <ul class="trash__list" data-testid="trash-list">
      {#each items as item (trashItemKey(item))}
        {@const busy = busyKey === trashItemKey(item)}
        <li class="trash__row" data-kind={item.kind}>
          <span class="trash__thumb" data-testid="trash-thumb">
            {#if item.thumbnail_url && failedThumbs[trashItemKey(item)] !== item.thumbnail_url}
              <img
                src={item.thumbnail_url}
                alt=""
                width="56"
                height="56"
                loading="lazy"
                decoding="async"
                onerror={() =>
                  (failedThumbs[trashItemKey(item)] = item.thumbnail_url ?? "")}
              />
            {:else}
              <span class="trash__thumb-icon" aria-hidden="true">
                <NavIcon name={kindIcon(item.kind)} />
              </span>
            {/if}
          </span>
          <div class="trash__main">
            <div class="trash__heading">
              <p class="trash__name">
                {#if item.kind === "project"}
                  <a
                    href={resolve("/projects/[id]", { id: String(item.id) })}
                    data-testid="trash-item-link">{item.name}</a
                  >
                {:else if item.kind === "yarn"}
                  <a
                    href={resolve("/yarns/[id]", { id: String(item.id) })}
                    data-testid="trash-item-link">{item.name}</a
                  >
                {:else}
                  {item.name}
                {/if}
              </p>
              <p class="trash__kind">{kindLabel(item)}</p>
            </div>
            {#if item.detail && item.detail !== item.name}
              <p class="trash__meta">{item.detail}</p>
            {/if}
            {#if item.parent}
              <p class="trash__meta">
                <a
                  href={item.parent.kind === "project"
                    ? resolve("/projects/[id]", {
                        id: String(item.parent.id),
                      })
                    : resolve("/yarns/[id]", {
                        id: String(item.parent.id),
                      })}
                  data-testid="trash-parent-link">{parentLabel(item)}</a
                >
              </p>
            {/if}
            {#if showsOwner(scope, item, session.data?.user?.id) && item.owner}
              <p class="trash__meta trash__owner">
                <UserSentence
                  template={t("Owner: {name}", locale)}
                  users={{ name: personUser(item.owner) }}
                  {locale}
                />
              </p>
            {/if}
            <p class="trash__meta">
              <span title={fullDate(item.deleted_at, locale)}
                >{deletedLabel(item)}</span
              >
              <span aria-hidden="true"> · </span>
              <span
                class="trash__days"
                class:trash__days--due={item.days_remaining <= 0}
                >{daysLeftLabel(item.days_remaining, locale)}</span
              >
            </p>
          </div>
          <div class="trash__actions">
            {#if item.can_restore}
              <Button
                variant="secondary"
                icon="reset"
                type="button"
                disabled={busyKey !== null}
                data-testid="trash-restore"
                onclick={() => void restore(item)}
              >
                {busy ? t("Restoring…", locale) : t("Restore", locale)}
              </Button>
              <Button
                variant="danger-ghost"
                icon="action-delete"
                type="button"
                disabled={busyKey !== null}
                data-testid="trash-purge"
                onclick={() => askPurge(item)}
              >
                {t("Delete forever", locale)}
              </Button>
            {:else}
              <p class="trash__meta">
                {t(
                  "Only the owner or an administrator can restore this.",
                  locale,
                )}
              </p>
            {/if}
          </div>
        </li>
      {/each}
    </ul>
  {/if}
</section>

<ConfirmDialog
  bind:this={purgeDialog}
  titleId="purge-trash-title"
  title={t("Delete forever?", locale)}
  busy={busyKey !== null}
  onclose={() => (purgeTarget = null)}
>
  {#snippet children()}
    {#if purgeTarget}
      <p>
        {t(
          "{name} will be deleted permanently. This cannot be undone.",
          locale,
        ).replace("{name}", purgeTarget.name)}
      </p>
    {/if}
  {/snippet}
  {#snippet actions()}
    <Button
      variant="secondary"
      icon="cancel"
      type="button"
      disabled={busyKey !== null}
      onclick={() => purgeDialog?.close()}
    >
      {t("Keep in trash", locale)}
    </Button>
    <Button
      variant="danger"
      icon="action-delete"
      type="button"
      disabled={busyKey !== null}
      data-testid="trash-purge-confirm"
      onclick={() => void purge()}
    >
      {busyKey !== null ? t("Deleting…", locale) : t("Delete forever", locale)}
    </Button>
  {/snippet}
</ConfirmDialog>

<style>
  .trash {
    display: grid;
    gap: 1rem;
  }

  .trash__intro {
    margin: 0;
    color: var(--ink-muted);
  }

  .trash__list {
    display: grid;
    gap: 0.55rem;
    margin: 0;
    padding: 0;
    list-style: none;
  }

  .trash__row {
    display: grid;
    grid-template-columns: 3.5rem minmax(0, 1fr);
    align-items: start;
    gap: 0.75rem;
    border: 1px solid var(--line);
    border-radius: var(--radius-sm);
    padding: 0.75rem 0.9rem;
    background: var(--surface);
  }

  .trash__thumb {
    display: grid;
    flex: 0 0 3.5rem;
    width: 3.5rem;
    height: 3.5rem;
    place-items: center;
    overflow: hidden;
    border-radius: var(--radius-sm);
    background: var(--surface-muted, var(--line));
    color: var(--ink-muted);
  }

  .trash__heading {
    display: flex;
    flex-wrap: wrap;
    align-items: baseline;
    gap: 0.25rem 0.6rem;
  }

  .trash__thumb img {
    display: block;
    width: 100%;
    height: 100%;
    object-fit: cover;
  }

  .trash__thumb-icon {
    display: grid;
    width: 1.6rem;
    height: 1.6rem;
  }

  .trash__thumb-icon :global(svg) {
    width: 100%;
    height: 100%;
  }

  .trash__main {
    display: grid;
    min-width: 0;
    flex: 1 1 14rem;
    gap: 0.15rem;
  }

  .trash__main p {
    margin: 0;
  }

  .trash__kind {
    color: var(--ink-muted);
    font-size: 0.78rem;
  }

  .trash__name {
    overflow-wrap: anywhere;
    font-weight: 650;
  }

  .trash__meta {
    color: var(--ink-muted);
    font-size: 0.88rem;
    overflow-wrap: anywhere;
  }

  .trash__owner {
    display: flex;
    align-items: center;
    gap: 0.4rem;
  }

  .trash__days--due {
    color: var(--danger);
    font-weight: 650;
  }

  .trash__actions {
    display: flex;
    grid-column: 2;
    align-self: center;
    flex-wrap: wrap;
    gap: 0.5rem;
  }

  .trash__actions :global(.ui-button--secondary) {
    border-color: var(--line);
    background: var(--surface);
  }

  @media (max-width: 40rem) {
    .trash__row {
      grid-template-columns: 3.5rem minmax(0, 1fr);
    }

    .trash__actions {
      grid-column: 1 / -1;
      justify-content: flex-start;
    }
  }

  .trash__restored {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 0.5rem;
    margin: 0;
    border: 1px solid var(--line);
    border-radius: var(--radius-xs);
    padding: 0.75rem 0.9rem;
    color: var(--accent-strong);
    background: var(--surface);
  }

  .trash__restored :global(svg) {
    width: 1.2rem;
    height: 1.2rem;
  }

  .trash__restored a {
    min-height: 44px;
    display: inline-flex;
    align-items: center;
    font-weight: 650;
  }

  .trash__error {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 0.7rem;
  }

  .trash__skeleton {
    min-height: 5rem;
    border-radius: var(--radius-sm);
    background: var(--surface-raised);
  }
</style>
