<script lang="ts">
  import { onMount } from "svelte";
  import { page } from "$app/state";
  import { ApiError } from "#lib/api.ts";
  import ActionLabel from "#lib/components/ActionLabel.svelte";
  import { registerPwaUpdateGuard } from "#lib/pwa-update-coordinator.ts";
  import { t } from "#lib/i18n.svelte.ts";
  import { session } from "#lib/session.svelte.ts";
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

<section class="manager" aria-labelledby="category-title">
  <header class="manager-heading">
    <div>
      <p class="section-mark">{t("Workspace", locale)}</p>
      <h1 id="category-title">{t("Categories", locale)}</h1>
      <p>{t("Use categories to find projects faster.", locale)}</p>
    </div>
    <span class="count">{categories.length} {t("categories", locale)}</span>
  </header>

  {#if errorMessage}
    <div class="notice-error" role="alert">
      <p>{errorMessage}</p>
      {#if loadState === "error"}
        <button type="button" onclick={() => (reload += 1)}
          ><ActionLabel icon="refresh">{t("Try again", locale)}</ActionLabel
          ></button
        >
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
    <button class="primary-action" type="submit" disabled={saving}>
      <ActionLabel icon="create">
        {saving ? t("Saving…", locale) : t("Add category", locale)}
      </ActionLabel>
    </button>
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
      <span class="empty-mark" aria-hidden="true">⌁</span>
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
              <button class="primary-action" type="submit" disabled={saving}>
                <ActionLabel icon="save">
                  {saving ? t("Saving…", locale) : t("Save", locale)}
                </ActionLabel>
              </button>
              <button
                class="quiet-button"
                type="button"
                disabled={saving}
                onclick={cancelEdit}
                ><ActionLabel icon="cancel">{t("Cancel", locale)}</ActionLabel
                ></button
              >
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
              <button
                class="quiet-button"
                type="button"
                onclick={() => beginEdit(category)}
                ><ActionLabel icon="action-update"
                  >{t("Rename", locale)}</ActionLabel
                ></button
              >
              <button
                class="quiet-button destructive"
                type="button"
                onclick={() => askDelete(category)}
                ><ActionLabel icon="action-delete"
                  >{t("Delete", locale)}</ActionLabel
                ></button
              >
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
    </p>
  {/if}
  {#if errorMessage}<p class="field-error" role="alert">{errorMessage}</p>{/if}
  <div class="dialog-actions">
    <button
      class="quiet-button"
      type="button"
      disabled={deleting}
      onclick={() => deleteDialog?.close()}
      ><ActionLabel icon="cancel">{t("Keep category", locale)}</ActionLabel
      ></button
    >
    <button
      class="danger-button"
      type="button"
      disabled={deleting}
      onclick={() => void removeCategory()}
    >
      <ActionLabel icon="action-delete">
        {deleting ? t("Deleting…", locale) : t("Delete category", locale)}
      </ActionLabel>
    </button>
  </div>
</dialog>

<style>
  .manager {
    display: grid;
    max-width: 56rem;
    gap: 1.15rem;
    margin: 0 auto;
  }

  .manager-heading,
  .create-form,
  .category-row,
  .row-actions,
  .dialog-actions {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 0.75rem;
  }

  .manager-heading {
    align-items: end;
  }

  .section-mark {
    margin: 0;
    color: var(--accent-strong);
    font-size: 0.85rem;
    font-weight: 650;
  }

  h1 {
    margin: 0.3rem 0 0.35rem;
    font-size: clamp(2rem, 5vw, 2.7rem);
    letter-spacing: -0.055em;
    line-height: 1.05;
  }

  .manager-heading p:last-child {
    margin: 0;
    color: var(--ink-muted);
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
    border-radius: 0.95rem;
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
    min-height: 44px;
    border: 1px solid var(--control-border);
    border-radius: 0.65rem;
    padding: 0.55rem 0.7rem;
    color: var(--ink);
    background: var(--surface-raised);
    font: inherit;
  }

  input[aria-invalid="true"] {
    border-color: var(--danger);
  }

  .primary-action,
  .quiet-button,
  .danger-button,
  .notice-error button {
    display: inline-flex;
    min-height: 44px;
    align-items: center;
    justify-content: center;
    border: 1px solid var(--line);
    border-radius: 0.65rem;
    padding: 0.55rem 0.8rem;
    color: var(--accent-strong);
    background: var(--surface);
    font: inherit;
    font-weight: 620;
    text-decoration: none;
    cursor: pointer;
    white-space: nowrap;
  }

  .primary-action {
    border-color: var(--accent-strong);
    color: var(--on-accent);
    background: var(--accent-strong);
  }

  .danger-button,
  .destructive {
    border-color: var(--danger);
    color: var(--danger);
  }

  .notice-error,
  .success-message {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 0.7rem;
    border: 1px solid var(--line);
    border-radius: 0.8rem;
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

  .empty-state {
    display: grid;
    justify-items: center;
    gap: 0.45rem;
    border: 1px dashed var(--line);
    border-radius: 1rem;
    padding: clamp(1.5rem, 6vw, 3rem) 1rem;
    text-align: center;
  }

  .empty-mark {
    color: var(--accent);
    font-size: 2rem;
  }

  .empty-state h2,
  .empty-state p {
    margin: 0;
  }

  .empty-state p {
    max-width: 30rem;
    color: var(--ink-muted);
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
    border-radius: 1rem;
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
    .manager-heading {
      align-items: start;
      flex-direction: column;
    }

    .create-form,
    .category-row {
      align-items: stretch;
      flex-direction: column;
    }

    .create-form .primary-action {
      width: 100%;
    }

    .row-actions {
      width: 100%;
    }

    .row-actions button {
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

    .dialog-actions button {
      width: 100%;
    }
  }
</style>
