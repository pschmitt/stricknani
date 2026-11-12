<script lang="ts">
  import UserLabel from "#lib/components/UserLabel.svelte";
  import type { Locale } from "#lib/i18n.svelte.ts";
  import { sentenceParts, type UserRef } from "#lib/user-label.ts";

  // A translated sentence with people in it ("Owner: {name}", "by {admin} as {user}"): every
  // `{placeholder}` that has an entry in `users` renders as a `UserLabel`. Props: `template`
  // (already translated), `users` (placeholder name to `UserRef`), `locale`, `size` (as `UserLabel`).
  let {
    template,
    users,
    locale,
    size = "inline",
  }: {
    template: string;
    users: Record<string, UserRef>;
    locale: Locale;
    size?: "inline" | "compact" | "regular";
  } = $props();

  const parts = $derived(sentenceParts(template, users));
</script>

<span class="user-sentence"
  >{#each parts as part, index (index)}{#if part.user}<UserLabel
        user={part.user}
        {locale}
        {size}
      />{:else}<span class="user-sentence__text">{part.text}</span
      >{/if}{/each}</span
>

<style>
  .user-sentence {
    display: inline-flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 0;
    min-width: 0;
  }

  /* Keep the spaces at the edges of a piece ("Owner: "); flex items would trim them. */
  .user-sentence__text {
    white-space: pre-wrap;
  }
</style>
