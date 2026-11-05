<script lang="ts">
  import NavIcon from "#lib/components/NavIcon.svelte";
  import { t, type Locale } from "#lib/i18n.svelte.ts";
  import { publishFeedback } from "#lib/feedback.ts";
  import { shareCurrentPage } from "#lib/share-link-action.ts";

  let {
    title,
    ownerId,
    locale,
    close,
    ondialog,
  }: {
    /** Name of the project or yarn, passed to the system share sheet. */
    title: string;
    ownerId: number | null;
    locale: Locale;
    close: () => void;
    /** Present for people who manage access: opens the Share dialog instead of sharing at once. */
    ondialog?: () => void;
  } = $props();

  async function share() {
    close();
    if (ondialog) {
      ondialog();
      return;
    }
    const outcome = await shareCurrentPage(title);
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
  aria-haspopup={ondialog ? "dialog" : undefined}
  onclick={() => void share()}
>
  <NavIcon name="share" />
  <span>{t("Share", locale)}</span>
</button>
