<script lang="ts">
  import SegmentedControl from "#lib/components/SegmentedControl.svelte";
  import { t, type Locale } from "#lib/i18n.svelte.ts";
  import { theme, setTheme } from "#lib/theme.svelte.ts";
  import { themeChoices, type ThemeChoice } from "#lib/theme.ts";

  let { locale, showLabels = false }: { locale: Locale; showLabels?: boolean } =
    $props();

  const icons = { system: "monitor", light: "sun", dark: "moon" } as const;
  const labels = $derived<Record<ThemeChoice, string>>({
    system: t("Follow system", locale),
    light: t("Light", locale),
    dark: t("Dark", locale),
  });
  const options = $derived(
    themeChoices.map((choice) => ({
      value: choice,
      label: labels[choice],
      icon: icons[choice],
    })),
  );
</script>

<SegmentedControl
  {options}
  value={theme.choice}
  label={t("Theme", locale)}
  iconOnly={!showLabels}
  onchange={setTheme}
/>
