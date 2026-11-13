<script lang="ts">
  import NavIcon from "#lib/components/NavIcon.svelte";
  import UserAvatar from "#lib/components/UserAvatar.svelte";
  import type { Locale } from "#lib/i18n.svelte.ts";
  import { userByText, userLabelText, type UserRef } from "#lib/user-label.ts";

  // THE way to show a person: wherever a user's name appears, render it with this component
  // (avatar + name). Android has the same one (`ui/common/UserLabel.kt`).
  //
  // Props
  //   user    `UserRef` (`#lib/user-label.ts`): `kind` is `self` ("You"), `user` (display name),
  //           `admin` ("an administrator", generic admin icon), `system` ("System", system icon)
  //           or `unknown` ("Unknown", "?" avatar); `name`; `avatarUrl` (same-origin path or null).
  //   locale  Locale for "You", "System", ... .
  //   size    `inline` (1.1rem avatar, inside a sentence or a meta line), `compact` (1.4rem,
  //           the default: list rows) or `regular` (2.25rem, headers and cards).
  //   by      Kept for callers that ask for the author wording; it is the plain label (no "by").
  //           For other sentences with people in them use `UserSentence`.
  //
  // The text is never an email: names come from the server's display-name helper.
  let {
    user,
    locale,
    size = "compact",
    by = false,
  }: {
    user: UserRef;
    locale: Locale;
    size?: "inline" | "compact" | "regular";
    by?: boolean;
  } = $props();

  const name = $derived(userLabelText(user, locale));
  const text = $derived(by ? userByText(user, locale) : name);
  // Both supported locales put the author preposition first. Keep the rest of the
  // translated phrase intact, including German case ("dir", "einem Administrator").
  const separator = $derived(by ? text.indexOf(" ") + 1 : 0);
  const prefix = $derived(text.slice(0, separator));
  const person = $derived(text.slice(separator));
  const icon = $derived(
    user.kind === "admin" ? "admin" : user.kind === "system" ? "monitor" : null,
  );
</script>

<span class="user-label" data-size={size} data-kind={user.kind}>
  {#if prefix}<span class="user-label__prefix">{prefix}</span>{/if}
  {#if icon}
    <span class="user-label__glyph" aria-hidden="true"
      ><NavIcon name={icon} /></span
    >
  {:else}
    <UserAvatar
      avatarUrl={user.kind === "unknown" ? null : user.avatarUrl}
      name={user.kind === "unknown" ? "?" : user.name?.trim() || name}
    />
  {/if}
  <span class="user-label__name">{person}</span>
</span>

<style>
  .user-label {
    --avatar: 1.4rem;
    display: inline-flex;
    align-items: center;
    gap: 0.35rem;
    min-width: 0;
    overflow-wrap: anywhere;
  }

  .user-label[data-size="inline"] {
    --avatar: 1.1rem;
  }

  .user-label[data-size="regular"] {
    --avatar: 2.25rem;
    gap: 0.6rem;
  }

  .user-label :global(.user-avatar),
  .user-label__glyph {
    flex: 0 0 var(--avatar);
    width: var(--avatar);
    height: var(--avatar);
    font-size: calc(var(--avatar) * 0.5);
  }

  .user-label__glyph {
    display: grid;
    place-items: center;
    border: 1px solid var(--line);
    border-radius: 50%;
    color: var(--ink-muted);
    background: var(--surface-raised);
  }

  .user-label__glyph :global(svg) {
    width: 62%;
    height: 62%;
  }
</style>
