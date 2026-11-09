<script lang="ts">
  import NavIcon from "#lib/components/NavIcon.svelte";
  import {
    isDefaultDetailOrder,
    moveDetailCard,
    parseDetailOrders,
    type DetailCardDomain,
  } from "#lib/detail-order.ts";
  import { nearestIndex } from "#lib/features/media/media-reorder.ts";
  import { ApiError } from "#lib/api.ts";
  import { t } from "#lib/i18n.svelte.ts";
  import { patchSessionDetailOrder, session } from "#lib/session.svelte.ts";
  import { setDetailCardOrder } from "./account-api.ts";

  let {
    locale,
    disabled = false,
  }: { locale: "en" | "de"; disabled?: boolean } = $props();

  const domains: DetailCardDomain[] = ["project", "yarn"];
  const orders = $derived(
    parseDetailOrders(session.data?.user?.detail_card_order),
  );
  let error = $state("");
  let status = $state("");
  let dragDomain = $state<DetailCardDomain | null>(null);
  let dragFrom = $state<number | null>(null);
  let dropTo = $state<number | null>(null);
  // Newest save per page wins; an older response must not undo a newer move.
  const saveSeq: Record<DetailCardDomain, number> = { project: 0, yarn: 0 };

  function title(domain: DetailCardDomain): string {
    return domain === "project"
      ? t("Project detail cards", locale)
      : t("Yarn detail cards", locale);
  }

  function label(domain: DetailCardDomain, id: string): string {
    if (domain === "project") {
      if (id === "details") return t("Project details", locale);
      if (id === "materials") return t("Materials", locale);
      if (id === "description") return t("Pattern", locale);
      if (id === "steps") return t("Steps", locale);
    } else {
      if (id === "details") return t("Yarn details", locale);
      if (id === "description") return t("Description", locale);
      if (id === "notes") return t("Notes", locale);
      if (id === "used_in") return t("Used in", locale);
    }
    return id;
  }

  async function save(domain: DetailCardDomain, next: readonly string[]) {
    const activeSession = session.data;
    const user = activeSession?.user;
    if (!activeSession || !user || disabled) return;
    const previous = orders[domain];
    const seq = ++saveSeq[domain];
    error = "";
    status = "";
    patchSessionDetailOrder(user.id, domain, next);
    try {
      const profile = await setDetailCardOrder(domain, next, activeSession);
      if (seq !== saveSeq[domain]) return;
      patchSessionDetailOrder(
        user.id,
        domain,
        profile.detail_card_order[domain],
      );
      status = t("Detail card order saved.", locale);
    } catch (failure) {
      if (seq !== saveSeq[domain]) return;
      patchSessionDetailOrder(user.id, domain, previous);
      error =
        failure instanceof ApiError && failure.code === "network_error"
          ? failure.message
          : t("The detail card order could not be saved.", locale);
    }
  }

  function move(domain: DetailCardDomain, from: number, to: number) {
    const current = orders[domain];
    const next = moveDetailCard(current, from, to);
    if (next.some((id, index) => id !== current[index]))
      void save(domain, next);
  }

  function startDrag(
    event: PointerEvent,
    domain: DetailCardDomain,
    index: number,
  ) {
    if (disabled || event.button > 0) return;
    (event.currentTarget as HTMLElement).setPointerCapture(event.pointerId);
    dragDomain = domain;
    dragFrom = index;
    dropTo = index;
  }

  function dragOver(event: PointerEvent) {
    if (dragDomain === null) return;
    const list = (event.currentTarget as HTMLElement).closest("ul");
    const rows = Array.from(
      list?.querySelectorAll<HTMLElement>("li[data-card]") ?? [],
    );
    const target = nearestIndex(
      rows.map((row) => {
        const box = row.getBoundingClientRect();
        return { x: box.left + box.width / 2, y: box.top + box.height / 2 };
      }),
      event.clientX,
      event.clientY,
    );
    if (target >= 0) dropTo = target;
  }

  function endDrag(commit: boolean) {
    const domain = dragDomain;
    const from = dragFrom;
    const to = dropTo;
    dragDomain = null;
    dragFrom = null;
    dropTo = null;
    if (commit && domain && from !== null && to !== null)
      move(domain, from, to);
  }
</script>

