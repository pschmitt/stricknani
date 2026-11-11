<script lang="ts">
  type Size = "chip" | "small" | "large";

  let {
    avatarUrl,
    name,
    size = "small",
  }: {
    avatarUrl: string | null;
    name: string;
    size?: Size;
  } = $props();

  let failedUrl = $state<string | null>(null);
  const imageSource = $derived(
    avatarUrl !== null && avatarUrl !== failedUrl ? avatarUrl : null,
  );
  const initial = $derived(name.trim().charAt(0).toLocaleUpperCase() || "?");

  function markBroken(source: string) {
    if (avatarUrl === source) failedUrl = source;
  }
</script>

<span
  class="user-avatar"
  class:large={size === "large"}
  class:chip={size === "chip"}
  aria-hidden="true"
>
  {#if imageSource}
    {@const source = imageSource}
    {#key source}
      <img
        src={source}
        alt=""
        loading="lazy"
        decoding="async"
        onerror={() => markBroken(source)}
      />
    {/key}
  {:else}
    <span>{initial}</span>
  {/if}
</span>

<style>
  .user-avatar {
    display: grid;
    flex: 0 0 2.25rem;
    width: 2.25rem;
    height: 2.25rem;
    overflow: hidden;
    place-items: center;
    border: 1px solid var(--line);
    border-radius: 50%;
    color: var(--accent-strong);
    background: var(--accent-wash);
    font-size: 0.95rem;
    font-weight: 700;
    line-height: 1;
  }

  .user-avatar.large {
    flex-basis: 2.75rem;
    width: 2.75rem;
    height: 2.75rem;
  }

  /* A filter chip's leading icon: Android's 18dp. */
  .user-avatar.chip {
    flex-basis: 1.125rem;
    width: 1.125rem;
    height: 1.125rem;
    font-size: 0.65rem;
  }

  .user-avatar img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
</style>
