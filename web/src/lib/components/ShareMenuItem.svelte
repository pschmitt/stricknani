<script lang="ts">
  import NavIcon from "#lib/components/NavIcon.svelte";
  import { t, type Locale } from "#lib/i18n.svelte.ts";
  import { publishFeedback } from "#lib/feedback.ts";
  import { shareableUrl, shareLink } from "#lib/share-link.ts";

  let {
    title,
    ownerId,
    locale,
    close,
  }: {
    /** Name of the project or yarn, passed to the system share sheet. */
    title: string;
    ownerId: number | null;
    locale: Locale;
    close: () => void;
  } = $props();

  async function share() {
    close();
    const outcome = await shareLink(shareableUrl(window.location), title);
    if (ownerId === null) return;
    if (outcome === "copied")
      publishFeedback({
        ownerId,
        kind: "success",
        message: t("Link copied.", locale),
      });
    else if (outcome === "failed")
      publishFeedback({
        ownerId,
        kind: "error",
        message: t("The link could not be shared.", locale),
      });
  }
</script>

<button
  class="action-overflow__item"
  role="menuitem"
  type="button"
  onclick={() => void share()}
>
  <NavIcon name="share" />
  <span>{t("Share", locale)}</span>
</button>