<section class="card" aria-labelledby="detail-cards-title">
  <header class="card__header">
    <span class="card__icon" aria-hidden="true"><NavIcon name="sliders" /></span
    >
    <h2 id="detail-cards-title">{t("Detail cards", locale)}</h2>
  </header>
  <p class="hint">
    {t(
      "Use the arrows to choose the default order. Optional sections appear when they have content.",
      locale,
    )}
  </p>
  {#each domains as domain (domain)}
    {@const order = orders[domain]}
    <div class="group" role="group" aria-labelledby={`detail-cards-${domain}`}>
      <h3 id={`detail-cards-${domain}`}>{title(domain)}</h3>
      <ul class="order-list">
        {#each order as id, index (id)}
          <li
            data-card={id}
            class:dragging={dragDomain === domain && dragFrom === index}
            class:drop={dragDomain === domain &&
              dropTo === index &&
              dragFrom !== index}
          >
            <span
              class="grip"
              aria-hidden="true"
              onpointerdown={(event) => startDrag(event, domain, index)}
              onpointermove={dragOver}
              onpointerup={() => endDrag(true)}
              onpointercancel={() => endDrag(false)}
            >
              <NavIcon name="grip" />
            </span>
            <span class="order-list__label">{label(domain, id)}</span>
            <button
              type="button"
              class="step-button"
              aria-label={`${t("Move up", locale)}: ${label(domain, id)}`}
              disabled={disabled || index === 0}
              onclick={() => move(domain, index, index - 1)}
            >
              <NavIcon name="move-up" />
            </button>
            <button
              type="button"
              class="step-button"
              aria-label={`${t("Move down", locale)}: ${label(domain, id)}`}
              disabled={disabled || index === order.length - 1}
              onclick={() => move(domain, index, index + 1)}
            >
              <NavIcon name="move-down" />
            </button>
          </li>
        {/each}
      </ul>
      <button
        type="button"
        class="reset"
        disabled={disabled || isDefaultDetailOrder(domain, order)}
        onclick={() => void save(domain, [])}
      >
        {t("Reset to default order", locale)}
      </button>
    </div>
  {/each}
  {#if error}<p class="field-error" role="alert">{error}</p>{/if}
  <p class="visually-hidden" role="status">{status}</p>
</section>

<style>
  .card {
    display: grid;
    min-width: 0;
    gap: 1rem;
    border: 1px solid var(--line);
    border-radius: var(--radius-md);
    padding: 1rem;
    background: var(--surface);
  }

  .card__header {
    display: flex;
    min-width: 0;
    align-items: center;
    gap: 0.75rem;
  }

  .card__icon {
    display: inline-flex;
    width: 2.5rem;
    height: 2.5rem;
    flex: none;
    align-items: center;
    justify-content: center;
    border-radius: 50%;
    font-size: 1.35rem;
    color: var(--tone-categories);
    background: var(--tone-categories-wash);
  }

  .hint {
    margin: 0;
    font-size: 0.9rem;
    line-height: 1.45;
    color: var(--ink-muted);
  }

  .group {
    display: grid;
    gap: 0.5rem;
    border-top: 1px solid var(--line);
    padding-top: 0.9rem;
  }

  h3 {
    margin: 0;
    font-size: 1rem;
  }

  .order-list {
    display: grid;
    margin: 0;
    padding: 0;
    list-style: none;
  }

  .order-list li {
    display: flex;
    min-width: 0;
    align-items: center;
    gap: 0.25rem;
    border-radius: var(--radius-xs);
    padding-inline: 0.25rem;
  }

  .order-list li.dragging {
    opacity: 0.55;
  }

  .order-list li.drop {
    outline: 2px solid var(--focus);
  }

  .order-list__label {
    flex: 1;
    min-width: 0;
  }

  .grip {
    display: inline-flex;
    width: 2rem;
    min-height: 44px;
    align-items: center;
    justify-content: center;
    color: var(--ink-muted);
    cursor: grab;
    touch-action: none;
  }

  .step-button {
    display: inline-flex;
    width: 44px;
    height: 44px;
    align-items: center;
    justify-content: center;
    border: 0;
    border-radius: 50%;
    color: inherit;
    background: transparent;
    font-size: 1.25rem;
    cursor: pointer;
  }

  .step-button:focus-visible,
  .reset:focus-visible {
    outline: 2px solid var(--focus);
  }

  .step-button:disabled,
  .reset:disabled {
    opacity: 0.4;
    cursor: default;
  }

  .reset {
    justify-self: start;
    min-height: 44px;
    padding: 0 0.4rem;
    border: 0;
    color: var(--accent-strong);
    background: transparent;
    font: inherit;
    font-weight: 700;
    text-decoration: underline;
    cursor: pointer;
  }

  .field-error {
    margin: 0;
    color: var(--danger);
  }

  .visually-hidden {
    position: absolute;
    width: 1px;
    height: 1px;
    margin: -1px;
    overflow: hidden;
    clip-path: inset(50%);
    white-space: nowrap;
  }

  @media (prefers-reduced-motion: no-preference) {
    .order-list li {
      transition: opacity 120ms ease;
    }
  }
</style>
