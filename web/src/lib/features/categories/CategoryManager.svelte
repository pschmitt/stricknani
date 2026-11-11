<script lang="ts">
  import EmptyArt from "#lib/components/EmptyArt.svelte";
  import { onMount } from "svelte";
  import { page } from "$app/state";
  import { ApiError } from "#lib/api.ts";
  import Button from "#lib/components/Button.svelte";
  import PageHeader from "#lib/components/PageHeader.svelte";
  import { registerPwaUpdateGuard } from "#lib/pwa-update-coordinator.ts";
  import { t } from "#lib/i18n.svelte.ts";
  import { session } from "#lib/session.svelte.ts";
  import { publishMovedToTrash } from "../trash/trash-undo";
  import {
    createCategory,
    deleteCategory,
    listCategories,
    renameCategory,
    type Category,
  } from "./category-api";

  type LoadState = "loading" | "ready" | "error";

  let loadState = $state<LoadState>("loading");
  let categories = $state<Category[]>([]);
  let newName = $state("");
  let editName = $state("");
  let editingId = $state<number | null>(null);
  let deleteTarget = $state<Category | null>(null);
  let deleteDialog = $state<HTMLDialogElement | null>(null);
  let deleting = $state(false);
  let saving = $state(false);
  let errorMessage = $state("");
  let fieldError = $state("");
  let statusMessage = $state("");
  let reload = $state(0);

  const locale = $derived(session.data?.locale ?? "en");
  const userId = $derived(session.data?.user?.id ?? null);
  const pathname = $derived(page.url.pathname);

  onMount(() =>
    registerPwaUpdateGuard(() => ({
      dirty:
        newName.trim().length > 0 ||
        editingId !== null ||
        deleteTarget !== null,
      busy:
        loadState === "loading" ||
        saving ||
        deleting ||
        Boolean(deleteDialog?.open),
    })),
  );

  $effect(() => {
    const currentUserId = userId;
    reload;
    pathname;
    const controller = new AbortController();
    categories = [];
    if (currentUserId === null) {
      loadState = "error";
      errorMessage = t("Sign in to manage categories.", locale);
      return () => controller.abort();
    }
    loadState = "loading";
    errorMessage = "";
    void listCategories(controller.signal)
      .then((result) => {
        if (controller.signal.aborted) return;
        categories = result.toSorted((left, right) =>
          left.name.localeCompare(right.name, locale),
        );
        loadState = "ready";
      })
      .catch((error: unknown) => {
        if (controller.signal.aborted) return;
        loadState = "error";
        errorMessage =
          error instanceof Error
            ? error.message
            : t("Categories could not be loaded.", locale);
      });
    return () => controller.abort();
  });

  async function addCategory(event: SubmitEvent) {
    event.preventDefault();
    const name = newName.trim();
    const currentSession = session.data;
    if (!currentSession?.user || saving) return;
    errorMessage = "";
    fieldError = "";
    statusMessage = "";
    if (!name) {
      fieldError = t("Enter a category name.", locale);
      return;
    }
    saving = true;
    try {
      const created = await createCategory(name, currentSession);
      categories = [
        ...categories.filter((item) => item.id !== created.id),
        created,
      ].toSorted((left, right) => left.name.localeCompare(right.name, locale));
      newName = "";
      statusMessage = t("Category added.", locale);
    } catch (error) {
      showMutationError(error, "create");
    } finally {
      saving = false;
    }
  }

  function beginEdit(category: Category) {
    editingId = category.id;
    editName = category.name;
    fieldError = "";
    errorMessage = "";
    statusMessage = "";
  }

  function cancelEdit() {
    editingId = null;
    editName = "";
    fieldError = "";
  }

  async function saveEdit(category: Category) {
    const currentSession = session.data;
    const name = editName.trim();
    if (!currentSession?.user || saving) return;
    fieldError = "";
    errorMessage = "";
    statusMessage = "";
    if (!name) {
      fieldError = t("Enter a category name.", locale);
      return;
    }
    if (name === category.name) {
      cancelEdit();
      return;
    }
    saving = true;
    try {
      const updated = await renameCategory(category.id, name, currentSession);
      categories = categories
        .map((item) => (item.id === updated.id ? updated : item))
        .toSorted((left, right) => left.name.localeCompare(right.name, locale));
      statusMessage = t("Category renamed.", locale);
      cancelEdit();
    } catch (error) {
      showMutationError(error, "rename");
    } finally {
      saving = false;
    }
  }

  function askDelete(category: Category) {
    deleteTarget = category;
    errorMessage = "";
    deleteDialog?.showModal();
  }

  async function removeCategory() {
    const category = deleteTarget;
    const currentSession = session.data;
    if (!category || !currentSession?.user || deleting) return;
    deleting = true;
    errorMessage = "";
    statusMessage = "";
    try {
      await deleteCategory(category.id, currentSession);
      categories = categories.filter((item) => item.id !== category.id);
      deleteDialog?.close();
      deleteTarget = null;
      statusMessage = t("Category deleted.", locale);
      publishMovedToTrash({
        session: currentSession,
        ownerId: currentSession.user.id,
        locale,
        kind: "category",
        id: category.id,
        onRestored: () => {
          if (userId === currentSession.user?.id) reload += 1;
        },
      });
    } catch (error) {
      showMutationError(error, "delete");
    } finally {
      deleting = false;
    }
  }

  function showMutationError(error: unknown, operation: string) {
    if (error instanceof ApiError && error.fields?.name) {
      fieldError =
        error.code === "conflict"
          ? t("A category with this name already exists.", locale)
          : t("Enter a category name.", locale);
      return;
    }
    errorMessage =
      error instanceof ApiError && error.status === 404
        ? t("This category is no longer available.", locale)
        : error instanceof Error
          ? error.message
          : operation === "delete"
            ? t("The category could not be deleted.", locale)
            : t("The category could not be saved.", locale);
  }
</script>

<svelte:head>
  <title>Stricknani · {t("Categories", locale)}</title>
  <meta
    name="description"
    content={t("Organize projects with your own categories.", locale)}
  />
</svelte:head>

<section
  class="manager page-container page-container--standard"
  aria-labelledby="category-title"
>
  <PageHeader title={t("Categories", locale)} titleId="category-title">
    {#snippet actions()}
      <span class="count">{categories.length} {t("categories", locale)}</span>
    {/snippet}
  </PageHeader>

  {#if errorMessage}
    <div class="notice-error" role="alert">
      <p>{errorMessage}</p>
      {#if loadState === "error"}
        <Button
          variant="secondary"
          type="button"
          icon="refresh"
          onclick={() => (reload += 1)}
        >
          {t("Try again", locale)}
        </Button>
      {/if}
    </div>
  {/if}
  {#if statusMessage}<p
      class="success-message"
      role="status"
      aria-live="polite"
    >
      {statusMessage}
    </p>{/if}

  <form class="create-form" onsubmit={(event) => void addCategory(event)}>
    <label class="field">
      <span>{t("New category", locale)}</span>
      <input
        bind:value={newName}
        maxlength="256"
        autocomplete="off"
        disabled={saving}
        aria-invalid={Boolean(fieldError)}
        aria-describedby={fieldError ? "category-field-error" : undefined}
      />
    </label>
    <Button
      class="primary-action"
      variant="primary"
      icon="create"
      type="submit"
      disabled={saving}
    >
      {saving ? t("Saving…", locale) : t("Add category", locale)}
    </Button>
  </form>
  {#if fieldError}<p id="category-field-error" class="field-error" role="alert">
      {fieldError}
    </p>{/if}

  {#if loadState === "loading"}
    <div class="category-list" aria-busy="true" aria-live="polite">
      {#each [1, 2, 3] as row (row)}<div
          class="category-skeleton"
          aria-hidden="true"
        ></div>{/each}
    </div>
  {:else if loadState === "ready" && categories.length === 0}
    <div class="empty-state" aria-live="polite">
      <EmptyArt icon="categories" tone="yarns" />
      <h2>{t("No categories yet.", locale)}</h2>
      <p>
        {t(
          "Add a category above to make your project library easier to browse.",
          locale,
        )}
      </p>
    </div>
  {:else if loadState === "ready"}
    <ul class="category-list">
      {#each categories as category (category.id)}
        <li class="category-row">
          {#if editingId === category.id}
            <form
              class="rename-form"
              onsubmit={(event) => {
                event.preventDefault();
                void saveEdit(category);
              }}
            >
              <label class="sr-only" for={`category-${category.id}`}
                >{t("Category name", locale)}</label
              >
              <input
                id={`category-${category.id}`}
                bind:value={editName}
                maxlength="256"
                autocomplete="off"
                disabled={saving}
                aria-invalid={Boolean(fieldError)}
                aria-describedby={fieldError
                  ? `category-${category.id}-error`
                  : undefined}
              />
              <Button
                class="primary-action"
                variant="primary"
                icon="save"
                type="submit"
                disabled={saving}
              >
                {saving ? t("Saving…", locale) : t("Save", locale)}
              </Button>
              <Button
                class="quiet-button"
                variant="secondary"
                icon="cancel"
                type="button"
                disabled={saving}
                onclick={cancelEdit}
              >
                {t("Cancel", locale)}
              </Button>
              {#if fieldError}<p
                  id={`category-${category.id}-error`}
                  class="field-error"
                  role="alert"
                >
                  {fieldError}
                </p>{/if}
            </form>
          {:else}
            <span class="category-name">{category.name}</span>
            <div class="row-actions">
              <Button
                class="quiet-button"
                variant="secondary"
                icon="action-update"
                type="button"
                onclick={() => beginEdit(category)}
              >
                {t("Rename", locale)}
              </Button>
              <Button
                class="quiet-button destructive"
                variant="danger"
                icon="action-delete"
                type="button"
                onclick={() => askDelete(category)}
              >
                {t("Delete", locale)}
              </Button>
            </div>
          {/if}
        </li>
      {/each}
    </ul>
  {/if}
</section>

<dialog
  bind:this={deleteDialog}
  class="confirm-dialog"
  aria-labelledby="delete-category-title"
>
  <h2 id="delete-category-title">{t("Delete category?", locale)}</h2>
  {#if deleteTarget}
    <p>
      {t("Delete", locale)} <strong>{deleteTarget.name}</strong>? {t(
        "Projects that use this category will no longer show it.",
        locale,
      )}
      {t(
        "It moves to the trash and can be restored from there for 90 days.",
        locale,
      )}
    </p>
  {/if}
  {#if errorMessage}<p class="field-error" role="alert">{errorMessage}</p>{/if}
  <div class="dialog-actions">
    <Button
      class="quiet-button"
      variant="secondary"
      icon="cancel"
      type="button"
      disabled={deleting}
      onclick={() => deleteDialog?.close()}
    >
      {t("Keep category", locale)}
    </Button>
    <Button
      class="danger-button"
      variant="danger"
      icon="action-delete"
      type="button"
      disabled={deleting}
      onclick={() => void removeCategory()}
    >
      {deleting ? t("Deleting…", locale) : t("Delete category", locale)}
    </Button>
  </div>
</dialog>

<style>
  .manager {
    display: grid;
    gap: 1.15rem;
  }

  .create-form,
  .category-row,
  .row-actions,
  .dialog-actions {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 0.75rem;
  }

  .count {
    min-height: 44px;
    align-content: center;
    color: var(--ink-muted);
    font-size: 0.9rem;
  }

  .create-form {
    align-items: end;
    border: 1px solid var(--line);
    border-radius: var(--radius-sm);
    padding: 0.9rem;
    background: var(--surface);
  }

  .field {
    display: grid;
    width: 100%;
    gap: 0.35rem;
    color: var(--ink-muted);
    font-size: 0.82rem;
    font-weight: 620;
  }

  input {
    width: 100%;
    min-width: 0;
  }

  .notice-error,
  .success-message {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 0.7rem;
    border: 1px solid var(--line);
    border-radius: var(--radius-xs);
    padding: 0.75rem 0.9rem;
    margin: 0;
    background: var(--surface);
  }

  .notice-error {
    color: var(--danger);
  }

  .notice-error p {
    margin: 0;
  }

  .success-message {
    color: var(--accent-strong);
  }

  .field-error {
    margin: 0;
    color: var(--danger);
  }

  .category-list {
    display: grid;
    gap: 0.55rem;
    margin: 0;
    padding: 0;
    list-style: none;
  }

  .category-row {
    min-height: 4rem;
    border-bottom: 1px solid var(--line);
    padding: 0.55rem 0.25rem;
  }

  .category-name {
    min-width: 0;
    overflow-wrap: anywhere;
    font-weight: 620;
  }

  .rename-form {
    display: grid;
    width: 100%;
    grid-template-columns: minmax(0, 1fr) auto auto;
    align-items: center;
    gap: 0.5rem;
  }

  .rename-form .field-error {
    grid-column: 1 / -1;
  }

  .category-skeleton {
    min-height: 3.5rem;
    border-bottom: 1px solid var(--line);
    background: var(--surface-raised);
  }

  .sr-only {
    position: absolute;
    width: 1px;
    height: 1px;
    overflow: hidden;
    clip: rect(0 0 0 0);
    clip-path: inset(50%);
    white-space: nowrap;
  }

  .confirm-dialog {
    max-width: min(92vw, 32rem);
    border: 1px solid var(--line);
    border-radius: var(--radius-sm);
    padding: 1.2rem;
    color: var(--ink);
    background: var(--surface);
  }

  .confirm-dialog::backdrop {
    background: rgb(15 28 27 / 72%);
  }

  .confirm-dialog h2 {
    margin-top: 0;
  }

  .confirm-dialog p {
    color: var(--ink-muted);
  }

  @media (max-width: 560px) {
    .create-form,
    .category-row {
      align-items: stretch;
      flex-direction: column;
    }

    .create-form :global(.ui-button) {
      width: 100%;
    }

    .row-actions {
      width: 100%;
    }

    .row-actions :global(.ui-button) {
      flex: 1;
    }

    .rename-form {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }

    .rename-form input {
      grid-column: 1 / -1;
    }

    .dialog-actions {
      align-items: stretch;
      flex-direction: column-reverse;
    }

    .dialog-actions :global(.ui-button) {
      width: 100%;
    }
  }
</style>
